package it.dogior.hadEnough

import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LiveSearchResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.VPNStatus
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newLiveSearchResponse
import com.lagradost.cloudstream3.newLiveStreamLoadResponse
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

class DaddyLiveTVProvider : MainAPI() {
    override var mainUrl = "https://daddylive.mov"
    override var name = "DaddyLive TV"
    override val supportedTypes = setOf(TvType.Live)
    override var lang = "un"
    override val hasMainPage = true
    override val vpnStatus = VPNStatus.MightBeNeeded
    override val hasDownloadSupport = false
    override val instantLinkLoading = true

    // I canali 24/7 di DaddyLive sono pubblicati come playlist JSON pronta (nome + url m3u8
    // diretto), niente più scraping di pagine HTML che cambiano struttura in continuazione.
    private val channelListPath = "/player/player9.json"

    @Suppress("ConstPropertyName")
    companion object {
        private const val poster =
            "https://raw.githubusercontent.com/doGior/doGiorsHadEnough/refs/heads/master/DaddyLive/daddylive.jpg"

        private var cachedChannels: List<Channel>? = null
        private var cachedAt: Long = 0
        private const val cacheDurationMs = 15 * 60 * 1000L
    }

    data class Channel(val name: String, val url: String)

    private fun groupKey(name: String): String {
        val c = name.trim().firstOrNull()?.uppercaseChar() ?: '#'
        return if (c in 'A'..'Z') c.toString() else "#"
    }

    private suspend fun getChannels(): List<Channel> {
        val now = System.currentTimeMillis()
        cachedChannels?.let { if (now - cachedAt < cacheDurationMs) return it }

        mainUrl = DaddyLiveDomain.getMainUrl()
        val json = app.get("$mainUrl$channelListPath", timeout = 20L).text
        val channels = tryParseJson<List<Channel>>(json)
            ?.filter { it.name.isNotBlank() && it.url.isNotBlank() }
            ?: emptyList()

        cachedChannels = channels
        cachedAt = now
        return channels
    }

    private fun toSearchResponse(channel: Channel): LiveSearchResponse {
        return newLiveSearchResponse(channel.name, channel.url) {
            posterUrl = poster
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val channels = getChannels()
        val grouped = channels.groupBy { groupKey(it.name) }
        val sections = grouped.map { (letter, list) ->
            HomePageList(
                letter,
                list.sortedBy { it.name.lowercase() }.map { toSearchResponse(it) },
                false
            )
        }.sortedBy { it.name }
        return newHomePageResponse(sections, false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val channels = getChannels()
        val q = query.lowercase().replace(" ", "")
        return channels.filter { q in it.name.lowercase().replace(" ", "") }
            .map { toSearchResponse(it) }
    }

    override suspend fun load(url: String): LoadResponse {
        val channels = getChannels()
        val title = channels.firstOrNull { it.url == url }?.name ?: "Channel"
        return newLiveStreamLoadResponse(title, url, url) {
            posterUrl = poster
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        callback(
            newExtractorLink(name, name, data, type = ExtractorLinkType.M3U8) {
                this.referer = mainUrl
                this.quality = Qualities.Unknown.value
            }
        )
        return true
    }
}
