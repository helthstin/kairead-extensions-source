package eu.kanade.tachiyomi.extension.es.submanhwa

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import keiyoushi.utils.asJsoup
import keiyoushi.utils.parseAs
import keiyoushi.utils.tryParseDate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.format.DateTimeFormatter
import java.util.Locale

@Source
abstract class Submanhwa : KeiSource() {

    private val dateFormat = DateTimeFormatter.ofPattern("d MMM. yyyy", Locale.ENGLISH)
    private val ALT_DELIMITER = Regex("""[|/•,;]""")
    private val CHAPTERS_PER_PAGE = 50

    override fun Headers.Builder.configureHeaders(): Headers.Builder = add("Accept-Language", "es-PE,es;q=0.9,en-US;q=0.8,en;q=0.7")

    override suspend fun getPopularManga(page: Int) = getSearchMangaList(page, "", FilterList())

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val document = client.get(baseUrl).asJsoup()

        val mangas = document.select("article.up-card").map { element ->
            SManga.create().apply {
                title = element.selectFirst("a.up-title")!!.text()
                setUrlWithoutDomain(element.selectFirst("a")!!.absUrl("href"))
                thumbnail_url = element.selectFirst("img")!!.absUrl("src")
            }
        }
        return MangasPage(mangas, false)
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.pathSegments.count(String::isNotBlank) < 2) return null
        return fetchMangaUpdate(
            SManga.create().apply { this.url = url.encodedPath },
            emptyList(),
            true,
            false,
        ).manga
    }

    override suspend fun getSearchMangaList(
        page: Int,
        query: String,
        filters: FilterList,
    ): MangasPage = parseMangaList(
        baseUrl.toHttpUrl().newBuilder().apply {
            addPathSegment("filterList")
            addQueryParameter("page", page.toString())
            addQueryParameter("sortBy", "views")
            addQueryParameter("asc", "false")
            addQueryParameter("alpha", query)
        }.build(),
    )

    private suspend fun parseMangaList(url: HttpUrl): MangasPage {
        val document = client.get(url).asJsoup()
        val mangas = document.select(".series-card").map { element ->
            SManga.create().apply {
                title = element.selectFirst(".series-title")!!.text()
                setUrlWithoutDomain(element.selectFirst("a")!!.absUrl("href"))
                thumbnail_url = element.selectFirst("img")!!.absUrl("src")
            }
        }
        val hasNextPage = document.selectFirst("li a[rel=next]") != null

        return MangasPage(mangas, hasNextPage)
    }

    override val supportRelatedMangasBySearch = true

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        if (!fetchDetails && !fetchChapters) return SMangaUpdate(manga, chapters)
        val document = client.get(getMangaUrl(manga)).asJsoup()

        val details = if (fetchDetails) {
            SManga.create().apply {
                url = manga.url
                title = document.selectFirst(".sr-info > h1")?.text()
                    ?: document.selectFirst("h1")?.text()
                    ?: manga.title
                thumbnail_url = document.selectFirst("img.sr-cover")?.absUrl("src")
                val info = document.selectFirst(".sr-info")
                val meta = info?.selectFirst(".sr-meta")

                description = buildString {
                    info?.select(".sr-stats .sr-stat")?.takeIf { it.isNotEmpty() }?.let {
                        append(it.joinToString(" | ") { stat -> stat.text() })
                    }
                    document.selectFirst(".sr-summary > p")?.text()?.takeIf { it.isNotBlank() }?.let {
                        if (isNotEmpty()) append("\n\n")
                        append(it)
                    }
                    info?.selectFirst(".sr-alt")?.text()?.split(ALT_DELIMITER)?.let { names ->
                        if (isNotEmpty()) append("\n\n")
                        append("Nombres alternativos:\n")
                        names.forEach { name -> append("- ${name.trim()}\n") }
                    }
                }

                status = when {
                    info?.selectFirst(".ongoing") != null -> SManga.ONGOING
                    info?.selectFirst(".ended") != null -> SManga.COMPLETED
                    else -> SManga.UNKNOWN
                }
                author = meta?.select("dt:contains(Autor(es)) + dd a")?.joinToString { it.text() }
                artist = meta?.select("dt:contains(Artist(s)) + dd a")?.joinToString { it.text() }
                genre = info?.select(".sr-badge.type, .sr-cat, .sr-tag")?.joinToString { it.text() }
            }
        } else {
            manga
        }

        if (!fetchChapters) return SMangaUpdate(details, chapters)

        val mangaId = document.selectFirst("#item-rating")?.attr("data-item")?.takeIf { it.isNotBlank() }
            ?: throw Exception("No se pudo encontrar el ID del manga")

        val chapterList = mutableListOf<SChapter>()
        var page = 1
        do {
            val url = "$baseUrl/lista-capitulos/serie/$mangaId?orden=desc&offset=${(page - 1) * CHAPTERS_PER_PAGE}&limit=$CHAPTERS_PER_PAGE"
            val chapterListDto = client.get(
                url,
                headers.newBuilder().set("X-Requested-With", "XMLHttpRequest").build(),
            ).parseAs<ChapterListDto>()
            val newChapters = parseChapterList(Jsoup.parseBodyFragment(chapterListDto.html, baseUrl))
            if (newChapters.isEmpty()) break
            chapterList.addAll(newChapters)
            page++
        } while (chapterListDto.hasNext)

        return SMangaUpdate(details, chapterList)
    }

    private fun parseChapterList(html: Document): List<SChapter> = html.select("[id^=chapter-card]").mapNotNull { element ->
        val chapterLink = element.selectFirst("a.lc-t") ?: return@mapNotNull null
        SChapter.create().apply {
            name = chapterLink.text()
            setUrlWithoutDomain(chapterLink.absUrl("href"))
            val date = element.selectFirst(".lc-d")?.ownText()
            date_upload = dateFormat.tryParseDate(date?.removePrefix("🕒 "))
        }
    }

    @Serializable
    class ChapterListDto(
        val html: String,
        @SerialName("mas") val hasNext: Boolean,
    )

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val document = client.get(getChapterUrl(chapter)).asJsoup()
        return document.select("#all img").mapIndexed { idx, img ->
            Page(idx, imageUrl = img.imgAttr())
        }
    }

    private fun Element.imgAttr(): String = when {
        hasAttr("data-src") -> attr("abs:data-src")
        else -> attr("abs:src")
    }
}
