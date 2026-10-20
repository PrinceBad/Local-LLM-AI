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
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

class BraveSearchProvider(
    private val client: OkHttpClient,
    private val endpointUrl: String = "https://api.search.brave.com/res/v1/llm/context"
) : SearchProvider {

    override val id: String = "brave"
    override val label: String = "Brave Search (recommended)"
    override val requiresApiKey: Boolean = true
    override val isEncyclopedicOnly: Boolean = false

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun search(
        query: String,
        apiKey: String,
        maxResults: Int,
        country: String,
        language: String
    ): SearchOutcome = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext SearchOutcome(
                query = query,
                sources = emptyList(),
                providerId = id,
                isError = true,
                errorMessage = "Brave Search API key is missing. Please configure your key in Settings."
            )
        }

        val url = endpointUrl.toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("count", maxResults.toString())
            .addQueryParameter("maximum_number_of_urls", maxResults.toString())
            .addQueryParameter("maximum_number_of_tokens", "1024")
            .addQueryParameter("context_threshold_mode", "strict")
            .addQueryParameter("safesearch", "moderate")
            .addQueryParameter("country", country.ifBlank { "us" })
            .addQueryParameter("search_lang", language.ifBlank { "en" })
            .build()
            .toString()

        val request = Request.Builder()
            .url(url)
            .addHeader("X-Subscription-Token", apiKey.trim())
            .addHeader("Accept", "application/json")
            .build()

        try {
            val response = client.newCall(request).execute()
            response.use { resp ->
                val code = resp.code
                if (code in 300..399) {
                    return@withContext SearchOutcome(
                        query = query,
                        sources = emptyList(),
                        providerId = id,
                        isError = true,
                        errorMessage = "Unexpected redirect blocked for security ($code)."
                    )
                }

                if (code == 401 || code == 403) {
                    return@withContext SearchOutcome(
                        query = query,
                        sources = emptyList(),
                        providerId = id,
                        isError = true,
                        errorMessage = "Invalid or unauthorized Brave API key ($code)."
                    )
                }

                if (code == 429) {
                    return@withContext SearchOutcome(
                        query = query,
                        sources = emptyList(),
                        providerId = id,
                        isError = true,
                        errorMessage = "Brave Search rate limit or quota exceeded ($code)."
                    )
                }

                if (!resp.isSuccessful) {
                    return@withContext SearchOutcome(
                        query = query,
                        sources = emptyList(),
                        providerId = id,
                        isError = true,
                        errorMessage = "Brave Search error: HTTP $code"
                    )
                }

                val bodyString = resp.body?.string().orEmpty()
                val parsedSources = parseBraveResponse(bodyString, maxResults)

                SearchOutcome(
                    query = query,
                    sources = parsedSources,
                    providerId = id
                )
            }
        } catch (e: IOException) {
            SearchOutcome(
                query = query,
                sources = emptyList(),
                providerId = id,
                isError = true,
                errorMessage = e.message ?: "Network error during Brave search."
            )
        } catch (e: Exception) {
            SearchOutcome(
                query = query,
                sources = emptyList(),
                providerId = id,
                isError = true,
                errorMessage = "Failed to parse Brave search response."
            )
        }
    }

    private fun parseBraveResponse(body: String, maxResults: Int): List<SearchSource> {
        val root = json.parseToJsonElement(body).jsonObject
        val grounding = root["grounding"]?.jsonObject ?: return emptyList()
        val generic = grounding["generic"]?.jsonArray ?: return emptyList()
        val sourcesMeta = root["sources"]?.jsonObject

        val results = mutableListOf<SearchSource>()
        for (item in generic) {
            if (results.size >= maxResults) break
            val itemObj = item.jsonObject
            val targetUrl = itemObj["url"]?.jsonPrimitive?.content ?: continue
            if (!SearchContextBuilder.isValidHttpUrl(targetUrl)) continue

            val title = itemObj["title"]?.jsonPrimitive?.content ?: "Untitled"
            val snippetArray = itemObj["snippets"]?.jsonArray
            val snippet = snippetArray?.joinToString(" ") { it.jsonPrimitive.content } ?: ""

            val domain = targetUrl.toHttpUrlOrNull()?.host ?: ""

            var publishedAt: String? = null
            if (sourcesMeta != null && sourcesMeta.containsKey(targetUrl)) {
                val sourceEntry = sourcesMeta[targetUrl]?.jsonObject
                val ageArray = sourceEntry?.get("age")?.jsonArray
                if (!ageArray.isNullOrEmpty()) {
                    publishedAt = ageArray[0].jsonPrimitive.content
                }
            }

            results.add(
                SearchSource(
                    title = SearchContextBuilder.sanitize(title),
                    url = targetUrl,
                    snippet = SearchContextBuilder.sanitize(snippet),
                    domain = domain,
                    publishedAt = publishedAt
                )
            )
        }
        return results
    }
}
