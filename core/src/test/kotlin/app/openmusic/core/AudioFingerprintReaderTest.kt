package app.openmusic.core

import java.io.ByteArrayInputStream
import org.junit.Assert.*
import org.junit.Test

class AudioFingerprintReaderTest {
    @Test fun readsSyntheticStreamWithExactLengthAndSha256() {
        val result = AudioFingerprintReader.inspect(
            ByteArrayInputStream("abc".toByteArray()))
        assertEquals(3L, result.sizeBytes)
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            result.sha256)
    }

    @Test fun normalizesNamesWithoutExposingAnyLocalPaths() {
        assertEquals("demo.mp3", AudioBackupFilename.sanitize(" demo.mp3 "))
        assertEquals("sub_track.flac", AudioBackupFilename.sanitize("sub/track.flac"))
        assertThrows(IllegalArgumentException::class.java) {
            AudioBackupFilename.sanitize("   ")
        }
    }
}
