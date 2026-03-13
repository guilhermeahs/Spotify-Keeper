package com.onix.spotifykeeper

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

data class SpotifyMediaItem(
    val title: String,
    val subtitle: String? = null,
    val imageUrl: String? = null
)

data class SpotifyDailySummary(
    val dateIso: String,
    val dateLabel: String,
    val minutesHeard: Int,
    val playsCount: Int,
    val uniqueTracksCount: Int,
    val topTracks: List<SpotifyMediaItem>
)

data class SpotifyTopSummary(
    val topTracks: List<SpotifyMediaItem>,
    val topArtists: List<SpotifyMediaItem>,
    val playlists: List<SpotifyMediaItem>,
    val recentlyPlayed: List<SpotifyMediaItem>,
    val dailySummaries: List<SpotifyDailySummary>,
    val recentWindowMinutes: Int,
    val recentWindowPlays: Int,
    val recentWindowUniqueTracks: Int,
    val likedSongsCount: Int,
    val followedArtistsCount: Int,
    val playlistsCount: Int
) {

    fun toJsonString(): String {
        return JSONObject().apply {
            put("topTracks", toMediaJsonArray(topTracks))
            put("topArtists", toMediaJsonArray(topArtists))
            put("playlists", toMediaJsonArray(playlists))
            put("recentlyPlayed", toMediaJsonArray(recentlyPlayed))
            put("dailySummaries", toDailyJsonArray(dailySummaries))
            put("recentWindowMinutes", recentWindowMinutes)
            put("recentWindowPlays", recentWindowPlays)
            put("recentWindowUniqueTracks", recentWindowUniqueTracks)
            put("likedSongsCount", likedSongsCount)
            put("followedArtistsCount", followedArtistsCount)
            put("playlistsCount", playlistsCount)
        }.toString()
    }

    private fun toMediaJsonArray(items: List<SpotifyMediaItem>): JSONArray {
        return JSONArray().apply {
            items.forEach { item ->
                put(
                    JSONObject().apply {
                        put("title", item.title)
                        put("subtitle", item.subtitle ?: "")
                        put("imageUrl", item.imageUrl ?: "")
                    }
                )
            }
        }
    }

    private fun toDailyJsonArray(items: List<SpotifyDailySummary>): JSONArray {
        return JSONArray().apply {
            items.forEach { day ->
                put(
                    JSONObject().apply {
                        put("dateIso", day.dateIso)
                        put("dateLabel", day.dateLabel)
                        put("minutesHeard", day.minutesHeard)
                        put("playsCount", day.playsCount)
                        put("uniqueTracksCount", day.uniqueTracksCount)
                        put("topTracks", toMediaJsonArray(day.topTracks))
                    }
                )
            }
        }
    }

    companion object {
        fun fromJsonString(json: String): SpotifyTopSummary {
            val root = JSONObject(json)
            return SpotifyTopSummary(
                topTracks = parseMediaArray(root.optJSONArray("topTracks")),
                topArtists = parseMediaArray(root.optJSONArray("topArtists")),
                playlists = parseMediaArray(root.optJSONArray("playlists")),
                recentlyPlayed = parseMediaArray(root.optJSONArray("recentlyPlayed")),
                dailySummaries = parseDailyArray(root.optJSONArray("dailySummaries")),
                recentWindowMinutes = root.optInt("recentWindowMinutes", 0).coerceAtLeast(0),
                recentWindowPlays = root.optInt("recentWindowPlays", 0).coerceAtLeast(0),
                recentWindowUniqueTracks = root.optInt("recentWindowUniqueTracks", 0).coerceAtLeast(0),
                likedSongsCount = root.optInt("likedSongsCount", 0).coerceAtLeast(0),
                followedArtistsCount = root.optInt("followedArtistsCount", 0).coerceAtLeast(0),
                playlistsCount = root.optInt("playlistsCount", 0).coerceAtLeast(0)
            )
        }

        private fun parseMediaArray(array: JSONArray?): List<SpotifyMediaItem> {
            if (array == null) return emptyList()
            return (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val title = item.optString("title")
                if (title.isBlank()) return@mapNotNull null
                SpotifyMediaItem(
                    title = title,
                    subtitle = item.optString("subtitle").takeIf { it.isNotBlank() },
                    imageUrl = item.optString("imageUrl").takeIf { it.isNotBlank() }
                )
            }
        }

        private fun parseDailyArray(array: JSONArray?): List<SpotifyDailySummary> {
            if (array == null) return emptyList()
            return (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val dateIso = item.optString("dateIso")
                if (dateIso.isBlank()) return@mapNotNull null

                SpotifyDailySummary(
                    dateIso = dateIso,
                    dateLabel = item.optString("dateLabel").ifBlank { dateIso },
                    minutesHeard = item.optInt("minutesHeard", 0).coerceAtLeast(0),
                    playsCount = item.optInt("playsCount", 0).coerceAtLeast(0),
                    uniqueTracksCount = item.optInt("uniqueTracksCount", 0).coerceAtLeast(0),
                    topTracks = parseMediaArray(item.optJSONArray("topTracks"))
                )
            }
        }
    }
}

