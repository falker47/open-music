package app.openmusic

import app.openmusic.core.LyricsCandidateResolver
import app.openmusic.core.LyricsMatchKind
import app.openmusic.core.LyricsTrack
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.runBlocking

class LrcRedLyricsProviderTest {
    private val track = LyricsTrack("A Song (Live)", "The Artist", "Live Album", 184)
    private val hits = """{"hits":[
        {"isrc":"GBABC2600001","title":"A Song (Live)","artist":"The Artist","album":"Live Album","duration":184.2},
        {"isrc":"GBABC2600002","title":"A Song","artist":"The Artist","album":"Studio Album","duration":205.0}
    ]}"""

    private class FakeHttp(val responses: Map<String, String?>) : LrcRedHttpTransport {
        val paths = mutableListOf<String>()
        override suspend fun get(path: String): String? {
            paths.add(path)
            return responses.entries.firstOrNull { path.startsWith(it.key) }?.value
        }
    }

    @Test fun findsOnlyExactlyMatchedEditionAndFetchesOnlyItsLyrics() = runBlocking {
        val http = FakeHttp(mapOf("/search.json?" to hits,
            "/s/GBABC2600001.lrc" to "[00:01.20] First line\\n[00:03.50] Second line"))
        val found = LrcRedLyricsProvider(http).find(track)
        assertEquals(LyricsMatchKind.FOUND, LyricsCandidateResolver.select(track, found).kind)
        assertEquals(2, http.paths.size)
        assertTrue(http.paths[0].startsWith("/search.json?q="))
        assertTrue(http.paths[1] == "/s/GBABC2600001.lrc")
        assertTrue(found.first().syncedLyrics!!.contains("First line"))
    }

    @Test fun duplicateCandidatesAreAmbiguousAndNeverDownloadLyrics() = runBlocking {
        val duplicated = """{"hits":[
            {"isrc":"GBABC2600001","title":"A Song (Live)","artist":"The Artist","album":"Live Album","duration":184},
            {"isrc":"GBABC2600003","title":"A Song (Live)","artist":"The Artist","album":"Live Album","duration":184}
        ]}"""
        val http = FakeHttp(mapOf("/search.json?" to duplicated))
        val found = LrcRedLyricsProvider(http).find(track)
        assertEquals(LyricsMatchKind.AMBIGUOUS, LyricsCandidateResolver.select(track, found).kind)
        assertEquals(1, http.paths.size)
    }

    @Test fun fallsBackToPlainTtmlWithoutInventingTiming() = runBlocking {
        val ttml = """<tt xmlns="http://www.w3.org/ns/ttml"><body><div>
            <p>First line</p><p>Second line</p>
        </div></body></tt>"""
        val http = FakeHttp(mapOf("/search.json?" to hits,
            "/s/GBABC2600001.lrc" to null, "/s/GBABC2600001.ttml" to ttml))
        val found = LrcRedLyricsProvider(http).find(track)
        assertEquals("First line\\nSecond line", found.first().plainLyrics)
        assertNull(found.first().syncedLyrics)
        assertEquals(3, http.paths.size)
    }

    @Test fun invalidIsrcNeverBecomesARequestPath() = runBlocking {
        val unsafe = """{"hits":[
            {"isrc":"../wrong","title":"A Song (Live)","artist":"The Artist","album":"Live Album","duration":184}
        ]}"""
        val http = FakeHttp(mapOf("/search.json?" to unsafe))
        assertTrue(LrcRedLyricsProvider(http).find(track).isEmpty())
        assertEquals(1, http.paths.size)
    }

    @Test fun missingResultsNeverCauseRequestForFeaturedSongs() = runBlocking {
        val http = FakeHttp(mapOf("/search.json?" to """{"hits":[]}"""))
        assertTrue(LrcRedLyricsProvider(http).find(track).isEmpty())
        assertEquals(1, http.paths.size)
        assertTrue(http.paths.single().contains("A+Song"))
    }
}
