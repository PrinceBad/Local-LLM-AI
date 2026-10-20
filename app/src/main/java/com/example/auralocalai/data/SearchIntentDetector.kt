package com.example.auralocalai.data

import java.time.Year
import java.util.regex.Pattern

/**
 * Result of analyzing a message for web search intent.
 */
sealed interface SearchIntentResult {
    data object NoSearch : SearchIntentResult
    data class DirectUrl(val url: String) : SearchIntentResult
    data class NeedsSearch(val cleanQuery: String) : SearchIntentResult
}

/**
 * Pure, unit-testable classifier to detect when a user message warrants a web search.
 * Incorporates conservative scoring, dynamic temporal cues, and provider-aware intent matching.
 */
object SearchIntentDetector {

    private val URL_PATTERN = Pattern.compile("https?://[\\w\\-._~:/?#\\[\\]@!$&'()*+,;=%]+")

    // Greetings & chat pleasantries to immediately suppress
    private val GREETINGS = setOf(
        "hi", "hello", "hey", "hola", "howdy", "good morning", "good afternoon",
        "good evening", "good night", "thanks", "thank you", "bye", "goodbye", "ok", "okay", "cool"
    )

    // Code and syntax cues to suppress
    private val CODE_KEYWORDS = listOf(
        "fun ", "class ", "def ", "import ", "package ", "val ", "var ",
        "return ", "public ", "private ", "const ", "console.log", "System.out"
    )

    // Assistant meta-questions to suppress
    private val ASSISTANT_META_PATTERNS = listOf(
        Regex("(?i)^who are you\\??$"),
        Regex("(?i)^what can you do\\??$"),
        Regex("(?i)^who am i\\??$"),
        Regex("(?i)^help me\\??$"),
        Regex("(?i)^what is your name\\??$")
    )

    // False-positive phrases that should not trigger search
    private val FALSE_POSITIVE_PHRASES = listOf(
        "credit score",
        "git version",
        "my score",
        "exam score",
        "role of the president",
        "powers of the president"
    )

    /**
     * Resolves follow-up queries (e.g. "what about tomorrow?", "and in Paris?")
     * using the context of the previous user turn.
     */
    fun resolveQuery(currentMessage: String, previousUserMessage: String?): String {
        val trimmed = currentMessage.trim()
        if (previousUserMessage.isNullOrBlank()) return trimmed

        val lower = trimmed.lowercase()
        val followUpPrefixes = listOf("what about ", "how about ", "and in ", "and for ", "what of ")
        for (prefix in followUpPrefixes) {
            if (lower.startsWith(prefix)) {
                val subject = trimmed.substring(prefix.length).trim().removeSuffix("?")
                val cleanPrev = previousUserMessage.trim().removeSuffix("?")
                return "$cleanPrev $subject"
            }
        }
        return trimmed
    }

    /**
     * Analyzes the message to determine if web search should be initiated.
     *
     * @param message The user prompt
     * @param isFreeProvider When true, restricts intent to encyclopedic/lookup queries
     * @param previousUserMessage The previous turn's prompt for follow-up resolution
     */
    fun detectIntent(
        message: String,
        isFreeProvider: Boolean = false,
        previousUserMessage: String? = null
    ): SearchIntentResult {
        val trimmed = message.trim()
        if (trimmed.isBlank()) return SearchIntentResult.NoSearch

        // 1. Direct URL check — if user pasted a link, route to direct reader
        val urlMatcher = URL_PATTERN.matcher(trimmed)
        if (urlMatcher.find()) {
            val url = urlMatcher.group()
            return SearchIntentResult.DirectUrl(url)
        }

        val lower = trimmed.lowercase().removeSuffix("?").trim()

        // 2. Suppress greetings
        if (GREETINGS.contains(lower)) {
            return SearchIntentResult.NoSearch
        }

        // 3. Suppress pure code or programming keywords
        for (kw in CODE_KEYWORDS) {
            if (trimmed.contains(kw)) return SearchIntentResult.NoSearch
        }

        // 4. Suppress pure arithmetic or math expressions
        if (lower.matches(Regex("^[0-9\\s+\\-*/%^=().]+$"))) {
            return SearchIntentResult.NoSearch
        }

        // 5. Suppress assistant identity questions
        for (pattern in ASSISTANT_META_PATTERNS) {
            if (pattern.matches(trimmed)) return SearchIntentResult.NoSearch
        }

        // 6. Suppress known false positives
        for (fp in FALSE_POSITIVE_PHRASES) {
            if (lower.contains(fp)) return SearchIntentResult.NoSearch
        }

        // 7. Resolve follow-ups
        val resolvedQuery = resolveQuery(trimmed, previousUserMessage)
        val queryLower = resolvedQuery.lowercase()

        // 8. Provider-specific intent evaluation:
        if (isFreeProvider) {
            // Free provider (DuckDuckGo Instant Answer + Wikipedia) is encyclopedic only.
            // Do NOT trigger on ephemeral/live things like "weather today", "live score", "bitcoin price".
            val encyclopedicPhrases = listOf(
                "who is", "who was", "what is", "what was", "where is", "when was",
                "history of", "biography of", "tell me about", "wikipedia",
                "definition of", "meaning of", "explain the concept of", "summary of",
                "author of", "overview of", "capital of"
            )

            val matchesEncyclopedic = encyclopedicPhrases.any { queryLower.contains(it) }
            return if (matchesEncyclopedic) {
                SearchIntentResult.NeedsSearch(resolvedQuery)
            } else {
                SearchIntentResult.NoSearch
            }
        }

        // For keyed live providers (Brave, Serper):
        var score = 0
        val currentYear = try { Year.now().value } catch (_: Throwable) { 2026 }

        // High-confidence live cues (weight = 2)
        val weight2Keywords = listOf(
            "weather", "forecast", "aqi", "bitcoin price", "crypto price", "stock price",
            "price", "stock", "release date", "news", "headlines", "latest news", "breaking news",
            "today news", "live score", "match score", "game score", "final score", "who won",
            "winner", "current president", "current ceo", "current prime minister"
        )
        for (kw in weight2Keywords) {
            if (queryLower.contains(kw)) {
                score += 2
                break
            }
        }

        // Contextual keywords (weight = 1)
        val weight1Keywords = listOf(
            "latest", "today", "yesterday", "current", "recently", "this week",
            "this month", currentYear.toString(), (currentYear - 1).toString(),
            "up to date", "look up", "search for"
        )
        for (kw in weight1Keywords) {
            if (queryLower.contains(kw)) {
                score += 1
            }
        }

        // Question phrases combined with real-time indicators
        if (queryLower.startsWith("what is the price of") ||
            queryLower.startsWith("who is currently") ||
            queryLower.startsWith("how much is") ||
            queryLower.contains("price of") ||
            queryLower.contains("weather in")
        ) {
            score += 2
        }

        return if (score >= 2) {
            SearchIntentResult.NeedsSearch(resolvedQuery)
        } else {
            SearchIntentResult.NoSearch
        }
    }
}
