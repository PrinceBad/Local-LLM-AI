package com.example.auralocalai.data

import com.example.auralocalai.data.providers.BraveSearchProvider
import com.example.auralocalai.data.providers.FreeSearchProvider
import com.example.auralocalai.data.providers.SerperSearchProvider
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class WebSearchClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun testBraveProviderParsesGenericGroundingAndSendsAuthHeader() = runBlocking {
        val mockJson = """
            {
              "grounding": {
                "generic": [
                  {
                    "url": "https://example.com/ai-news",
                    "title": "Latest AI Breakthrough",
                    "snippets": ["Researchers announced a new mobile model.", "It runs fully on-device."]
                  }
                ]
              },
              "sources": {
                "https://example.com/ai-news": {
                  "title": "Latest AI Breakthrough",
                  "hostname": "example.com",
                  "age": ["2026-04-12T10:00:00Z"]
                }
              }
            }
        """.trimIndent()

        server.enqueue(MockResponse().setResponseCode(200).setBody(mockJson))

        val provider = BraveSearchProvider(client, endpointUrl = server.url("/res/v1/llm/context").toString())
        val outcome = provider.search(
            query = "AI news",
            apiKey = "brave_test_secret_key",
            maxResults = 5,
            country = "in",
            language = "hi"
        )

        assertFalse(outcome.isError)
        assertEquals(1, outcome.sources.size)
        val source = outcome.sources[0]
        assertEquals("Latest AI Breakthrough", source.title)
        assertEquals("https://example.com/ai-news", source.url)
        assertEquals("example.com", source.domain)
        assertTrue(source.snippet.contains("Researchers announced"))

        val recorded = server.takeRequest()
        assertEquals("brave_test_secret_key", recorded.getHeader("X-Subscription-Token"))
        assertTrue(recorded.path?.contains("country=in") == true)
        assertTrue(recorded.path?.contains("search_lang=hi") == true)
    }

    @Test
    fun testSerperProviderPromotesAnswerBoxAndParsesOrganic() = runBlocking {
        val mockJson = """
            {
              "answerBox": {
                "title": "Bitcoin Price Today",
                "snippet": "1 Bitcoin is currently 95,000 USD.",
                "link": "https://coinmarketcap.com/currencies/bitcoin",
                "date": "Just now"
              },
              "organic": [
                {
                  "title": "Bitcoin News & Analysis",
                  "link": "https://coindesk.com/btc",
                  "snippet": "Markets update on Bitcoin trading.",
                  "date": "2 hours ago"
                }
              ]
            }
        """.trimIndent()

        server.enqueue(MockResponse().setResponseCode(200).setBody(mockJson))

        val provider = SerperSearchProvider(client, endpointUrl = server.url("/search").toString())
        val outcome = provider.search(
            query = "bitcoin price",
            apiKey = "serper_secret_key_123",
            maxResults = 5
        )

        assertFalse(outcome.isError)
        assertEquals(2, outcome.sources.size)

        // AnswerBox must be promoted to source #1
        assertEquals("Bitcoin Price Today", outcome.sources[0].title)
        assertTrue(outcome.sources[0].snippet.contains("95,000 USD"))
        assertEquals("https://coinmarketcap.com/currencies/bitcoin", outcome.sources[0].url)

        // Organic item #2
        assertEquals("Bitcoin News & Analysis", outcome.sources[1].title)

        val recorded = server.takeRequest()
        assertEquals("serper_secret_key_123", recorded.getHeader("X-API-KEY"))
        assertTrue(recorded.getHeader("Content-Type")?.startsWith("application/json") == true)
        val body = recorded.body.readUtf8()
        assertTrue(body.contains("\"q\":\"bitcoin price\""))
    }

    @Test
    fun test3xxRedirectBlockedAsError() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(302)
                .addHeader("Location", "https://malicious-site.com/intercept")
        )

        val provider = BraveSearchProvider(client, endpointUrl = server.url("/res/v1/llm/context").toString())
        val outcome = provider.search(
            query = "security query",
            apiKey = "my_private_key",
            maxResults = 5
        )

        assertTrue(outcome.isError)
        assertTrue(outcome.errorMessage?.contains("redirect blocked") == true)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun testBrave401And429Handling() = runBlocking {
        // 401
        server.enqueue(MockResponse().setResponseCode(401).setBody("Unauthorized"))
        val provider = BraveSearchProvider(client, endpointUrl = server.url("/brave").toString())
        val outcome401 = provider.search("test", "bad_key", 5)
        assertTrue(outcome401.isError)
        assertTrue(outcome401.errorMessage?.contains("Invalid or unauthorized") == true)

        // 429
        server.enqueue(MockResponse().setResponseCode(429).setBody("Rate limit exceeded"))
        val outcome429 = provider.search("test", "valid_key", 5)
        assertTrue(outcome429.isError)
        assertTrue(outcome429.errorMessage?.contains("rate limit or quota") == true)
    }

    @Test
    fun testFreeProviderDuckDuckGoAndWikipedia() = runBlocking {
        val ddgJson = """
            {
              "Heading": "Albert Einstein",
              "AbstractText": "Albert Einstein was a German-born theoretical physicist.",
              "AbstractURL": "https://en.wikipedia.org/wiki/Albert_Einstein"
            }
        """.trimIndent()

        val wikiJson = """
            {
              "query": {
                "search": [
                  {
                    "title": "General relativity",
                    "pageid": 12345,
                    "snippet": "General relativity is the <span class=\"searchmatch\">geometric</span> theory of gravitation."
                  }
                ]
              }
            }
        """.trimIndent()

        server.enqueue(MockResponse().setResponseCode(200).setBody(ddgJson))
        server.enqueue(MockResponse().setResponseCode(200).setBody(wikiJson))

        val provider = FreeSearchProvider(
            client = client,
            duckDuckGoBaseUrl = server.url("/ddg").toString(),
            wikipediaBaseUrl = server.url("/wiki").toString()
        )

        val outcome = provider.search(
            query = "Albert Einstein",
            apiKey = "",
            maxResults = 3
        )

        assertFalse(outcome.isError)
        assertTrue(outcome.sources.size >= 2)

        // First source from DDG
        assertEquals("Albert Einstein", outcome.sources[0].title)
        assertTrue(outcome.sources[0].snippet.contains("theoretical physicist"))

        // Second source from Wikipedia with HTML tags stripped
        assertEquals("General relativity", outcome.sources[1].title)
        assertFalse(outcome.sources[1].snippet.contains("<span"))
        assertTrue(outcome.sources[1].snippet.contains("geometric theory"))
        assertEquals("https://en.wikipedia.org/?curid=12345", outcome.sources[1].url)
    }

    @Test
    fun testBraveMissingApiKeyReturnsErrorWithoutNetwork() = runBlocking {
        val provider = BraveSearchProvider(client)
        val outcome = provider.search(
            query = "test",
            apiKey = "",
            maxResults = 5
        )

        assertTrue(outcome.isError)
        assertTrue(outcome.errorMessage?.contains("missing") == true)
        assertEquals(0, outcome.sources.size)
    }

    @Test
    fun testSerperMissingApiKeyReturnsErrorWithoutNetwork() = runBlocking {
        val provider = SerperSearchProvider(client)
        val outcome = provider.search(
            query = "test",
            apiKey = "",
            maxResults = 5
        )

        assertTrue(outcome.isError)
        assertTrue(outcome.errorMessage?.contains("missing") == true)
        assertEquals(0, outcome.sources.size)
    }
}
