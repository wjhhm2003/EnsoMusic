package com.enso.music.data.lyric

import com.enso.music.data.lyric.online.AmllCandidate
import com.enso.music.data.lyric.online.AmllLookup
import com.enso.music.data.lyric.online.AmllResponseParsing
import com.enso.music.data.lyric.online.HttpClassifier
import com.enso.music.data.lyric.online.HttpResult
import com.enso.music.data.lyric.online.LookupBudget
import com.enso.music.data.lyric.online.LookupOutcome
import com.enso.music.data.lyric.online.NegativeCachePolicy
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import org.json.JSONException
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class AmllLookupTest {

    private val ttml = "<tt xmlns=\"http://www.w3.org/ns/ttml\"><body><div><p begin=\"00:01.00\" end=\"00:02.00\">x</p></div></body></tt>"

    // ---- HTTP classification ----

    @Test
    fun status404And410AreDefiniteMisses() {
        assertTrue(HttpClassifier.classifyStatus(404) is HttpResult.Miss)
        assertTrue(HttpClassifier.classifyStatus(410) is HttpResult.Miss)
        assertTrue(HttpClassifier.classifyStatus(400) is HttpResult.Miss)
    }

    @Test
    fun serverErrorsAndRateLimitsAreFailures() {
        for (code in listOf(500, 502, 503, 504, 408, 429, 301, -1)) {
            val r = HttpClassifier.classifyStatus(code)
            assertTrue("code $code should be failure", r is HttpResult.Failure)
            assertFalse((r as HttpResult.Failure).networkLevel)
        }
    }

    @Test
    fun fiveHundredDoesNotAbortButRateLimitDoes() {
        assertFalse((HttpClassifier.classifyStatus(503) as HttpResult.Failure).abortsLookup)
        assertTrue((HttpClassifier.classifyStatus(429) as HttpResult.Failure).abortsLookup)
    }

    @Test
    fun exceptionsAreNetworkLevelFailures() {
        for (e in listOf(SocketTimeoutException("t"), UnknownHostException("h"), IOException("io"), SecurityException("s"))) {
            val f = HttpClassifier.classifyException(e)
            assertTrue(f.networkLevel)
            assertTrue(f.abortsLookup)
        }
    }

    // ---- Budget / dedupe ----

    @Test
    fun budgetClipsTimeoutAndExhausts() {
        var now = 0L
        val budget = LookupBudget(totalMs = 12_000, maxRequestTimeoutMs = 5000, minRequestTimeoutMs = 1000, clock = { now })
        assertEquals(5000, budget.nextRequestTimeoutMs())
        now = 9_000
        assertEquals(3000, budget.nextRequestTimeoutMs())
        now = 11_500
        assertEquals(0, budget.nextRequestTimeoutMs())
        assertTrue(budget.isExhausted())
        now = 20_000
        assertEquals(0L, budget.remainingMs())
    }

    @Test
    fun budgetDedupesUrls() {
        val budget = LookupBudget(totalMs = 1000, maxRequestTimeoutMs = 500, clock = { 0L })
        assertTrue(budget.markTried("a"))
        assertFalse(budget.markTried("a"))
        assertTrue(budget.markTried("b"))
    }

    // ---- Negative cache policy ----

    @Test
    fun negativeCacheCooldownAndPruning() {
        val cooldown = 1000L
        assertTrue(NegativeCachePolicy.isInCooldown(500, 1000, cooldown))
        assertFalse(NegativeCachePolicy.isInCooldown(0, 1000, cooldown))
        assertFalse(NegativeCachePolicy.isInCooldown(100, 2000, cooldown))
        assertFalse(NegativeCachePolicy.isInCooldown(5000, 1000, cooldown)) // future timestamp

        val stale = NegativeCachePolicy.staleKeys(
            mapOf("fresh" to 1500L, "old" to 100L, "future" to 9999L, "junk" to "x"),
            now = 2000, cooldownMs = cooldown
        )
        assertEquals(setOf("old", "future", "junk"), stale.toSet())
    }

    // ---- Response parsing ----

    @Test
    fun selectCandidatesKeepsOrderAndStopsAtInline() {
        val json = """
            {"data":{"items":[
              {"id":"1","duration":200},
              {"id":"2","duration":500},
              {"id":"3","lyrics":"${ttml.replace("\"", "\\\"")}"},
              {"id":"4"}
            ]}}
        """.trimIndent()
        val c = AmllResponseParsing.selectCandidates(json, 210_000)
        assertEquals(2, c.size)
        assertEquals(AmllCandidate.ById("1"), c[0]) // within 25s
        assertTrue(c[1] is AmllCandidate.Inline)    // item 2 skipped by duration, 4 never reached
    }

    @Test
    fun selectCandidatesSupportsAllRootShapes() {
        assertEquals(listOf(AmllCandidate.ById("a")), AmllResponseParsing.selectCandidates("""{"data":[{"id":"a"}]}""", 0))
        assertEquals(listOf(AmllCandidate.ById("b")), AmllResponseParsing.selectCandidates("""{"items":[{"id":"b"}]}""", 0))
        assertTrue(AmllResponseParsing.selectCandidates("""{"status":200}""", 0).isEmpty())
    }

    @Test(expected = JSONException::class)
    fun selectCandidatesThrowsOnNonJson() {
        AmllResponseParsing.selectCandidates("<html>captive portal</html>", 0)
    }

    // ---- Lookup orchestration ----

    private class FakeServer(val handler: (String) -> HttpResult) {
        val calls = mutableListOf<String>()
        val fetch: suspend (String, Int) -> HttpResult = { url, _ -> calls.add(url); handler(url) }
    }

    private fun budget() = LookupBudget(totalMs = 12_000, maxRequestTimeoutMs = 5000, clock = { 0L })

    private val emptySearch = HttpResult.Success(200, """{"data":{"items":[]}}""")

    @Test
    fun searchesAccentedTitleAndFetchesDatabaseResult() = runBlocking {
        val server = FakeServer { url ->
            when {
                url.contains("/v1/lyrics/get?id=2211533994720664") ->
                    HttpResult.Success(200, org.json.JSONObject().put("data", org.json.JSONObject().put("lyrics", ttml)).toString())
                url.contains("musicName=S%C3%A3o+Paulo") ->
                    HttpResult.Success(200, """{"data":{"items":[{"id":2211533994720664}]}}""")
                else -> HttpResult.Miss(404)
            }
        }
        val outcome = AmllLookup(server.fetch, budget()).run("São Paulo", "The Weeknd", "Hurry Up Tomorrow", 301_623)
        assertEquals(LookupOutcome.Found(ttml), outcome)
        assertEquals(2, server.calls.size)
    }

    @Test
    fun allEmptyAnswersYieldNotFoundWithoutDuplicates() = runBlocking {
        val server = FakeServer { emptySearch }
        // Title with brackets and trailing punctuation exercises every strategy.
        val outcome = AmllLookup(server.fetch, budget()).run("world.execute (me) ;", "Mili", "Album", 200_000)
        assertEquals(LookupOutcome.NotFound, outcome)
        assertEquals("no duplicate URLs", server.calls.size, server.calls.toSet().size)
        assertTrue(server.calls.last().contains("/v1/lrclib/get?"))
    }

    @Test
    fun duplicateStrategiesAreSkipped() = runBlocking {
        val server = FakeServer { emptySearch }
        // Unknown artist + no album: strategy 3 (clean title + artist + album) produces the
        // same URL as strategy 4 (clean title only), so only 2 searches go out.
        AmllLookup(server.fetch, budget()).run("Song (Live)", "未知歌手", "", 0)
        val searches = server.calls.filter { it.contains("/v1/lyrics/search") }
        assertEquals(2, searches.size)
        assertEquals(searches.size, searches.toSet().size)
    }

    @Test
    fun plan404IsDefiniteMiss() = runBlocking {
        val server = FakeServer { HttpResult.Miss(404) }
        assertEquals(LookupOutcome.NotFound, AmllLookup(server.fetch, budget()).run("A", "B", "C", 0))
    }

    @Test
    fun networkFailureStopsEarlyAndIsNotAMiss() = runBlocking {
        val server = FakeServer { HttpClassifier.classifyException(UnknownHostException("api.amll.dev")) }
        val outcome = AmllLookup(server.fetch, budget()).run("world.execute (me) ;", "Mili", "", 0)
        assertTrue(outcome is LookupOutcome.Failed)
        assertEquals(1, server.calls.size)
    }

    @Test
    fun serverErrorContinuesButPreventsNegativeCache() = runBlocking {
        var first = true
        val server = FakeServer {
            if (first) { first = false; HttpClassifier.classifyStatus(503) } else emptySearch
        }
        val outcome = AmllLookup(server.fetch, budget()).run("Song", "Artist", "", 0)
        assertTrue(outcome is LookupOutcome.Failed)
        assertTrue(server.calls.size > 1)
    }

    @Test
    fun serverErrorThenHitIsFound() = runBlocking {
        var first = true
        val server = FakeServer { url ->
            when {
                first -> { first = false; HttpClassifier.classifyStatus(500) }
                url.contains("/v1/lyrics/get?id=") ->
                    HttpResult.Success(200, org.json.JSONObject().put("data", org.json.JSONObject().put("lyrics", ttml)).toString())
                else -> HttpResult.Success(200, """{"data":[{"id":"7"}]}""")
            }
        }
        val outcome = AmllLookup(server.fetch, budget()).run("Song", "Artist", "", 0)
        assertEquals(LookupOutcome.Found(ttml), outcome)
    }

    @Test
    fun detailFetchedById() = runBlocking {
        val server = FakeServer { url ->
            when {
                url.contains("/v1/lyrics/get?id=42") ->
                    HttpResult.Success(200, org.json.JSONObject().put("data", org.json.JSONObject().put("ttml", ttml)).toString())
                url.contains("/v1/lyrics/search") -> HttpResult.Success(200, """{"data":{"items":[{"id":"42"}]}}""")
                else -> HttpResult.Miss(404)
            }
        }
        val outcome = AmllLookup(server.fetch, budget()).run("Song", "Artist", "", 0)
        assertEquals(LookupOutcome.Found(ttml), outcome)
        assertEquals(2, server.calls.size)
    }

    @Test
    fun malformedSuccessBodyAbortsWithoutMiss() = runBlocking {
        val server = FakeServer { HttpResult.Success(200, "<html>login</html>") }
        val outcome = AmllLookup(server.fetch, budget()).run("Song", "Artist", "", 0)
        assertTrue(outcome is LookupOutcome.Failed)
        assertEquals(1, server.calls.size)
    }

    @Test
    fun exhaustedBudgetFailsWithoutRequests() = runBlocking {
        var now = 0L
        val b = LookupBudget(totalMs = 12_000, maxRequestTimeoutMs = 5000, clock = { now })
        val server = FakeServer { now += 6_000; emptySearch }
        val outcome = AmllLookup(server.fetch, b).run("world.execute (me) ;", "Mili", "", 0)
        assertTrue(outcome is LookupOutcome.Failed)
        assertEquals(2, server.calls.size) // 0 -> 6s -> 12s, then budget is gone
    }

    @Test
    fun cancellationStopsLookup() {
        lateinit var job: Job
        var completed = false
        val server = FakeServer { job.cancel(); emptySearch }
        runBlocking {
            job = launch {
                AmllLookup(server.fetch, budget()).run("world.execute (me) ;", "Mili", "", 0)
                completed = true
            }
            job.join()
        }
        assertFalse(completed)
        assertTrue(job.isCancelled)
        assertEquals(1, server.calls.size)
    }
}
