package eu.kanade.tachiyomi.extension.en.kunmangaonline

import eu.kanade.tachiyomi.multisrc.madara.MadaraNoAjax
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.network.rateLimit
import keiyoushi.utils.parseAs
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import kotlin.time.Duration.Companion.seconds

@Source
abstract class KunMangaOnline : MadaraNoAjax() {
    override val supportsPostId = false
    
    override fun OkHttpClient.Builder.configureClient() = apply {
    addInterceptor(KunMangaRetryInterceptor())
    rateLimit(1, 2.seconds)
}


    private val apiHeaders get() = headersBuilder()
        .add("Accept", "application/json")
        .build()

    override fun archiveSelector() = ".c-tabs-item__content, .page-item-detail"
    override val archiveUrlSelector = ".post-title a, h3.h4 a"
    override fun nextPageSelector() = "a[aria-label=Next]"

    override fun getChapterUrl(chapter: SChapter) = "$baseUrl${chapter.url}"

    override suspend fun fetchChapters(mangaPath: String, id: String, mangaPage: Document?): List<SChapter> {
        val slug = "$baseUrl$mangaPath".toHttpUrl().pathSegments.getOrNull(1) ?: return emptyList()

        val firstUrl = "$baseUrl/api/comics/$slug/chapters?page=1&per_page=$CHAPTERS_PER_PAGE&order=desc"
        val firstResponse = client.get(firstUrl, apiHeaders)
        val firstPage = firstResponse.parseAs<ChapterListResponse>().data

        val allChapters = firstPage.chapters.map { it.toSChapter(slug) }.toMutableList()

        if (firstPage.lastPage > 1) {
            coroutineScope {
                (2..firstPage.lastPage).map { page ->
                    async {
                        val apiUrl = firstResponse.request.url.newBuilder()
                            .setQueryParameter("page", page.toString())
                            .build()
                        client.get(apiUrl, apiHeaders).parseAs<ChapterListResponse>().data.chapters
                    }
                }.awaitAll().forEach { chapters ->
                    allChapters.addAll(chapters.map { it.toSChapter(slug) })
                }
            }
        }

        return allChapters
    }

    override fun imageFromElement(element: Element) = listOf("data-src", "data-lazy-src", "data-aload", "src", "data-backup")
        .map { element.absUrl(it) }
        .firstOrNull { it.startsWith("http") && !it.contains("${baseUrl.toHttpUrl().host}/thumb/") }

    override fun imageRequest(page: Page) = super.imageRequest(page)
    .newBuilder()
    .header("Referer", "$baseUrl/")
    .build()

    
    private class KunMangaRetryInterceptor : Interceptor {

    companion object {
        private const val MAX_RETRIES = 1
        private const val INITIAL_WAIT_MS = 5_000L
        private const val MAX_WAIT_MS = 30_000L

        private val lock = Object()
        private val cooldowns = mutableMapOf<String, Long>()
        private val failures = mutableMapOf<String, Int>()
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val host = request.url.host

        if (host != "cdn.zinmanga1.com" &&
            host != "img-r2.2xstorage.com"
        ) {
            return chain.proceed(request)
        }

        var retries = 0

        while (true) {
            waitForCooldown(chain, host)

            val response = chain.proceed(request)

            if (response.code != 429) {
                if (response.isSuccessful) {
                    synchronized(lock) {
                        failures.remove(host)
                    }
                }
                return response
            }

            val retryAfter = response.header("Retry-After")
                ?.trim()
                ?.toLongOrNull()
                ?.takeIf { it >= 0L }

            val failuresCount = synchronized(lock) {
                val count = (failures[host] ?: 0) + 1
                failures[host] = count.coerceAtMost(4)
                count
            }

            val backoff = when {
                failuresCount <= 1 -> INITIAL_WAIT_MS
                failuresCount == 2 -> 10_000L
                failuresCount == 3 -> 20_000L
                else -> MAX_WAIT_MS
            }

            val waitMs = if (retryAfter != null) {
                if (retryAfter > MAX_WAIT_MS / 1000L) {
                    null
                } else {
                    TimeUnit.SECONDS.toMillis(retryAfter)
                        .coerceAtLeast(1_000L)
                }
            } else if (response.header("Retry-After") == null) {
                backoff
            } else {
                null
            }

            if (waitMs == null) {
                val error = diagnostic429(response)
                response.close()
                throw error
            }

            synchronized(lock) {
                val now = android.os.SystemClock.elapsedRealtime()
                cooldowns[host] = maxOf(
                    cooldowns[host] ?: 0L,
                    now + waitMs,
                )
            }

            if (retries >= MAX_RETRIES) {
                val error = diagnostic429(response)
                response.close()
                throw error
            }

            response.close()
            retries++
        }
    }

