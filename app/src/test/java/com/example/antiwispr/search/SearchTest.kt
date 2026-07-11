package com.example.antiwispr.search

import com.example.antiwispr.data.StoredTranscript
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** JVM tests for the search core — ICU seam swapped for a fake Devanagari→Latin fold. */
class SearchTest {

    // Tiny fake transliterator: a few Devanagari words map like ICU's ISO-15919-ish output.
    private val fakeIcu = mapOf("घर" to "ghara", "आराम" to "ārāma", "पैसा" to "paisā")

    @Before fun seamIn() {
        TextFolder.translit = { s ->
            var out = s.lowercase()
            fakeIcu.forEach { (dev, latin) -> out = out.replace(dev, latin) }
            // crude Latin-ASCII: strip the macrons our fake emits
            out.replace("ā", "a").replace("ī", "i").replace("ū", "u")
        }
    }

    // Keys must be unique across ALL tests — SearchEngine's normalized cache is process-wide
    // and JUnit re-instantiates the class per test; reused keys would poison later tests.
    private companion object { var gseq = 0 }

    private fun note(
        text: String,
        chatName: String = "",
        summary: String = "",
        waDate: Int = 20260701,
        updatedAt: Long = 1_000L + gseq,
    ): StoredTranscript {
        gseq++
        return StoredTranscript(
            key = "k$gseq", path = "/p$gseq", name = "PTT-$gseq.opus", waDate = waDate, seq = gseq,
            durationSec = -1.0, text = text, updatedAt = updatedAt, summary = summary,
            chatName = chatName, source = "",
        )
    }

    private fun texts(hits: List<SearchHit>) = hits.map { it.transcript.text }

    // ---- tokenize -------------------------------------------------------------------

    @Test fun `tokenize splits and drops short terms`() =
        assertEquals(listOf("ghar", "ka", "address"), SearchEngine.tokenize("Ghar, ka address!"))

    // ---- matching -------------------------------------------------------------------

    @Test fun `multi term AND requires all terms`() {
        val notes = listOf(
            note("address hai MG Road wala"),
            note("Rahul ne address bheja", chatName = "Rahul"),
            note("Rahul birthday party", chatName = "Rahul"),
        )
        val hits = SearchEngine.search(notes, "rahul address")
        assertEquals(listOf("Rahul ne address bheja"), texts(hits))
    }

    @Test fun `cross script query matches devanagari record`() {
        assertEquals(1, SearchEngine.search(listOf(note("घर आ जाना shaam ko")), "ghar").size)
        assertEquals(1, SearchEngine.search(listOf(note("आराम karo, घर pe")), "aaram ghar").size)
    }

    @Test fun `chatName and summary are searchable fields`() {
        val notes = listOf(note("kuch bhi text", chatName = "Rahul", summary = "SUMMARY: lease documents"))
        assertEquals(1, SearchEngine.search(notes, "rahul").size)
        assertEquals(1, SearchEngine.search(notes, "lease").size)
    }

    // ---- scoring / ordering -----------------------------------------------------------

    @Test fun `chatName hit outranks text hit`() {
        val inText = note("rahul se milna hai")
        val inChat = note("kal milte hain", chatName = "Rahul")
        val hits = SearchEngine.search(listOf(inText, inChat), "rahul")
        assertEquals(listOf("kal milte hain", "rahul se milna hai"), texts(hits))
    }

    @Test fun `equal score ties break by recency`() {
        val old = note("ghar aana", updatedAt = 100)
        val new = note("ghar jana", updatedAt = 200)
        val hits = SearchEngine.search(listOf(old, new), "ghar")
        assertEquals(listOf("ghar jana", "ghar aana"), texts(hits))
    }

    // ---- filters ----------------------------------------------------------------------

    @Test fun `sender filter is hard`() {
        val notes = listOf(note("paisa bhejna", chatName = "Rahul"), note("paisa lena", chatName = "Mom"))
        val hits = SearchEngine.search(notes, "paisa", SearchFilters(chatName = "Rahul"))
        assertEquals(listOf("paisa bhejna"), texts(hits))
    }

