package it.dogior.hadEnough

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.exc.MismatchedInputException
import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URL

/**
 * Estrae il link m3u8 finale a partire da:
 * - un URL m3u8 diretto (canali 24/7 di DaddyLive: viene solo incapsulato)
 * - un URL "mirror" di embed (es. dlhd.st/watch/stream-X.php, daddylive1.cx/new/stream-X.php,
 *   apexstreams.cfd/live/stream-X.php, cricsfree.cfd/live/stream-X.php, ecc.) usato dagli eventi
 *   dello schedule. Questi mirror contengono un <iframe> il cui player espone l'm3u8
 *   codificato in base64 dentro uno script (pattern `atob('...')`), oppure - sui mirror più
 *   vecchi - lo schema "newzar" con CHANNEL_KEY + auth.php + server_lookup.php.
 *
 * Questa classe non fa affidamento sul routing automatico di loadExtractor() (i domini mirror
 * cambiano troppo spesso), viene sempre invocata esplicitamente dai provider.
 */
class DaddyLiveExtractor : ExtractorApi() {
    override val mainUrl = "https://daddylive.mov"
    override val name = "DaddyLive"
    override val requiresReferer = false
    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // Lista di coppie <nome canale, link mirror>, prova ognuno finché uno funziona
        val links = tryParseJson<List<Pair<String, String>>>(url)
        Log.d("DDLExt - Links", links?.toJson() ?: "null")

        if (links != null) {
            for ((channelName, link) in links) {
                val extracted = extractFromSource(link, channelName)
                if (extracted != null) {
                    callback(extracted)
                    return
                }
            }
            return
        }

        extractFromSource(url)?.let { callback(it) }
    }

    private suspend fun extractFromSource(url: String, sourceName: String = this.name): ExtractorLink? {
        return try {
            if (url.contains(".m3u8")) {
                newExtractorLink(sourceName, sourceName, url, type = ExtractorLinkType.M3U8) {
                    this.referer = mainUrl
                    this.quality = Qualities.Unknown.value
                    this.headers = mapOf("User-Agent" to userAgent)
                }
            } else {
                extractFromMirror(url, sourceName)
            }
        } catch (e: Exception) {
            Log.e("DDLExt", "Estrazione fallita per $url: $e")
            null
        }
    }

    private suspend fun extractFromMirror(mirrorUrl: String, sourceName: String): ExtractorLink? {
        var currentUrl = mirrorUrl
        var currentReferer = mirrorUrl

        // Alcuni mirror incapsulano il vero player dietro 1-2 livelli di iframe, e l'attributo
        // src può essere assoluto, protocol-relative (//host/...) o relativo al dominio corrente.
        repeat(3) {
            val headers = mapOf("User-Agent" to userAgent, "Referer" to currentReferer)
            val html = app.get(currentUrl, headers = headers, timeout = 15L).text
            val doc = Jsoup.parse(html)
            val currentHost = URL(currentUrl).let { "${it.protocol}://${it.host}" }

            val m3u8 = extractAtobStream(html) ?: extractFromNewzar(doc, currentHost)
            if (m3u8 != null) {
                return newExtractorLink(sourceName, sourceName, m3u8, type = ExtractorLinkType.M3U8) {
                    this.referer = "$currentHost/"
                    this.quality = Qualities.Unknown.value
                    this.headers = mapOf(
                        "Origin" to currentHost,
                        "Referer" to "$currentHost/",
                        "User-Agent" to userAgent
                    )
                }
            }

            val iframeSrc = doc.selectFirst("iframe")?.attr("src")
            if (iframeSrc.isNullOrBlank()) {
                Log.d("DDLExt", "Nessun player/iframe trovato su $currentUrl")
                return null
            }
            currentReferer = currentUrl
            currentUrl = URL(URL(currentUrl), iframeSrc).toString()
        }

        Log.d("DDLExt", "Troppi livelli di iframe per $mirrorUrl")
        return null
    }

    // Player più recenti: `source: window.atob('BASE64_URL')` dentro un <script>
    private fun extractAtobStream(html: String): String? {
        val base64 = Regex("""atob\(\s*['"]([A-Za-z0-9+/=]+)['"]\s*\)""").find(html)
            ?.groupValues?.get(1) ?: return null
        return try {
            base64Decode(base64).takeIf { it.startsWith("http") }
        } catch (e: Exception) {
            null
        }
    }

    // Schema legacy "newzar": CHANNEL_KEY + bundle firmato -> auth.php -> server_lookup.php
    private suspend fun extractFromNewzar(page: Document, serverUrl: String): String? {
        val script = page.select("script").firstOrNull { it.data().contains("CHANNEL_KEY") }?.data()
            ?: return null

        val bundle = base64Decode(
            Regex("""(?<=const IJXX=").*(?=")""").find(script)?.value ?: return null
        )
        val bundleObj = parseJson<Bundle>(bundle)
        val channelKey =
            Regex("""(?<=const CHANNEL_KEY=").*(?=")""").find(script)?.value ?: return null
        val params = mapOf(
            "channel_id" to channelKey,
            "ts" to base64Decode(bundleObj.bTs),
            "rnd" to base64Decode(bundleObj.bRnd),
            "sig" to base64Decode(bundleObj.bSig),
        )

        val authResponse = app.get(
            "https://top2new.newkso.ru/auth.php",
            params = params,
            headers = mapOf(
                "User-Agent" to userAgent,
                "Referer" to "$serverUrl/",
                "Origin" to serverUrl
            ),
        )
        if (authResponse.code == 403) return null

        val serverKey = app.get("$serverUrl/server_lookup.php?channel_id=$channelKey").text
        val data = try {
            parseJson<DataResponse>(serverKey)
        } catch (e: MismatchedInputException) {
            Log.d("DDLExt", "server_lookup.php risposta inattesa: $serverKey")
            return null
        }

        return when (data.serverKey) {
            "top1/cdn" -> "https://top1.newkso.ru/top1/cdn/$channelKey/mono.m3u8"
            else -> "https://${data.serverKey}new.newkso.ru/${data.serverKey}/$channelKey/mono.m3u8"
        }
    }

    data class DataResponse(@JsonProperty("server_key") val serverKey: String)
    data class Bundle(
        @JsonProperty("b_host") val bHost: String,
        @JsonProperty("b_rnd") val bRnd: String,
        @JsonProperty("b_script") val bScript: String,
        @JsonProperty("b_sig") val bSig: String,
        @JsonProperty("b_ts") val bTs: String
    )
}
