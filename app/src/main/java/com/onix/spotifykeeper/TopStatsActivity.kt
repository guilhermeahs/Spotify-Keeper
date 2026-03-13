package com.onix.spotifykeeper

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import coil.load
import com.google.android.material.chip.Chip
import com.google.android.material.tabs.TabLayout
import com.onix.spotifykeeper.databinding.ActivityTopStatsBinding
import com.onix.spotifykeeper.databinding.ItemDailyStatsBinding
import com.onix.spotifykeeper.databinding.ItemTopEntryBinding

class TopStatsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTopStatsBinding
    private val dayByChipId = mutableMapOf<Int, SpotifyDailySummary?>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTopStatsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupBottomTabs()
        renderContent()
    }

    private fun setupToolbar() {
        binding.topStatsToolbar.setNavigationOnClickListener {
            finish()
        }
    }

    private fun setupBottomTabs() {
        if (binding.bottomTabs.tabCount == 0) {
            listOf("Resumo", "Diario", "Musicas", "Artistas", "Playlists", "Recentes").forEach { label ->
                binding.bottomTabs.addTab(binding.bottomTabs.newTab().setText(label))
            }
        }

        binding.bottomTabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                showTab(tab.position)
            }

            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })

        showTab(0)
    }

    private fun showTab(position: Int) {
        binding.summaryCard.visibility = if (position == 0) View.VISIBLE else View.GONE
        binding.dailyCard.visibility = if (position == 1) View.VISIBLE else View.GONE
        binding.tracksCard.visibility = if (position == 2) View.VISIBLE else View.GONE
        binding.artistsCard.visibility = if (position == 3) View.VISIBLE else View.GONE
        binding.playlistsCard.visibility = if (position == 4) View.VISIBLE else View.GONE
        binding.recentCard.visibility = if (position == 5) View.VISIBLE else View.GONE
    }

    private fun renderContent() {
        val summaryJson = intent.getStringExtra(EXTRA_SUMMARY_JSON).orEmpty()
        if (summaryJson.isBlank()) {
            binding.windowSummaryText.text = "Sem dados para mostrar ainda."
            addEmptyLabel(binding.dailySelectedContainer, "Sem dados diarios para mostrar.")
            addEmptyLabel(binding.tracksContainer, "Sem dados para mostrar.")
            addEmptyLabel(binding.artistsContainer, "Sem dados para mostrar.")
            addEmptyLabel(binding.playlistsContainer, "Sem dados para mostrar.")
            addEmptyLabel(binding.recentContainer, "Sem dados para mostrar.")
            return
        }

        val summary = runCatching { SpotifyTopSummary.fromJsonString(summaryJson) }.getOrNull()
        if (summary == null) {
            binding.windowSummaryText.text = "Falha ao abrir os tops."
            addEmptyLabel(binding.dailySelectedContainer, "Falha ao abrir os tops diarios.")
            addEmptyLabel(binding.tracksContainer, "Falha ao abrir os tops.")
            addEmptyLabel(binding.artistsContainer, "Falha ao abrir os tops.")
            addEmptyLabel(binding.playlistsContainer, "Falha ao abrir os tops.")
            addEmptyLabel(binding.recentContainer, "Falha ao abrir os tops.")
            return
        }

        binding.windowSummaryText.text = buildWindowSummary(summary)
        renderDayChips(summary.dailySummaries)

        renderSection(binding.tracksContainer, summary.topTracks, "Sem musicas no momento.")
        renderSection(binding.artistsContainer, summary.topArtists, "Sem artistas no momento.")
        renderSection(binding.playlistsContainer, summary.playlists, "Sem playlists no momento.")
        renderSection(binding.recentContainer, summary.recentlyPlayed, "Sem historico recente.")
    }

    private fun buildWindowSummary(summary: SpotifyTopSummary): String {
        if (summary.recentWindowPlays <= 0) {
            return "Sem historico recente suficiente para montar estatisticas."
        }

        return "Janela recente: ${summary.recentWindowMinutes} min, ${summary.recentWindowPlays} plays, " +
            "${summary.recentWindowUniqueTracks} musicas unicas. Curtidas: ${summary.likedSongsCount}. " +
            "Seguindo artistas: ${summary.followedArtistsCount}. Playlists: ${summary.playlistsCount}."
    }

    private fun renderDayChips(items: List<SpotifyDailySummary>) {
        dayByChipId.clear()
        binding.dayChipGroup.removeAllViews()

        if (items.isEmpty()) {
            addEmptyLabel(binding.dailySelectedContainer, "Sem dados diarios no momento.")
            return
        }

        val allChip = createDayChip("Todos")
        dayByChipId[allChip.id] = null
        binding.dayChipGroup.addView(allChip)

        items.forEach { day ->
            val chip = createDayChip(day.dateLabel)
            dayByChipId[chip.id] = day
            binding.dayChipGroup.addView(chip)
        }

        binding.dayChipGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            val selectedId = checkedIds.firstOrNull()
            val selectedDay = selectedId?.let { dayByChipId[it] }
            if (selectedId == null || selectedDay == null) {
                renderDailyCards(items)
            } else {
                renderDailyCards(listOf(selectedDay))
            }
        }

        allChip.isChecked = true
        renderDailyCards(items)
    }

    private fun createDayChip(text: String): Chip {
        return Chip(this).apply {
            id = View.generateViewId()
            this.text = text
            isCheckable = true
            chipBackgroundColor = ColorStateList.valueOf(0xFF27344A.toInt())
            setTextColor(0xFFEAF0FF.toInt())
            chipStrokeColor = ColorStateList.valueOf(0xFF486286.toInt())
            chipStrokeWidth = 1f
        }
    }

    private fun renderDailyCards(days: List<SpotifyDailySummary>) {
        binding.dailySelectedContainer.removeAllViews()
        if (days.isEmpty()) {
            addEmptyLabel(binding.dailySelectedContainer, "Sem dados para esse filtro.")
            return
        }

        val inflater = LayoutInflater.from(this)
        days.forEach { day ->
            val card = ItemDailyStatsBinding.inflate(inflater, binding.dailySelectedContainer, false)
            card.dailyDateText.text = day.dateLabel
            card.dailyMetaText.text = "${day.minutesHeard} min - ${day.playsCount} plays - ${day.uniqueTracksCount} musicas"

            if (day.topTracks.isEmpty()) {
                val label = TextView(this)
                label.text = "Sem top para esse dia."
                label.setTextColor(0xFFB9B1D1.toInt())
                card.dailyTopTracksContainer.addView(label)
            } else {
                day.topTracks.forEachIndexed { index, item ->
                    val row = ItemTopEntryBinding.inflate(inflater, card.dailyTopTracksContainer, false)
                    bindMediaRow(row, item, prefix = "${index + 1}.")
                    card.dailyTopTracksContainer.addView(row.root)
                }
            }

            binding.dailySelectedContainer.addView(card.root)
        }
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
            bindMediaRow(row, item)
            container.addView(row.root)
        }
    }

    private fun bindMediaRow(row: ItemTopEntryBinding, item: SpotifyMediaItem, prefix: String? = null) {
        row.entryTitle.text = if (prefix.isNullOrBlank()) item.title else "$prefix ${item.title}"

        val subtitle = item.subtitle.orEmpty()
        if (subtitle.isBlank()) {
            row.entrySubtitle.text = ""
            row.entrySubtitle.visibility = View.GONE
        } else {
            row.entrySubtitle.visibility = View.VISIBLE
            row.entrySubtitle.text = subtitle
        }

        if (item.imageUrl.isNullOrBlank()) {
            row.entryImage.setImageResource(R.drawable.ic_music_placeholder)
        } else {
            row.entryImage.load(item.imageUrl) {
                crossfade(false)
                placeholder(row.entryImage.drawable)
                error(R.drawable.ic_music_placeholder)
            }
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
