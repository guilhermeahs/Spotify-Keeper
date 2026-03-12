package com.onix.spotifykeeper

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.onix.spotifykeeper.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var spotifyController: SpotifyController
    private lateinit var appUpdater: AppUpdater

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        spotifyController = SpotifyController(this) { status ->
            renderStatus(status)
        }
        appUpdater = AppUpdater(this)
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

        binding.updateButton.setOnClickListener {
            checkForUpdates(silent = false)
        }
    }

    override fun onDestroy() {
        if (::spotifyController.isInitialized) {
            spotifyController.disconnect()
        }
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
}
