package com.onix.spotifykeeper

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.spotify.android.appremote.api.ConnectionParams
import com.spotify.android.appremote.api.Connector
import com.spotify.android.appremote.api.SpotifyAppRemote
import com.spotify.protocol.client.Subscription
import com.spotify.protocol.types.PlayerState

data class PlaybackSnapshot(
    val connected: Boolean,
    val isPaused: Boolean,
    val trackName: String?,
    val artistName: String?,
    val playbackPositionMs: Long,
    val message: String
)

class SpotifyController(
    private val context: Context,
    private val onStatusChanged: (String) -> Unit
) {

    private val mainHandler = Handler(Looper.getMainLooper())

    private var connected = false
    private var connecting = false
    private var paused = true
    private var lastUri: String? = null
    private var pendingPlayUri: String? = null
    private var trackName: String? = null
    private var artistName: String? = null
    private var playbackPositionMs: Long = 0L
    private var pauseRequestedByUser = false
    private var autoResumeInFlight = false
    private var lastAutoResumeAtMs: Long = 0L

    private var spotifyAppRemote: SpotifyAppRemote? = null
    private var playerStateSubscription: Subscription<PlayerState>? = null

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
            return "Conexao em andamento..."
        }

        connecting = true
        val connectionParams = ConnectionParams.Builder(BuildConfig.SPOTIFY_CLIENT_ID)
            .setRedirectUri(BuildConfig.SPOTIFY_REDIRECT_URI)
            .showAuthView(true)
            .build()

        SpotifyAppRemote.connect(
            context,
            connectionParams,
            object : Connector.ConnectionListener {
                override fun onConnected(appRemote: SpotifyAppRemote) {
                    connecting = false
                    connected = true
                    spotifyAppRemote = appRemote
                    subscribeToPlayerState(appRemote)
                    emitStatus("Conectado ao Spotify.")
                    pendingPlayUri?.let { queuedUri ->
                        pendingPlayUri = null
                        playInternal(queuedUri, origin = "play pendente")
                    }
                }

                override fun onFailure(error: Throwable) {
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
            ?: return "URI invalida. Use spotify:playlist:<id> ou link https://open.spotify.com/playlist/<id>."

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

    fun pause(): String {
        val appRemote = spotifyAppRemote
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
            appRemote.playerApi.resume()
                .setResultCallback {
                    paused = false
                    emitStatus("Reproducao retomada.")
                }
                .setErrorCallback { error ->
                    val fallbackUri = lastUri
                    if (fallbackUri.isNullOrBlank()) {
                        emitStatus(describeError("Falha ao retomar", error))
                    } else {
                        playInternal(fallbackUri, origin = "fallback do resume")
                    }
                }
            "Comando de retomada enviado."
        }
    }

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
        playerStateSubscription?.cancel()
        playerStateSubscription = null
        spotifyAppRemote?.let { SpotifyAppRemote.disconnect(it) }
        spotifyAppRemote = null
        connected = false
        connecting = false
    }

    private fun subscribeToPlayerState(appRemote: SpotifyAppRemote) {
        playerStateSubscription?.cancel()
        val subscription = appRemote.playerApi.subscribeToPlayerState()
        playerStateSubscription = subscription
        subscription.setEventCallback { state ->
            applyPlayerState(state)
            maybeAutoResume(appRemote, state)
        }
        subscription.setErrorCallback { error ->
            emitStatus(describeError("Erro ao ler estado do player", error))
        }
    }

    private fun applyPlayerState(state: PlayerState) {
        paused = state.isPaused
        playbackPositionMs = state.playbackPosition
        trackName = state.track?.name
        artistName = state.track?.artist?.name
        state.track?.uri?.takeIf { it.isNotBlank() }?.let { uri ->
            lastUri = uri
        }
    }

    private fun maybeAutoResume(appRemote: SpotifyAppRemote, state: PlayerState) {
        if (!connected || !state.isPaused || pauseRequestedByUser) {
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

        val playlistRegex = Regex("""https?://open\.spotify\.com/playlist/([a-zA-Z0-9]+)""")
        val match = playlistRegex.find(value) ?: return null
        val playlistId = match.groupValues.getOrNull(1).orEmpty()
        return if (playlistId.isBlank()) null else "spotify:playlist:$playlistId"
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
            "Falha ao conectar: autorizacao pendente. No Spotify Developer Dashboard, confirme redirect URI \"spotifykeeper://callback\" e configure Android package \"com.onix.spotifykeeper\" com SHA1 da assinatura do APK. Depois toque em Conectar novamente."
        } else {
            describeError("Falha ao conectar", error)
        }
    }

    private fun emitStatus(message: String) {
        mainHandler.post {
            onStatusChanged(message)
        }
    }

    private companion object {
        const val SPOTIFY_PACKAGE_NAME = "com.spotify.music"
        const val AUTO_RESUME_DEBOUNCE_MS = 5_000L
        const val DEFAULT_CLIENT_ID_PLACEHOLDER = "COLOQUE_SEU_CLIENT_ID_AQUI"
    }
}
