package it.dogior.hadEnough

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.lagradost.api.Log
import com.lagradost.cloudstream3.ErrorLoadingException
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
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink

/**
 * Lo schedule (eventi/partite live) di DaddyLive viene pubblicato come JSON sotto /player/tv*.json
 * invece che come pagina HTML da fare scraping. I vari file non condividono uno schema fisso
 * (cambia il livello di nidificazione a seconda della sezione), quindi il parsing naviga
 * l'albero JSON in modo generico cercando nodi con la forma di un evento
 * ({time, event, channels}) invece di mappare una struttura rigida.
 */
class DaddyLiveScheduleProvider : MainAPI() {
    override var mainUrl = "https://daddylive.mov"
    override var name = "DaddyLive Schedule"
    override val supportedTypes = setOf(TvType.Live)
    override var lang = "un"
    override val hasMainPage = true
    override val vpnStatus = VPNStatus.MightBeNeeded
    override val hasDownloadSupport = false

    private val mapper = ObjectMapper().registerKotlinModule()

    data class ScheduleEvent(
        val time: String = "",
        val event: String = "",
        val channels: List<Map<String, String>> = emptyList()
    )

    data class FlatEvent(val category: String, val event: ScheduleEvent)

    @Suppress("ConstPropertyName")
    companion object {
        private const val posterUrl =
            "https://raw.githubusercontent.com/doGior/doGiorsHadEnough/refs/heads/master/DaddyLive/daddylive.jpg"

        private val schedulePaths = listOf(
            "/player/tv1.json", "/player/tv2.json", "/player/tv3.json", "/player/tv4.json",
            "/player/tv5.json", "/player/tv6.json", "/player/tv7.json", "/player/tv8.json",
            "/player/tv12.json", "/player/tv13.json",
        )

        private var cachedFlatEvents: List<FlatEvent>? = null
        private var cachedAt: Long = 0
        private const val cacheDurationMs = 5 * 60 * 1000L
    }

    private fun collectEvents(
        node: JsonNode,
        sectionHint: String,
        out: MutableMap<String, MutableList<ScheduleEvent>>
    ) {
        when {
            node.isObject -> {
                if (node.has("time") && node.has("event") && node.has("channels")) {
                    val ev = try {
                        mapper.treeToValue(node, ScheduleEvent::class.java)
                    } catch (e: Exception) {
                        null
                    }
                    if (ev != null) out.getOrPut(sectionHint) { mutableListOf() }.add(ev)
                    return
                }
                node.fields().forEach { (key, child) -> collectEvents(child, key, out) }
            }

            node.isArray -> {
                node.forEach { child ->
                    val childSection = if (child.has("Category")) child.get("Category").asText() else sectionHint
                    collectEvents(child, childSection, out)
                }
            }
        }
    }

    private suspend fun getFlatEvents(): List<FlatEvent> {
        val now = System.currentTimeMillis()
        cachedFlatEvents?.let { if (now - cachedAt < cacheDurationMs) return it }

        mainUrl = DaddyLiveDomain.getMainUrl()
        val grouped = mutableMapOf<String, MutableList<ScheduleEvent>>()
        for (path in schedulePaths) {
            try {
                val body = app.get("$mainUrl$path", timeout = 20L).text
                collectEvents(mapper.readTree(body), "Schedule", grouped)
            } catch (e: Exception) {
                Log.e("DDL Schedule", "Impossibile leggere $path: $e")
            }
        }

        val flat = grouped.flatMap { (category, events) -> events.map { FlatEvent(category, it) } }
        cachedFlatEvents = flat
        cachedAt = now
        return flat
    }

    private fun eventTitle(event: ScheduleEvent) =
        listOf(event.time, event.event).filter { it.isNotBlank() }.joinToString(" - ")

    private fun toSearchResponse(index: Int, flat: FlatEvent): LiveSearchResponse {
        return newLiveSearchResponse(eventTitle(flat.event), "event-$index") {
            posterUrl = Companion.posterUrl
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val flat = getFlatEvents()
        val sections = flat.withIndex()
            .groupBy { it.value.category }
            .map { (category, items) ->
                HomePageList(category, items.map { toSearchResponse(it.index, it.value) }, false)
            }
        return newHomePageResponse(sections, false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val flat = getFlatEvents()
        val q = query.lowercase().replace(" ", "")
        return flat.withIndex()
            .filter { q in it.value.event.event.lowercase().replace(" ", "") }
            .map { toSearchResponse(it.index, it.value) }
    }

    override suspend fun load(url: String): LoadResponse {
        val index = url.removePrefix("event-").toIntOrNull()
            ?: throw ErrorLoadingException("URL evento non valido")
        val flat = getFlatEvents()
        val flatEvent = flat.getOrNull(index) ?: throw ErrorLoadingException("Evento non trovato")

        // Un canale per ogni sorgente reale, ciascuno con i suoi mirror embed_1..N come fallback
        val channelMirrors = flatEvent.event.channels.map { channel ->
            val channelName = channel["channel_name"] ?: channel["channel_id"] ?: "Link"
            channel.filterKeys { it.startsWith("embed") }
                .toSortedMap()
                .map { (key, mirrorUrl) -> "$channelName ($key)" to mirrorUrl }
        }

        return newLiveStreamLoadResponse(eventTitle(flatEvent.event), url, channelMirrors.toJson()) {
            posterUrl = Companion.posterUrl
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val channelMirrors = tryParseJson<List<List<Pair<String, String>>>>(data)
        if (channelMirrors.isNullOrEmpty()) return false

        for (mirrors in channelMirrors) {
            if (mirrors.isEmpty()) continue
            DaddyLiveExtractor().getUrl(mirrors.toJson(), null, subtitleCallback, callback)
        }
        return true
    }
}
