package app.openmusic.core

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset

/**
 * Reads lyrics already embedded in a user-selected local audio stream.
 * Never reads audio samples, writes a tag, or contacts a third-party service.
 *
 * Supported: ID3v2.3/v2.4 USLT, lyric TXXX and millisecond SYLT;
 * FLAC Vorbis LYRICS/UNSYNCEDLYRICS/SYNCEDLYRICS.
 * Encrypted, compressed, unsynchronised and malformed ID3 frames fail closed.
 */
object EmbeddedLyricsReader {
    private const val MAX_METADATA_BYTES = 32 * 1024 * 1024
    private const val MAX_LYRICS_BYTES = 512 * 1024

    fun read(stream: InputStream): ParsedLyrics? {
        val signature = stream.readBytesExactly(4) ?: return null
        return when {
            signature[0] == 'I'.code.toByte() &&
                signature[1] == 'D'.code.toByte() &&
                signature[2] == '3'.code.toByte() ->
                readId3(stream, signature[3].toInt() and 0xff)
            signature.contentEquals("fLaC".toByteArray(Charsets.US_ASCII)) ->
                readFlac(stream)
            else -> null
        }
    }

    private fun readId3(stream: InputStream, version: Int): ParsedLyrics? {
        if (version !in 3..4) return null
        val header = stream.readBytesExactly(6) ?: return null
        val flags = header[1].unsigned()
        val size = synchsafe(header, 2) ?: return null
        if (size > MAX_METADATA_BYTES || flags and 0x80 != 0) return null
        var remaining = size

        if (flags and 0x40 != 0) {
            val length = stream.readBytesExactly(4) ?: return null
            val extended = if (version == 4) synchsafe(length, 0)
                else bigEndian(length, 0)
            if (extended == null) return null
            val extra = if (version == 3) extended else extended - 4
            if (extra < 0 || extra > remaining - 4 || !stream.skipExactly(extra)) return null
            remaining -= extra + 4
        }

        var best: ParsedLyrics? = null
        while (remaining >= 10) {
            val frame = stream.readBytesExactly(10) ?: break
            remaining -= 10
            if (frame.take(4).all { it == 0.toByte() }) break
            val id = frame.copyOfRange(0, 4).toString(Charsets.US_ASCII)
            if (!id.matches(Regex("[A-Z0-9]{4}"))) break
            val length = if (version == 4) synchsafe(frame, 4)
                else bigEndian(frame, 4)
            if (length == null || length > remaining) break
            remaining -= length

            // Frame flags: grouping, encryption, compression, or per-frame
            // unsynchronisation can change the layout. Do not guess.
            val formatFlags = frame[9].unsigned()
            val supported = (id == "USLT" || id == "TXXX" || id == "SYLT") &&
                formatFlags == 0 && length in 1..MAX_LYRICS_BYTES
            if (!supported) {
                if (!stream.skipExactly(length)) break
                continue
            }
            val bytes = stream.readBytesExactly(length) ?: break
            val candidate = when (id) {
                "USLT" -> decodeUslt(bytes)
                "TXXX" -> decodeTxxx(bytes)
                "SYLT" -> decodeSylt(bytes)
                else -> null
            }
            if (candidate != null &&
                (best == null || candidate.timedLines.size > best.timedLines.size)
            ) best = candidate
        }
        return best
    }

    private fun decodeUslt(data: ByteArray): ParsedLyrics? {
        if (data.size < 5) return null
        val encoding = data[0].unsigned()
        val afterDescription = stringEnd(data, 4, encoding) ?: return null
        return parseContent(decode(data, afterDescription, data.size, encoding))
    }

    private fun decodeTxxx(data: ByteArray): ParsedLyrics? {
        if (data.size < 3) return null
        val encoding = data[0].unsigned()
        val descriptionEnd = stringEndOffset(data, 1, encoding) ?: return null
        val description = decode(data, 1, descriptionEnd, encoding)?.uppercase()
        if (description !in setOf("LYRICS", "UNSYNCEDLYRICS", "SYNCEDLYRICS")) return null
        val valueOffset = stringEnd(data, 1, encoding) ?: return null
        return parseContent(decode(data, valueOffset, data.size, encoding))
    }

    private fun decodeSylt(data: ByteArray): ParsedLyrics? {
        if (data.size < 12 || data[4].unsigned() != 2 || data[5].unsigned() != 1) return null
        val encoding = data[0].unsigned()
        var cursor = stringEnd(data, 6, encoding) ?: return null
        val lines = mutableListOf<TimedLyricLine>()
        while (cursor < data.size && lines.size < 5000) {
            val end = stringEndOffset(data, cursor, encoding) ?: break
            val text = decode(data, cursor, end, encoding)
            cursor = stringEnd(data, cursor, encoding) ?: break
            if (cursor > data.size - 4) break
            val timestamp = bigEndianUnsigned(data, cursor)
            cursor += 4
            if (!text.isNullOrBlank() && timestamp <= 24L * 60 * 60 * 1000)
                lines.add(TimedLyricLine(timestamp, text))
        }
        if (lines.isEmpty()) return null
        val ordered = lines.sortedBy { it.startMs }
        return ParsedLyrics(ordered.joinToString("\n") { it.text }, ordered)
    }