class SpotifyInsightsService(context: Context) {

    private val dailyHistoryStore = SpotifyDailyHistoryStore(context.applicationContext)

    fun fetchTopSummary(accessToken: String): SpotifyTopSummary {
        val topTracksJson = getJson(accessToken, "https://api.spotify.com/v1/me/top/tracks?limit=10&time_range=medium_term")
        val topArtistsJson = getJson(accessToken, "https://api.spotify.com/v1/me/top/artists?limit=10&time_range=medium_term")
        val playlistsJson = getJson(accessToken, "https://api.spotify.com/v1/me/playlists?limit=10")

        val recentEntries = fetchRecentEntries(accessToken, maxPages = 10, maxItems = 500)
        val recentUniqueTracks = buildRecentUniqueTracks(recentEntries, limit = 30)
        val dailySummaries = buildDailySummaries(recentEntries, maxDays = 30)
        val mergedDailySummaries = dailyHistoryStore.mergeLatest(dailySummaries)

        val likedSongsCount = fetchSimpleTotal(accessToken, "https://api.spotify.com/v1/me/tracks?limit=1")
        val followedArtistsCount = fetchFollowedArtistsTotal(accessToken)
        val playlistsCount = playlistsJson.optInt("total", 0).coerceAtLeast(0)

        return SpotifyTopSummary(
            topTracks = parseTopTracks(topTracksJson),
            topArtists = parseTopArtists(topArtistsJson),
            playlists = parsePlaylists(playlistsJson),
            recentlyPlayed = recentUniqueTracks,
            dailySummaries = mergedDailySummaries,
            recentWindowMinutes = calculateWindowMinutes(recentEntries),
            recentWindowPlays = recentEntries.size,
            recentWindowUniqueTracks = recentEntries.map { it.trackKey }.distinct().size,
            likedSongsCount = likedSongsCount,
            followedArtistsCount = followedArtistsCount,
            playlistsCount = playlistsCount
        )
    }

    fun enrichMissingArtwork(summary: SpotifyTopSummary, accessToken: String): SpotifyTopSummary {
        if (accessToken.isBlank()) {
            return summary
        }
        if (!hasMissingArtwork(summary)) {
            return summary
        }

        val budget = ArtworkLookupBudget(MAX_ARTWORK_LOOKUPS)
        val trackArtworkCache = mutableMapOf<String, String?>()
        val artistArtworkCache = mutableMapOf<String, String?>()

        val enrichedTopTracks = enrichTrackItems(
            items = summary.topTracks,
            accessToken = accessToken,
            budget = budget,
            trackArtworkCache = trackArtworkCache,
            artistArtworkCache = artistArtworkCache
        )
        val enrichedRecent = enrichTrackItems(
            items = summary.recentlyPlayed,
            accessToken = accessToken,
            budget = budget,
            trackArtworkCache = trackArtworkCache,
            artistArtworkCache = artistArtworkCache
        )
        val enrichedTopArtists = enrichArtistItems(
            items = summary.topArtists,
            accessToken = accessToken,
            budget = budget,
            artistArtworkCache = artistArtworkCache
        )
        val enrichedDaily = summary.dailySummaries.map { day ->
            day.copy(
                topTracks = enrichTrackItems(
                    items = day.topTracks,
                    accessToken = accessToken,
                    budget = budget,
                    trackArtworkCache = trackArtworkCache,
                    artistArtworkCache = artistArtworkCache
                )
            )
        }

        return summary.copy(
            topTracks = enrichedTopTracks,
            topArtists = enrichedTopArtists,
            recentlyPlayed = enrichedRecent,
            dailySummaries = enrichedDaily
        )
    }

