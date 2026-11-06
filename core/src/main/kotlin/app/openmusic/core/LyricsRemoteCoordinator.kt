package app.openmusic.core

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.CancellationException

enum class LyricsResolutionKind {
    AVAILABLE, INSTRUMENTAL, NOT_FOUND, OFFLINE, RIGHTS_UNVERIFIED,
    MISSING_METADATA, AMBIGUOUS, PROVIDER_ERROR,
}

data class LyricsResolution(
    val kind: LyricsResolutionKind,
    val lyrics: ParsedLyrics? = null,
    val fromCache: Boolean = false,
    val source: String? = null,
)

/**
 * App-private, bounded, atomic storage. Pass Context.noBackupFilesDir from Android.
 * Keys hash media identity + all supplied metadata; no URI, audio or path is
 * written to disk. This cache only stores authorized *positive* text results.
 */
class LyricsPrivateCache(private val root: File) {
    data class Entry(val source: String, val plain: String?, val synced: String?)

    private val directory get() = File(root, "lyrics-v1")
    private val maxTextBytes = 256 * 1024
    private val maxFileBytes = 2 * maxTextBytes + 4096
    private val magic = 0x4F4D4C31 // OML1

    private fun path(track: LyricsTrack, audioIdentity: String): File {
        val key = listOf(
            track.title.trim().lowercase(Locale.ROOT),
            track.artist.trim().lowercase(Locale.ROOT),
            track.album?.trim()?.lowercase(Locale.ROOT).orEmpty(),
            track.durationSeconds?.toString().orEmpty(),
            audioIdentity,
        ).joinToString("\u0000")
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(key.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return File(directory, "$" + "{digest}.bin")
    }

    private fun DataOutputStream.putText(value: String?) {
        if (value == null) {
            writeInt(-1)
            return
        }
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= maxTextBytes) { "Lyrics exceed private cache limit" }
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.takeText(): String? {
        val length = readInt()
        if (length == -1) return null
        if (length !in 0..maxTextBytes) throw IOException("Invalid lyrics cache length")
        val bytes = ByteArray(length)
        readFully(bytes)
        return bytes.toString(Charsets.UTF_8)
    }

    fun read(track: LyricsTrack, audioIdentity: String): Entry? {
        if (audioIdentity.isBlank()) return null
        val file = path(track, audioIdentity)
        if (!file.isFile || file.length() !in 1..maxFileBytes.toLong()) return null
        return try {
            DataInputStream(FileInputStream(file).buffered()).use { input ->
                if (input.readInt() != magic) return null
                val source = input.takeText()?.takeIf { it.isNotBlank() } ?: return null
                val plain = input.takeText()
                val synced = input.takeText()
                if (plain.isNullOrBlank() && synced.isNullOrBlank()) null
                else Entry(source, plain, synced)
            }
        } catch (_: IOException) {
            null // Corrupt or incomplete cache does not become fabricated lyrics.
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    fun write(track: LyricsTrack, audioIdentity: String, entry: Entry) {
        require(audioIdentity.isNotBlank())
        require(entry.source.isNotBlank() && entry.source.length <= 200)
        require(!entry.plain.isNullOrBlank() || !entry.synced.isNullOrBlank())
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw IOException("Cannot create private lyrics directory")
        }
        val dest = path(track, audioIdentity)
        val tmp = File.createTempFile("lyrics-", ".tmp", directory)
        try {
            FileOutputStream(tmp).use { output ->
                DataOutputStream(output.buffered()).use { data ->
                    data.writeInt(magic)
                    data.putText(entry.source)
                    data.putText(entry.plain)
                    data.putText(entry.synced)
                    data.flush()
                }
            }
            try {
                Files.move(tmp.toPath(), dest.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            tmp.delete()
        }
    }

    fun remove(track: LyricsTrack, audioIdentity: String): Boolean =
        audioIdentity.isNotBlank() && path(track, audioIdentity).delete()
}

/**
 * On-demand only; deliberately has no library-wide sweep or background fetch.
 * The caller must supply independently established content rights. An API grant
 * alone (e.g. from lrc.red) MUST NOT enable display or offline writing.
 */
class LyricsRemoteCoordinator(
    private val provider: LyricsProvider,
    private val cache: LyricsPrivateCache,
    private val rights: LyricsSourceRights,
    private val providerName: String,
) {
    suspend fun load(
        track: LyricsTrack,
        audioIdentity: String,
        online: Boolean,
        forceRefresh: Boolean = false,
    ): LyricsResolution {
        if (track.title.isBlank() || track.artist.isBlank() || audioIdentity.isBlank()) {
            return LyricsResolution(LyricsResolutionKind.MISSING_METADATA)
        }
        if (!rights.allows(LyricsAction.QUERY) || !rights.allows(LyricsAction.DISPLAY)) {
            return LyricsResolution(LyricsResolutionKind.RIGHTS_UNVERIFIED)
        }
        val cached = cache.read(track, audioIdentity)
        if (cached != null && (!forceRefresh || !online)) {
            val lyrics = decode(cached.plain, cached.synced)
            if (lyrics != null) {
                return LyricsResolution(
                    LyricsResolutionKind.AVAILABLE, lyrics, fromCache = true,
                    source = cached.source,
                )
            }
        }
        if (!online) return LyricsResolution(LyricsResolutionKind.OFFLINE)
        val candidates = try {
            provider.find(track)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            return cached?.let { saved ->
                decode(saved.plain, saved.synced)?.let {
                    LyricsResolution(LyricsResolutionKind.AVAILABLE, it, true, saved.source)
                }
            } ?: LyricsResolution(LyricsResolutionKind.PROVIDER_ERROR)
        }
        val match = LyricsCandidateResolver.select(track, candidates)
        val candidate = when (match.kind) {
            LyricsMatchKind.FOUND -> match.candidate!!
            LyricsMatchKind.NOT_FOUND -> return LyricsResolution(LyricsResolutionKind.NOT_FOUND)
            LyricsMatchKind.AMBIGUOUS -> return LyricsResolution(LyricsResolutionKind.AMBIGUOUS)
            LyricsMatchKind.MISSING_METADATA ->
                return LyricsResolution(LyricsResolutionKind.MISSING_METADATA)
        }
        if (candidate.instrumental) return LyricsResolution(LyricsResolutionKind.INSTRUMENTAL)
        val parsed = decode(candidate.plainLyrics, candidate.syncedLyrics)
            ?: return LyricsResolution(LyricsResolutionKind.NOT_FOUND)
        if (rights.allows(LyricsAction.STORE_OFFLINE)) {
            try {
                cache.write(track, audioIdentity,
                    LyricsPrivateCache.Entry(providerName,
                        candidate.plainLyrics, candidate.syncedLyrics))
            } catch (_: IOException) {
                // Network result is still usable, but must not be labelled cached.
            } catch (_: IllegalArgumentException) {
                // Oversized content is display-only; never write a partial file.
            }
        }
        return LyricsResolution(LyricsResolutionKind.AVAILABLE, parsed, source = providerName)
    }

    fun removeCached(track: LyricsTrack, audioIdentity: String): Boolean =
        cache.remove(track, audioIdentity)

    private fun decode(plain: String?, synced: String?): ParsedLyrics? {
        val timed = synced?.takeIf { it.isNotBlank() }?.let(LocalLyricsParser::parse)
        if (timed != null && timed.timedLines.isNotEmpty() && timed.plainText.isNotBlank())
            return timed
        return plain?.takeIf { it.isNotBlank() }?.let(LocalLyricsParser::parse)
            ?.takeIf { it.plainText.isNotBlank() }
    }
}
