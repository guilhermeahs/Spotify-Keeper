package com.onix.spotifykeeper

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.onix.spotifykeeper.databinding.ActivityTopStatsBinding

class TopStatsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTopStatsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTopStatsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        renderContent()
    }

    private fun setupToolbar() {
        binding.topStatsToolbar.setNavigationOnClickListener {
            finish()
        }
    }

    private fun renderContent() {
        val tracks = intent.getStringArrayListExtra(EXTRA_TOP_TRACKS).orEmpty()
        val artists = intent.getStringArrayListExtra(EXTRA_TOP_ARTISTS).orEmpty()
        val playlists = intent.getStringArrayListExtra(EXTRA_PLAYLISTS).orEmpty()
        val recent = intent.getStringArrayListExtra(EXTRA_RECENT).orEmpty()

        binding.tracksContent.text = formatRankedList(tracks, "Sem musicas no momento.")
        binding.artistsContent.text = formatRankedList(artists, "Sem artistas no momento.")
        binding.playlistsContent.text = formatRankedList(playlists, "Sem playlists no momento.")
        binding.recentContent.text = formatRankedList(recent, "Sem historico recente.")
    }

    private fun formatRankedList(items: List<String>, emptyText: String): String {
        if (items.isEmpty()) {
            return emptyText
        }
        return items.mapIndexed { index, value ->
            "${index + 1}. $value"
        }.joinToString("\n")
    }

    companion object {
        const val EXTRA_TOP_TRACKS = "extra_top_tracks"
        const val EXTRA_TOP_ARTISTS = "extra_top_artists"
        const val EXTRA_PLAYLISTS = "extra_playlists"
        const val EXTRA_RECENT = "extra_recent"
    }
}
