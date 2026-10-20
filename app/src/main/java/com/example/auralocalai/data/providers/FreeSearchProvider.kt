package com.example.auralocalai.data.providers

import com.example.auralocalai.data.SearchContextBuilder
import com.example.auralocalai.data.SearchOutcome
import com.example.auralocalai.data.SearchProvider
import com.example.auralocalai.data.SearchSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URLEncoder

class FreeSearchProvider(
    private val client: OkHttpClient,
    private val duckDuckGoBaseUrl: String = "https://api.duckduckgo.com/",
    private val wikipediaBaseUrl: String = "https://en.wikipedia.org/w/api.php",
    private val jinaBaseUrl: String = "https://r.jina.ai/"
) : SearchProvider {

    override val id: String = "free"
    override val label: String = "Built-in Free (Encyclopedic & Wikipedia)"
    override val requiresApiKey: Boolean = false
    override val isEncyclopedicOnly: Boolean = true

    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        const val USER_AGENT = "AuraLocalAI/1.0 (Android; https://github.com/PrinceBad/Local-LLM-AI)"
        private val HTML_TAG_REGEX = Regex("<[^>]*>")
    }

    override suspend fun search(
        query: String,
        apiKey: String,
        maxResults: Int,
        country: String,
        language: String
    ): SearchOutcome = withContext(Dispatchers.IO) {
        val trimmedQuery = query.trim()
        if (trimmedQuery.isBlank()) {
            return@withContext SearchOutcome(query, emptyList(), id)
        }

        // Check if query is a direct URL -> route to Jina Reader
        if (SearchContextBuilder.isValidHttpUrl(trimmedQuery)) {
            val jinaSource = fetchViaJina(trimmedQuery)
            return@withContext if (jinaSource != null) {
                SearchOutcome(query, listOf(jinaSource), id)
            } else {
                SearchOutcome(query, emptyList(), id, isError = true, errorMessage = "Failed to fetch webpage content.")
            }
        }

        val aggregatedSources = mutableListOf<SearchSource>()

        // 1. Query DuckDuckGo Instant Answer
        try {
            val ddgSources = fetchDuckDuckGo(trimmedQuery)
            aggregatedSources.addAll(ddgSources)
        } catch (_: Throwable) {
            // Best effort; continue to Wikipedia
        }

        // 2. Query Wikipedia Search API if we need more sources
        if (aggregatedSources.size < maxResults) {
            try {
                val wikiSources = fetchWikipedia(trimmedQuery, maxResults - aggregatedSources.size)
                aggregatedSources.addAll(wikiSources)
            } catch (_: Throwable) {
                // Best effort
            }
        }

        SearchOutcome(
            query = query,
            sources = aggregatedSources.take(maxResults),
            providerId = id
        )
    }

    /**
     * Fetches instant encyclopedic summary from DuckDuckGo Instant Answer API.
     */
    private fun fetchDuckDuckGo(query: String): List<SearchSource> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "${duckDuckGoBaseUrl.trimEnd('/')}/?q=$encoded&format=json&no_html=1&skip_disambig=1"

        val request = Request.Builder()
            .url(url)
            .addHeader("User-Agent", USER_AGENT)
            .build()

        val response = client.newCall(request).execute()
        response.use { resp ->
            if (!resp.isSuccessful) return emptyList()
            val body = resp.body?.string().orEmpty()
            if (body.isBlank()) return emptyList()

            val root = json.parseToJsonElement(body).jsonObject
            val abstractText = root["AbstractText"]?.jsonPrimitive?.content.orEmpty()
            val abstractUrl = root["AbstractURL"]?.jsonPrimitive?.content.orEmpty()
            val heading = root["Heading"]?.jsonPrimitive?.content.orEmpty()

            val sources = mutableListOf<SearchSource>()
            if (abstractText.isNotBlank() && SearchContextBuilder.isValidHttpUrl(abstractUrl)) {
                val domain = abstractUrl.toHttpUrlOrNull()?.host ?: "duckduckgo.com"
                sources.add(
                    SearchSource(
                        title = SearchContextBuilder.sanitize(heading.ifBlank { query }),
                        url = abstractUrl,
                        snippet = SearchContextBuilder.sanitize(abstractText),
                        domain = domain
                    )
                )
            } else {
                // Fallback to RelatedTopics
                val related = root["RelatedTopics"]?.jsonArray
                if (related != null) {
                    for (item in related) {
                        if (sources.size >= 2) break
                        val obj = item.jsonObject
                        val text = obj["Text"]?.jsonPrimitive?.content ?: continue
                        val firstUrl = obj["FirstURL"]?.jsonPrimitive?.content ?: continue
                        if (SearchContextBuilder.isValidHttpUrl(firstUrl)) {
                            val domain = firstUrl.toHttpUrlOrNull()?.host ?: "duckduckgo.com"
                            sources.add(
                                SearchSource(
                                    title = SearchContextBuilder.sanitize(query),
                                    url = firstUrl,
                                    snippet = SearchContextBuilder.sanitize(text),
                                    domain = domain
                                )
                            )
                        }
                    }
                }
            }
            return sources
        }
    }

    /**
     * Queries Wikipedia Search API with a descriptive User-Agent as required by MediaWiki policy.
     */
    private fun fetchWikipedia(query: String, maxCount: Int): List<SearchSource> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val sep = if (wikipediaBaseUrl.contains("?")) "&" else "?"
        val url = "${wikipediaBaseUrl}${sep}action=query&list=search&srsearch=$encoded&srlimit=$maxCount&format=json&srprop=snippet"

        val request = Request.Builder()
            .url(url)
            .addHeader("User-Agent", USER_AGENT)
            .build()

        val response = client.newCall(request).execute()
        response.use { resp ->
            if (!resp.isSuccessful) return emptyList()
            val body = resp.body?.string().orEmpty()
            if (body.isBlank()) return emptyList()

            val root = json.parseToJsonElement(body).jsonObject
            val queryObj = root["query"]?.jsonObject ?: return emptyList()
            val searchItems = queryObj["search"]?.jsonArray ?: return emptyList()

            val sources = mutableListOf<SearchSource>()
            for (item in searchItems) {
                if (sources.size >= maxCount) break
                val obj = item.jsonObject
                val pageId = obj["pageid"]?.jsonPrimitive?.content ?: continue
                val title = obj["title"]?.jsonPrimitive?.content ?: continue
                val rawSnippet = obj["snippet"]?.jsonPrimitive?.content.orEmpty()
                val cleanSnippet = HTML_TAG_REGEX.replace(rawSnippet, "")

                val pageUrl = "https://en.wikipedia.org/?curid=$pageId"
                sources.add(
                    SearchSource(
                        title = SearchContextBuilder.sanitize(title),
                        url = pageUrl,
                        snippet = SearchContextBuilder.sanitize(cleanSnippet),
                        domain = "wikipedia.org"
                    )
                )
            }
            return sources
        }
    }

    /**
     * Fetches reader-mode content for a pasted URL via Jina Reader (https://r.jina.ai/).
     */
    fun fetchViaJina(targetUrl: String): SearchSource? {
        val readerUrl = "${jinaBaseUrl.trimEnd('/')}/$targetUrl"
        val request = Request.Builder()
            .url(readerUrl)
            .addHeader("User-Agent", USER_AGENT)
            .addHeader("Accept", "text/plain")
            .build()

        return try {
            val response = client.newCall(request).execute()
            response.use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string().orEmpty()
                if (body.isBlank()) return null

                // Jina Reader prepends metadata like:
                // Title: ...
                // URL Source: ...
                var title = targetUrl
                val lines = body.lines()
                for (line in lines.take(5)) {
                    if (line.startsWith("Title:", ignoreCase = true)) {
                        title = line.removePrefix("Title:").trim()
                        break
                    }
                }

                val domain = targetUrl.toHttpUrlOrNull()?.host ?: ""
                val contentSnippet = body.take(800)

                SearchSource(
                    title = SearchContextBuilder.sanitize(title),
                    url = targetUrl,
                    snippet = SearchContextBuilder.sanitize(contentSnippet),
                    domain = domain
                )
            }
        } catch (_: Throwable) {
            null
        }
    }
}
