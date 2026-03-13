package com.onix.spotifykeeper

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

data class UpdateInfo(
    val tagName: String,
    val downloadUrl: String
)

class AppUpdater(private val context: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()

    fun checkForUpdate(onComplete: (Result<UpdateInfo?>) -> Unit) {
        executor.execute {
            runCatching {
                val owner = BuildConfig.UPDATE_REPO_OWNER
                val repo = BuildConfig.UPDATE_REPO_NAME
                if (owner.isBlank() || repo.isBlank()) {
                    return@runCatching null
                }

                val currentCode = getCurrentVersionCode()
                val apiInfo = fetchLatestFromApi(owner, repo)
                val latest = apiInfo ?: fetchLatestFromReleaseRedirect(owner, repo)
                if (latest == null) {
                    return@runCatching null
                }

                val latestCode = extractTrailingNumber(latest.tagName) ?: return@runCatching null
                if (latestCode <= currentCode) {
                    return@runCatching null
                }

                latest
            }.also { result ->
                mainHandler.post {
                    onComplete(result)
                }
            }
        }
    }

    private fun fetchLatestFromApi(owner: String, repo: String): UpdateInfo? {
        val endpoint = "https://api.github.com/repos/$owner/$repo/releases/latest"
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 7000
        connection.readTimeout = 7000
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        connection.setRequestProperty("User-Agent", "${context.packageName}/${BuildConfig.VERSION_NAME}")

        val responseCode = connection.responseCode
        if (responseCode == HttpURLConnection.HTTP_NOT_FOUND) {
            return null
        }
        if (responseCode == HttpURLConnection.HTTP_FORBIDDEN) {
            return null
        }
        if (responseCode !in 200..299) {
            throw IllegalStateException("Falha HTTP $responseCode ao buscar release")
        }

        val body = connection.inputStream.bufferedReader().use { it.readText() }
        val json = JSONObject(body)
        val tagName = json.optString("tag_name")
        if (tagName.isBlank()) {
            return null
        }

        val assets = json.optJSONArray("assets")
        var apkUrl = ""
        if (assets != null) {
            for (index in 0 until assets.length()) {
                val asset = assets.optJSONObject(index) ?: continue
                val assetName = asset.optString("name")
                if (assetName.endsWith(".apk", ignoreCase = true)) {
                    apkUrl = asset.optString("browser_download_url")
                    break
                }
            }
        }
        if (apkUrl.isBlank()) {
            apkUrl = json.optString("html_url")
        }
        if (apkUrl.isBlank()) {
            return null
        }
        return UpdateInfo(tagName = tagName, downloadUrl = apkUrl)
    }

    private fun fetchLatestFromReleaseRedirect(owner: String, repo: String): UpdateInfo? {
        val endpoint = "https://github.com/$owner/$repo/releases/latest"
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 7000
        connection.readTimeout = 7000
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("User-Agent", "${context.packageName}/${BuildConfig.VERSION_NAME}")

        val responseCode = connection.responseCode
        if (responseCode !in setOf(301, 302, 303, 307, 308)) {
            return null
        }

        val location = connection.getHeaderField("Location").orEmpty()
        val tagMatch = Regex(""".*/tag/([^/?#]+)""").find(location) ?: return null
        val tagName = tagMatch.groupValues.getOrNull(1).orEmpty()
        if (tagName.isBlank()) {
            return null
        }

        val apkUrl = "https://github.com/$owner/$repo/releases/download/$tagName/app-debug.apk"
        return UpdateInfo(tagName = tagName, downloadUrl = apkUrl)
    }

    private fun getCurrentVersionCode(): Long {
        return try {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode.toLong()
            }
        } catch (_: Exception) {
            1L
        }
    }

    private fun extractTrailingNumber(tag: String): Long? {
        val match = Regex("""(\d+)(?!.*\d)""").find(tag) ?: return null
        return match.value.toLongOrNull()
    }
}
