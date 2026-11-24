package keiyoushi.lib.aedex

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.nodes.Element

/** HTML conventions shared by Aedex-style sources, independently of the browsing engine. */
object AedexHtml {
    const val ARCHIVE_CARD = "div.group"
    const val ARCHIVE_LINK = "a:has(h3)"
    const val ARCHIVE_TITLE = "h3"
    const val DETAILS_TITLE = "div.wp-manga div.grid > h1"
    const val DETAILS_STATUS = "div.wp-manga div[alt=type]:eq(0) > span"
    const val DETAILS_GENRE = "div.wp-manga div[alt=type]:gt(0) > span"
    const val DETAILS_DESCRIPTION = "div.wp-manga div#expand_content"
    const val CHAPTER_LINK = "ul#list-chapters li > a"
    const val CHAPTER_NAME = "div.grid > span"
    const val CHAPTER_DATE = "div.grid > div"

    private val backgroundUrl = Regex("""url\(\s*['"]?([^'")]+)['"]?\s*\)""", RegexOption.IGNORE_CASE)

    /** Resolves a CSS background cover against the page URL, if present. */
    fun backgroundCover(card: Element): String? {
        val element = if (card.attr("style").contains("background-image", ignoreCase = true)) {
            card
        } else {
            card.selectFirst("[style*=background-image]") ?: return null
        }
        val url = backgroundUrl.find(element.attr("style"))?.groupValues?.get(1)?.trim()
            ?.takeIf(String::isNotBlank) ?: return null
        return element.baseUri().toHttpUrlOrNull()?.resolve(url)?.toString()
    }
}
