package eu.kanade.tachiyomi.extension.es.capibaratraductor

import android.webkit.WebResourceResponse
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import java.io.ByteArrayInputStream
import java.io.IOException
import keiyoushi.annotation.Source
import keiyoushi.network.Fetch
import keiyoushi.network.get
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.WebViewTimeoutException
import keiyoushi.utils.parseAs
import keiyoushi.utils.runWebView
import keiyoushi.utils.toJsonElement
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

@Source
abstract class CapibaraTraductor : KeiSource() {

    private val baseUrlHost get() = baseUrl.toHttpUrl().host

    override fun OkHttpClient.Builder.configureClient() = rateLimit(3) { it.host == baseUrlHost }

    private fun getScanHeaders(organizationSlug: String): Headers = headers.newBuilder()
        .add("x-organization", organizationSlug)
        .build()

    override suspend fun getPopularManga(page: Int): MangasPage = parseMangaList("$baseUrl/api/manga-custom?page=$page&limit=$PAGE_LIMIT&order=popular".toHttpUrl(), page, headers)

    override suspend fun getLatestUpdates(page: Int): MangasPage = parseMangaList("$baseUrl/api/manga-custom?page=$page&limit=$PAGE_LIMIT&order=latest".toHttpUrl(), page, headers)

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val url = "$baseUrl/api/manga-custom".toHttpUrl().newBuilder()

        url.setQueryParameter("page", page.toString())
        url.setQueryParameter("limit", PAGE_LIMIT.toString())

        val headers = headers.newBuilder()

        filters.forEach { filter ->
            when (filter) {
                is SortByFilter -> url.setQueryParameter("order", filter.toUriPart())
                is ScanlatorFilter -> if (filter.state != 0) headers["x-organization"] = filter.toUriPart()
                else -> {}
            }
        }

        if (query.isNotBlank()) url.setQueryParameter("search", query)

        return parseMangaList(url.build(), page, headers.build())
    }

    private suspend fun parseMangaList(url: HttpUrl, page: Int, headers: Headers): MangasPage {
        val result = client.get(url, headers).parseAs<Data<SeriesListDataDto>>()

        val mangas = result.data.series.map { it.toSManga() }
        val hasNextPage = page < result.data.maxPage

        return MangasPage(mangas, hasNextPage)
    }

    override val supportsFilterFetching get() = true

    override suspend fun fetchFilterData(): JsonElement {
        val sfwScans = fetchAllScans(includeNsfw = false)
        val nsfwScans = fetchAllScans(includeNsfw = true)

        return buildList {
            add("Todos" to "")
            addAll(
                (sfwScans + nsfwScans)
                    .distinctBy { it.second }
                    .sortedBy { it.first },
            )
        }.toJsonElement()
    }

    override fun getFilterList(data: JsonElement?): FilterList {
        val filters = mutableListOf<Filter<*>>()

        data?.parseAs<List<Pair<String, String>>>()?.also {
            filters.add(ScanlatorFilter("Scanlator", it.toTypedArray()))
        }

        filters.add(SortByFilter("Ordenar por", getSortList()))

        return FilterList(filters)
    }

    private fun getSortList() = arrayOf(
        Pair("Recientes", "latest"),
        Pair("Popularidad", "popular"),
        Pair("A-Z", "alphabetical"),
    )

    private suspend fun fetchAllScans(includeNsfw: Boolean): List<Pair<String, String>> {
        val scans = mutableListOf<Pair<String, String>>()
        var page = 1

        while (true) {
            val url = "$baseUrl/api/landing/scans".toHttpUrl().newBuilder()
                .addQueryParameter("page", page.toString())
                .addQueryParameter("sort", "name")
                .addQueryParameter("limit", "100")
                .apply {
                    if (includeNsfw) {
                        addQueryParameter("includeNSFW", "true")
                    }
                }
                .build()

            val response = client.get(url)
                .parseAs<Data<ScanListDto>>()
                .data

            scans += response.items.map { it.name to it.id }

            if (!response.hasNextPage()) {
                break
            }

            page++
        }

        return scans
    }

    override fun getMangaUrl(manga: SManga): String {
        val (seriesSlug, organizationSlug) = manga.url.split("/", limit = 2)
        return "$baseUrl/$organizationSlug/manga/$seriesSlug"
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val (seriesSlug, organizationSlug) = manga.url.split("/", limit = 2)
        val result = client.get("$baseUrl/api/manga-custom/$seriesSlug", getScanHeaders(organizationSlug))
            .parseAs<Data<SeriesDto>>()
            .data

        val chapterList = result.chapters
            ?.filter { it.isUnreleased.not() }
            ?.map { it.toSChapter(result.manga.slug, result.organization.slug) }
            ?.filter { it.date_upload < System.currentTimeMillis() }
            ?: emptyList()

        return SMangaUpdate(result.toSMangaDetails(), chapterList)
    }

    override fun getChapterUrl(chapter: SChapter): String {
        val (chapterSlug, seriesSlug, organizationSlug) = chapter.url.split("/", limit = 3)

        return "$baseUrl/$organizationSlug/manga/$seriesSlug/chapters/$chapterSlug"
    }

    // The site's reader decodes the page tokens and removes decoy pages, read its result
    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val (chapterSlug, seriesSlug, organizationSlug) = chapter.url.split("/", limit = 3)
        val seriesPath = if (chapter.memo["jointId"] != null) {
            "joint/${chapter.memo["jointSlug"]?.stringOrNull ?: seriesSlug}"
        } else {
            "manga-custom/$seriesSlug"
        }

        // The site's login errors and empty chapters, which the reader only shows on screen
        val pages = client.get("$baseUrl/api/$seriesPath/chapter/$chapterSlug/pages", getScanHeaders(organizationSlug), Fetch.XHR)
            .parseAs<Data<JsonArray>>()
            .data
        if (pages.isEmpty()) return emptyList()

        val script = javaClass.getResource("/assets/page_list.js")!!.readText()
        val urls = try {
            runWebView<List<String>> {
                blockImages = true
                userAgent = headers["User-Agent"].orEmpty()
                // Keeps the site's ads out, but not Cloudflare's challenge
                interceptRequest { request ->
                    val host = request.url.host
                    if (host == null || host == baseUrlHost || host.endsWith(".$baseUrlHost") || host == "challenges.cloudflare.com") {
                        null
                    } else {
                        WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                    }
                }
                poll {
                    evaluateJs(script) { result ->
                        result.parseAs<List<String>?>()?.let(::resolve)
                    }
                }
                loadUrl(getChapterUrl(chapter))
            }
        } catch (_: WebViewTimeoutException) {
            throw Exception("Failed to load the chapter, open it in WebView")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            throw IOException(e.message, e)
        }

        return urls.mapIndexed { index, url -> Page(index, imageUrl = url) }
    }

    companion object {
        private const val PAGE_LIMIT = 36
    }
}
