package app.openmusic

import android.content.Context
import android.util.AtomicFile
import app.openmusic.core.CloudCheckpoint
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** The journal contains only non-secret revision metadata, never access tokens or audio. */
internal data class CloudJournal(
    val binding: String? = null,
    val checkpoints: Map<String, CloudCheckpoint> = emptyMap(),
)

internal class CloudJournalStorage(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "open-music-cloud-journal.json"))

    fun load(): CloudJournal {
        if (!file.baseFile.exists()) return CloudJournal()
        val root = JSONObject(file.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() })
        require(root.optInt("schema") == 1) { "Registro cloud incompatibile" }
        val items = root.optJSONArray("checkpoints") ?: JSONArray()
        require(items.length() <= 10_000) { "Registro cloud troppo grande" }
        val points = mutableMapOf<String, CloudCheckpoint>()
        repeat(items.length()) { i ->
            val point = items.getJSONObject(i)
            val id = point.getString("playlistId")
            require(id.isNotBlank() && !points.containsKey(id)) { "Registro cloud non valido" }
            points[id] = CloudCheckpoint(point.getString("revisionId"), point.getString("digest"))
        }
        return CloudJournal(if (root.isNull("binding")) null else root.optString("binding").takeIf { it.isNotBlank() }, points)
    }

    fun save(journal: CloudJournal) {
        val items = JSONArray()
        journal.checkpoints.forEach { (playlistId, point) ->
            items.put(JSONObject().put("playlistId", playlistId)
                .put("revisionId", point.revisionId).put("digest", point.playlistDigest))
        }
        val bytes = JSONObject().put("schema", 1)
            .put("binding", journal.binding ?: JSONObject.NULL)
            .put("checkpoints", items).toString().toByteArray(Charsets.UTF_8)
        val stream = file.startWrite()
        try {
            stream.write(bytes)
            file.finishWrite(stream)
        } catch (failure: Exception) {
            file.failWrite(stream)
            throw failure
        }
    }

    /** Forget the account binding only on explicit disconnect; local playlists remain. */
    fun disconnect() = save(CloudJournal())
}
