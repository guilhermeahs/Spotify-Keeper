package com.onix.spotifykeeper

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import coil.load
import com.onix.spotifykeeper.databinding.ActivityTopStatsBinding
import com.onix.spotifykeeper.databinding.ItemTopEntryBinding

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
        val summaryJson = intent.getStringExtra(EXTRA_SUMMARY_JSON).orEmpty()
        if (summaryJson.isBlank()) {
            addEmptyLabel(binding.tracksContainer, "Sem dados para mostrar.")
            addEmptyLabel(binding.artistsContainer, "Sem dados para mostrar.")
            addEmptyLabel(binding.playlistsContainer, "Sem dados para mostrar.")
            addEmptyLabel(binding.recentContainer, "Sem dados para mostrar.")
            return
        }

        val summary = runCatching { SpotifyTopSummary.fromJsonString(summaryJson) }.getOrNull()
        if (summary == null) {
            addEmptyLabel(binding.tracksContainer, "Falha ao abrir os tops.")
            addEmptyLabel(binding.artistsContainer, "Falha ao abrir os tops.")
            addEmptyLabel(binding.playlistsContainer, "Falha ao abrir os tops.")
            addEmptyLabel(binding.recentContainer, "Falha ao abrir os tops.")
            return
        }

        renderSection(binding.tracksContainer, summary.topTracks, "Sem musicas no momento.")
        renderSection(binding.artistsContainer, summary.topArtists, "Sem artistas no momento.")
        renderSection(binding.playlistsContainer, summary.playlists, "Sem playlists no momento.")
        renderSection(binding.recentContainer, summary.recentlyPlayed, "Sem historico recente.")
    }

    private fun renderSection(container: LinearLayout, items: List<SpotifyMediaItem>, emptyText: String) {
        container.removeAllViews()
        if (items.isEmpty()) {
            addEmptyLabel(container, emptyText)
            return
        }

        val inflater = LayoutInflater.from(this)
        items.forEach { item ->
            val row = ItemTopEntryBinding.inflate(inflater, container, false)
            row.entryTitle.text = item.title
            val subtitle = item.subtitle.orEmpty()
            if (subtitle.isBlank()) {
                row.entrySubtitle.text = ""
                row.entrySubtitle.visibility = View.GONE
            } else {
                row.entrySubtitle.visibility = View.VISIBLE
                row.entrySubtitle.text = subtitle
            }
            row.entryImage.load(item.imageUrl) {
                crossfade(true)
                placeholder(R.drawable.ic_music_placeholder)
                error(R.drawable.ic_music_placeholder)
            }
            container.addView(row.root)
        }
    }

    private fun addEmptyLabel(container: LinearLayout, message: String) {
        container.removeAllViews()
        val label = TextView(this)
        label.text = message
        label.setTextColor(0xFFD6D0EE.toInt())
        label.textSize = 14f
        container.addView(label)
    }

    companion object {
        const val EXTRA_SUMMARY_JSON = "extra_summary_json"
    }
}
