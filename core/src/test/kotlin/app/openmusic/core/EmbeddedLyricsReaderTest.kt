package app.openmusic.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test

class EmbeddedLyricsReaderTest {
    private fun synchsafe(size: Int) = byteArrayOf(
        ((size shr 21) and 0x7f).toByte(), ((size shr 14) and 0x7f).toByte(),
        ((size shr 7) and 0x7f).toByte(), (size and 0x7f).toByte(),
    )

    private fun be(size: Int) = byteArrayOf(
        (size shr 24).toByte(), (size shr 16).toByte(),
        (size shr 8).toByte(), size.toByte(),
    )

    private fun le(size: Int) = byteArrayOf(
        size.toByte(), (size shr 8).toByte(),
        (size shr 16).toByte(), (size shr 24).toByte(),
    )

    private fun bytes(vararg parts: ByteArray): ByteArray =
        ByteArrayOutputStream().apply { parts.forEach { write(it) } }.toByteArray()

    private fun frame(id: String, payload: ByteArray, version: Int = 3): ByteArray =
        bytes(id.toByteArray(Charsets.US_ASCII),
            if (version == 4) synchsafe(payload.size) else be(payload.size),
            byteArrayOf(0, 0), payload)

    private fun id3(version: Int, flags: Int = 0, vararg frames: ByteArray): ByteArray {
        val body = bytes(*frames)
        return bytes(byteArrayOf('I'.code.toByte(), 'D'.code.toByte(),
            '3'.code.toByte(), version.toByte(), 0, flags.toByte()),
            synchsafe(body.size), body, "AUDIO-BYTES".toByteArray())
    }

    private fun uslt(text: String, version: Int = 3, encoding: Int = 0): ByteArray {
        val content = if (encoding == 1) text.toByteArray(Charsets.UTF_16)
            else text.toByteArray(if (encoding == 3) Charsets.UTF_8 else Charsets.ISO_8859_1)
        return frame("USLT", bytes(
            byteArrayOf(encoding.toByte()), "eng".toByteArray(), byteArrayOf(0), content
        ), version)
    }

    private fun flac(vararg fields: String): ByteArray {
        val vendor = "OpenMusicTest".toByteArray()
        val comments = ByteArrayOutputStream().apply {
            write(le(vendor.size)); write(vendor)
            write(le(fields.size))
            fields.forEach {
                val data = it.toByteArray(Charsets.UTF_8)
                write(le(data.size)); write(data)
            }
        }.toByteArray()
        return bytes("fLaC".toByteArray(),
            byteArrayOf(0, 0, 0, 34), ByteArray(34), // STREAMINFO
            byteArrayOf(0x84.toByte(), (comments.size shr 16).toByte(),
                (comments.size shr 8).toByte(), comments.size.toByte()),
            comments, "SOUND".toByteArray())
    }

    private fun read(bytes: ByteArray): ParsedLyrics? =
        EmbeddedLyricsReader.read(ByteArrayInputStream(bytes))

    @Test fun readsPlainLyricsFromMp3Id3v23WithoutTouchingAudio() {
        val parsed = read(id3(3, 0, uslt("First verse\nSecond verse")))
        assertEquals("First verse\nSecond verse", parsed?.plainText)
        assertTrue(parsed!!.timedLines.isEmpty())
    }

    @Test fun readsUtf8LrcFromId3v24AndSelectsTimedOverPlain() {
        val plain = uslt("Plain version", 4, 3)
        val timed = uslt("[00:02.50] Timed\n[00:05.000] Second", 4, 3)
        val parsed = read(id3(4, 0, plain, timed))
        assertEquals("Timed\nSecond", parsed?.plainText)
        assertEquals(2500L, parsed!!.timedLines.first().startMs)
    }

    @Test fun readsUnicodeUtf16LyricsAndSkipsArtwork() {
        val hugeArtwork = frame("APIC", ByteArray(250_000) { 0x45.toByte() })
        val parsed = read(id3(3, 0, hugeArtwork, uslt("Città è mia", 3, 1)))
        assertEquals("Città è mia", parsed?.plainText)
    }

    @Test fun readsCommonTxxxLyricsButIgnoresUnrelatedDescriptions() {
        val ignored = frame("TXXX", byteArrayOf(3) + "COMMENT\u0000Not lyrics".toByteArray())
        val accepted = frame("TXXX", byteArrayOf(3) +
            "LYRICS\u0000Actual lyric".toByteArray())
        assertEquals("Actual lyric", read(id3(3, 0, ignored, accepted))?.plainText)
    }

    @Test fun readsMillisecondSyltAndHighlightsOnlyActiveLine() {
        val payload = bytes(byteArrayOf(3), "eng".toByteArray(),
            byteArrayOf(2, 1, 0),
            "First".toByteArray(), byteArrayOf(0), be(1000),
            "Second".toByteArray(), byteArrayOf(0), be(3300))
        val parsed = read(id3(4, 0, frame("SYLT", payload, 4)))
        assertEquals("First\nSecond", parsed?.plainText)
        val lines = parsed!!.timedLines
        assertEquals(-1, LyricsTimeline.activeIndex(lines, 999))
        assertEquals(0, LyricsTimeline.activeIndex(lines, 1000))
        assertEquals(0, LyricsTimeline.activeIndex(lines, 3299))
        assertEquals(1, LyricsTimeline.activeIndex(lines, 3300))
    }

    @Test fun readsFlacVorbisCommentsWithTimedPreferredOverPlain() {
        val parsed = read(flac(
            "ARTIST=An Artist", "LYRICS=Plain lyrics",
            "SYNCEDLYRICS=[00:01.000] One\n[00:03.400] Two",
        ))
        assertEquals("One\nTwo", parsed?.plainText)
        assertEquals(3400L, parsed!!.timedLines.last().startMs)
    }

    @Test fun malformedTagsDoNotCreateFalsePositiveLyrics() {
        assertNull(read("not a music file".toByteArray()))
        assertNull(read(id3(3, 0x80, uslt("must skip globally unsynced tag"))))
        assertNull(read("ID3".toByteArray()))
        assertNull(read("fLaC".toByteArray()))
        assertNull(read(id3(3, 0, frame("TIT2", "Hello".toByteArray()))))
    }

    @Test fun ignoresInvalidId3FrameSizes() {
        val frame = bytes("USLT".toByteArray(), be(999999), byteArrayOf(0, 0),
            "bad".toByteArray())
        assertNull(read(id3(3, 0, frame)))
    }

    @Test fun emptyVorbisLyricsCannotBeDisplayed() {
        assertNull(read(flac("LYRICS=", "ARTIST=Unknown")))
    }
}
