package com.onix.spotifykeeper

import android.content.Context

class SpotifyTopCacheStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun save(summary: SpotifyTopSummary) {
        prefs.edit()
            .putString(KEY_TOP_SUMMARY_JSON, summary.toJsonString())
            .putLong(KEY_TOP_SUMMARY_UPDATED_AT, System.currentTimeMillis())
            .apply()
    }

    fun load(): SpotifyTopSummary? {
        val json = prefs.getString(KEY_TOP_SUMMARY_JSON, "").orEmpty()
        if (json.isBlank()) {
            return null
        }
        return runCatching { SpotifyTopSummary.fromJsonString(json) }.getOrNull()
    }

    fun lastUpdatedAtMs(): Long {
        return prefs.getLong(KEY_TOP_SUMMARY_UPDATED_AT, 0L)
    }

    companion object {
        private const val PREFS_NAME = "spotify_keeper_top_cache"
        private const val KEY_TOP_SUMMARY_JSON = "top_summary_json_v1"
        private const val KEY_TOP_SUMMARY_UPDATED_AT = "top_summary_updated_at_ms"
    }
}
