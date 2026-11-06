package app.openmusic

import app.openmusic.core.LyricsCandidate
import app.openmusic.core.LyricsCandidateResolver
import app.openmusic.core.LyricsMatchKind
import app.openmusic.core.LyricsProvider
import app.openmusic.core.LyricsTrack
import app.openmusic.core.LocalLyricsParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.xml.sax.InputSource
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.roundToInt

/**
 * Network access and provider storage permission are NOT a content copyright license.
 * Not enabled in public player UX until the underlying lyrics' rights are established.
 */
internal interface LrcRedHttpTransport {
    suspend fun get(path: String): String?
}

/**
 * Only documented HTTPS paths and app-identifying User-Agent, no secret credentials.
 * Bounded responses, serialized requests, timeout and Retry-After protection.
 */
internal class HttpsLrcRedTransport(contactEmail: String) : LrcRedHttpTransport {
    private val contact = contactEmail.trim()
    private val mutex = Mutex()
    private var lastRequestMillis = 0L
    private var retryNotBeforeMillis = 0L
    private val userAgent =
        "OpenMusic/0.1.0 (https://github.com/falker47/open-music; $contact)"

    init {
        require(contact.matches(Regex("""[^\s@]+@[^\s@]+\.[^\s@]+"""))) {
            "A reachable project contact is required by lrc.red terms"
        }
    }

    override suspend fun get(path: String): String? = mutex.withLock {
        require(path.startsWith("/search.json?q=") &&
                path.length <= 600 && !path.contains('#') ||
            Regex("""/s/[A-Z0-9]{12}\.(?:lrc|ttml)""").matches(path)) {
            "Unexpected lrc.red request path"
        }
        val now = System.currentTimeMillis()
        if (now < retryNotBeforeMillis) throw IOException("lrc.red cooldown active")
        val delayMs = 400L - (now - lastRequestMillis)
        if (delayMs > 0L) delay(delayMs)
        lastRequestMillis = System.currentTimeMillis()
        withContext(Dispatchers.IO) {
            val connection =
                URL("https://lrc.red" + path).openConnection() as HttpURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.requestMethod = "GET"
                connection.connectTimeout = 5_000
                connection.readTimeout = 6_000
                connection.setRequestProperty("User-Agent", userAgent)
                connection.setRequestProperty(
                    "Accept", "application/json, text/plain, application/xml")
                when (val status = connection.responseCode) {
                    200 -> connection.inputStream.use { input ->
                        val output = ByteArrayOutputStream()
                        val bytes = ByteArray(8192)
                        while (true) {
                            val read = input.read(bytes)
                            if (read < 0) break
                            if (output.size() + read > 262_144)
                                throw IOException("lrc.red response too large")
                            output.write(bytes, 0, read)
                        }
                        output.toString(Charsets.UTF_8.name())
                    }
                    404 -> null
                    429 -> {
                        val seconds = connection.getHeaderField("Retry-After")
                            ?.toLongOrNull()?.coerceIn(1L, 3600L) ?: 60L
                        retryNotBeforeMillis = System.currentTimeMillis() + seconds * 1000L
                        throw IOException("lrc.red rate limited")
                    }
                    else -> throw IOException("lrc.red HTTP $status")
                }
            } finally {
                connection.disconnect()
            }
        }
    }
}

/** Search exact metadata before fetching ONE selected ISRC. No bulk requests. */
internal class LrcRedLyricsProvider(private val http: LrcRedHttpTransport) : LyricsProvider {
    private data class Hit(val isrc: String, val candidate: LyricsCandidate)
    private data class TtmlResult(val plain: String, val synced: String?)
    private val validIsrc = Regex("[A-Z0-9]{12}")

    override suspend fun find(track: LyricsTrack): List<LyricsCandidate> {
        if (track.title.isBlank() || track.artist.isBlank()) return emptyList()
        val query = URLEncoder.encode(
            track.title.trim() + " " + track.artist.trim(), "UTF-8")
        val body = http.get("/search.json?q=" + query) ?: return emptyList()
        val hitsJson = JSONObject(body).optJSONArray("hits") ?: return emptyList()
        val hits = buildList {
            for (index in 0 until minOf(hitsJson.length(), 20)) {
                val hit = hitsJson.optJSONObject(index) ?: continue
                val isrc = hit.optString("isrc").uppercase(Locale.ROOT)
                if (!validIsrc.matches(isrc)) continue
                val title = hit.optString("title").trim()
                val artist = hit.optString("artist").trim()
                if (title.isBlank() || artist.isBlank()) continue
                val album = hit.optString("album").trim()
                    .takeUnless { it.isBlank() || it == "null" }
                val duration = hit.optDouble("duration", Double.NaN)
                    .takeIf { it.isFinite() && it in 1.0..3600.0 }?.roundToInt()
                add(Hit(isrc, LyricsCandidate(title, artist, album, duration,
                    instrumental = false, plainLyrics = null, syncedLyrics = null)))
            }
        }
        val candidates = hits.map { it.candidate }
        val matched = LyricsCandidateResolver.select(track, candidates)
        if (matched.kind != LyricsMatchKind.FOUND) return candidates
        val selected = hits.first { it.candidate == matched.candidate }
        val isrc = selected.isrc
        val lrc = http.get("/s/$isrc.lrc")
        val synchronized = lrc?.takeIf {
            LocalLyricsParser.parse(it).timedLines.isNotEmpty()
        }
        val ttml = if (synchronized == null) {
            http.get("/s/$isrc.ttml")?.let(::readTtml)
        } else null
        return hits.map { hit ->
            if (hit === selected) hit.candidate.copy(
                plainLyrics = ttml?.plain,
                syncedLyrics = synchronized ?: ttml?.synced,
            ) else hit.candidate
        }
    }

    /** Parse TTML without DTD, external entities or externally fetched resources. */
    private fun readTtml(raw: String): TtmlResult? {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isXIncludeAware = false
            isExpandEntityReferences = false
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        }
        val document = factory.newDocumentBuilder().parse(InputSource(StringReader(raw)))
        val tags = document.getElementsByTagNameNS("*", "p")
        val lines = mutableListOf<String>()
        val times = mutableListOf<String>()
        for (i in 0 until minOf(tags.length, 2000)) {
            val element = tags.item(i) as? org.w3c.dom.Element ?: continue
            val text = element.textContent.replace(Regex("""\s+"""), " ").trim()
            if (text.isBlank()) continue
            lines.add(text)
            val start = lrcStamp(element.getAttribute("begin"))
            if (start != null) times.add(start + text)
        }
        if (lines.isEmpty()) return null
        return TtmlResult(
            lines.joinToString("\n"),
            if (times.size == lines.size) times.joinToString("\n") else null,
        )
    }

    private fun lrcStamp(raw: String?): String? {
        val match = Regex("""(?:(\d{1,2}):)?(\d{2}):(\d{2})(?:\.(\d{1,3}))?""")
            .matchEntire(raw.orEmpty()) ?: return null
        val hours = match.groupValues[1].toLongOrNull() ?: 0L
        val minutes = match.groupValues[2].toLong()
        val seconds = match.groupValues[3].toLong()
        if (minutes > 59 || seconds > 59) return null
        val totalMinutes = hours * 60L + minutes
        if (totalMinutes > 999L) return null
        val ms = match.groupValues[4].padEnd(3, '0').toIntOrNull() ?: 0
        return "[%02d:%02d.%03d]".format(Locale.ROOT, totalMinutes, seconds, ms)
    }
}
