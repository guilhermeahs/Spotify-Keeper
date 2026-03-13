package com.onix.spotifykeeper

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class SpotifyDailyHistoryStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun mergeLatest(latest: List<SpotifyDailySummary>): List<SpotifyDailySummary> {
        if (latest.isEmpty()) {
            return load()
        }

        val merged = load().associateBy { it.dateIso }.toMutableMap()
        latest.forEach { day ->
            merged[day.dateIso] = day
        }

        val ordered = merged.values
            .sortedByDescending { it.dateIso }
            .take(MAX_DAYS_TO_KEEP)

        save(ordered)
        return ordered
    }

    private fun load(): List<SpotifyDailySummary> {
        val raw = prefs.getString(KEY_DAILY_SUMMARIES, "").orEmpty()
        if (raw.isBlank()) {
            return emptyList()
        }

        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val dateIso = item.optString("dateIso")
                if (dateIso.isBlank()) {
                    return@mapNotNull null
                }

                SpotifyDailySummary(
                    dateIso = dateIso,
                    dateLabel = item.optString("dateLabel").ifBlank { dateIso },
                    minutesHeard = item.optInt("minutesHeard", 0).coerceAtLeast(0),
                    playsCount = item.optInt("playsCount", 0).coerceAtLeast(0),
                    uniqueTracksCount = item.optInt("uniqueTracksCount", 0).coerceAtLeast(0),
                    topTracks = parseTracks(item.optJSONArray("topTracks"))
                )
            }.sortedByDescending { it.dateIso }
        }.getOrDefault(emptyList())
    }

    private fun save(days: List<SpotifyDailySummary>) {
        prefs.edit()
            .putString(KEY_DAILY_SUMMARIES, toJson(days).toString())
            .apply()
    }

    private fun toJson(days: List<SpotifyDailySummary>): JSONArray {
        return JSONArray().apply {
            days.forEach { day ->
                put(
                    JSONObject().apply {
                        put("dateIso", day.dateIso)
                        put("dateLabel", day.dateLabel)
                        put("minutesHeard", day.minutesHeard)
                        put("playsCount", day.playsCount)
                        put("uniqueTracksCount", day.uniqueTracksCount)
                        put("topTracks", toTracksJson(day.topTracks))
                    }
                )
            }
        }
    }

    private fun toTracksJson(items: List<SpotifyMediaItem>): JSONArray {
        return JSONArray().apply {
            items.forEach { item ->
                put(
                    JSONObject().apply {
                        put("title", item.title)
                        put("subtitle", item.subtitle.orEmpty())
                        put("imageUrl", item.imageUrl.orEmpty())
                    }
                )
            }
        }
    }

    private fun parseTracks(array: JSONArray?): List<SpotifyMediaItem> {
        if (array == null) {
            return emptyList()
        }
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val title = item.optString("title")
            if (title.isBlank()) {
                return@mapNotNull null
            }
            SpotifyMediaItem(
                title = title,
                subtitle = item.optString("subtitle").takeIf { it.isNotBlank() },
                imageUrl = item.optString("imageUrl").takeIf { it.isNotBlank() }
            )
        }
    }

    companion object {
        private const val PREFS_NAME = "spotify_keeper_daily_history"
        private const val KEY_DAILY_SUMMARIES = "daily_summaries_v1"
        private const val MAX_DAYS_TO_KEEP = 120
    }
}