    private fun readFlac(stream: InputStream): ParsedLyrics? {
        var consumed = 0L
        var best: ParsedLyrics? = null
        while (consumed < MAX_METADATA_BYTES) {
            val header = stream.readBytesExactly(4) ?: break
            val isLast = header[0].unsigned() and 0x80 != 0
            val type = header[0].unsigned() and 0x7f
            val length = (header[1].unsigned() shl 16) or
                (header[2].unsigned() shl 8) or header[3].unsigned()
            consumed += 4L + length
            if (consumed > MAX_METADATA_BYTES) break
            val candidate = if (type == 4 && length <= MAX_LYRICS_BYTES * 2) {
                stream.readBytesExactly(length)?.let(::decodeVorbisComments)
            } else {
                if (!stream.skipExactly(length)) break
                null
            }
            if (candidate != null &&
                (best == null || candidate.timedLines.size > best.timedLines.size)
            ) best = candidate
            if (isLast) break
        }
        return best
    }

    private fun decodeVorbisComments(bytes: ByteArray): ParsedLyrics? {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        fun sized(): ByteArray? {
            if (buffer.remaining() < 4) return null
            val size = buffer.int
            if (size < 0 || size > buffer.remaining()) return null
            return ByteArray(size).also { buffer.get(it) }
        }
        if (sized() == null || buffer.remaining() < 4) return null // vendor
        val count = buffer.int
        if (count < 0 || count > 10_000) return null
        var best: ParsedLyrics? = null
        for (i in 0 until count) {
            val field = sized() ?: return best
            if (field.size > MAX_LYRICS_BYTES) continue
            val entry = field.toString(Charsets.UTF_8)
            val separator = entry.indexOf('=')
            if (separator < 1) continue
            val key = entry.substring(0, separator).trim().uppercase()
            if (key !in setOf("LYRICS", "UNSYNCEDLYRICS", "SYNCEDLYRICS")) continue
            val candidate = parseContent(entry.substring(separator + 1))
            if (candidate != null &&
                (best == null || candidate.timedLines.size > best.timedLines.size)
            ) best = candidate
        }
        return best
    }

    private fun parseContent(raw: String?): ParsedLyrics? {
        val text = raw?.trim('\uFEFF', '\u0000', ' ', '\n', '\r', '\t')
            ?.takeIf { it.isNotBlank() } ?: return null
        return LocalLyricsParser.parse(text).takeIf { it.plainText.isNotBlank() }
    }

    private fun decode(data: ByteArray, start: Int, end: Int, encoding: Int): String? {
        val charset: Charset = when (encoding) {
            0 -> Charsets.ISO_8859_1
            1 -> Charsets.UTF_16
            2 -> Charsets.UTF_16BE
            3 -> Charsets.UTF_8
            else -> return null
        }
        if (start !in 0..end || end > data.size) return null
        return String(data, start, end - start, charset)
            .trim('\uFEFF', '\u0000', ' ', '\r', '\n')
    }

    private fun stringEndOffset(data: ByteArray, start: Int, encoding: Int): Int? {
        val width = if (encoding == 1 || encoding == 2) 2 else 1
        if (encoding !in 0..3) return null
        var index = start
        while (index + width <= data.size) {
            if (data[index] == 0.toByte() &&
                (width == 1 || data[index + 1] == 0.toByte())
            ) return index
            index += width
        }
        return null
    }

    private fun stringEnd(data: ByteArray, start: Int, encoding: Int): Int? =
        stringEndOffset(data, start, encoding)?.plus(
            if (encoding == 1 || encoding == 2) 2 else 1)

    private fun Byte.unsigned(): Int = toInt() and 0xff

    private fun synchsafe(bytes: ByteArray, offset: Int): Int? {
        if (offset + 4 > bytes.size || (offset until offset + 4)
                .any { bytes[it].unsigned() and 0x80 != 0 }) return null
        return (bytes[offset].unsigned() shl 21) or
            (bytes[offset + 1].unsigned() shl 14) or
            (bytes[offset + 2].unsigned() shl 7) or bytes[offset + 3].unsigned()
    }

    private fun bigEndian(bytes: ByteArray, offset: Int): Int? {
        val value = bigEndianUnsigned(bytes, offset)
        return value.takeIf { it <= Int.MAX_VALUE }?.toInt()
    }

    private fun bigEndianUnsigned(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].unsigned().toLong() shl 24) or
            (bytes[offset + 1].unsigned().toLong() shl 16) or
            (bytes[offset + 2].unsigned().toLong() shl 8) or
            bytes[offset + 3].unsigned().toLong()

    private fun InputStream.readBytesExactly(length: Int): ByteArray? {
        if (length < 0 || length > MAX_METADATA_BYTES) return null
        val buffer = ByteArray(length)
        var index = 0
        while (index < length) {
            val count = read(buffer, index, length - index)
            if (count < 0) return null
            if (count == 0) {
                val single = read()
                if (single < 0) return null
                buffer[index++] = single.toByte()
            } else index += count
        }
        return buffer
    }

    private fun InputStream.skipExactly(length: Int): Boolean {
        var remaining = length.toLong()
        while (remaining > 0) {
            val skipped = skip(remaining)
            if (skipped > 0) remaining -= skipped
            else if (read() == -1) return false else remaining--
        }
        return true
    }
}

/** Stable line highlighting even before the first timestamp (returns -1). */
object LyricsTimeline {
    fun activeIndex(lines: List<TimedLyricLine>, positionMs: Long): Int =
        lines.indexOfLast { it.startMs <= positionMs }
}
