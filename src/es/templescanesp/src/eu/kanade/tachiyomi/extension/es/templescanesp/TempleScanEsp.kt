package eu.kanade.tachiyomi.extension.es.templescanesp

import eu.kanade.tachiyomi.multisrc.madara.Madara
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.annotation.Source
import keiyoushi.lib.aedex.AedexHtml
import keiyoushi.network.get
import keiyoushi.network.post
import keiyoushi.network.rateLimit
import keiyoushi.utils.asJsoup
import keiyoushi.utils.parseAs
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
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
    override val supportsPostId = false
    override val chapterDateFormat = DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.forLanguageTag("es"))
    override val mangaSubString = "comic"

    override suspend fun getPopularManga(page: Int) = catalog(page, "", "-n_visitas")

    override suspend fun getLatestUpdates(page: Int) = catalog(page, "", "-fecha_ultimo_capitulo")

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList) = catalog(page, query, "-n_visitas")

    override fun getFilterList(data: JsonElement?) = FilterList()

    private suspend fun catalog(page: Int, query: String, sort: String): MangasPage {
        val url = "$baseUrl/api/backend/mangas".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("limit", "24")
            .addQueryParameter("sort", sort)
            .apply { if (query.isNotBlank()) addQueryParameter("name", query.trim()) }
            .build()
        val apiHeaders = headers.newBuilder()
            .set("System", "aedex")
            .set("X-FK-Sistema", "1")
            .set("Accept", "application/json")
            .build()
        val result = client.get(url, apiHeaders).parseAs<CatalogPage>()
        return MangasPage(
            result.data.map { item ->
                SManga.create().apply {
                    this.url = "/serie/${item.slug}"
                    title = item.nombre
                    thumbnail_url = item.cover?.let { baseUrl.toHttpUrl().resolve("/assets/$it")?.toString() }
                    memo = mangaMemo("/comic/${item.slug}", emptyList())
                }
            },
            page < result.totalPages,
        )
    }

    override fun getMangaUrl(manga: SManga): String {
        val path = manga.url.replaceFirst("/serie/", "/comic/")
        return baseUrl.toHttpUrl().resolve(path)?.toString() ?: super.getMangaUrl(manga)
    }

    override fun OkHttpClient.Builder.configureClient() = rateLimit(3, 1.seconds) { it.host == baseUrl.toHttpUrl().host }

    override val mangaDetailsSelectorTitle = "div.wp-manga div.grid > h1"
    override val mangaDetailsSelectorStatus = "div.wp-manga div[alt=type]:eq(0) > span"
    override val mangaDetailsSelectorGenre = "div.wp-manga div[alt=type]:gt(0) > span"
    override val mangaDetailsSelectorDescription = "div.wp-manga div#expand_content"

    override fun archiveManga(element: Element, id: String) = super.archiveManga(element, id)?.apply {
        thumbnail_url = thumbnail_url ?: AedexHtml.backgroundCover(element)
    }
    override fun archiveSelector() = "div.group"
    override val archiveUrlSelector = "a:has(h3)"
    override val archiveTitleSelector = "h3"

    override fun chapterListSelector() = "ul#list-chapters li > a"
    override val chapterNameSelector = "div.grid > span"
    override val chapterDateSelector = "div.grid > div"
    override val chapterUrlSelector = "a"

    override suspend fun fetchChapterDocument(chapterUrl: String): Document {
        val document = super.fetchChapterDocument(chapterUrl)

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

    override val supportsFilterFetching get() = false
}

@Serializable
private class CatalogPage(
    val data: List<CatalogManga>,
    @SerialName("total_pages") val totalPages: Int,
)

@Serializable
private class CatalogManga(
    val slug: String,
    val nombre: String,
    @SerialName("portada_url") val cover: String? = null,
)
