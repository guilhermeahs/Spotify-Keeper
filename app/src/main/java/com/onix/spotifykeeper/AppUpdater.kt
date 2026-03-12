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

                val endpoint = "https://api.github.com/repos/$owner/$repo/releases/latest"
                val connection = URL(endpoint).openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 7000
                connection.readTimeout = 7000
                connection.setRequestProperty("Accept", "application/vnd.github+json")

                val responseCode = connection.responseCode
                if (responseCode == HttpURLConnection.HTTP_NOT_FOUND) {
                    return@runCatching null
                }
                if (responseCode !in 200..299) {
                    throw IllegalStateException("Falha HTTP $responseCode ao buscar release")
                }

                val body = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(body)
                val tagName = json.optString("tag_name")
                if (tagName.isBlank()) {
                    return@runCatching null
                }

                val latestCode = extractTrailingNumber(tagName) ?: return@runCatching null
                val currentCode = getCurrentVersionCode()
                if (latestCode <= currentCode) {
                    return@runCatching null
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
                    return@runCatching null
                }

                UpdateInfo(tagName = tagName, downloadUrl = apkUrl)
            }.also { result ->
                mainHandler.post {
                    onComplete(result)
                }
            }
        }
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
