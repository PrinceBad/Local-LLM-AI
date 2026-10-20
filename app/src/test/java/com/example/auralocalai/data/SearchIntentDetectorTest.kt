package com.example.auralocalai.data

import org.junit.Assert.*
import org.junit.Test
import java.time.Year

class SearchIntentDetectorTest {

    @Test
    fun testShortQueriesAreNotSuppressed() {
        // User feedback specifically emphasized: "weather today", "bitcoin price", "Delhi AQI" are short but critical
        val q1 = SearchIntentDetector.detectIntent("weather today")
        assertTrue("Short query 'weather today' must trigger search", q1 is SearchIntentResult.NeedsSearch)

        val q2 = SearchIntentDetector.detectIntent("bitcoin price")
        assertTrue("Short query 'bitcoin price' must trigger search", q2 is SearchIntentResult.NeedsSearch)

        val q3 = SearchIntentDetector.detectIntent("Delhi AQI")
        assertTrue("Short query 'Delhi AQI' must trigger search", q3 is SearchIntentResult.NeedsSearch)
    }

    @Test
    fun testGreetingsAreSuppressed() {
        assertEquals(SearchIntentResult.NoSearch, SearchIntentDetector.detectIntent("hi"))
        assertEquals(SearchIntentResult.NoSearch, SearchIntentDetector.detectIntent("hello!"))
        assertEquals(SearchIntentResult.NoSearch, SearchIntentDetector.detectIntent("good morning"))
        assertEquals(SearchIntentResult.NoSearch, SearchIntentDetector.detectIntent("thank you"))
    }

    @Test
    fun testCodeAndMathAreSuppressed() {
        assertEquals(SearchIntentResult.NoSearch, SearchIntentDetector.detectIntent("val x = 10 + 20"))
        assertEquals(SearchIntentResult.NoSearch, SearchIntentDetector.detectIntent("fun calculateScore()"))
        assertEquals(SearchIntentResult.NoSearch, SearchIntentDetector.detectIntent("import java.util.*"))
        assertEquals(SearchIntentResult.NoSearch, SearchIntentDetector.detectIntent("45 * 12 + 8 / 2"))
    }

    @Test
    fun testAssistantMetaQuestionsAreSuppressed() {
        assertEquals(SearchIntentResult.NoSearch, SearchIntentDetector.detectIntent("who are you?"))
        assertEquals(SearchIntentResult.NoSearch, SearchIntentDetector.detectIntent("what can you do"))
        assertEquals(SearchIntentResult.NoSearch, SearchIntentDetector.detectIntent("who am i"))
    }

    @Test
    fun testFalsePositivesAreSuppressed() {
        assertEquals(SearchIntentResult.NoSearch, SearchIntentDetector.detectIntent("how do I check my credit score?"))
        assertEquals(SearchIntentResult.NoSearch, SearchIntentDetector.detectIntent("check git version"))
        assertEquals(SearchIntentResult.NoSearch, SearchIntentDetector.detectIntent("the role of the president"))
    }

    @Test
    fun testDynamicYearDetection() {
        val currentYear = Year.now().value
        val result = SearchIntentDetector.detectIntent("champions league winner $currentYear")
        assertTrue("Query with current year $currentYear must trigger search", result is SearchIntentResult.NeedsSearch)
    }

    @Test
    fun testDirectUrlDetection() {
        val result = SearchIntentDetector.detectIntent("Please summarize https://example.com/blog/ai-future")
        assertTrue(result is SearchIntentResult.DirectUrl)
        assertEquals("https://example.com/blog/ai-future", (result as SearchIntentResult.DirectUrl).url)
    }

    @Test
    fun testFreeProviderEncyclopedicGating() {
        // Free provider should trigger on encyclopedic queries
        val wikiResult = SearchIntentDetector.detectIntent(
            message = "who is Albert Einstein",
            isFreeProvider = true
        )
        assertTrue("Encyclopedic query on Free provider must trigger search", wikiResult is SearchIntentResult.NeedsSearch)

        val historyResult = SearchIntentDetector.detectIntent(
            message = "history of quantum mechanics",
            isFreeProvider = true
        )
        assertTrue(historyResult is SearchIntentResult.NeedsSearch)

        // Free provider should NOT trigger on ephemeral/live things it cannot answer
        val weatherResult = SearchIntentDetector.detectIntent(
            message = "weather today in Tokyo",
            isFreeProvider = true
        )
        assertEquals(
            "Live ephemeral query on Free provider must not trigger",
            SearchIntentResult.NoSearch,
            weatherResult
        )

        val priceResult = SearchIntentDetector.detectIntent(
            message = "bitcoin price today",
            isFreeProvider = true
        )
        assertEquals(
            "Live price query on Free provider must not trigger",
            SearchIntentResult.NoSearch,
            priceResult
        )
    }

    @Test
    fun testFollowUpResolution() {
        val resolved = SearchIntentDetector.resolveQuery(
            currentMessage = "what about tomorrow?",
            previousUserMessage = "weather in Tokyo"
        )
        assertEquals("weather in Tokyo tomorrow", resolved)

        val searchResult = SearchIntentDetector.detectIntent(
            message = "what about tomorrow?",
            isFreeProvider = false,
            previousUserMessage = "weather in Tokyo"
        )
        assertTrue(searchResult is SearchIntentResult.NeedsSearch)
        assertEquals("weather in Tokyo tomorrow", (searchResult as SearchIntentResult.NeedsSearch).cleanQuery)
    }
}