    private fun parseTopTracks(json: JSONObject): List<SpotifyMediaItem> {
        val items = json.optJSONArray("items") ?: return emptyList()
        return (0 until items.length()).mapNotNull { index ->
            val item = items.optJSONObject(index) ?: return@mapNotNull null
            val name = item.optString("name")
            val artist = item.optJSONArray("artists")?.optJSONObject(0)?.optString("name").orEmpty()
            val imageUrl = item.optJSONObject("album")
                ?.optJSONArray("images")
                ?.optJSONObject(0)
                ?.optString("url")
            if (name.isBlank()) return@mapNotNull null
            SpotifyMediaItem(
                title = name,
                subtitle = artist.ifBlank { null },
                imageUrl = imageUrl?.takeIf { it.isNotBlank() }
            )
        }
    }

    private fun parseTopArtists(json: JSONObject): List<SpotifyMediaItem> {
        val items = json.optJSONArray("items") ?: return emptyList()
        return (0 until items.length()).mapNotNull { index ->
            val item = items.optJSONObject(index) ?: return@mapNotNull null
            val name = item.optString("name")
            val genre = item.optJSONArray("genres")?.optString(0).orEmpty()
            val imageUrl = item.optJSONArray("images")?.optJSONObject(0)?.optString("url")
            if (name.isBlank()) return@mapNotNull null
            SpotifyMediaItem(
                title = name,
                subtitle = genre.ifBlank { null },
                imageUrl = imageUrl?.takeIf { it.isNotBlank() }
            )
        }
    }

    private fun parsePlaylists(json: JSONObject): List<SpotifyMediaItem> {
        val items = json.optJSONArray("items") ?: return emptyList()
        return (0 until items.length()).mapNotNull { index ->
            val item = items.optJSONObject(index) ?: return@mapNotNull null
            val name = item.optString("name")
            val owner = item.optJSONObject("owner")?.optString("display_name").orEmpty()
            val imageUrl = item.optJSONArray("images")?.optJSONObject(0)?.optString("url")
            if (name.isBlank()) return@mapNotNull null
            SpotifyMediaItem(
                title = name,
                subtitle = owner.ifBlank { null },
                imageUrl = imageUrl?.takeIf { it.isNotBlank() }
            )
        }
    }

    private fun fetchRecentEntries(accessToken: String, maxPages: Int, maxItems: Int): List<RecentEntry> {
        var endpoint: String? = "https://api.spotify.com/v1/me/player/recently-played?limit=50"
        val entries = mutableListOf<RecentEntry>()
        val visitedUrls = mutableSetOf<String>()
        var page = 0

        while (!endpoint.isNullOrBlank() && page < maxPages && entries.size < maxItems) {
            if (!visitedUrls.add(endpoint)) {
                break
            }

            val json = getJson(accessToken, endpoint)
            val parsed = parseRecentEntriesPage(json)
            if (parsed.isEmpty()) {
                break
            }

            val remaining = maxItems - entries.size
            entries.addAll(parsed.take(remaining))
            endpoint = json.optString("next").takeIf { it.isNotBlank() }
            page += 1
        }

        return entries.sortedByDescending { it.playedAt }
    }

