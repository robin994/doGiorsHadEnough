package it.dogior.hadEnough

import com.lagradost.api.Log
import com.lagradost.cloudstream3.app

/**
 * DaddyLive cambia dominio molto spesso (i vecchi domini vengono oscurati).
 * Invece di tenere un mainUrl fisso nel codice, lo risolviamo a runtime
 * leggendo l'elenco dei domini attivi pubblicato su warflix.cv, con una
 * piccola lista di riserva nel caso warflix.cv stesso sia irraggiungibile.
 */
object DaddyLiveDomain {
    private const val domainsSourceUrl = "https://warflix.cv/"
    private const val cacheDurationMs = 10 * 60 * 1000L

    // Ultima spiaggia se warflix.cv non risponde o non trova nulla di attivo
    private val fallbackDomains = listOf(
        "https://daddylive.mov",
        "https://daddylive.org",
        "https://daddylive.li",
        "https://daddylive.app",
    )

    private var cachedUrl: String? = null
    private var cachedAt: Long = 0

    suspend fun getMainUrl(): String {
        val now = System.currentTimeMillis()
        cachedUrl?.let { if (now - cachedAt < cacheDurationMs) return it }

        val resolved = resolveActiveDomain()
        cachedUrl = resolved
        cachedAt = now
        return resolved
    }

    private suspend fun resolveActiveDomain(): String {
        val activeFromWarflix = try {
            fetchActiveDomainsFromWarflix()
        } catch (e: Exception) {
            Log.e("DDL Domain", "Impossibile leggere warflix.cv: $e")
            emptyList()
        }

        val candidates = (activeFromWarflix + fallbackDomains).distinct()
        for (domain in candidates) {
            if (pingDomain(domain)) {
                Log.d("DDL Domain", "Dominio DaddyLive attivo: $domain")
                return domain
            }
        }

        Log.e("DDL Domain", "Nessun dominio DaddyLive raggiungibile, uso l'ultima spiaggia")
        return candidates.firstOrNull() ?: fallbackDomains.first()
    }

    private suspend fun fetchActiveDomainsFromWarflix(): List<String> {
        val doc = app.get(domainsSourceUrl, timeout = 15L).document
        val section = doc.select("div[itemtype=https://schema.org/ItemList]")
            .firstOrNull {
                it.select(".category-title").text().contains("DaddyLive", ignoreCase = true)
            } ?: return emptyList()

        return section.select("a.link-card.status-active")
            .mapNotNull { it.attr("href").trim().takeIf { url -> url.isNotBlank() } }
            .map { it.removeSuffix("/") }
    }

    private suspend fun pingDomain(url: String): Boolean {
        return try {
            app.get(url, timeout = 8L).isSuccessful
        } catch (e: Exception) {
            false
        }
    }
}
