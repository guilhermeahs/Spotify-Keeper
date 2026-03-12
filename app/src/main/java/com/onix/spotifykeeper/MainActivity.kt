package com.onix.spotifykeeper

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.onix.spotifykeeper.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var spotifyController: SpotifyController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        spotifyController = SpotifyController(this) { status ->
            renderStatus(status)
        }
        bindActions()
        renderStatus("Projeto iniciado. Conecte ao Spotify.")
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
}