    private fun parseRecentEntriesPage(json: JSONObject): List<RecentEntry> {
        val items = json.optJSONArray("items") ?: return emptyList()
        val parsed = mutableListOf<RecentEntry>()

        for (index in 0 until items.length()) {
            val item = items.optJSONObject(index) ?: continue
            val track = item.optJSONObject("track") ?: continue

            val name = track.optString("name")
            if (name.isBlank()) {
                continue
            }

            val artist = track.optJSONArray("artists")?.optJSONObject(0)?.optString("name").orEmpty()
            val imageUrl = track.optJSONObject("album")
                ?.optJSONArray("images")
                ?.optJSONObject(0)
                ?.optString("url")

            val playedAtText = item.optString("played_at")
            val playedAt = runCatching { Instant.parse(playedAtText) }.getOrNull() ?: continue

            val durationMs = track.optLong("duration_ms", 0L).coerceAtLeast(0L)
            val trackKey = track.optString("uri").takeIf { it.isNotBlank() }
                ?: "$name|$artist"

            parsed.add(
                RecentEntry(
                    media = SpotifyMediaItem(
                        title = name,
                        subtitle = artist.ifBlank { null },
                        imageUrl = imageUrl?.takeIf { it.isNotBlank() }
                    ),
                    playedAt = playedAt,
                    durationMs = durationMs,
                    trackKey = trackKey
                )
            )
        }

        return parsed
    }

    private fun buildRecentUniqueTracks(entries: List<RecentEntry>, limit: Int): List<SpotifyMediaItem> {
        val seen = mutableSetOf<String>()
        val list = mutableListOf<SpotifyMediaItem>()

        entries.forEach { entry ->
            if (!seen.add(entry.trackKey)) {
                return@forEach
            }
            list.add(entry.media)
            if (list.size >= limit) {
                return list
            }
        }

        return list
    }

    private fun buildDailySummaries(entries: List<RecentEntry>, maxDays: Int): List<SpotifyDailySummary> {
        if (entries.isEmpty()) {
            return emptyList()
        }

        val zone = ZoneId.systemDefault()
        val formatter = DateTimeFormatter.ofPattern("dd/MM")
        val grouped = entries.groupBy { it.playedAt.atZone(zone).toLocalDate() }

        return grouped.entries
            .sortedByDescending { it.key }
            .take(maxDays)
            .map { (date, dayEntries) ->
                val totalMs = dayEntries.sumOf { it.durationMs }
                val minutes = toRoundedMinutes(totalMs)
                val uniqueTracksCount = dayEntries.map { it.trackKey }.distinct().size
                val rankedTracks = dayEntries
                    .groupBy { it.trackKey }
                    .values
                    .map { sameTrackEntries ->
                        val base = sameTrackEntries.first().media
                        val plays = sameTrackEntries.size
                        val duration = sameTrackEntries.sumOf { it.durationMs }
                        val minutesForTrack = toRoundedMinutes(duration)
                        val subtitleParts = mutableListOf<String>()
                        if (!base.subtitle.isNullOrBlank()) {
                            subtitleParts += base.subtitle
                        }
                        subtitleParts += "${plays}x"
                        subtitleParts += "${minutesForTrack} min"
                        TrackRanking(
                            media = SpotifyMediaItem(
                                title = base.title,
                                subtitle = subtitleParts.joinToString(" - "),
                                imageUrl = base.imageUrl
                            ),
                            plays = plays,
                            durationMs = duration
                        )
                    }
                    .sortedWith(
                        compareByDescending<TrackRanking> { it.plays }
                            .thenByDescending { it.durationMs }
                    )
                    .take(5)
                    .map { it.media }

                SpotifyDailySummary(
                    dateIso = date.toString(),
                    dateLabel = formatter.format(date),
                    minutesHeard = minutes,
                    playsCount = dayEntries.size,
                    uniqueTracksCount = uniqueTracksCount,
                    topTracks = rankedTracks
                )
            }
    }

