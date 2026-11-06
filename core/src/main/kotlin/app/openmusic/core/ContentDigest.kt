package app.openmusic.core

import java.io.InputStream
import java.security.MessageDigest

object ContentDigest {
    /** Only invoke for files selected by the user. Hashing reads all bytes. */
    fun sha256(input: InputStream): String = input.use { stream ->
        val md = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = stream.read(buffer)
            if (count == -1) break
            if (count > 0) md.update(buffer, 0, count)
        }
        md.digest().joinToString("") { "%02x".format(it) }
    }
}
