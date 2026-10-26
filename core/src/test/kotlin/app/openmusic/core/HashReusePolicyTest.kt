package app.openmusic.core

import org.junit.Assert.*
import org.junit.Test

class HashReusePolicyTest {
    private fun stamp(uri: String = "content://media/external_primary/audio/media/7") =
        DigestFingerprint(uri, "external_primary", 8000, 1740000000, 45, "version-A")

    @Test fun coldThenWarmAndSameBytesOnRescan() {
        val first = stamp()
        assertFalse(HashReusePolicy.reusable(first, first.copy(generationModified = null)))
        assertTrue(HashReusePolicy.reusable(first, first.copy()))
        assertEquals(setOf(first.uri), HashReusePolicy.retain(
            mapOf(first.uri to "SHA", "removed" to "SHA2"), setOf(first.uri),
        ).keys)
    }

    @Test fun invalidatesOnSizeTimeGenerationVolumeOrProviderReset() {
        val before = stamp()
        val changed = listOf(
            before.copy(sizeBytes = 8001), before.copy(modifiedSeconds = 1740000001),
            before.copy(generationModified = 46), before.copy(volume = "sdcard"),
            before.copy(mediaStoreVersion = "version-B"),
            before.copy(uri = "content://media/external_primary/audio/media/8"),
        )
        changed.forEach { assertFalse(HashReusePolicy.reusable(before, it)) }
    }

    @Test fun unknownAndOlderApiMetadataCannotSilentlyReuseDigest() {
        val good = stamp()
        listOf(
            good.copy(generationModified = null), good.copy(generationModified = 0),
            good.copy(mediaStoreVersion = null), good.copy(mediaStoreVersion = ""),
            good.copy(sizeBytes = 0), good.copy(modifiedSeconds = 0),
        ).forEach { assertFalse(HashReusePolicy.reusable(it, it)) }
    }

    @Test fun renameToDifferentMediaIdForcesRehashButMatchingCanStillRecover() {
        val saved = stamp()
        val renamed = stamp("content://media/external_primary/audio/media/999")
        assertFalse(HashReusePolicy.reusable(saved, renamed))
        val sha = "a".repeat(64)
        val metadata = TrackMetadata("Artist", "Title", durationMs = 150000, source = "tags")
        val playlist = PortablePlaylist("p", "P", "", "", listOf(
            PlaylistEntry("track", TrackIdentity(sha, metadata)),
        ))
        assertEquals(MatchStatus.EXACT, PlaylistMatcher.resolve(playlist,
            listOf(LocalCopy(renamed.uri, TrackIdentity(sha, metadata)))).single().status)
    }
    @Test fun simulatedColdWarmAndSingleChangeAvoidUnnecessaryRehash() {
        val current = (1..795).map { i ->
            stamp("content://media/external_primary/audio/media/$i").copy(
                sizeBytes = 8000L + i, generationModified = 50L + i,
            )
        }
        val empty = emptyMap<String, DigestFingerprint>()
        fun countRehashed(
            saved: Map<String, DigestFingerprint>, scanned: List<DigestFingerprint>,
        ): Int = scanned.count { item ->
            val old = saved[item.uri]
            old == null || !HashReusePolicy.reusable(old, item)
        }
        assertEquals(795, countRehashed(empty, current)) // cold cache
        val saved = current.associateBy { it.uri }
        assertEquals(0, countRehashed(saved, current)) // unchanged second import
        val modified = current.toMutableList()
        modified[10] = modified[10].copy(generationModified = modified[10].generationModified!! + 1)
        assertEquals(1, countRehashed(saved, modified)) // one modified file
    }

}