    private fun fetchSimpleTotal(accessToken: String, endpoint: String): Int {
        return runCatching {
            getJson(accessToken, endpoint).optInt("total", 0).coerceAtLeast(0)
        }.getOrDefault(0)
    }

    private fun enrichTrackItems(
        items: List<SpotifyMediaItem>,
        accessToken: String,
        budget: ArtworkLookupBudget,
        trackArtworkCache: MutableMap<String, String?>,
        artistArtworkCache: MutableMap<String, String?>
    ): List<SpotifyMediaItem> {
        return items.map { item ->
            if (!item.imageUrl.isNullOrBlank()) {
                return@map item
            }

            val trackKey = normalizeTrackLookupKey(item.title, extractArtistFromSubtitle(item.subtitle))
            val cached = if (trackArtworkCache.containsKey(trackKey)) trackArtworkCache[trackKey] else null
            val imageUrl = if (cached != null || trackArtworkCache.containsKey(trackKey)) {
                cached
            } else {
                val artist = extractArtistFromSubtitle(item.subtitle)
                val trackImage = resolveTrackArtwork(
                    accessToken = accessToken,
                    title = item.title,
                    artist = artist,
                    budget = budget
                )
                val fallbackArtistImage = if (trackImage.isNullOrBlank() && !artist.isNullOrBlank()) {
                    resolveArtistArtwork(
                        accessToken = accessToken,
                        artist = artist,
                        budget = budget,
                        artistArtworkCache = artistArtworkCache
                    )
                } else {
                    null
                }
                (trackImage ?: fallbackArtistImage).also { found ->
                    trackArtworkCache[trackKey] = found
                }
            }

            if (imageUrl.isNullOrBlank()) item else item.copy(imageUrl = imageUrl)
        }
    }

    private fun enrichArtistItems(
        items: List<SpotifyMediaItem>,
        accessToken: String,
        budget: ArtworkLookupBudget,
        artistArtworkCache: MutableMap<String, String?>
    ): List<SpotifyMediaItem> {
        return items.map { item ->
            if (!item.imageUrl.isNullOrBlank()) {
                return@map item
            }
            val image = resolveArtistArtwork(
                accessToken = accessToken,
                artist = item.title,
                budget = budget,
                artistArtworkCache = artistArtworkCache
            )
            if (image.isNullOrBlank()) item else item.copy(imageUrl = image)
        }
    }

    private fun resolveTrackArtwork(
        accessToken: String,
        title: String,
        artist: String?,
        budget: ArtworkLookupBudget
    ): String? {
        if (!budget.consume()) {
            return null
        }
        return runCatching {
            searchTrackArtwork(accessToken, title, artist)
        }.getOrNull()
    }

    private fun resolveArtistArtwork(
        accessToken: String,
        artist: String,
        budget: ArtworkLookupBudget,
        artistArtworkCache: MutableMap<String, String?>
    ): String? {
        val normalized = artist.trim().lowercase()
        if (artistArtworkCache.containsKey(normalized)) {
            return artistArtworkCache[normalized]
        }
        if (!budget.consume()) {
            artistArtworkCache[normalized] = null
            return null
        }

        val image = runCatching {
            searchArtistArtwork(accessToken, artist)
        }.getOrNull()
        artistArtworkCache[normalized] = image
        return image
    }

    private fun searchTrackArtwork(accessToken: String, title: String, artist: String?): String? {
        val query = if (artist.isNullOrBlank()) {
            "track:$title"
        } else {
            "track:$title artist:$artist"
        }
        val endpoint = "https://api.spotify.com/v1/search?type=track&limit=1&q=${query.urlEncode()}"
        val json = getJson(accessToken, endpoint)
        val firstTrack = json.optJSONObject("tracks")
            ?.optJSONArray("items")
            ?.optJSONObject(0)
            ?: return null
        return firstTrack.optJSONObject("album")
            ?.optJSONArray("images")
            ?.optJSONObject(0)
            ?.optString("url")
            ?.takeIf { it.isNotBlank() }
    }

