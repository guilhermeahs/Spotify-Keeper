package com.onix.spotifykeeper

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class SpotifyTopSummary(
    val topTracks: List<String>,
    val topArtists: List<String>,
    val playlists: List<String>,
    val recentlyPlayed: List<String>
)

class SpotifyInsightsService {

    fun fetchTopSummary(accessToken: String): SpotifyTopSummary {
        val topTracksJson = getJson(accessToken, "https://api.spotify.com/v1/me/top/tracks?limit=10&time_range=medium_term")
        val topArtistsJson = getJson(accessToken, "https://api.spotify.com/v1/me/top/artists?limit=10&time_range=medium_term")
        val playlistsJson = getJson(accessToken, "https://api.spotify.com/v1/me/playlists?limit=10")
        val recentJson = getJson(accessToken, "https://api.spotify.com/v1/me/player/recently-played?limit=20")

        return SpotifyTopSummary(
            topTracks = parseTopTracks(topTracksJson),
            topArtists = parseTopArtists(topArtistsJson),
            playlists = parsePlaylists(playlistsJson),
            recentlyPlayed = parseRecentlyPlayed(recentJson)
        )
    }

    private fun parseTopTracks(json: JSONObject): List<String> {
        val items = json.optJSONArray("items") ?: return emptyList()
        return (0 until items.length()).mapNotNull { index ->
            val item = items.optJSONObject(index) ?: return@mapNotNull null
            val name = item.optString("name")
            val artists = item.optJSONArray("artists")
            val artist = artists?.optJSONObject(0)?.optString("name").orEmpty()
            if (name.isBlank()) return@mapNotNull null
            if (artist.isBlank()) name else "$name - $artist"
        }
    }

    private fun parseTopArtists(json: JSONObject): List<String> {
        val items = json.optJSONArray("items") ?: return emptyList()
        return (0 until items.length()).mapNotNull { index ->
            items.optJSONObject(index)?.optString("name")?.takeIf { it.isNotBlank() }
        }
    }

    private fun parsePlaylists(json: JSONObject): List<String> {
        val items = json.optJSONArray("items") ?: return emptyList()
        return (0 until items.length()).mapNotNull { index ->
            items.optJSONObject(index)?.optString("name")?.takeIf { it.isNotBlank() }
        }
    }

    private fun parseRecentlyPlayed(json: JSONObject): List<String> {
        val items = json.optJSONArray("items") ?: return emptyList()
        return (0 until items.length()).mapNotNull { index ->
            val item = items.optJSONObject(index) ?: return@mapNotNull null
            val track = item.optJSONObject("track") ?: return@mapNotNull null
            val name = track.optString("name")
            val artists = track.optJSONArray("artists")
            val artist = artists?.optJSONObject(0)?.optString("name").orEmpty()
            if (name.isBlank()) return@mapNotNull null
            if (artist.isBlank()) name else "$name - $artist"
        }.distinct()
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
}
