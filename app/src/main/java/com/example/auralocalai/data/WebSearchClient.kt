package com.example.auralocalai.data

import android.util.Log
import com.example.auralocalai.data.providers.BraveSearchProvider
import com.example.auralocalai.data.providers.FreeSearchProvider
import com.example.auralocalai.data.providers.SerperSearchProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * High-level search client coordinating web search providers,
 * connection pooling, security timeouts, and error handling.
 */
class WebSearchClient(
    val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
) {

    companion object {
        private const val TAG = "WebSearchClient"
    }

    private val braveProvider = BraveSearchProvider(okHttpClient)
    private val serperProvider = SerperSearchProvider(okHttpClient)
    private val freeProvider = FreeSearchProvider(okHttpClient)

    private val providers = listOf(braveProvider, serperProvider, freeProvider)

    fun getAllProviders(): List<SearchProvider> = providers

    fun getProvider(id: String): SearchProvider {
        return providers.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: freeProvider
    }

    /**
     * Executes a web search using the specified provider.
     * Guaranteed to execute on Dispatchers.IO and never log secret API keys.
     */
    suspend fun search(
        providerId: String,
        query: String,
        apiKey: String,
        maxResults: Int = 5,
        country: String = "us",
        language: String = "en"
    ): SearchOutcome = withContext(Dispatchers.IO) {
        val provider = getProvider(providerId)
        val clampedResults = maxResults.coerceIn(3, 8)

        Log.d(TAG, "Executing search with provider='${provider.id}', maxResults=$clampedResults, country='$country', language='$language'")

        try {
            val outcome = provider.search(
                query = query,
                apiKey = apiKey,
                maxResults = clampedResults,
                country = country,
                language = language
            )
            Log.d(TAG, "Search completed: sourcesCount=${outcome.sources.size}, isError=${outcome.isError}")
            outcome
        } catch (e: Exception) {
            Log.w(TAG, "Search execution failed: ${e.message}")
            SearchOutcome(
                query = query,
                sources = emptyList(),
                providerId = provider.id,
                isError = true,
                errorMessage = e.message ?: "Search request failed"
            )
        }
    }
}
