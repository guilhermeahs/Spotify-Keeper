package com.onix.spotifykeeper

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import coil.load
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
    private lateinit var topCacheStore: SpotifyTopCacheStore

    private val ioExecutor = Executors.newSingleThreadExecutor()

    private var webApiAccessToken: String? = null
    private var pendingTopRequest = false
    private var latestStatus: String = "Projeto iniciado. Conecte ao Spotify."
    private var cachedTopSummary: SpotifyTopSummary? = null
    private var lastNowPlayingArtworkKey: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        spotifyController = SpotifyControllerProvider.get(applicationContext)
        appUpdater = AppUpdater(this)
        spotifyInsightsService = SpotifyInsightsService(this)
        topCacheStore = SpotifyTopCacheStore(this)
        cachedTopSummary = topCacheStore.load()

        bindActions()
        renderNowPlaying(NowPlayingVisual(title = null, artist = null, artwork = null, artworkUrl = null))
        renderStatus(latestStatus)
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
            val cached = cachedTopSummary ?: topCacheStore.load()?.also { restored ->
                cachedTopSummary = restored
            }
            if (cached != null) {
                openTopStatsScreen(cached)
                if (!webApiAccessToken.isNullOrBlank()) {
                    loadTopStats(openOnSuccess = false, showLoadingStatus = false)
                }
            } else {
                loadTopStats(openOnSuccess = true, showLoadingStatus = true)
            }
        }

        binding.updateButton.setOnClickListener {
            checkForUpdates(silent = false)
        }

        binding.keepAliveStartButton.setOnClickListener {
            startKeepAliveMode()
        }

        binding.keepAliveStopButton.setOnClickListener {
            stopKeepAliveMode()
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
                    loadTopStats(openOnSuccess = true, showLoadingStatus = true)
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
        ioExecutor.shutdownNow()
        super.onDestroy()
    }

    override fun onStart() {
        super.onStart()
        spotifyController.setCallbacks(
            onStatusChanged = { status ->
                latestStatus = status
                renderStatus(status)
            },
            onNowPlayingChanged = { nowPlaying ->
                renderNowPlaying(nowPlaying)
            }
        )
    }

    override fun onStop() {
        spotifyController.clearCallbacks()
        super.onStop()
    }

    private fun renderStatus(text: String) {
        binding.statusText.text = "Status:\n$text"
    }

    private fun renderNowPlaying(visual: NowPlayingVisual) {
        val title = visual.title?.trim().orEmpty()
        val artist = visual.artist?.trim().orEmpty()
        val fallbackLetter = fallbackLetter(title)

        if (title.isBlank()) {
            binding.nowPlayingTitle.text = "Nada tocando agora"
            binding.nowPlayingArtist.text = "Conecte no Spotify e escolha uma musica."
            binding.nowPlayingImage.setImageResource(R.drawable.bg_cover_missing)
            binding.nowPlayingFallbackText.text = "S"
            binding.nowPlayingFallbackText.visibility = View.VISIBLE
            lastNowPlayingArtworkKey = null
            return
        }

        binding.nowPlayingTitle.text = title
        binding.nowPlayingArtist.text = if (artist.isBlank()) "Artista indisponivel" else artist

        if (visual.artwork != null) {
            binding.nowPlayingImage.setImageBitmap(visual.artwork)
            binding.nowPlayingFallbackText.visibility = View.GONE
            lastNowPlayingArtworkKey = "bitmap"
            return
        }

        val artworkUrl = visual.artworkUrl?.trim().orEmpty()
        if (artworkUrl.isNotBlank()) {
            if (lastNowPlayingArtworkKey != artworkUrl) {
                lastNowPlayingArtworkKey = artworkUrl
                binding.nowPlayingImage.load(artworkUrl) {
                    crossfade(false)
                    placeholder(R.drawable.bg_cover_missing)
                    error(R.drawable.bg_cover_missing)
                    listener(
                        onSuccess = { _, _ ->
                            binding.nowPlayingFallbackText.visibility = View.GONE
                        },
                        onError = { _, _ ->
                            binding.nowPlayingImage.setImageResource(R.drawable.bg_cover_missing)
                            binding.nowPlayingFallbackText.text = fallbackLetter
                            binding.nowPlayingFallbackText.visibility = View.VISIBLE
                        }
                    )
                }
            }
        } else if (lastNowPlayingArtworkKey != "placeholder") {
            binding.nowPlayingImage.setImageResource(R.drawable.bg_cover_missing)
            binding.nowPlayingFallbackText.text = fallbackLetter
            binding.nowPlayingFallbackText.visibility = View.VISIBLE
            lastNowPlayingArtworkKey = "placeholder"
        }
    }

    private fun fallbackLetter(text: String?): String {
        val char = text
            ?.firstOrNull { it.isLetterOrDigit() }
            ?.uppercaseChar()
            ?.toString()
        return char ?: "S"
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

    private fun loadTopStats(openOnSuccess: Boolean, showLoadingStatus: Boolean) {
        val token = webApiAccessToken
        if (token.isNullOrBlank()) {
            pendingTopRequest = true
            renderStatus("Autorizando acesso aos seus tops no Spotify...")
            requestTopAuthorization()
            return
        }

        if (showLoadingStatus) {
            renderStatus("Atualizando seus dados do Spotify...")
        }
        ioExecutor.execute {
            val result = runCatching {
                spotifyInsightsService.fetchTopSummary(token)
            }

            runOnUiThread {
                result.onSuccess { summary ->
                    cachedTopSummary = summary
                    topCacheStore.save(summary)
                    if (openOnSuccess) {
                        openTopStatsScreen(summary)
                    }
                }.onFailure { error ->
                    if (error.message == "TOKEN_EXPIRED") {
                        webApiAccessToken = null
                        cachedTopSummary = null
                        renderStatus("Token expirou. Toque em 'Ver tops' novamente para autorizar.")
                    } else if (showLoadingStatus) {
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
            putExtra(TopStatsActivity.EXTRA_SUMMARY_JSON, summary.toJsonString())
        }
        startActivity(intent)
    }

    private fun startKeepAliveMode() {
        val intent = Intent(this, SpotifyKeepAliveService::class.java).apply {
            action = SpotifyKeepAliveService.ACTION_START
        }
        ContextCompat.startForegroundService(this, intent)
        renderStatus("Modo sempre ligado ativado. O app vai manter conexao em background.")
    }

    private fun stopKeepAliveMode() {
        val intent = Intent(this, SpotifyKeepAliveService::class.java).apply {
            action = SpotifyKeepAliveService.ACTION_STOP
        }
        startService(intent)
        renderStatus("Modo sempre ligado desativado.")
    }

    private companion object {
        const val REQUEST_CODE_SPOTIFY_AUTH = 9876
        val TOP_SCOPES = arrayOf(
            "user-top-read",
            "user-read-recently-played",
            "playlist-read-private",
            "playlist-read-collaborative",
            "user-library-read",
            "user-follow-read"
        )
    }
}
