package eu.kanade.tachiyomi.extension.es.taurusfansub

import android.content.SharedPreferences
import android.widget.Toast
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.multisrc.madara.Madara
import eu.kanade.tachiyomi.source.ConfigurableSource
import keiyoushi.annotation.Source
import keiyoushi.network.rateLimit
import keiyoushi.utils.getPreferences
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

@Source
abstract class TaurusFansub :
    Madara(),
    ConfigurableSource {
    override val supportsPostId = false
    override val chapterDateFormat = DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ROOT)

    override fun OkHttpClient.Builder.configureClient() = addInterceptor(::resolveCover).rateLimit(2, 1.seconds)

    override val chapterMode = ChapterMode.MangaAjax

    override val mangaDetailsSelectorTitle = "h1.post-title"
    override val mangaDetailsSelectorStatus = "div.manga-status span:last-child"
    override val mangaDetailsSelectorDescription = "div.summary__content p"

    // The catalogue can return the same branding image for every series.
    // Resolve the actual cover from the series page only when the image is requested.
    override fun archiveManga(element: Element, id: String) = super.archiveManga(element, id)?.apply {
        thumbnail_url = coverLookupUrl(url)
    }

    override fun parseSearchCards(document: Document): List<SearchCard> = document.select(searchCardSelector()).mapNotNull { element ->
        val link = element.selectFirst(archiveUrlSelector) ?: return@mapNotNull null
        val href = link.attr("abs:href").takeIf(String::isNotBlank) ?: return@mapNotNull null
        val path = href.toHttpUrl().encodedPath
        val title = archiveTitleSelector?.let { element.selectFirst(it)?.text() } ?: link.text()
        SearchCard(title, path, coverLookupUrl(path))
    }

    private fun coverLookupUrl(path: String): String? = baseUrl.toHttpUrl().resolve(path)?.newBuilder()
        ?.addQueryParameter(COVER_LOOKUP, "1")
        ?.build()
        ?.toString()

    private fun resolveCover(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.url.host != baseUrl.toHttpUrl().host || request.url.queryParameter(COVER_LOOKUP) != "1") {
            return chain.proceed(request)
        }
        val detailUrl = request.url.newBuilder().removeAllQueryParameters(COVER_LOOKUP).build()
        val detailRequest = request.newBuilder().url(detailUrl).header("Accept", "text/html").build()
        val cover = chain.proceed(detailRequest).use { response ->
            if (!response.isSuccessful) throw IOException("Cover page HTTP ${response.code}")
            val document = Jsoup.parse(response.body.string(), response.request.url.toString())
            document.selectFirst(mangaDetailsSelectorThumbnail)?.let(::imageFromElement)?.takeIf(String::isNotBlank)
                ?: throw IOException("Series cover not found")
        }
        val imageRequest = request.newBuilder().url(cover).header("Referer", detailUrl.toString()).build()
        return chain.proceed(imageRequest)
    }

    private val preferences: SharedPreferences = getPreferences()

    override fun chapterListSelector(): String {
        val baseSelector = super.chapterListSelector()
        val removePremium = preferences.getBoolean(REMOVE_PREMIUM_CHAPTERS, REMOVE_PREMIUM_CHAPTERS_DEFAULT)

        if (!removePremium) {
            return baseSelector
        }

        return "$baseSelector:not(.scheduled)"
    }
    override val filterGenresSelector = ".manga-genres"

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        SwitchPreferenceCompat(screen.context).apply {
            key = REMOVE_PREMIUM_CHAPTERS
            title = "Filtrar capítulos de pago"
            summary = "Oculta automáticamente los capítulos que requieren pago."
            setDefaultValue(REMOVE_PREMIUM_CHAPTERS_DEFAULT)
            setOnPreferenceChangeListener { _, _ ->
                Toast.makeText(screen.context, "Para aplicar los cambios, actualiza la lista de capítulos", Toast.LENGTH_LONG).show()
                true
            }
        }.also { screen.addPreference(it) }
    }

    companion object {
        private const val COVER_LOOKUP = "kairead_cover"
        private const val REMOVE_PREMIUM_CHAPTERS = "removePremiumChapters"
        private const val REMOVE_PREMIUM_CHAPTERS_DEFAULT = true
    }
}
