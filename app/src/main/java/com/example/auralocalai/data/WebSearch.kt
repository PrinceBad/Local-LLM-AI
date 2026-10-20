package com.example.auralocalai.data

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

/**
 * Represents a single web search or extracted page source.
 * Persisted in ChatMessage so citations and sources survive app restarts.
 */
@Immutable
@Serializable
data class SearchSource(
    val title: String,
    val url: String,
    val snippet: String,
    val domain: String,
    val publishedAt: String? = null
)

/**
 * The final outcome of an executed web search query.
 */
@Immutable
data class SearchOutcome(
    val query: String,
    val sources: List<SearchSource>,
    val providerId: String,
    val isError: Boolean = false,
    val errorMessage: String? = null
)

/**
 * Trigger modes for web search.
 */
enum class WebSearchMode {
    OFF,
    AUTO,
    ALWAYS
}

/**
 * Abstraction for search providers (Brave, Serper, Free tier, etc.)
 */
interface SearchProvider {
    val id: String
    val label: String
    val requiresApiKey: Boolean
    val isEncyclopedicOnly: Boolean
    suspend fun search(
        query: String,
        apiKey: String,
        maxResults: Int,
        country: String = "us",
        language: String = "en"
    ): SearchOutcome
}
