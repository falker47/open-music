package app.openmusic

import android.content.Context
import android.util.AtomicFile
import app.openmusic.core.AudioItemStamp
import app.openmusic.core.ManualAudioOverride
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * App-private manual classification. Not part of the portable playlist schema and
 * never changes, moves or deletes the underlying audio content.
 */
internal class ManualAudioStorage(context: Context) {
    private val storage = AtomicFile(File(context.filesDir, "open-music-audio-categories.json"))

    fun load(): Map<String, ManualAudioOverride> {
        if (!storage.baseFile.exists()) return emptyMap()
        val root = JSONObject(storage.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() })
        require(root.optInt("version") == 1) { "Archivio classificazioni incompatibile" }
        val items = root.getJSONArray("items")
        val result = mutableMapOf<String, ManualAudioOverride>()
        repeat(items.length()) { i ->
            val item = items.getJSONObject(i)
            val uri = item.getString("uri")
            if (!uri.startsWith("content://media/")) return@repeat
            val stamp = AudioItemStamp(uri, item.getLong("size"), item.getLong("modified"))
            result[uri] = ManualAudioOverride(stamp, item.getBoolean("music"))
        }
        return result
    }

    fun save(overrides: Map<String, ManualAudioOverride>) {
        val items = JSONArray()
        overrides.values.forEach { entry ->
            items.put(JSONObject()
                .put("uri", entry.stamp.uri)
                .put("size", entry.stamp.sizeBytes)
                .put("modified", entry.stamp.modifiedSeconds)
                .put("music", entry.music))
        }
        val bytes = JSONObject().put("version", 1).put("items", items)
            .toString().toByteArray(Charsets.UTF_8)
        val stream = storage.startWrite()
        try {
            stream.write(bytes)
            storage.finishWrite(stream)
        } catch (error: Exception) {
            storage.failWrite(stream)
            throw error
        }
    }
}
