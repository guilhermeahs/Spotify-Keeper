package com.onix.spotifykeeper

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.LruCache
import com.spotify.android.appremote.api.ConnectionParams
import com.spotify.android.appremote.api.Connector
import com.spotify.android.appremote.api.SpotifyAppRemote
import com.spotify.protocol.client.Subscription
import com.spotify.protocol.types.ImageUri
import com.spotify.protocol.types.PlayerState

data class PlaybackSnapshot(
    val connected: Boolean,
    val isPaused: Boolean,
    val trackName: String?,
    val artistName: String?,
    val playbackPositionMs: Long,
    val message: String
)

data class NowPlayingVisual(
    val title: String?,
    val artist: String?,
    val artwork: Bitmap?,
    val artworkUrl: String?,
    val playbackPositionMs: Long,
    val durationMs: Long
)

class SpotifyController(
    private val context: Context
) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var onStatusChanged: (String) -> Unit = {}
    private var onNowPlayingChanged: (NowPlayingVisual) -> Unit = {}

    private var connected = false
    private var connecting = false
    private var connectStartedAtMs: Long = 0L
    private var paused = true
    private var lastUri: String? = null
    private var pendingPlayUri: String? = null
    private var pendingResumeCurrent: Boolean = false
    private var trackName: String? = null
    private var artistName: String? = null
    private var playbackPositionMs: Long = 0L
    private var trackDurationMs: Long = 0L
    private var pauseRequestedByUser = false
    private var autoResumeInFlight = false
    private var lastAutoResumeAtMs: Long = 0L
    private var continuousPlayModeEnabled = false
    private var lastContinuousEnsureAtMs: Long = 0L

    private var lastImageUriRaw: String? = null
    private var lastImageUrl: String? = null
    private var currentArtwork: Bitmap? = null
    private val artworkCache = object : LruCache<String, Bitmap>(32) {}

    private var spotifyAppRemote: SpotifyAppRemote? = null
    private var playerStateSubscription: Subscription<PlayerState>? = null
    private var connectTimeoutRunnable: Runnable? = null
    private val continuousPlayRunnable = object : Runnable {
        override fun run() {
            if (!continuousPlayModeEnabled) {
                return
            }
            ensureContinuousPlayback()
            mainHandler.postDelayed(this, CONTINUOUS_PLAY_CHECK_MS)
        }
    }

    fun setCallbacks(
        onStatusChanged: (String) -> Unit,
        onNowPlayingChanged: (NowPlayingVisual) -> Unit
    ) {
        this.onStatusChanged = onStatusChanged
        this.onNowPlayingChanged = onNowPlayingChanged
        emitNowPlaying()
    }

    fun clearCallbacks() {
        onStatusChanged = {}
        onNowPlayingChanged = {}
    }

    fun connect(force: Boolean = false): String {
        if (!isClientConfigured()) {
            return "Configure SPOTIFY_CLIENT_ID no app/build.gradle antes de conectar."
        }
        if (!isSpotifyInstalled()) {
            return "Spotify nao encontrado. Instale o app oficial (com.spotify.music)."
        }
        if (connected && !force) {
            return "Ja conectado ao Spotify."
        }
        if (connecting) {
            val elapsed = SystemClock.elapsedRealtime() - connectStartedAtMs
            if (elapsed <= CONNECT_TIMEOUT_MS) {
                return "Conexao em andamento..."
            }
            connecting = false
            clearConnectTimeout()
            spotifyAppRemote = null
            emitStatus("Conexao anterior ficou travada. Tentando reconectar...")
        }
        if (force) {
            disconnect()
        }

        openSpotifyApp()
        connecting = true
        connectStartedAtMs = SystemClock.elapsedRealtime()
        scheduleConnectTimeout()
        val connectionParams = ConnectionParams.Builder(BuildConfig.SPOTIFY_CLIENT_ID)
            .setRedirectUri(BuildConfig.SPOTIFY_REDIRECT_URI)
            .showAuthView(true)
            .build()

        SpotifyAppRemote.connect(
            context,
            connectionParams,
            object : Connector.ConnectionListener {
                override fun onConnected(appRemote: SpotifyAppRemote) {
                    clearConnectTimeout()
                    connecting = false
                    connected = true
                    spotifyAppRemote = appRemote
                    if (continuousPlayModeEnabled) {
                        pauseRequestedByUser = false
                        startContinuousPlayLoop()
                    }
                    subscribeToPlayerState(appRemote)
                    emitStatus("Conectado ao Spotify.")
                    pendingPlayUri?.let { queuedUri ->
                        pendingPlayUri = null
                        playInternal(queuedUri, origin = "play pendente")
                        return
                    }
                    if (pendingResumeCurrent) {
                        pendingResumeCurrent = false
                        resumeInternal(origin = "retomar pendente")
                    }
                }

                override fun onFailure(error: Throwable) {
                    clearConnectTimeout()
                    connecting = false
                    connected = false
                    spotifyAppRemote = null
                    emitStatus(describeConnectionError(error))
                }
            }
        )

        return "Tentando conectar ao Spotify..."
    }

    fun playPlaylist(uri: String): String {
        val parsedUri = normalizeSpotifyUri(uri)
            ?: return "URI invalida. Use spotify:<tipo>:<id> ou link open.spotify.com (playlist/faixa/album/artista)."

        lastUri = parsedUri
        pauseRequestedByUser = false
        val appRemote = spotifyAppRemote
        if (!connected || appRemote == null) {
            pendingPlayUri = parsedUri
            val connectMessage = connect()
            return if (connecting || connected) {
                "Conectando para tocar $parsedUri..."
            } else {
                pendingPlayUri = null
                connectMessage
            }
        }

        playInternal(parsedUri, origin = "comando manual")
        return "Comando de play enviado."
    }

    fun playCurrent(): String {
        pauseRequestedByUser = false
        val appRemote = spotifyAppRemote
        if (!connected || appRemote == null) {
            pendingResumeCurrent = true
            val connectMessage = connect()
            return if (connecting || connected) {
                "Conectando para retomar a reproducao atual..."
            } else {
                pendingResumeCurrent = false
                connectMessage
            }
        }

        resumeInternal(origin = "comando manual")
        return "Comando para retomar reproducao atual enviado."
    }

    fun pause(): String {
        val appRemote = spotifyAppRemote
        if (continuousPlayModeEnabled) {
            emitStatus("Modo travar play ativo. Desative para pausar.")
            return "Modo travar play ativo. Desative para pausar."
        }
        return if (!connected || appRemote == null) {
            "Conecte primeiro."
        } else {
            pauseRequestedByUser = true
            appRemote.playerApi.pause()
                .setResultCallback {
                    paused = true
                    emitStatus("Reproducao pausada.")
                }
                .setErrorCallback { error ->
                    emitStatus(describeError("Falha ao pausar", error))
                }
            "Comando de pausa enviado."
        }
    }

    fun resume(): String {
        val appRemote = spotifyAppRemote
        return if (!connected || appRemote == null) {
            "Conecte primeiro."
        } else {
            pauseRequestedByUser = false
            resumeInternal(origin = "botao retomar")
            "Comando de retomada enviado."
        }
    }

    fun setContinuousPlayMode(enabled: Boolean) {
        continuousPlayModeEnabled = enabled
        if (enabled) {
            pauseRequestedByUser = false
            startContinuousPlayLoop()
            spotifyAppRemote?.let {
                if (paused) {
                    resumeInternal(origin = "travar play")
                } else {
                    emitStatus("Modo travar play ativo.")
                }
            } ?: emitStatus("Modo travar play ativo.")
        } else {
            stopContinuousPlayLoop()
            emitStatus("Modo travar play desativado.")
        }
    }

    fun isContinuousPlayModeEnabled(): Boolean = continuousPlayModeEnabled

    fun getStatus(): PlaybackSnapshot {
        val text = when {
            connecting -> "Conectando..."
            !connected -> "Nao conectado."
            paused -> "Conectado, mas pausado."
            else -> "Conectado e reproduzindo."
        }

        val nowPlaying = when {
            trackName.isNullOrBlank() -> ""
            artistName.isNullOrBlank() -> " Faixa: $trackName."
            else -> " Faixa: $trackName - $artistName."
        }
        val uriInfo = if (lastUri.isNullOrBlank()) "" else " URI: $lastUri."

        return PlaybackSnapshot(
            connected = connected,
            isPaused = paused,
            trackName = trackName,
            artistName = artistName,
            playbackPositionMs = playbackPositionMs,
            message = "$text$nowPlaying$uriInfo"
        )
    }

    fun disconnect() {
        clearConnectTimeout()
        stopContinuousPlayLoop()
        connectStartedAtMs = 0L
        pendingPlayUri = null
        pendingResumeCurrent = false
        playerStateSubscription?.cancel()
        playerStateSubscription = null
        spotifyAppRemote?.let { SpotifyAppRemote.disconnect(it) }
        spotifyAppRemote = null
        connected = false
        connecting = false
        trackName = null
        artistName = null
        playbackPositionMs = 0L
        trackDurationMs = 0L
        currentArtwork = null
        lastImageUriRaw = null
        lastImageUrl = null
        emitNowPlaying()
    }

    private fun subscribeToPlayerState(appRemote: SpotifyAppRemote) {
        playerStateSubscription?.cancel()
        val subscription = appRemote.playerApi.subscribeToPlayerState()
        playerStateSubscription = subscription
        subscription.setEventCallback { state ->
            applyPlayerState(state, appRemote)
            maybeAutoResume(appRemote, state)
        }
        subscription.setErrorCallback { error ->
            emitStatus(describeError("Erro ao ler estado do player", error))
        }
    }

    private fun applyPlayerState(state: PlayerState, appRemote: SpotifyAppRemote) {
        paused = state.isPaused
        playbackPositionMs = state.playbackPosition
        trackDurationMs = state.track?.duration ?: 0L
        trackName = state.track?.name
        artistName = state.track?.artist?.name
        state.track?.uri?.takeIf { it.isNotBlank() }?.let { uri ->
            lastUri = uri
        }

        val currentImageUriRaw = state.track?.imageUri?.raw
        if (currentImageUriRaw != lastImageUriRaw) {
            lastImageUriRaw = currentImageUriRaw
            lastImageUrl = toArtworkHttpUrl(currentImageUriRaw)
            if (currentImageUriRaw.isNullOrBlank()) {
                currentArtwork = null
            } else {
                val cachedArtwork = artworkCache.get(currentImageUriRaw)
                if (cachedArtwork != null) {
                    currentArtwork = cachedArtwork
                } else {
                    fetchArtwork(appRemote, state.track?.imageUri, currentImageUriRaw)
                }
            }
        }
        emitNowPlaying()
    }

    private fun fetchArtwork(appRemote: SpotifyAppRemote, imageUri: ImageUri?, imageKey: String) {
        if (imageUri == null) {
            currentArtwork = null
            emitNowPlaying()
            return
        }

        appRemote.imagesApi.getImage(imageUri)
            .setResultCallback { bitmap ->
                artworkCache.put(imageKey, bitmap)
                if (lastImageUriRaw == imageKey) {
                    currentArtwork = bitmap
                    emitNowPlaying()
                }
            }
            .setErrorCallback {
                // Keep previous artwork on failures to avoid UI flicker/loading effect.
            }
    }

    private fun maybeAutoResume(appRemote: SpotifyAppRemote, state: PlayerState) {
        if (!connected || !state.isPaused || (pauseRequestedByUser && !continuousPlayModeEnabled)) {
            return
        }
        if (autoResumeInFlight) {
            return
        }

        val now = SystemClock.elapsedRealtime()
        if (now - lastAutoResumeAtMs < AUTO_RESUME_DEBOUNCE_MS) {
            return
        }

        autoResumeInFlight = true
        lastAutoResumeAtMs = now

        appRemote.playerApi.resume()
            .setResultCallback {
                paused = false
                autoResumeInFlight = false
                emitStatus("Spotify pausou. Retomando automaticamente.")
            }
            .setErrorCallback { resumeError ->
                val fallbackUri = lastUri
                if (fallbackUri.isNullOrBlank()) {
                    autoResumeInFlight = false
                    emitStatus(describeError("Falha no auto-retomar", resumeError))
                } else {
                    appRemote.playerApi.play(fallbackUri)
                        .setResultCallback {
                            paused = false
                            autoResumeInFlight = false
                            emitStatus("Retomando automaticamente pela ultima URI.")
                        }
                        .setErrorCallback { playError ->
                            autoResumeInFlight = false
                            emitStatus(describeError("Falha no auto-retomar", playError))
                        }
                }
            }
    }

    private fun ensureContinuousPlayback() {
        if (!continuousPlayModeEnabled || !connected || connecting || !paused) {
            return
        }
        if (autoResumeInFlight) {
            return
        }
        val appRemote = spotifyAppRemote ?: return
        val now = SystemClock.elapsedRealtime()
        if (now - lastContinuousEnsureAtMs < CONTINUOUS_PLAY_CHECK_MS) {
            return
        }
        lastContinuousEnsureAtMs = now
        pauseRequestedByUser = false
        autoResumeInFlight = true

        appRemote.playerApi.resume()
            .setResultCallback {
                paused = false
                autoResumeInFlight = false
            }
            .setErrorCallback {
                val fallbackUri = lastUri
                if (fallbackUri.isNullOrBlank()) {
                    autoResumeInFlight = false
                } else {
                    appRemote.playerApi.play(fallbackUri)
                        .setResultCallback {
                            paused = false
                            autoResumeInFlight = false
                        }
                        .setErrorCallback {
                            autoResumeInFlight = false
                        }
                }
            }
    }

    private fun startContinuousPlayLoop() {
        stopContinuousPlayLoop()
        mainHandler.post(continuousPlayRunnable)
    }

    private fun stopContinuousPlayLoop() {
        mainHandler.removeCallbacks(continuousPlayRunnable)
    }

    private fun resumeInternal(origin: String) {
        val appRemote = spotifyAppRemote ?: return
        appRemote.playerApi.resume()
            .setResultCallback {
                paused = false
                emitStatus("Reproducao retomada ($origin).")
            }
            .setErrorCallback { error ->
                val fallbackUri = lastUri
                if (fallbackUri.isNullOrBlank()) {
                    emitStatus(describeError("Falha ao retomar", error))
                } else {
                    playInternal(fallbackUri, origin = "fallback do resume")
                }
            }
    }

    private fun playInternal(uri: String, origin: String) {
        val appRemote = spotifyAppRemote ?: return
        appRemote.playerApi.play(uri)
            .setResultCallback {
                paused = false
                emitStatus("Tocando ($origin): $uri")
            }
            .setErrorCallback { error ->
                emitStatus(describeError("Falha ao tocar URI", error))
            }
    }

    private fun normalizeSpotifyUri(input: String): String? {
        val value = input.trim()
        if (value.isBlank()) {
            return null
        }
        if (value.startsWith("spotify:")) {
            return value
        }

        val entityRegex = Regex(
            """https?://open\.spotify\.com/(playlist|track|album|artist|show|episode)/([a-zA-Z0-9]+)""",
            RegexOption.IGNORE_CASE
        )
        val match = entityRegex.find(value) ?: return null
        val entityType = match.groupValues.getOrNull(1)?.lowercase().orEmpty()
        val entityId = match.groupValues.getOrNull(2).orEmpty()
        return if (entityType.isBlank() || entityId.isBlank()) null else "spotify:$entityType:$entityId"
    }

    private fun isSpotifyInstalled(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    SPOTIFY_PACKAGE_NAME,
                    PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(SPOTIFY_PACKAGE_NAME, 0)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun isClientConfigured(): Boolean {
        return BuildConfig.SPOTIFY_CLIENT_ID != DEFAULT_CLIENT_ID_PLACEHOLDER
    }

    private fun describeError(prefix: String, error: Throwable): String {
        val details = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
        return "$prefix: $details"
    }

    private fun describeConnectionError(error: Throwable): String {
        val message = error.message.orEmpty()
        return if (message.contains("Explicit user authorization is required", ignoreCase = true)) {
            openSpotifyApp()
            "Falha ao conectar: autorizacao pendente. No Spotify Developer Dashboard, confirme redirect URI \"spotifykeeper://callback\" e configure Android package \"com.onix.spotifykeeper\" com SHA1 da assinatura do APK. Depois toque em Conectar novamente."
        } else {
            describeError("Falha ao conectar", error)
        }
    }

    private fun scheduleConnectTimeout() {
        clearConnectTimeout()
        connectTimeoutRunnable = Runnable {
            if (!connecting) {
                return@Runnable
            }
            connecting = false
            connected = false
            spotifyAppRemote = null
            openSpotifyApp()
            emitStatus("Tempo limite na conexao. Abrimos o Spotify para autorizar. Volte ao app e toque em Conectar novamente.")
        }
        mainHandler.postDelayed(connectTimeoutRunnable!!, CONNECT_TIMEOUT_MS)
    }

    private fun clearConnectTimeout() {
        connectTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        connectTimeoutRunnable = null
    }

    private fun openSpotifyApp() {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(SPOTIFY_PACKAGE_NAME) ?: return
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        mainHandler.post {
            runCatching { context.startActivity(launchIntent) }
        }
    }

    private fun emitStatus(message: String) {
        mainHandler.post {
            onStatusChanged(message)
        }
    }

    private fun emitNowPlaying() {
        mainHandler.post {
            onNowPlayingChanged(
                NowPlayingVisual(
                    title = trackName,
                    artist = artistName,
                    artwork = currentArtwork,
                    artworkUrl = lastImageUrl,
                    playbackPositionMs = playbackPositionMs,
                    durationMs = trackDurationMs
                )
            )
        }
    }

    private fun toArtworkHttpUrl(rawImageUri: String?): String? {
        if (rawImageUri.isNullOrBlank()) {
            return null
        }
        val imageId = rawImageUri.removePrefix("spotify:image:")
        return if (imageId.isBlank()) null else "https://i.scdn.co/image/$imageId"
    }

    private companion object {
        const val SPOTIFY_PACKAGE_NAME = "com.spotify.music"
        const val AUTO_RESUME_DEBOUNCE_MS = 1_200L
        const val CONTINUOUS_PLAY_CHECK_MS = 1_500L
        const val CONNECT_TIMEOUT_MS = 20_000L
        const val DEFAULT_CLIENT_ID_PLACEHOLDER = "COLOQUE_SEU_CLIENT_ID_AQUI"
    }
}
