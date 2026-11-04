package app.openmusic.core

import java.io.ByteArrayInputStream
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

class AudioBackupPlannerTest {
    private fun sha(data: ByteArray) = MessageDigest.getInstance("SHA-256")
        .digest(data).joinToString("") { "%02x".format(it) }
    private fun file(name: String, data: String, id: String? = null): AudioBackupFile {
        val bytes = data.toByteArray()
        return AudioBackupFile(name, sha(bytes), bytes.size.toLong(), "audio/mpeg", id)
    }
    private fun manifest(vararg items: AudioBackupFile) = AudioBackupManifest(
        1, "folder-123", "account-abc", items.toList())

    @Test fun identicalContentWithDifferentNamesDoesNotUploadAgain() {
        val remote = manifest(file("one.mp3", "same", "remote-one"))
        val decisions = AudioBackupPlanner.planUpload(listOf(file("two.mp3", "same")), remote)
        assertEquals(AudioBackupDisposition.REMOTE_EXISTS, decisions.single().disposition)
        assertEquals("remote-one", decisions.single().existingRemoteFileId)
    }

    @Test fun nameCollisionWithDifferentContentGetsDeterministicNewName() {
        val remote = manifest(file("song.mp3", "first", "remote-one"))
        val next = AudioBackupPlanner.planUpload(listOf(file("song.mp3", "second")), remote).single()
        assertEquals(AudioBackupDisposition.RENAME_ON_UPLOAD, next.disposition)
        assertTrue(next.proposedRemoteName.startsWith("song.mp3 ("))
        assertEquals(AudioBackupPlanner.planUpload(listOf(file("song.mp3", "second")), remote)
            .single().proposedRemoteName, next.proposedRemoteName)
    }

    @Test fun localDuplicateHashIsOnlyPlannedOnce() {
        val remote = manifest()
        val plan = AudioBackupPlanner.planUpload(
            listOf(file("one.mp3", "abc"), file("two.mp3", "abc")), remote)
        assertEquals(AudioBackupDisposition.UPLOAD, plan[0].disposition)
        assertEquals(AudioBackupDisposition.REUSE_LOCAL_CONTENT, plan[1].disposition)
    }

    @Test fun interruptedUploadResumesWithAnExactOffset() {
        assertEquals(700L, AudioBackupPlanner.remainingBytes(1000, 300))
        assertEquals(0L, AudioBackupPlanner.remainingBytes(1000, 1000))
        assertThrows(IllegalArgumentException::class.java) {
            AudioBackupPlanner.remainingBytes(100, 101)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AudioBackupPlanner.remainingBytes(100, -1)
        }
    }

    @Test fun incompleteOrCorruptDownloadsAreNeverVerified() {
        val entry = file("song.mp3", "correct audio", "id")
        assertTrue(AudioBackupPlanner.verifyDownloaded(entry,
            ByteArrayInputStream("correct audio".toByteArray())))
        assertFalse(AudioBackupPlanner.verifyDownloaded(entry,
            ByteArrayInputStream("wrong".toByteArray())))
        assertFalse(AudioBackupPlanner.verifyDownloaded(entry,
            ByteArrayInputStream("incorrect data".toByteArray())))
    }

    @Test fun incompatibleVersionAccountOrFolderRejectBeforeTransfers() {
        val valid = manifest(file("a.mp3", "valid", "remote-one"))
        AudioBackupPlanner.requireValid(valid, "folder-123", "account-abc")
        assertThrows(IllegalArgumentException::class.java) {
            AudioBackupPlanner.requireValid(valid.copy(version = 2), "folder-123", "account-abc")
        }
        assertThrows(IllegalArgumentException::class.java) {
            AudioBackupPlanner.requireValid(valid, "different", "account-abc")
        }
        assertThrows(IllegalArgumentException::class.java) {
            AudioBackupPlanner.requireValid(valid, "folder-123", "other-account")
        }
        assertThrows(IllegalArgumentException::class.java) {
            AudioBackupPlanner.requireValid(manifest(file("../leak", "x", "remote-x")),
                "folder-123", "account-abc")
        }
    }

    @Test fun duplicateRemoteIdCannotMasqueradeAsTwoFiles() {
        assertThrows(IllegalArgumentException::class.java) {
            AudioBackupPlanner.requireValid(
                manifest(file("one.mp3", "first", "same"), file("two.mp3", "second", "same")),
                "folder-123", "account-abc")
        }
    }
}
