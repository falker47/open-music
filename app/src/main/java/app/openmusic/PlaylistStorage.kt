package app.openmusic

import android.content.Context
import android.util.AtomicFile
import app.openmusic.core.PortablePlaylist
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets

/** Application-private and atomic local playlist persistence. No cloud access or secrets. */
class PlaylistStorage(context: Context) {
    private val storage = AtomicFile(File(context.filesDir, "open-music-playlists.json"))

    fun load(): List<PortablePlaylist> {
        if (!storage.baseFile.exists()) return emptyList()
        val json = JSONObject(storage.openRead().bufferedReader(StandardCharsets.UTF_8).use { it.readText() })
        require(json.optInt("storage_version") == 1) { "Archivio playlist incompatibile" }
        val array = json.getJSONArray("playlists")
        return List(array.length()) { index -> PortableJson.fromObject(array.getJSONObject(index)) }
    }

    fun save(playlists: List<PortablePlaylist>) {
        val array = JSONArray()
        playlists.forEach { array.put(PortableJson.toObject(it)) }
        val content = JSONObject().put("storage_version", 1).put("playlists", array)
            .toString().toByteArray(StandardCharsets.UTF_8)
        val stream = storage.startWrite()
        try {
            stream.write(content)
            storage.finishWrite(stream)
        } catch (failure: Exception) {
            storage.failWrite(stream)
            throw failure
        }
    }
}
