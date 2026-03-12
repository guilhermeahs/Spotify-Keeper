package com.onix.spotifykeeper

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.onix.spotifykeeper.databinding.ActivityMainBinding
import com.spotify.sdk.android.auth.AuthorizationClient
import com.spotify.sdk.android.auth.AuthorizationRequest
import com.spotify.sdk.android.auth.AuthorizationResponse
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var spotifyController: SpotifyController
    private lateinit var appUpdater: AppUpdater
    private lateinit var spotifyInsightsService: SpotifyInsightsService

    private val ioExecutor = Executors.newSingleThreadExecutor()

    private var webApiAccessToken: String? = null
    private var pendingTopRequest = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        spotifyController = SpotifyController(this) { status ->
            renderStatus(status)
        }
        appUpdater = AppUpdater(this)
        spotifyInsightsService = SpotifyInsightsService()

        bindActions()
        renderStatus("Projeto iniciado. Conecte ao Spotify.")
        checkForUpdates(silent = true)
    }

    private fun bindActions() {
        binding.connectButton.setOnClickListener {
            renderStatus(spotifyController.connect())
        }

        binding.playButton.setOnClickListener {
            val uri = binding.playlistInput.text?.toString()?.trim().orEmpty()
            val result = if (uri.isBlank()) {
                spotifyController.playCurrent()
            } else {
                spotifyController.playPlaylist(uri)
            }
            renderStatus(result)
        }

        binding.pauseButton.setOnClickListener {
            renderStatus(spotifyController.pause())
        }

        binding.resumeButton.setOnClickListener {
            renderStatus(spotifyController.resume())
        }

        binding.checkStatusButton.setOnClickListener {
            val snapshot = spotifyController.getStatus()
            renderStatus(
                "connected=${snapshot.connected} | paused=${snapshot.isPaused} | positionMs=${snapshot.playbackPositionMs}\n${snapshot.message}"
            )
        }

        binding.topStatsButton.setOnClickListener {
            loadTopStats()
        }

        binding.updateButton.setOnClickListener {
            checkForUpdates(silent = false)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode != REQUEST_CODE_SPOTIFY_AUTH) {
            return
        }

        val response = AuthorizationClient.getResponse(resultCode, data)
        when (response.type) {
            AuthorizationResponse.Type.TOKEN -> {
                webApiAccessToken = response.accessToken
                renderStatus("Autorizacao concluida para carregar tops.")
                if (pendingTopRequest) {
                    pendingTopRequest = false
                    loadTopStats()
                }
            }

            AuthorizationResponse.Type.ERROR -> {
                pendingTopRequest = false
                renderStatus("Falha na autorizacao para tops: ${response.error}")
            }

            else -> {
                pendingTopRequest = false
                renderStatus("Autorizacao para tops cancelada.")
            }
        }
    }

    override fun onDestroy() {
        if (::spotifyController.isInitialized) {
            spotifyController.disconnect()
        }
        ioExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun renderStatus(text: String) {
        binding.statusText.text = "Status:\n$text"
    }

    private fun checkForUpdates(silent: Boolean) {
        appUpdater.checkForUpdate { result ->
            result.onSuccess { update ->
                if (update == null) {
                    if (!silent) {
                        renderStatus("Seu app ja esta atualizado.")
                    }
                    return@onSuccess
                }
                AlertDialog.Builder(this)
                    .setTitle("Atualizacao disponivel")
                    .setMessage("Nova versao ${update.tagName} encontrada. Deseja baixar agora?")
                    .setNegativeButton("Depois", null)
                    .setPositiveButton("Atualizar") { _, _ ->
                        renderStatus("Abrindo download da atualizacao...")
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(update.downloadUrl)))
                    }
                    .show()
            }.onFailure { error ->
                if (!silent) {
                    renderStatus("Falha ao checar atualizacao: ${error.message}")
                }
            }
        }
    }

    private fun loadTopStats() {
        val token = webApiAccessToken
        if (token.isNullOrBlank()) {
            pendingTopRequest = true
            renderStatus("Autorizando acesso aos seus tops no Spotify...")
            requestTopAuthorization()
            return
        }

        renderStatus("Carregando tops de musicas, artistas e playlists...")
        ioExecutor.execute {
            val result = runCatching {
                spotifyInsightsService.fetchTopSummary(token)
            }

            runOnUiThread {
                result.onSuccess { summary ->
                    openTopStatsScreen(summary)
                }.onFailure { error ->
                    if (error.message == "TOKEN_EXPIRED") {
                        webApiAccessToken = null
                        renderStatus("Token expirou. Toque em 'Ver tops' novamente para autorizar.")
                    } else {
                        renderStatus("Falha ao carregar tops: ${error.message}")
                    }
                }
            }
        }
    }

    private fun requestTopAuthorization() {
        val request = AuthorizationRequest.Builder(
            BuildConfig.SPOTIFY_CLIENT_ID,
            AuthorizationResponse.Type.TOKEN,
            BuildConfig.SPOTIFY_REDIRECT_URI
        )
            .setScopes(TOP_SCOPES)
            .setShowDialog(true)
            .build()

        AuthorizationClient.openLoginActivity(this, REQUEST_CODE_SPOTIFY_AUTH, request)
    }

    private fun openTopStatsScreen(summary: SpotifyTopSummary) {
        val intent = Intent(this, TopStatsActivity::class.java).apply {
            putStringArrayListExtra(
                TopStatsActivity.EXTRA_TOP_TRACKS,
                ArrayList(summary.topTracks)
            )
            putStringArrayListExtra(
                TopStatsActivity.EXTRA_TOP_ARTISTS,
                ArrayList(summary.topArtists)
            )
            putStringArrayListExtra(
                TopStatsActivity.EXTRA_PLAYLISTS,
                ArrayList(summary.playlists)
            )
            putStringArrayListExtra(
                TopStatsActivity.EXTRA_RECENT,
                ArrayList(summary.recentlyPlayed)
            )
        }
        startActivity(intent)
    }

    private companion object {
        const val REQUEST_CODE_SPOTIFY_AUTH = 9876
        val TOP_SCOPES = arrayOf(
            "user-top-read",
            "user-read-recently-played",
            "playlist-read-private",
            "playlist-read-collaborative"
        )
    }
}
