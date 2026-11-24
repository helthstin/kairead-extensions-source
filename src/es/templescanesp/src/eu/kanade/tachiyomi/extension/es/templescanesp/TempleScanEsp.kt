package eu.kanade.tachiyomi.extension.es.templescanesp

import eu.kanade.tachiyomi.multisrc.madara.Madara
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.network.get
import keiyoushi.utils.parseAs
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import keiyoushi.annotation.Source
import keiyoushi.network.post
import keiyoushi.network.rateLimit
import keiyoushi.utils.asJsoup
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

@Source
abstract class TempleScanEsp : Madara() {
    // Aedex no longer serves Madara's WordPress AJAX catalog.
    override suspend fun getPopularManga(page: Int): MangasPage = fetchAedexCatalog(page, "-n_visitas")

    override suspend fun getLatestUpdates(page: Int): MangasPage = fetchAedexCatalog(page, "-fecha_ultimo_capitulo")

    private suspend fun fetchAedexCatalog(page: Int, sort: String): MangasPage {
        val url = baseUrl.toHttpUrl().newBuilder()
            .addPathSegments("api/backend/mangas")
            .addQueryParameter("page", page.toString())
            .addQueryParameter("limit", "24")
            .addQueryParameter("sort", sort)
            .build()
        val requestHeaders = headersBuilder()
            .set("System", "aedex")
            .set("X-FK-Sistema", "1")
            .build()
        val result = client.get(url, requestHeaders).parseAs<TempleCatalogResponse>()
        return MangasPage(
            result.data.map { entry ->
                SManga.create().apply {
                    url = "/comic/${entry.slug}"
                    title = entry.nombre
                    thumbnail_url = entry.portadaUrl?.let {
                        baseUrl.toHttpUrl().resolve("/assets/$it")?.toString()
                    }
                }
            },
            page < result.totalPages,
        )
    }

    override val supportsPostId = false
    override val chapterDateFormat = DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.forLanguageTag("es"))
    override val mangaSubString = "serie"

    override fun OkHttpClient.Builder.configureClient() = rateLimit(3, 1.seconds) { it.host == baseUrl.toHttpUrl().host }

    override val mangaDetailsSelectorTitle = "div.wp-manga div.grid > h1"
    override val mangaDetailsSelectorStatus = "div.wp-manga div[alt=type]:eq(0) > span"
    override val mangaDetailsSelectorGenre = "div.wp-manga div[alt=type]:gt(0) > span"
    override val mangaDetailsSelectorDescription = "div.wp-manga div#expand_content"

    override fun archiveManga(element: Element, id: String) = super.archiveManga(element, id)?.apply {
        thumbnail_url = thumbnail_url ?: element.selectFirst("[style*='background-image']")
            ?.attr("style")
            ?.substringAfter("url(")
            ?.substringBefore(")")
    }
    override fun archiveSelector() = "div.group"
    override val archiveUrlSelector = "a:has(h3)"
    override val archiveTitleSelector = "h3"

    override fun chapterListSelector() = "ul#list-chapters li > a"
    override val chapterNameSelector = "div.grid > span"
    override val chapterDateSelector = "div.grid > div"
    override val chapterUrlSelector = "a"

    override suspend fun fetchChapterDocument(chapterUrl: String): Document {
        var document = super.fetchChapterDocument(chapterUrl)

        val form = document.selectFirst("form#redirect-form[method=post]")
            ?: return document

        val url = form.attr("action")

        val headers = headersBuilder()
            .set("Referer", document.location())
            .build()

        val body = FormBody.Builder().apply {
            form.select("input[name]").forEach {
                add(it.attr("name"), it.attr("value"))
            }
        }.build()

        return client.post(url, headers, body).asJsoup()
    }

    override val supportsFilterFetching get() = true
}

@Serializable
private class TempleCatalogResponse(
    val data: List<TempleCatalogManga>,
    @SerialName("total_pages") val totalPages: Int,
)

@Serializable
private class TempleCatalogManga(
    val slug: String,
    val nombre: String,
    @SerialName("portada_url") val portadaUrl: String? = null,
)
