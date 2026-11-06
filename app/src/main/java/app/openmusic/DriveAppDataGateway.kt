package app.openmusic

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID

/** Google Drive appDataFolder only. Tokens are held in memory for one user-triggered sync. */
internal class DriveAppDataGateway(private val accessToken: String) {
    data class FileMeta(val id: String, val name: String, val description: String?)
    private val base = "https://www.googleapis.com/drive/v3/files"
    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun request(
        url: String, method: String = "GET", contentType: String? = null,
        payload: ByteArray? = null, limit: Int = 2_500_000,
    ): ByteArray {
        require(accessToken.isNotBlank()) { "Autorizzazione Google Drive assente" }
        require(url.startsWith("https://www.googleapis.com/")) { "URL Drive non valido" }
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Authorization", "Bearer $accessToken")
            connection.setRequestProperty("Accept", "application/json")
            if (payload != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", contentType ?: "application/json")
                connection.setFixedLengthStreamingMode(payload.size)
                connection.outputStream.use { it.write(payload) }
            }
            val status = connection.responseCode
            if (status !in 200..299) {
                val retry = if (status == 429 || status >= 500)
                    " · Riprova quando la rete è disponibile" else ""
                throw IOException("Google Drive HTTP $status$retry")
            }
            val out = ByteArrayOutputStream()
            connection.inputStream.use { stream ->
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val bytes = stream.read(buffer)
                    if (bytes < 0) break
                    if (out.size() + bytes > limit)
                        throw IOException("Risposta cloud troppo grande")
                    out.write(buffer, 0, bytes)
                }
            }
            return out.toByteArray()
        } finally {
            connection.disconnect()
        }
    }

    /** Metadata-only listing is paginated; no audio data is read from Drive. */
    fun listFiles(): List<FileMeta> {
        val found = mutableListOf<FileMeta>()
        var pageToken: String? = null
        do {
            val args = listOf(
                "spaces=appDataFolder", "pageSize=1000",
                "q=" + encode("name contains 'om-sync-v1-' and trashed = false"),
                "fields=" + encode("nextPageToken,files(id,name,description)"),
            ) + listOfNotNull(pageToken?.let { "pageToken=" + encode(it) })
            val json = JSONObject(String(request("$base?" + args.joinToString("&")), Charsets.UTF_8))
            val records = json.optJSONArray("files")
                ?: throw IOException("Elenco cloud non valido")
            repeat(records.length()) { i ->
                val file = records.getJSONObject(i)
                found.add(FileMeta(
                    file.getString("id"), file.getString("name"),
                    file.optString("description").takeIf { it.isNotBlank() },
                ))
            }
            if (found.size > 10_000) throw IOException("Troppe revisioni cloud")
            pageToken = json.optString("nextPageToken").takeIf { it.isNotBlank() }
        } while (pageToken != null)
        return found
    }

    fun download(id: String): String {
        require(id.isNotBlank() && id.length < 400) { "Identificativo cloud non valido" }
        return String(request(base + "/" + encode(id) + "?alt=media"), Charsets.UTF_8)
    }

    /** Append-only multipart upload: remote history is never overwritten. */
    fun upload(name: String, description: String, body: String): String {
        require(name.startsWith("om-sync-v1-") && name.endsWith(".json")) {
            "Nome cloud non valido"
        }
        val metadata = JSONObject()
            .put("name", name)
            .put("description", description)
            .put("mimeType", "application/json")
            .put("parents", org.json.JSONArray().put("appDataFolder"))
        val boundary = "openmusic-" + UUID.randomUUID().toString()
        val output = ByteArrayOutputStream()
        fun append(text: String) { output.write(text.toByteArray(Charsets.UTF_8)) }
        append("--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n")
        append(metadata.toString())
        append("\r\n--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n")
        append(body)
        append("\r\n--$boundary--\r\n")
        if (output.size() > 2_500_000) throw IOException("Metadati playlist troppo grandi")
        val reply = request(
            "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id",
            "POST", "multipart/related; boundary=$boundary", output.toByteArray(),
        )
        return JSONObject(String(reply, Charsets.UTF_8)).getString("id")
    }
}
