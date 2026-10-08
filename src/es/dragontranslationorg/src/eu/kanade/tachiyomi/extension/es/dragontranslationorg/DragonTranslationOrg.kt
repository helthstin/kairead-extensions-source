package eu.kanade.tachiyomi.extension.es.dragontranslationorg

import eu.kanade.tachiyomi.multisrc.madara.MadaraNoAjax
import eu.kanade.tachiyomi.source.model.SChapter
import keiyoushi.annotation.Source
import keiyoushi.network.rateLimit
import keiyoushi.utils.parseAs
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import java.time.format.DateTimeFormatter
import java.util.Locale
import eu.kanade.tachiyomi.source.model.SManga
import org.jsoup.nodes.Element

@Source
abstract class DragonTranslationOrg : MadaraNoAjax() {
    override val supportsPostId = false
    override val chapterDateFormat = DateTimeFormatter.ofPattern("MMMM dd, yyyy", Locale.forLanguageTag("es"))

    override fun OkHttpClient.Builder.configureClient() = rateLimit(3)

    override val filterGenresSelector = ".filters"
    override fun archiveSelector() = "a.mb799cfc"
    override val archiveUrlSelector = ""
    override val archiveTitleSelector = ".m3ae409d"
    override fun archiveManga(element: Element, id: String): SManga? {
    val href = element.attr("abs:href")
    if (href.isBlank()) return null

    return SManga.create().apply {
        url = java.net.URI(href).path
        title = element.selectFirst(".m3ae409d")?.text() ?: element.attr("title")
        thumbnail_url = element.selectFirst("img")?.absUrl("src")
    }
    }

    
    override val mangaDetailsSelectorTitle = ".hero__in h1"
    override val mangaDetailsSelectorStatus = ".htags .htag"
    override val mangaDetailsSelectorDescription = ".syn p"
    override val mangaDetailsSelectorThumbnail = ".hposter img"
    override val mangaDetailsSelectorGenre = ".hchips a.chip"
  

    override fun getChapterUrl(chapter: SChapter) = "$baseUrl${chapter.url}"

    override suspend fun fetchChapters(
    mangaPath: String,
    id: String,
    mangaPage: Document?,
): List<SChapter> {
    val chapterJson = mangaPage
        ?.select("script[type=application/json]")
        ?.firstOrNull { script ->
            val data = script.data()
            data.contains("\"mangaId\"") &&
                data.contains("\"items\"")
        }
        ?.data()
        ?: return emptyList()

    return chapterJson.parseAs<ChapterListDto>().items.map { chapterDto ->
        SChapter.create().apply {
            setUrlWithoutDomain(chapterDto.url)
            name = chapterDto.name
            date_upload = runCatching {
                parseChapterDate(chapterDto.ago)
            }.getOrDefault(0L)
          }
        }
      }
    } 
