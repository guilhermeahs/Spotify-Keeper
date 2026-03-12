package com.onix.spotifykeeper

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class SpotifyMediaItem(
    val title: String,
    val subtitle: String? = null,
    val imageUrl: String? = null
)

data class SpotifyTopSummary(
    val topTracks: List<SpotifyMediaItem>,
    val topArtists: List<SpotifyMediaItem>,
    val playlists: List<SpotifyMediaItem>,
    val recentlyPlayed: List<SpotifyMediaItem>
) {

    fun toJsonString(): String {
        return JSONObject().apply {
            put("topTracks", toJsonArray(topTracks))
            put("topArtists", toJsonArray(topArtists))
            put("playlists", toJsonArray(playlists))
            put("recentlyPlayed", toJsonArray(recentlyPlayed))
        }.toString()
    }

    private fun toJsonArray(items: List<SpotifyMediaItem>): JSONArray {
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

    companion object {
        fun fromJsonString(json: String): SpotifyTopSummary {
            val root = JSONObject(json)
            return SpotifyTopSummary(
                topTracks = parseArray(root.optJSONArray("topTracks")),
                topArtists = parseArray(root.optJSONArray("topArtists")),
                playlists = parseArray(root.optJSONArray("playlists")),
                recentlyPlayed = parseArray(root.optJSONArray("recentlyPlayed"))
            )
        }

        private fun parseArray(array: JSONArray?): List<SpotifyMediaItem> {
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
    }
}

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

    private fun parseRecentlyPlayed(json: JSONObject): List<SpotifyMediaItem> {
        val items = json.optJSONArray("items") ?: return emptyList()
        val seenUris = mutableSetOf<String>()
        val list = mutableListOf<SpotifyMediaItem>()

        for (index in 0 until items.length()) {
            val item = items.optJSONObject(index) ?: continue
            val track = item.optJSONObject("track") ?: continue
            val uri = track.optString("uri")
            if (uri.isNotBlank() && !seenUris.add(uri)) {
                continue
            }

            val name = track.optString("name")
            val artist = track.optJSONArray("artists")?.optJSONObject(0)?.optString("name").orEmpty()
            val imageUrl = track.optJSONObject("album")
                ?.optJSONArray("images")
                ?.optJSONObject(0)
                ?.optString("url")
            if (name.isBlank()) continue

            list.add(
                SpotifyMediaItem(
                    title = name,
                    subtitle = artist.ifBlank { null },
                    imageUrl = imageUrl?.takeIf { it.isNotBlank() }
                )
            )
        }

        return list
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
