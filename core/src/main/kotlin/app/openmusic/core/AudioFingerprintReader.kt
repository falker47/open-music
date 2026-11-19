package app.openmusic.core

import java.io.InputStream
import java.security.MessageDigest

data class AudioFingerprint(val sha256: String, val sizeBytes: Long)

/** Streaming digest/size avoids trusting SAF metadata for file length. */
object AudioFingerprintReader {
    fun inspect(input: InputStream): AudioFingerprint = input.use { stream ->
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        var bytes = 0L
        while (true) {
            val n = stream.read(buf)
            if (n < 0) break
            if (n > 0) {
                bytes = Math.addExact(bytes, n.toLong())
                md.update(buf, 0, n)
            }
        }
        AudioFingerprint(md.digest().joinToString("") { "%02x".format(it) }, bytes)
    }
}

/** Validate filenames before creating any file in a visible Drive folder. */
object AudioBackupFilename {
    fun sanitize(raw: String): String {
        val name = raw.trim().replace(Regex("""[/\\\x00-\x1f]"""), "_")
            .take(240)
        require(name.isNotBlank() && name != "." && name != "..") {
            "Missing filename"
        }
        return name
    }
}