    @Test fun `date range filters on waDate`() {
        val notes = listOf(
            note("purana note", waDate = 20260601),
            note("naya note", waDate = 20260704),
        )
        val hits = SearchEngine.search(notes, "note", SearchFilters(fromYmd = 20260701))
        assertEquals(listOf("naya note"), texts(hits))
    }

    @Test fun `waDate unknown falls back to updatedAt`() {
        // 2026-07-04 in epoch ms (local-tz safe margin: mid-day UTC)
        val julyMs = 1_783_500_000_000L // ~2026-07-08; exact day doesn't matter, just >= fromYmd
        val notes = listOf(note("fallback note", waDate = -1, updatedAt = julyMs))
        val hits = SearchEngine.search(notes, "fallback", SearchFilters(fromYmd = 20260101, toYmd = 20291231))
        assertEquals(1, hits.size)
    }

    // ---- ask retrieval ------------------------------------------------------------------

    @Test fun `stopwords stripped for retrieval`() {
        val notes = listOf(note("MG Road ka address bheja tha", chatName = "Rahul"))
        val hits = SearchEngine.retrieveForAsk(notes, "kya address tha jo Rahul ne bheja?")
        assertEquals(1, hits.size)
    }

    @Test fun `all-stopword question keeps original terms`() {
        val notes = listOf(note("kya hua tha kal"))
        // every token is a stopword → fall back to the original terms, no crash, still matches
        val hits = SearchEngine.retrieveForAsk(notes, "kya hua")
        assertEquals(1, hits.size)
    }

    @Test fun `AND falls back to OR when too few hits`() {
        val notes = listOf(
            note("lease agreement ready hai"),
            note("agreement sign karna"),
            note("kuch aur baat"),
        )
        // "lease" AND "sign" matches nothing; OR should return both partial matches
        val hits = SearchEngine.retrieveForAsk(notes, "lease sign")
        assertEquals(2, hits.size)
    }

    // ---- ask context + answer parse -------------------------------------------------------

    @Test fun `context numbering and headers`() {
        val hits = listOf(
            SearchHit(note("pehla note", chatName = "Rahul", waDate = 20260629), 1),
            SearchHit(note("doosra note", waDate = -1), 1),
        )
        val ctx = buildAskContext(hits, maxChars = 10_000)
        assertTrue(ctx.startsWith("[Note 1 — 29 Jun 2026, Rahul]\npehla note"))
        assertTrue(ctx.contains("[Note 2]\ndoosra note"))
    }

    @Test fun `context respects budget`() {
        val hits = (1..10).map { SearchHit(note("x".repeat(500)), 1) }
        val ctx = buildAskContext(hits, maxChars = 1200)
        assertTrue(ctx.length <= 1200)
        assertTrue(ctx.contains("[Note 2"))
        assertTrue(!ctx.contains("[Note 4"))
    }

    @Test fun `parse full contract`() {
        val a = parseAskAnswer("ANSWER: MG Road wala address.\nSOURCES: [Note 2], [Note 5]")
        assertEquals("MG Road wala address.", a.answer)
        assertEquals(listOf(2, 5), a.sources)
    }

    @Test fun `parse sources none`() {
        val a = parseAskAnswer("ANSWER: Notes mein address nahi mila.\nSOURCES: none")
        assertTrue(a.sources.isEmpty())
    }

    @Test fun `parse missing headers keeps whole text`() {
        val a = parseAskAnswer("MG Road ke paas wala ghar.")
        assertEquals("MG Road ke paas wala ghar.", a.answer)
        assertTrue(a.sources.isEmpty())
    }

    // ---- folding ----------------------------------------------------------------------------

    @Test fun `digraph reduction is symmetric`() {
        assertEquals(TextFolder.normalize("kheer"), TextFolder.normalize("kheer"))
        assertEquals("wik", TextFolder.reduceDigraphs("week"))
        assertEquals("aram", TextFolder.normalize("aaram"))
    }
}
