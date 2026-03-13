package com.onix.spotifykeeper

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.zip.ZipInputStream
import kotlin.math.roundToInt

data class ImportedHistoryResult(
    val summary: SpotifyTopSummary,
    val importedPlays: Int
)

class SpotifyHistoryImportService(private val context: Context) {

    fun importFromUri(uri: Uri): ImportedHistoryResult {
        val fileName = resolveFileName(uri).orEmpty().lowercase(Locale.ROOT)
        val plays = if (fileName.endsWith(".zip")) {
            parseZip(uri)
        } else {
            parseJsonDocument(uri)
        }
        if (plays.isEmpty()) {
            throw IllegalStateException("Nenhuma reproducao valida encontrada no arquivo.")
        }
        return ImportedHistoryResult(summary = buildSummary(plays), importedPlays = plays.size)
    }

    private fun parseZip(uri: Uri): List<ImportedPlay> {
        val stream = openInputStream(uri)
        ZipInputStream(BufferedInputStream(stream)).use { zip ->
            val all = mutableListOf<ImportedPlay>()
            var entry = zip.nextEntry
            while (entry != null) {
                val name = entry.name.orEmpty().lowercase(Locale.ROOT)
                if (!entry.isDirectory && name.endsWith(".json") && name.contains("stream")) {
                    val bytes = readBytes(zip)
                    val jsonPlays = parseJsonBytes(bytes)
                    all.addAll(jsonPlays)
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
            return all
        }
    }

    private fun parseJsonDocument(uri: Uri): List<ImportedPlay> {
        openInputStream(uri).use { input ->
            val bytes = input.readBytes()
            return parseJsonBytes(bytes)
        }
    }

    private fun parseJsonBytes(bytes: ByteArray): List<ImportedPlay> {
        val text = bytes.toString(Charsets.UTF_8).trim()
        if (text.isBlank()) {
            return emptyList()
        }
        val plays = mutableListOf<ImportedPlay>()

        if (text.startsWith("[")) {
            parseJsonArray(JSONArray(text), plays)
            return plays
        }

        if (text.startsWith("{")) {
            val root = JSONObject(text)
            val keysToTry = listOf("entries", "items", "plays")
            keysToTry.forEach { key ->
                val array = root.optJSONArray(key)
                if (array != null) {
                    parseJsonArray(array, plays)
                    return@forEach
                }
            }
            if (plays.isEmpty()) {
                // Some exports contain a single object per file.
                parseJsonObject(root)?.let { plays.add(it) }
            }
        }

        return plays
    }

    private fun parseJsonArray(array: JSONArray, output: MutableList<ImportedPlay>) {
        for (index in 0 until array.length()) {
            val obj = array.optJSONObject(index) ?: continue
            parseJsonObject(obj)?.let { output.add(it) }
        }
    }

    private fun parseJsonObject(obj: JSONObject): ImportedPlay? {
        val trackName = obj.optString("trackName")
            .ifBlank { obj.optString("master_metadata_track_name") }
            .trim()
        val artistName = obj.optString("artistName")
            .ifBlank { obj.optString("master_metadata_album_artist_name") }
            .trim()
        val msPlayed = obj.optLong("msPlayed", obj.optLong("ms_played", 0L)).coerceAtLeast(0L)
        val timestampRaw = obj.optString("endTime")
            .ifBlank { obj.optString("ts") }
            .trim()

        if (trackName.isBlank() || msPlayed <= 0L || timestampRaw.isBlank()) {
            return null
        }

        val playedAt = parseTimestamp(timestampRaw) ?: return null
        val spotifyUri = obj.optString("spotify_track_uri").takeIf { it.isNotBlank() }
            ?: obj.optString("trackUri").takeIf { it.isNotBlank() }
        return ImportedPlay(
            trackName = trackName,
            artistName = artistName,
            playedAt = playedAt,
            msPlayed = msPlayed,
            trackKey = spotifyUri ?: "${trackName.lowercase(Locale.ROOT)}|${artistName.lowercase(Locale.ROOT)}"
        )
    }

    private fun parseTimestamp(raw: String): Instant? {
        return runCatching { Instant.parse(raw) }.getOrNull()
            ?: runCatching {
                val local = LocalDateTime.parse(raw, LEGACY_EXPORT_FORMAT)
                local.atZone(ZoneId.systemDefault()).toInstant()
            }.getOrNull()
            ?: runCatching {
                val local = LocalDateTime.parse(raw, LEGACY_EXPORT_FORMAT_SECONDS)
                local.atZone(ZoneId.systemDefault()).toInstant()
            }.getOrNull()
    }

    private fun buildSummary(plays: List<ImportedPlay>): SpotifyTopSummary {
        val sortedPlays = plays.sortedByDescending { it.playedAt }
        val zone = ZoneId.systemDefault()
        val labelFormatter = DateTimeFormatter.ofPattern("dd/MM")

        val dailySummaries = sortedPlays
            .groupBy { it.playedAt.atZone(zone).toLocalDate() }
            .entries
            .sortedByDescending { it.key }
            .map { (day, dayPlays) ->
                val minutes = toRoundedMinutes(dayPlays.sumOf { it.msPlayed })
                val uniqueTracks = dayPlays.map { it.trackKey }.distinct().size
                val topTracks = dayPlays
                    .groupBy { it.trackKey }
                    .values
                    .map { sameTrack ->
                        val base = sameTrack.first()
                        val count = sameTrack.size
                        val trackMinutes = toRoundedMinutes(sameTrack.sumOf { it.msPlayed })
                        val subtitleArtist = base.artistName.ifBlank { "Artista desconhecido" }
                        SpotifyMediaItem(
                            title = base.trackName,
                            subtitle = "$subtitleArtist - ${count}x - ${trackMinutes} min",
                            imageUrl = null
                        ) to (count * 1000 + trackMinutes)
                    }
                    .sortedByDescending { it.second }
                    .take(5)
                    .map { it.first }

                SpotifyDailySummary(
                    dateIso = day.toString(),
                    dateLabel = labelFormatter.format(day),
                    minutesHeard = minutes,
                    playsCount = dayPlays.size,
                    uniqueTracksCount = uniqueTracks,
                    topTracks = topTracks
                )
            }

        val topTracks = sortedPlays
            .groupBy { it.trackKey }
            .values
            .map { sameTrack ->
                val base = sameTrack.first()
                val count = sameTrack.size
                val minutes = toRoundedMinutes(sameTrack.sumOf { it.msPlayed })
                SpotifyMediaItem(
                    title = base.trackName,
                    subtitle = "${base.artistName.ifBlank { "Artista desconhecido" }} - ${count}x - ${minutes} min",
                    imageUrl = null
                ) to (count * 1000 + minutes)
            }
            .sortedByDescending { it.second }
            .take(50)
            .map { it.first }

        val topArtists = sortedPlays
            .groupBy { it.artistName.ifBlank { "Artista desconhecido" } }
            .map { (artist, artistPlays) ->
                val minutes = toRoundedMinutes(artistPlays.sumOf { it.msPlayed })
                SpotifyMediaItem(
                    title = artist,
                    subtitle = "${artistPlays.size}x - ${minutes} min",
                    imageUrl = null
                ) to (artistPlays.size * 1000 + minutes)
            }
            .sortedByDescending { it.second }
            .take(50)
            .map { it.first }

        val recentlyPlayed = sortedPlays
            .distinctBy { it.trackKey }
            .take(40)
            .map {
                SpotifyMediaItem(
                    title = it.trackName,
                    subtitle = it.artistName.ifBlank { "Artista desconhecido" },
                    imageUrl = null
                )
            }

        val recentDays = dailySummaries.take(30)
        val recentWindowMinutes = recentDays.sumOf { it.minutesHeard }
        val recentWindowPlays = recentDays.sumOf { it.playsCount }
        val recentWindowUnique = recentDays
            .flatMap { it.topTracks.map { track -> "${track.title}|${track.subtitle}" } }
            .distinct()
            .size

        return SpotifyTopSummary(
            topTracks = topTracks,
            topArtists = topArtists,
            playlists = emptyList(),
            recentlyPlayed = recentlyPlayed,
            dailySummaries = dailySummaries,
            recentWindowMinutes = recentWindowMinutes,
            recentWindowPlays = recentWindowPlays,
            recentWindowUniqueTracks = recentWindowUnique,
            likedSongsCount = 0,
            followedArtistsCount = topArtists.size,
            playlistsCount = 0
        )
    }

    private fun toRoundedMinutes(ms: Long): Int {
        if (ms <= 0L) {
            return 0
        }
        return (ms / 60000.0).roundToInt().coerceAtLeast(1)
    }

    private fun resolveFileName(uri: Uri): String? {
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) {
                    return@use null
                }
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx < 0) null else cursor.getString(idx)
            }
        }.getOrNull()
    }

    private fun openInputStream(uri: Uri): InputStream {
        return context.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("Nao foi possivel abrir o arquivo selecionado.")
    }

    private fun readBytes(input: InputStream): ByteArray {
        val buffer = ByteArray(16 * 1024)
        val output = ByteArrayOutputStream()
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) {
                break
            }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private data class ImportedPlay(
        val trackName: String,
        val artistName: String,
        val playedAt: Instant,
        val msPlayed: Long,
        val trackKey: String
    )

    companion object {
        private val LEGACY_EXPORT_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        private val LEGACY_EXPORT_FORMAT_SECONDS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    }
}
