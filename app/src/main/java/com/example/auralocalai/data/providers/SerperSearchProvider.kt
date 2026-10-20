package com.example.auralocalai.data.providers

import com.example.auralocalai.data.SearchContextBuilder
import com.example.auralocalai.data.SearchOutcome
import com.example.auralocalai.data.SearchProvider
import com.example.auralocalai.data.SearchSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

class SerperSearchProvider(
    private val client: OkHttpClient,
    private val endpointUrl: String = "https://google.serper.dev/search"
) : SearchProvider {

    override val id: String = "serper"
    override val label: String = "Serper (Google Search)"
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
                errorMessage = "Serper API key is missing. Please configure your key in Settings."
            )
        }

        val requestPayload = buildJsonObject {
            put("q", query)
            put("gl", country.ifBlank { "us" }.lowercase())
            put("hl", language.ifBlank { "en" }.lowercase())
            put("num", maxResults)
        }.toString()

        val mediaType = "application/json; charset=utf-8".toMediaType()
        val request = Request.Builder()
            .url(endpointUrl)
            .addHeader("X-API-KEY", apiKey.trim())
            .addHeader("Content-Type", "application/json")
            .post(requestPayload.toRequestBody(mediaType))
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
                        errorMessage = "Invalid or unauthorized Serper API key ($code)."
                    )
                }

                if (code == 429) {
                    return@withContext SearchOutcome(
                        query = query,
                        sources = emptyList(),
                        providerId = id,
                        isError = true,
                        errorMessage = "Serper rate limit or credit quota exceeded ($code)."
                    )
                }

                if (!resp.isSuccessful) {
                    return@withContext SearchOutcome(
                        query = query,
                        sources = emptyList(),
                        providerId = id,
                        isError = true,
                        errorMessage = "Serper search error: HTTP $code"
                    )
                }

                val bodyString = resp.body?.string().orEmpty()
                val parsedSources = parseSerperResponse(bodyString, maxResults)

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
                errorMessage = e.message ?: "Network error during Serper search."
            )
        } catch (e: Exception) {
            SearchOutcome(
                query = query,
                sources = emptyList(),
                providerId = id,
                isError = true,
                errorMessage = "Failed to parse Serper search response."
            )
        }
    }

    private fun parseSerperResponse(body: String, maxResults: Int): List<SearchSource> {
        val root = json.parseToJsonElement(body).jsonObject
        val results = mutableListOf<SearchSource>()

        // 1. Promote answerBox if present
        root["answerBox"]?.jsonObject?.let { box ->
            val link = box["link"]?.jsonPrimitive?.content ?: ""
            val title = box["title"]?.jsonPrimitive?.content ?: "Direct Answer"
            val snippet = box["snippet"]?.jsonPrimitive?.content
                ?: box["answer"]?.jsonPrimitive?.content
                ?: ""
            val date = box["date"]?.jsonPrimitive?.content

            if (snippet.isNotBlank()) {
                val domain = if (link.isNotBlank()) link.toHttpUrlOrNull()?.host ?: "" else ""

                results.add(
                    SearchSource(
                        title = SearchContextBuilder.sanitize(title),
                        url = if (SearchContextBuilder.isValidHttpUrl(link)) link else "https://google.com",
                        snippet = SearchContextBuilder.sanitize(snippet),
                        domain = domain.ifBlank { "google.com" },
                        publishedAt = date
                    )
                )
            }
        }

        // 2. Parse organic results
        val organic = root["organic"]?.jsonArray
        if (organic != null) {
            for (item in organic) {
                if (results.size >= maxResults) break
                val obj = item.jsonObject
                val link = obj["link"]?.jsonPrimitive?.content ?: continue
                if (!SearchContextBuilder.isValidHttpUrl(link)) continue

                val title = obj["title"]?.jsonPrimitive?.content ?: "Untitled"
                val snippet = obj["snippet"]?.jsonPrimitive?.content ?: ""
                val date = obj["date"]?.jsonPrimitive?.content

                val domain = link.toHttpUrlOrNull()?.host ?: ""

                results.add(
                    SearchSource(
                        title = SearchContextBuilder.sanitize(title),
                        url = link,
                        snippet = SearchContextBuilder.sanitize(snippet),
                        domain = domain,
                        publishedAt = date
                    )
                )
            }
        }

        return results
    }
}