    private fun diagnostic429(response: Response): IOException {
        val host = response.request.url.host
        val server = response.header("Server")
            ?.take(40) ?: "No informado"
        val cfRay = response.header("CF-Ray")
            ?.take(40) ?: "No informado"
        val retryAfter = response.header("Retry-After")
            ?.take(40) ?: "No informado"

        return IOException(
            "KunManga HTTP 429\n" +
                "Host: $host\n" +
                "Server: $server\n" +
                "CF-Ray: $cfRay\n" +
                "Retry-After: $retryAfter"
        )
    }

    private fun waitForCooldown(
        chain: Interceptor.Chain,
        host: String,
    ) {
        while (true) {
            if (chain.call().isCanceled()) {
                throw IOException("Solicitud cancelada")
            }

            val remaining = synchronized(lock) {
                (cooldowns[host] ?: 0L) -
                    android.os.SystemClock.elapsedRealtime()
            }

            if (remaining <= 0L) return

            try {
                Thread.sleep(minOf(remaining, 250L))
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("Espera interrumpida", e)
            }
        }
    }
}
    companion object {
        private const val MAX_RETRIES = 1
        private const val DEFAULT_WAIT_MS = 5_000L
        private const val MAX_AUTO_WAIT_MS = 30_000L

        private val lock = Object()
        private var cooldownUntil = 0L
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()

        val isImageHost = request.url.host in setOf(
            "cdn.zinmanga1.com",
            "img-r2.2xstorage.com",
        )

        if (!isImageHost) {
            return chain.proceed(request)
        }

        var retries = 0

        while (true) {
            waitForCooldown(chain)

            val response = chain.proceed(request)

            if (response.code != 429) {
                return response
            }

            val retryAfterHeader = response.header("Retry-After")
                ?.trim()

            val retryAfterSeconds = retryAfterHeader
                ?.toLongOrNull()

            val waitMs = when {
                retryAfterHeader == null -> DEFAULT_WAIT_MS
                retryAfterSeconds == null -> null
                retryAfterSeconds < 0L -> null
                retryAfterSeconds > MAX_AUTO_WAIT_MS / 1000L -> null
                else -> TimeUnit.SECONDS.toMillis(retryAfterSeconds)
            }

            
            if (waitMs == null) {
              val error = diagnostic429(response)
              response.close()
              throw error
            }


            val safeWaitMs = waitMs.coerceAtLeast(1_000L)

            synchronized(lock) {
                val now = android.os.SystemClock.elapsedRealtime()
                cooldownUntil = maxOf(
                    cooldownUntil,
                    now + safeWaitMs,
                )
            }

            
            if (retries >= MAX_RETRIES) {
              val error = diagnostic429(response)
              response.close()
              throw error
            }


            response.close()
            retries++
        }
    }

    
       private fun diagnostic429(response: Response): IOException {
          val host = response.request.url.host
          val server = response.header("Server")
        ?.take(40) ?: "No informado"
          val cfRay = response.header("CF-Ray")
        ?.take(40) ?: "No informado"
          val retryAfter = response.header("Retry-After")
        ?.take(40) ?: "No informado"

          return IOException(
           "KunManga HTTP 429\n" +
           "Host: $host\n" +
           "Server: $server\n" +
           "CF-Ray: $cfRay\n" +
           "Retry-After: $retryAfter"
        )
    }


        private fun waitForCooldown(chain: Interceptor.Chain) {
        while (true) {
            
            if (chain.call().isCanceled()) {
                throw IOException("Solicitud cancelada")
            }

            val remaining = synchronized(lock) {
                cooldownUntil -
                    android.os.SystemClock.elapsedRealtime()
            }

            if (remaining <= 0L) return

            try {
                Thread.sleep(minOf(remaining, 250L))
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("Espera interrumpida", e)
            }
        }
    }
}

    companion object {
        private const val POSTS_PER_PAGE = 20
        private const val CHAPTERS_PER_PAGE = 50
    }
}
