package com.onix.spotifykeeper

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import coil.load
import com.onix.spotifykeeper.databinding.ActivityMainBinding
import com.onix.spotifykeeper.databinding.ItemHighlightTileBinding
import com.onix.spotifykeeper.databinding.ItemRecentTrackBinding
import com.onix.spotifykeeper.databinding.ItemStatMetricBinding
import com.spotify.sdk.android.auth.AuthorizationClient
import com.spotify.sdk.android.auth.AuthorizationRequest
import com.spotify.sdk.android.auth.AuthorizationResponse
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private enum class MainPage {
        HOME,
        HIGHLIGHTS,
        STATS
    }

    private enum class HighlightsPeriod {
        FOUR_WEEKS,
        SIX_MONTHS,
        ALL_TIME
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var spotifyController: SpotifyController
    private lateinit var appUpdater: AppUpdater
    private lateinit var spotifyInsightsService: SpotifyInsightsService
    private lateinit var topCacheStore: SpotifyTopCacheStore
    private lateinit var dailyHistoryStore: SpotifyDailyHistoryStore
    private lateinit var historyImportService: SpotifyHistoryImportService

    private val ioExecutor = Executors.newSingleThreadExecutor()

    private var webApiAccessToken: String? = null
    private var pendingTopRequest = false
    private var cachedTopSummary: SpotifyTopSummary? = null
    private var latestStatus: String = "Projeto iniciado. Conecte ao Spotify."
    private var keepAliveEnabled = false
    private var currentPage = MainPage.HOME
    private var highlightsPeriod = HighlightsPeriod.FOUR_WEEKS
    private var lastNowPlayingArtworkKey: String? = null

    private val importHistoryLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) {
            return@registerForActivityResult
        }
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        importHistoryFromUri(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        spotifyController = SpotifyControllerProvider.get(applicationContext)
        appUpdater = AppUpdater(this)
        spotifyInsightsService = SpotifyInsightsService(this)
        topCacheStore = SpotifyTopCacheStore(this)
        dailyHistoryStore = SpotifyDailyHistoryStore(this)
        historyImportService = SpotifyHistoryImportService(this)
        cachedTopSummary = topCacheStore.load()

        setupUi()
        renderNowPlaying(
            NowPlayingVisual(
                title = null,
                artist = null,
                artwork = null,
                artworkUrl = null,
                playbackPositionMs = 0L,
                durationMs = 0L
            )
        )
        renderStatus(latestStatus)
        applyTopSummary(cachedTopSummary)
        checkForUpdates(silent = true)
    }

    override fun onStart() {
        super.onStart()
        spotifyController.setCallbacks(
            onStatusChanged = { status ->
                latestStatus = status
                renderStatus(status)
            },
            onNowPlayingChanged = { visual ->
                renderNowPlaying(visual)
            }
        )
    }

    override fun onStop() {
        spotifyController.clearCallbacks()
        super.onStop()
    }

    override fun onDestroy() {
        ioExecutor.shutdownNow()
        super.onDestroy()
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
                renderStatus("Autorizacao concluida. Atualizando seus dados...")
                if (pendingTopRequest) {
                    pendingTopRequest = false
                    loadTopStats(showLoadingStatus = true)
                }
            }

            AuthorizationResponse.Type.ERROR -> {
                pendingTopRequest = false
                renderStatus("Falha na autorizacao: ${response.error}")
            }

            else -> {
                pendingTopRequest = false
                renderStatus("Autorizacao cancelada.")
            }
        }
    }

    private fun setupUi() {
        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> {
                    showPage(MainPage.HOME)
                    true
                }

                R.id.nav_highlights -> {
                    showPage(MainPage.HIGHLIGHTS)
                    true
                }

                R.id.nav_stats -> {
                    showPage(MainPage.STATS)
                    true
                }

                else -> false
            }
        }
        binding.bottomNav.selectedItemId = R.id.nav_home

        binding.searchButton.setOnClickListener {
            requestTopRefresh()
        }

        binding.updateHeaderButton.setOnClickListener {
            checkForUpdates(silent = false)
        }

        binding.profileImage.setOnClickListener {
            showPage(MainPage.HOME)
            binding.bottomNav.selectedItemId = R.id.nav_home
        }

        binding.connectQuickButton.setOnClickListener {
            renderStatus(spotifyController.connect())
        }

        binding.playQuickButton.setOnClickListener {
            val snapshot = spotifyController.getStatus()
            val status = when {
                !snapshot.connected -> spotifyController.playCurrent()
                snapshot.isPaused -> spotifyController.resume()
                else -> spotifyController.pause()
            }
            renderStatus(status)
        }

        binding.refreshQuickButton.setOnClickListener {
            requestTopRefresh()
        }

        binding.updateAppButton.setOnClickListener {
            checkForUpdates(silent = false)
        }

        binding.importHistoryButton.setOnClickListener {
            importHistoryLauncher.launch(arrayOf("application/json", "application/zip", "application/x-zip-compressed", "text/plain", "*/*"))
        }

        binding.viewStatsCard.setOnClickListener {
            showPage(MainPage.STATS)
            binding.bottomNav.selectedItemId = R.id.nav_stats
        }

        binding.keepAliveQuickButton.setOnClickListener {
            keepAliveEnabled = !keepAliveEnabled
            if (keepAliveEnabled) {
                val intent = Intent(this, SpotifyKeepAliveService::class.java).apply {
                    action = SpotifyKeepAliveService.ACTION_START
                }
                ContextCompat.startForegroundService(this, intent)
                binding.keepAliveQuickButton.text = "Sempre OFF"
                renderStatus("Modo sempre ligado ativado.")
            } else {
                val intent = Intent(this, SpotifyKeepAliveService::class.java).apply {
                    action = SpotifyKeepAliveService.ACTION_STOP
                }
                startService(intent)
                binding.keepAliveQuickButton.text = "Sempre ON"
                renderStatus("Modo sempre ligado desativado.")
            }
        }

        binding.shareButton.setOnClickListener {
            shareHighlights()
        }

        binding.periodFourWeeksChip.setOnClickListener {
            highlightsPeriod = HighlightsPeriod.FOUR_WEEKS
            updatePeriodChipStyle()
            renderHighlights(cachedTopSummary)
        }

        binding.periodSixMonthsChip.setOnClickListener {
            highlightsPeriod = HighlightsPeriod.SIX_MONTHS
            updatePeriodChipStyle()
            renderHighlights(cachedTopSummary)
        }

        binding.periodAllChip.setOnClickListener {
            highlightsPeriod = HighlightsPeriod.ALL_TIME
            updatePeriodChipStyle()
            renderHighlights(cachedTopSummary)
        }

        updatePeriodChipStyle()
    }

    private fun requestTopRefresh() {
        if (webApiAccessToken.isNullOrBlank()) {
            pendingTopRequest = true
            renderStatus("Autorizando acesso aos seus dados no Spotify...")
            requestTopAuthorization()
            return
        }
        loadTopStats(showLoadingStatus = true)
    }

    private fun importHistoryFromUri(uri: Uri) {
        renderStatus("Importando historico do Spotify...")
        ioExecutor.execute {
            val result = runCatching {
                historyImportService.importFromUri(uri)
            }
            runOnUiThread {
                result.onSuccess { imported ->
                    val merged = mergeImportedSummary(imported.summary)
                    cachedTopSummary = merged
                    topCacheStore.save(merged)
                    applyTopSummary(merged)
                    renderStatus("Historico importado: ${imported.importedPlays} reproducoes processadas.")
                }.onFailure { error ->
                    renderStatus("Falha ao importar historico: ${error.message}")
                }
            }
        }
    }

    private fun mergeImportedSummary(imported: SpotifyTopSummary): SpotifyTopSummary {
        val existing = cachedTopSummary ?: topCacheStore.load()

        val mergedDaily = dailyHistoryStore.mergeLatest(imported.dailySummaries)

        val mergedTopTracks = (imported.topTracks + existing?.topTracks.orEmpty())
            .distinctBy { normalizeTrackKey(it.title, it.subtitle) }
            .take(60)
        val mergedTopArtists = (imported.topArtists + existing?.topArtists.orEmpty())
            .distinctBy { (it.title + "|" + it.subtitle).lowercase(Locale.ROOT) }
            .take(60)
        val mergedRecent = (imported.recentlyPlayed + existing?.recentlyPlayed.orEmpty())
            .distinctBy { normalizeTrackKey(it.title, it.subtitle) }
            .take(60)

        val recentDays = mergedDaily.take(30)
        val recentMinutes = recentDays.sumOf { it.minutesHeard }
        val recentPlays = recentDays.sumOf { it.playsCount }
        val recentUnique = recentDays
            .flatMap { it.topTracks }
            .distinctBy { normalizeTrackKey(it.title, it.subtitle) }
            .size

        return SpotifyTopSummary(
            topTracks = mergedTopTracks,
            topArtists = mergedTopArtists,
            playlists = existing?.playlists.orEmpty(),
            recentlyPlayed = mergedRecent,
            dailySummaries = mergedDaily,
            recentWindowMinutes = recentMinutes,
            recentWindowPlays = recentPlays,
            recentWindowUniqueTracks = recentUnique,
            likedSongsCount = maxOf(imported.likedSongsCount, existing?.likedSongsCount ?: 0),
            followedArtistsCount = maxOf(imported.followedArtistsCount, existing?.followedArtistsCount ?: 0, mergedTopArtists.size),
            playlistsCount = maxOf(imported.playlistsCount, existing?.playlistsCount ?: 0)
        )
    }

    private fun loadTopStats(showLoadingStatus: Boolean) {
        val token = webApiAccessToken
        if (token.isNullOrBlank()) {
            pendingTopRequest = true
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
                    applyTopSummary(summary)
                    renderStatus("Dados sincronizados com sucesso.")
                }.onFailure { error ->
                    if (error.message == "TOKEN_EXPIRED") {
                        webApiAccessToken = null
                        renderStatus("Token expirou. Toque em atualizar para autorizar novamente.")
                    } else {
                        renderStatus("Falha ao atualizar: ${error.message}")
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

    private fun applyTopSummary(summary: SpotifyTopSummary?) {
        renderHomeTrends(summary)
        renderRecentList(summary)
        renderHighlights(summary)
        renderStats(summary)
    }

    private fun renderHomeTrends(summary: SpotifyTopSummary?) {
        if (summary == null) {
            binding.trendPlaysValue.text = "0"
            binding.trendMinutesValue.text = "0"
            binding.trendPlaysDelta.text = "-0%"
            binding.trendMinutesDelta.text = "-0%"
            applyDeltaColor(binding.trendPlaysDelta, isNegative = true)
            applyDeltaColor(binding.trendMinutesDelta, isNegative = true)
            return
        }

        binding.trendPlaysValue.text = summary.recentWindowPlays.toString()
        binding.trendMinutesValue.text = summary.recentWindowMinutes.toString()

        val today = summary.dailySummaries.getOrNull(0)
        val yesterday = summary.dailySummaries.getOrNull(1)

        val playsDelta = percentDelta(today?.playsCount, yesterday?.playsCount)
        val minutesDelta = percentDelta(today?.minutesHeard, yesterday?.minutesHeard)

        binding.trendPlaysDelta.text = playsDelta.text
        binding.trendMinutesDelta.text = minutesDelta.text
        applyDeltaColor(binding.trendPlaysDelta, playsDelta.isNegative)
        applyDeltaColor(binding.trendMinutesDelta, minutesDelta.isNegative)
    }

    private fun renderRecentList(summary: SpotifyTopSummary?) {
        binding.recentListContainer.removeAllViews()
        val items = summary?.recentlyPlayed.orEmpty().take(8)
        if (items.isEmpty()) {
            val empty = TextView(this).apply {
                text = "Sem historico recente ainda."
                setTextColor(Color.parseColor("#C8D4E8"))
                textSize = 14f
            }
            binding.recentListContainer.addView(empty)
            return
        }

        val inflater = layoutInflater
        items.forEachIndexed { index, item ->
            val row = ItemRecentTrackBinding.inflate(inflater, binding.recentListContainer, false)
            row.recentTitle.text = item.title
            row.recentSubtitle.text = item.subtitle.orEmpty().ifBlank { "Sem artista informado" }
            row.recentAgoText.text = when (index) {
                0 -> "agora"
                1 -> "5m"
                2 -> "14m"
                3 -> "24m"
                else -> "${(index + 1) * 12}m"
            }

            if (item.imageUrl.isNullOrBlank()) {
                row.recentImage.setImageResource(R.drawable.bg_cover_missing)
                row.recentFallbackText.text = fallbackLetter(item.title)
                row.recentFallbackText.visibility = View.VISIBLE
            } else {
                row.recentFallbackText.visibility = View.GONE
                row.recentImage.load(item.imageUrl) {
                    crossfade(false)
                    placeholder(R.drawable.bg_cover_missing)
                    error(R.drawable.bg_cover_missing)
                    listener(
                        onError = { _, _ ->
                            row.recentImage.setImageResource(R.drawable.bg_cover_missing)
                            row.recentFallbackText.text = fallbackLetter(item.title)
                            row.recentFallbackText.visibility = View.VISIBLE
                        }
                    )
                }
            }

            binding.recentListContainer.addView(row.root)
        }
    }

    private fun renderHighlights(summary: SpotifyTopSummary?) {
        binding.highlightsGridContainer.removeAllViews()
        binding.highlightsTitleText.text = when (highlightsPeriod) {
            HighlightsPeriod.FOUR_WEEKS -> "Destaques ultimas 4 semanas"
            HighlightsPeriod.SIX_MONTHS -> "Destaques ultimos 6 meses"
            HighlightsPeriod.ALL_TIME -> "Destaques completos"
        }
        val items = buildHighlightsForPeriod(summary).take(18)
        if (items.isEmpty()) {
            val empty = TextView(this).apply {
                text = "Sem destaques para mostrar ainda."
                setTextColor(Color.parseColor("#CED9EA"))
                textSize = 14f
            }
            binding.highlightsGridContainer.addView(empty)
            return
        }

        val inflater = layoutInflater
        items.chunked(2).forEachIndexed { rowIndex, pair ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = if (rowIndex == 0) 0 else 10.dp
                }
            }

            pair.forEachIndexed { index, item ->
                val tile = ItemHighlightTileBinding.inflate(inflater, row, false)
                val params = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (index == 0) {
                        marginEnd = 6.dp
                    } else {
                        marginStart = 6.dp
                    }
                }
                tile.root.layoutParams = params
                tile.highlightTitle.text = item.title
                tile.highlightMeta.text = item.subtitle.orEmpty().ifBlank { "Sem detalhes" }

                if (item.imageUrl.isNullOrBlank()) {
                    tile.highlightImage.setImageResource(R.drawable.bg_cover_missing)
                    tile.highlightFallbackText.text = fallbackLetter(item.title)
                    tile.highlightFallbackText.visibility = View.VISIBLE
                } else {
                    tile.highlightFallbackText.visibility = View.GONE
                    tile.highlightImage.load(item.imageUrl) {
                        crossfade(false)
                        placeholder(R.drawable.bg_cover_missing)
                        error(R.drawable.bg_cover_missing)
                        listener(
                            onError = { _, _ ->
                                tile.highlightImage.setImageResource(R.drawable.bg_cover_missing)
                                tile.highlightFallbackText.text = fallbackLetter(item.title)
                                tile.highlightFallbackText.visibility = View.VISIBLE
                            }
                        )
                    }
                }

                row.addView(tile.root)
            }

            if (pair.size == 1) {
                row.addView(
                    View(this).apply {
                        layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
                    }
                )
            }

            binding.highlightsGridContainer.addView(row)
        }
    }

    private fun buildHighlightsForPeriod(summary: SpotifyTopSummary?): List<SpotifyMediaItem> {
        summary ?: return emptyList()
        val daysWindow = when (highlightsPeriod) {
            HighlightsPeriod.FOUR_WEEKS -> 28
            HighlightsPeriod.SIX_MONTHS -> 180
            HighlightsPeriod.ALL_TIME -> Int.MAX_VALUE
        }

        val selectedDays = summary.dailySummaries
            .sortedByDescending { it.dateIso }
            .take(daysWindow)

        val rankedFromDaily = rankHighlightsFromDaily(selectedDays)
        val fallback = when (highlightsPeriod) {
            HighlightsPeriod.FOUR_WEEKS -> summary.topTracks + summary.recentlyPlayed.take(10)
            HighlightsPeriod.SIX_MONTHS -> summary.topTracks + summary.recentlyPlayed
            HighlightsPeriod.ALL_TIME -> summary.topTracks + summary.recentlyPlayed + summary.dailySummaries.flatMap { it.topTracks }
        }

        return (rankedFromDaily + fallback)
            .distinctBy { normalizeTrackKey(it.title, it.subtitle) }
    }

    private fun rankHighlightsFromDaily(days: List<SpotifyDailySummary>): List<SpotifyMediaItem> {
        if (days.isEmpty()) {
            return emptyList()
        }

        data class TrackAgg(
            var score: Int = 0,
            var imageUrl: String? = null,
            var artistLabel: String? = null
        )

        val buckets = linkedMapOf<String, TrackAgg>()
        days.forEach { day ->
            day.topTracks.forEachIndexed { index, item ->
                val key = normalizeTrackKey(item.title, item.subtitle)
                val agg = buckets.getOrPut(key) { TrackAgg() }
                agg.score += (6 - index).coerceAtLeast(1)
                if (agg.imageUrl.isNullOrBlank() && !item.imageUrl.isNullOrBlank()) {
                    agg.imageUrl = item.imageUrl
                }
                if (agg.artistLabel.isNullOrBlank()) {
                    agg.artistLabel = extractArtistLabel(item.subtitle)
                }
            }
        }

        return buckets.entries
            .sortedByDescending { it.value.score }
            .map { (key, agg) ->
                val title = key.substringBefore("::")
                val artist = agg.artistLabel.orEmpty()
                SpotifyMediaItem(
                    title = title,
                    subtitle = if (artist.isBlank()) "${agg.score} pts no periodo" else "$artist - ${agg.score} pts no periodo",
                    imageUrl = agg.imageUrl
                )
            }
    }

    private fun normalizeTrackKey(title: String?, subtitle: String?): String {
        val normalizedTitle = title.orEmpty().trim().lowercase(Locale.ROOT)
        val normalizedArtist = extractArtistLabel(subtitle).trim().lowercase(Locale.ROOT)
        return "$normalizedTitle::$normalizedArtist"
    }

    private fun extractArtistLabel(subtitle: String?): String {
        val value = subtitle.orEmpty()
        if (value.isBlank()) {
            return ""
        }
        return value.substringBefore(" - ").substringBefore(" • ").trim()
    }

    private fun renderStats(summary: SpotifyTopSummary?) {
        if (summary == null) {
            bindStatCard(binding.statsCardPlays, "reproducoes", 0, null)
            bindStatCard(binding.statsCardTracks, "faixas diferentes", 0, null)
            bindStatCard(binding.statsCardMinutes, "minutos ouvidos", 0, null)
            bindStatCard(binding.statsCardArtists, "artistas diferentes", 0, null)
            bindStatCard(binding.statsCardPlaylists, "playlists", 0, null)
            bindStatCard(binding.statsCardDays, "dias reproduzidos", 0, null)
            binding.statsInsightText.text = "Atualize seus dados para carregar suas estatisticas."
            return
        }

        val today = summary.dailySummaries.getOrNull(0)
        val yesterday = summary.dailySummaries.getOrNull(1)

        bindStatCard(
            binding.statsCardPlays,
            "reproducoes",
            summary.recentWindowPlays,
            percentDelta(today?.playsCount, yesterday?.playsCount).value
        )
        bindStatCard(
            binding.statsCardTracks,
            "faixas diferentes",
            summary.recentWindowUniqueTracks,
            percentDelta(today?.uniqueTracksCount, yesterday?.uniqueTracksCount).value
        )
        bindStatCard(
            binding.statsCardMinutes,
            "minutos ouvidos",
            summary.recentWindowMinutes,
            percentDelta(today?.minutesHeard, yesterday?.minutesHeard).value
        )
        bindStatCard(
            binding.statsCardArtists,
            "artistas diferentes",
            summary.followedArtistsCount,
            null
        )
        bindStatCard(
            binding.statsCardPlaylists,
            "playlists",
            summary.playlistsCount,
            null
        )
        bindStatCard(
            binding.statsCardDays,
            "dias reproduzidos",
            summary.dailySummaries.size,
            null
        )

        val topArtist = summary.topArtists.firstOrNull()?.title ?: "sem destaque"
        binding.statsInsightText.text = "Seu artista em destaque agora: $topArtist."
    }

    private fun bindStatCard(card: ItemStatMetricBinding, label: String, value: Int, deltaPercent: Int?) {
        card.statValueText.text = value.toString()
        card.statLabelText.text = label

        if (deltaPercent == null) {
            card.statDeltaText.text = "-0%"
            applyDeltaColor(card.statDeltaText, isNegative = true)
            return
        }

        val prefix = if (deltaPercent > 0) "+" else ""
        card.statDeltaText.text = "$prefix$deltaPercent%"
        applyDeltaColor(card.statDeltaText, isNegative = deltaPercent <= 0)
    }

    private fun renderNowPlaying(visual: NowPlayingVisual) {
        val title = visual.title?.trim().orEmpty()
        val artist = visual.artist?.trim().orEmpty()
        val fallback = fallbackLetter(title)

        binding.nowPlayingTitle.text = if (title.isBlank()) "Nada tocando agora" else title
        binding.nowPlayingArtist.text = if (artist.isBlank()) "Conecte no Spotify para iniciar." else artist

        val durationMs = visual.durationMs.coerceAtLeast(0L)
        val positionMs = visual.playbackPositionMs.coerceAtLeast(0L).coerceAtMost(durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE)
        val progress = if (durationMs > 0L) ((positionMs * 1000L) / durationMs).toInt() else 0
        binding.nowPlayingProgress.progress = progress
        binding.nowPlayingDurationText.text = if (durationMs > 0L) formatDuration(durationMs) else "0:00"

        if (title.isBlank()) {
            binding.nowPlayingImage.setImageResource(R.drawable.bg_cover_missing)
            binding.nowPlayingFallbackText.text = "S"
            binding.nowPlayingFallbackText.visibility = View.VISIBLE
            lastNowPlayingArtworkKey = null
            return
        }

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
                            binding.nowPlayingFallbackText.text = fallback
                            binding.nowPlayingFallbackText.visibility = View.VISIBLE
                        }
                    )
                }
            }
        } else if (lastNowPlayingArtworkKey != "fallback") {
            binding.nowPlayingImage.setImageResource(R.drawable.bg_cover_missing)
            binding.nowPlayingFallbackText.text = fallback
            binding.nowPlayingFallbackText.visibility = View.VISIBLE
            lastNowPlayingArtworkKey = "fallback"
        }
    }

    private fun showPage(page: MainPage) {
        currentPage = page
        binding.homePage.visibility = if (page == MainPage.HOME) View.VISIBLE else View.GONE
        binding.highlightsPage.visibility = if (page == MainPage.HIGHLIGHTS) View.VISIBLE else View.GONE
        binding.statsPage.visibility = if (page == MainPage.STATS) View.VISIBLE else View.GONE

        binding.screenTitleText.text = when (page) {
            MainPage.HOME -> "Pagina inicial"
            MainPage.HIGHLIGHTS -> "Destaques"
            MainPage.STATS -> "Estatisticas"
        }
    }

    private fun updatePeriodChipStyle() {
        val activeBackground = R.drawable.bg_chip_active
        val inactiveBackground = R.drawable.bg_chip_inactive

        binding.periodFourWeeksChip.setBackgroundResource(if (highlightsPeriod == HighlightsPeriod.FOUR_WEEKS) activeBackground else inactiveBackground)
        binding.periodSixMonthsChip.setBackgroundResource(if (highlightsPeriod == HighlightsPeriod.SIX_MONTHS) activeBackground else inactiveBackground)
        binding.periodAllChip.setBackgroundResource(if (highlightsPeriod == HighlightsPeriod.ALL_TIME) activeBackground else inactiveBackground)

        binding.periodFourWeeksChip.setTextColor(if (highlightsPeriod == HighlightsPeriod.FOUR_WEEKS) Color.parseColor("#102010") else Color.parseColor("#CFD8E8"))
        binding.periodSixMonthsChip.setTextColor(if (highlightsPeriod == HighlightsPeriod.SIX_MONTHS) Color.parseColor("#102010") else Color.parseColor("#CFD8E8"))
        binding.periodAllChip.setTextColor(if (highlightsPeriod == HighlightsPeriod.ALL_TIME) Color.parseColor("#102010") else Color.parseColor("#CFD8E8"))
    }

    private fun renderStatus(text: String) {
        binding.statusText.text = "Status: $text"
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

    private fun shareHighlights() {
        val summary = cachedTopSummary
        if (summary == null) {
            renderStatus("Atualize os dados antes de compartilhar.")
            return
        }
        val topList = summary.topTracks.take(5).mapIndexed { index, item ->
            "${index + 1}. ${item.title}"
        }
        val content = buildString {
            appendLine("Meus destaques no Spotify Keeper")
            appendLine()
            topList.forEach { appendLine(it) }
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, content)
        }
        startActivity(Intent.createChooser(intent, "Compartilhar destaques"))
    }

    private fun fallbackLetter(text: String?): String {
        return text
            ?.firstOrNull { it.isLetterOrDigit() }
            ?.uppercaseChar()
            ?.toString()
            ?: "S"
    }

    private fun applyDeltaColor(textView: TextView, isNegative: Boolean) {
        textView.setTextColor(Color.parseColor(if (isNegative) "#F06A6A" else "#62E38F"))
    }

    private fun formatDuration(durationMs: Long): String {
        val totalSeconds = (durationMs / 1000L).coerceAtLeast(0L)
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        return String.format(Locale.getDefault(), "%d:%02d", minutes, seconds)
    }

    private fun percentDelta(current: Int?, previous: Int?): DeltaResult {
        val currentSafe = current ?: 0
        val previousSafe = previous ?: 0
        if (previousSafe <= 0) {
            return DeltaResult(value = 0, text = "-0%", isNegative = true)
        }
        val raw = ((currentSafe - previousSafe) * 100f / previousSafe.toFloat()).toInt()
        val prefix = if (raw > 0) "+" else ""
        return DeltaResult(value = raw, text = "$prefix$raw%", isNegative = raw <= 0)
    }

    private data class DeltaResult(
        val value: Int,
        val text: String,
        val isNegative: Boolean
    )

    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density).toInt()

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
