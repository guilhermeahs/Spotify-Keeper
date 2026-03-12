package com.onix.spotifykeeper

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat

class SpotifyKeepAliveService : Service() {

    private lateinit var spotifyController: SpotifyController
    private val handler = Handler(Looper.getMainLooper())
    private var lastConnectAttemptAtMs: Long = 0L

    private val keepAliveRunnable = object : Runnable {
        override fun run() {
            ensureConnected()
            handler.postDelayed(this, KEEP_ALIVE_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        spotifyController = SpotifyControllerProvider.get(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildNotification())
        ensureConnected()
        handler.removeCallbacks(keepAliveRunnable)
        handler.postDelayed(keepAliveRunnable, KEEP_ALIVE_INTERVAL_MS)
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(keepAliveRunnable)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun ensureConnected() {
        val snapshot = spotifyController.getStatus()
        if (!snapshot.connected && canAttemptReconnect()) {
            lastConnectAttemptAtMs = SystemClock.elapsedRealtime()
            spotifyController.connect()
        }
    }

    private fun canAttemptReconnect(): Boolean {
        val now = SystemClock.elapsedRealtime()
        return now - lastConnectAttemptAtMs >= RECONNECT_COOLDOWN_MS
    }

    private fun buildNotification(): Notification {
        ensureNotificationChannel()
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Spotify Keeper ativo")
            .setContentText("Mantendo conexao com Spotify em segundo plano.")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val existing = manager.getNotificationChannel(CHANNEL_ID)
        if (existing != null) {
            return
        }
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Spotify Keeper Background",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Mantem o Spotify Keeper ativo em segundo plano."
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_START = "com.onix.spotifykeeper.action.START_KEEP_ALIVE"
        const val ACTION_STOP = "com.onix.spotifykeeper.action.STOP_KEEP_ALIVE"
        private const val CHANNEL_ID = "spotify_keeper_keep_alive"
        private const val NOTIFICATION_ID = 5010
        private const val KEEP_ALIVE_INTERVAL_MS = 25_000L
        private const val RECONNECT_COOLDOWN_MS = 90_000L
    }
}
