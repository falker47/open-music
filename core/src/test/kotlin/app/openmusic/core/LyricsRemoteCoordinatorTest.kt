package app.openmusic.core

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.util.concurrent.CancellationException
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

/** End-to-end core contract: a tagless local track gets a permitted text, then works offline. */
class LyricsRemoteCoordinatorTest {
    @get:Rule val temp = TemporaryFolder()
    private val track = LyricsTrack("A Song (Live)", "The Artist", "Live Album", 184)
    private val allowed = LyricsSourceRights(true, true, true)
    private val sample = LyricsCandidate(
        "A Song (Live)", "The Artist", "Live Album", 184, false,
        "First line\nSecond line", "[00:01.20] First line\n[00:03.50] Second line"
    )
    private val audio = "content://media/external/audio/123|size=12345|modified=1762500000"

    private class Source(var results: List<LyricsCandidate>) : LyricsProvider {
        var requests = 0
        var failure: Exception? = null
        override suspend fun find(track: LyricsTrack): List<LyricsCandidate> {
            requests++
            failure?.let { throw it }
            return results
        }
    }
    private fun <T> runNow(block: suspend () -> T): T {
        var value: Result<T>? = null
        block.startCoroutine(object : Continuation<T> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(result: Result<T>) { value = result }
        })
        return checkNotNull(value).getOrThrow()
    }
    private fun service(source: Source, rights: LyricsSourceRights = allowed) =
        LyricsRemoteCoordinator(source, LyricsPrivateCache(temp.root), rights, "lrc.red")

    @Test fun taglessTrackFetchesTimedLyricsOnceAndSurvivesRestartWithoutInternet() {
        val source = Source(listOf(sample))
        val first = runNow { service(source).load(track, audio, online = true) }
        assertEquals(LyricsResolutionKind.AVAILABLE, first.kind)
        assertFalse(first.fromCache)
        assertEquals(1200L, first.lyrics!!.timedLines[0].startMs)
        assertEquals("lrc.red", first.source)
        val offline = runNow { service(source).load(track, audio, online = false) }
        assertEquals(LyricsResolutionKind.AVAILABLE, offline.kind)
        assertTrue(offline.fromCache)
        assertEquals(first.lyrics, offline.lyrics)
        assertEquals(1, source.requests)
    }

    @Test fun plainTextWorksAndCanBeDeletedThenRefetched() {
        val source = Source(listOf(sample.copy(syncedLyrics = null)))
        val coordinator = service(source)
        val first = runNow { coordinator.load(track, audio, true) }
        assertEquals(LyricsResolutionKind.AVAILABLE, first.kind)
        assertEquals("First line\nSecond line", first.lyrics!!.plainText)
        assertTrue(first.lyrics.timedLines.isEmpty())
        assertTrue(coordinator.removeCached(track, audio))
        assertEquals(LyricsResolutionKind.OFFLINE,
            runNow { coordinator.load(track, audio, false) }.kind)
        assertEquals(LyricsResolutionKind.AVAILABLE,
            runNow { coordinator.load(track, audio, true) }.kind)
        assertEquals(2, source.requests)
    }

    @Test fun wrongVersionsAreRejectedAndAmbiguousMatchesNeverPersist() {
        val source = Source(listOf(sample.copy(title = "A Song"), sample.copy(durationSeconds = 204)))
        assertEquals(LyricsResolutionKind.NOT_FOUND, runNow {
            service(source).load(track, audio, true)
        }.kind)
        source.results = listOf(sample, sample)
        assertEquals(LyricsResolutionKind.AMBIGUOUS, runNow {
            service(source).load(track, audio, true)
        }.kind)
        assertEquals(LyricsResolutionKind.OFFLINE, runNow {
            service(source).load(track, audio, false)
        }.kind)
    }

    @Test fun permissionIsEnforcedBeforeNetworkAndNoNegativeOrTransientResultsAreCached() {
        val source = Source(listOf(sample))
        val unlicensed = LyricsSourceRights(true, false, true)
        assertEquals(LyricsResolutionKind.RIGHTS_UNVERIFIED, runNow {
            service(source, unlicensed).load(track, audio, true)
        }.kind)
        assertEquals(0, source.requests)
        assertEquals(LyricsResolutionKind.MISSING_METADATA, runNow {
            service(source).load(track.copy(artist = " "), audio, true)
        }.kind)
        source.results = emptyList()
        assertEquals(LyricsResolutionKind.NOT_FOUND, runNow {
            service(source).load(track, audio, true)
        }.kind)
        source.failure = IOException("timeout")
        assertEquals(LyricsResolutionKind.PROVIDER_ERROR, runNow {
            service(source).load(track, audio, true)
        }.kind)
        source.failure = null
        source.results = listOf(sample)
        assertEquals(LyricsResolutionKind.AVAILABLE, runNow {
            service(source).load(track, audio, true)
        }.kind)
    }

    @Test fun differentAudioIdentityOrMetadataCannotReuseAnotherFilesLyrics() {
        val source = Source(listOf(sample))
        val coordinator = service(source)
        assertEquals(LyricsResolutionKind.AVAILABLE, runNow {
            coordinator.load(track, audio, true)
        }.kind)
        assertEquals(LyricsResolutionKind.OFFLINE, runNow {
            coordinator.load(track, "content://media/another", false)
        }.kind)
        assertEquals(LyricsResolutionKind.OFFLINE, runNow {
            coordinator.load(track.copy(album = "Another edition"), audio, false)
        }.kind)
        assertEquals(1, source.requests)
    }

    @Test fun displayOnlySourceNeverWritesCacheAndInstrumentalIsNotConfusedWithAbsent() {
        val source = Source(listOf(sample.copy(plainLyrics = null, syncedLyrics = null, instrumental = true)))
        val displayOnly = LyricsSourceRights(true, true, false)
        val coordinator = service(source, displayOnly)
        assertEquals(LyricsResolutionKind.INSTRUMENTAL, runNow {
            coordinator.load(track, audio, true)
        }.kind)
        assertEquals(LyricsResolutionKind.OFFLINE, runNow {
            coordinator.load(track, audio, false)
        }.kind)
        source.results = listOf(sample)
        assertEquals(LyricsResolutionKind.AVAILABLE, runNow {
            coordinator.load(track, audio, true)
        }.kind)
        assertEquals(LyricsResolutionKind.OFFLINE, runNow {
            coordinator.load(track, audio, false)
        }.kind)
    }

    @Test fun cancellationIsNeverSwallowed() {
        val source = Source(listOf(sample))
        source.failure = CancellationException("track changed")
        try {
            runNow { service(source).load(track, audio, true) }
            fail("Cancellation must propagate")
        } catch (_: CancellationException) {
            // A rapid track switch must not display the cancelled result.
        }
    }
}