    private fun searchArtistArtwork(accessToken: String, artist: String): String? {
        val endpoint = "https://api.spotify.com/v1/search?type=artist&limit=1&q=${artist.urlEncode()}"
        val json = getJson(accessToken, endpoint)
        val firstArtist = json.optJSONObject("artists")
            ?.optJSONArray("items")
            ?.optJSONObject(0)
            ?: return null
        return firstArtist.optJSONArray("images")
            ?.optJSONObject(0)
            ?.optString("url")
            ?.takeIf { it.isNotBlank() }
    }

    private fun hasMissingArtwork(summary: SpotifyTopSummary): Boolean {
        if (summary.topTracks.any { it.imageUrl.isNullOrBlank() }) return true
        if (summary.topArtists.any { it.imageUrl.isNullOrBlank() }) return true
        if (summary.recentlyPlayed.any { it.imageUrl.isNullOrBlank() }) return true
        return summary.dailySummaries.any { day -> day.topTracks.any { it.imageUrl.isNullOrBlank() } }
    }

    private fun extractArtistFromSubtitle(subtitle: String?): String? {
        val raw = subtitle.orEmpty().trim()
        if (raw.isBlank()) {
            return null
        }
        val artist = raw.substringBefore(" - ").substringBefore(" • ").trim()
        if (artist.matches(Regex("""\d+\s*x""", RegexOption.IGNORE_CASE))) {
            return null
        }
        if (artist.matches(Regex("""\d+\s*min""", RegexOption.IGNORE_CASE))) {
            return null
        }
        return artist.ifBlank { null }
    }

    private fun normalizeTrackLookupKey(title: String, artist: String?): String {
        val normalizedTitle = title.trim().lowercase()
        val normalizedArtist = artist.orEmpty().trim().lowercase()
        return "$normalizedTitle::$normalizedArtist"
    }

    private fun String.urlEncode(): String = URLEncoder.encode(this, Charsets.UTF_8.name())

    private fun fetchFollowedArtistsTotal(accessToken: String): Int {
        return runCatching {
            val json = getJson(accessToken, "https://api.spotify.com/v1/me/following?type=artist&limit=1")
            json.optJSONObject("artists")?.optInt("total", 0)?.coerceAtLeast(0) ?: 0
        }.getOrDefault(0)
    }

    private fun calculateWindowMinutes(entries: List<RecentEntry>): Int {
        return toRoundedMinutes(entries.sumOf { it.durationMs })
    }

    private fun toRoundedMinutes(durationMs: Long): Int {
        if (durationMs <= 0L) return 0
        val rounded = (durationMs / 60000.0).roundToInt()
        return rounded.coerceAtLeast(1)
    }

    private fun getJson(accessToken: String, endpoint: String): JSONObject {
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 10000
        connection.readTimeout = 10000
        connection.setRequestProperty("Authorization", "Bearer $accessToken")
        connection.setRequestProperty("Accept", "application/json")

        val code = connection.responseCode
        val body = runCatching {
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        }.getOrDefault("")

        if (code == 401) {
            throw IllegalStateException("TOKEN_EXPIRED")
        }
        if (code !in 200..299) {
            throw IllegalStateException("Spotify Web API retornou HTTP $code.")
        }
        if (body.isBlank()) {
            throw IllegalStateException("Resposta vazia da Spotify Web API.")
        }
        return JSONObject(body)
    }

    private data class RecentEntry(
        val media: SpotifyMediaItem,
        val playedAt: Instant,
        val durationMs: Long,
        val trackKey: String
    )

    private data class TrackRanking(
        val media: SpotifyMediaItem,
        val plays: Int,
        val durationMs: Long
    )

    private data class ArtworkLookupBudget(var remaining: Int) {
        fun consume(): Boolean {
            if (remaining <= 0) {
                return false
            }
            remaining -= 1
            return true
        }
    }

    companion object {
        private const val MAX_ARTWORK_LOOKUPS = 120
    }
}
