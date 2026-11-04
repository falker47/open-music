package app.openmusic

import android.content.Context
import android.net.Uri
import app.openmusic.core.LocalLyricsParser
import app.openmusic.core.LyricsUiState
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * Private, opt-in local lyrics. Neither audio bytes, network calls nor playlist
 * JSON is changed. The source URI becomes only an opaque internal filename.
 */
internal class LocalLyricsStorage(private val context: Context) {
    private val directory = File(context.noBackupFilesDir, "open-music-lyrics-v1")
    private val maxBytes = 256 * 1024

    private fun fileFor(source: String): File {
        require(source.isNotBlank())
        val name = MessageDigest.getInstance("SHA-256")
            .digest(source.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) } + ".txt"
        return File(directory, name)
    }

    fun load(source: String): LyricsUiState {
        if (source.isBlank()) return LyricsUiState.NotFound
        val file = fileFor(source)
        if (!file.isFile) return LyricsUiState.NotFound
        return runCatching {
            if (file.length() > maxBytes) return@runCatching LyricsUiState.NotFound
            val parsed = LocalLyricsParser.parse(file.readText(Charsets.UTF_8))
            if (parsed.plainText.isBlank()) LyricsUiState.NotFound
            else LyricsUiState.Available(parsed.plainText, parsed.timedLines.isNotEmpty())
        }.getOrDefault(LyricsUiState.NotFound)
    }

    fun importDocument(source: String, document: Uri): LyricsUiState.Available {
        require(document.scheme == "content") { "Only a user-picked document can be imported" }
        val data = ByteArrayOutputStream()
        val stream = context.contentResolver.openInputStream(document)
            ?: throw IOException("Document could not be opened")
        stream.use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (data.size() + count > maxBytes) throw IOException("Lyrics file too large")
                data.write(buffer, 0, count)
            }
        }
        val raw = data.toString(Charsets.UTF_8.name())
        val parsed = LocalLyricsParser.parse(raw)
        if (parsed.plainText.isBlank()) throw IOException("Empty lyrics file")
        if (!directory.isDirectory && !directory.mkdirs())
            throw IOException("Unable to create lyrics directory")
        val target = fileFor(source)
        val temp = File.createTempFile("lyrics-", ".tmp", directory)
        try {
            temp.writeText(raw, Charsets.UTF_8)
            try {
                Files.move(temp.toPath(), target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temp.delete()
        }
        return LyricsUiState.Available(parsed.plainText, parsed.timedLines.isNotEmpty())
    }
}
