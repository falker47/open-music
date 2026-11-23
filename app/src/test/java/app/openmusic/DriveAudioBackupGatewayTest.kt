package app.openmusic

import app.openmusic.core.AudioBackupFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketException
import java.net.URL

class DriveAudioBackupGatewayTest {
    private val session = "https://upload.googleapis.com/example-session"
    private val started = Reply(200, headers = mapOf("Location" to session))
    private val lost = Reply(0, failure = SocketException("connection lost"))
    private val finished = Reply(201, """{"id":"confirmed-file"}""")
    private val quota = Reply(403, """{"error":{"errors":[{"reason":"storageQuotaExceeded"}]}}""")

    private data class Reply(
        val status: Int, val body: String = "", val headers: Map<String, String> = emptyMap(),
        val failure: IOException? = null,
    )

    private class Exchange(url: URL, val reply: Reply) : HttpURLConnection(url) {
        var closed = false
        val sent = ByteArrayOutputStream()
        override fun connect() = Unit
        override fun disconnect() { closed = true }
        override fun usingProxy() = false
        override fun getOutputStream() = sent
        override fun getResponseCode(): Int = reply.failure?.let { throw it } ?: reply.status
        override fun getInputStream() = reply.body.byteInputStream()
        override fun getErrorStream() = reply.body.byteInputStream()
        override fun getHeaderField(name: String) = reply.headers[name]
    }

    private class Drive(vararg responses: Reply) {
        private val replies = responses.toList().iterator()
        val requests = mutableListOf<Exchange>()
        val gateway = DriveAudioBackupGateway("unused-fixture") { url ->
            check(replies.hasNext()) { "Unexpected Drive request" }
            Exchange(url, replies.next()).also { requests.add(it) }
        }
        fun upload(bytes: ByteArray): String = runBlocking {
            val file = AudioBackupFile("song.flac", "b".repeat(64), bytes.size.toLong(), "audio/flac")
            gateway.upload("folder", file) { offset, count ->
                bytes.copyOfRange(offset.toInt(), offset.toInt() + count)
            }
        }
    }

    @Test fun quotaDuringInitiationOrWriteStopsImmediatelyWithoutConfirmation() {
        for (responses in listOf(listOf(quota), listOf(started, quota))) {
            val drive = Drive(*responses.toTypedArray())
            val error = assertThrows(IOException::class.java) { drive.upload(byteArrayOf(8)) }
            assertEquals("Spazio Google Drive esaurito", error.message)
            assertEquals(responses.size, drive.requests.size)
            assertTrue(drive.requests.all { it.closed })
        }
    }

    @Test fun lostResponseResumesAtExactConfirmedByteWithoutResendingPrefix() {
        val bytes = byteArrayOf(8, 7, 6, 5)
        val partial = Reply(308, headers = mapOf("Range" to "bytes=0-1"))
        val drive = Drive(started, lost, partial, finished)
        assertEquals("confirmed-file", drive.upload(bytes))
        assertEquals(listOf("POST", "PUT", "PUT", "PUT"), drive.requests.map { it.requestMethod })
        assertEquals(listOf("bytes 0-3/4", "bytes */4", "bytes 2-3/4"),
            drive.requests.drop(1).map { it.getRequestProperty("Content-Range") })
        assertArrayEquals(bytes, drive.requests[1].sent.toByteArray())
        assertEquals(0, drive.requests[2].sent.size())
        assertArrayEquals(byteArrayOf(6, 5), drive.requests[3].sent.toByteArray())
        assertTrue(drive.requests.all { it.closed })
    }

    @Test fun lostFinalResponseUsesServerConfirmationWithoutCreatingAnotherFile() {
        val drive = Drive(started, lost, finished)
        assertEquals("confirmed-file", drive.upload(byteArrayOf(8)))
        assertEquals("bytes */1", drive.requests.last().getRequestProperty("Content-Range"))
        assertEquals(0, drive.requests.last().sent.size())
    }

    @Test fun disconnectedStatusProbeFailsWithoutCountingOrBlindlyRetryingTheFile() {
        val drive = Drive(started, lost, lost)
        assertThrows(SocketException::class.java) { drive.upload(byteArrayOf(8)) }
        assertEquals(3, drive.requests.size)
        assertTrue(drive.requests.all { it.closed })
    }

    @Test fun acknowledgementsWithoutProgressHaveBoundedRetries() {
        val empty = Reply(308)
        val drive = Drive(started, empty, empty, empty)
        assertThrows(IOException::class.java) { drive.upload(byteArrayOf(8)) }
        assertEquals(4, drive.requests.size)
    }

    @Test fun malformedOrOutOfBoundsRangesDoNotAdvance() {
        for (range in listOf("bytes=1-2", "bytes=0-100", "garbage")) {
            val drive = Drive(started, Reply(308, headers = mapOf("Range" to range)))
            assertThrows(IOException::class.java) { drive.upload(byteArrayOf(8, 7, 6, 5)) }
            assertEquals(2, drive.requests.size)
        }
    }

    @Test fun cancellationWhileReadingAbortsOnlyTheOpenSession() {
        val drive = Drive(started, Reply(204))
        val file = AudioBackupFile("song.flac", "b".repeat(64), 1, "audio/flac")
        assertThrows(CancellationException::class.java) {
            runBlocking {
                drive.gateway.upload("folder", file) { _, _ -> throw CancellationException("fixture") }
            }
        }
        assertEquals(listOf("POST", "DELETE"), drive.requests.map { it.requestMethod })
        assertEquals(session, drive.requests.last().url.toString())
        assertTrue(drive.requests.all { it.closed })
    }
}
