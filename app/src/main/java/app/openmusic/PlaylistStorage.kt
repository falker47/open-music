package app.openmusic

import android.content.Context
import android.util.AtomicFile
import app.openmusic.core.PortablePlaylist
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets
import java.time.Instant

/** Local-only deletion markers are outside portable JSON v1. */
internal data class DeletedPlaylist(
    val playlistId: String,
    val name: String,
    val deletedAt: String,
)
internal data class PlaylistLocalState(
    val playlists: List<PortablePlaylist> = emptyList(),
    val deleted: List<DeletedPlaylist> = emptyList(),
)

/** Private atomic snapshot, including local deletions that must never sync back as imports. */
class PlaylistStorage(context: Context) {
    private val storage = AtomicFile(File(context.filesDir, "open-music-playlists.json"))

    internal fun loadState(): PlaylistLocalState {
        if (!storage.baseFile.exists()) return PlaylistLocalState()
        val json = JSONObject(storage.openRead().bufferedReader(StandardCharsets.UTF_8)
            .use { it.readText() })
        require(json.optInt("storage_version") == 1) { "Archivio playlist incompatibile" }
        val lists = json.getJSONArray("playlists")
        val deleted = json.optJSONArray("deleted_playlists") ?: JSONArray()
        require(deleted.length() <= 10_000) { "Registro eliminazioni troppo grande" }
        return PlaylistLocalState(
            playlists = List(lists.length()) { PortableJson.fromObject(lists.getJSONObject(it)) },
            deleted = List(deleted.length()) {
                val record = deleted.getJSONObject(it)
                DeletedPlaylist(record.getString("playlistId"), record.getString("name"),
                    record.getString("deletedAt"))
            },
        ).also { state ->
            require(state.deleted.map { it.playlistId }.distinct().size == state.deleted.size) {
                "Registro eliminazioni duplicato"
            }
            require(state.playlists.none { list -> state.deleted.any { it.playlistId == list.playlistId } }) {
                "Playlist attive ed eliminate sovrapposte"
            }
        }
    }

    fun load(): List<PortablePlaylist> = loadState().playlists

    /** Ordinary edits preserve tombstones; explicit reimports of the same ID restore it. */
    fun save(playlists: List<PortablePlaylist>) {
        val activeIds = playlists.map { it.playlistId }.toSet()
        saveState(PlaylistLocalState(playlists,
            loadState().deleted.filterNot { it.playlistId in activeIds }))
    }

    internal fun deletePlaylist(id: String): PlaylistLocalState {
        val state = loadState()
        val victim = state.playlists.firstOrNull { it.playlistId == id } ?: return state
        val next = state.copy(
            playlists = state.playlists.filterNot { it.playlistId == id },
            deleted = state.deleted + DeletedPlaylist(id, victim.name, Instant.now().toString()),
        )
        saveState(next)
        return next
    }

    internal fun saveState(state: PlaylistLocalState) {
        require(state.playlists.map { it.playlistId }.distinct().size == state.playlists.size)
        val activeIds = state.playlists.map { it.playlistId }.toSet()
        require(state.deleted.none { it.playlistId in activeIds })
        val lists = JSONArray().also { arr ->
            state.playlists.forEach { arr.put(PortableJson.toObject(it)) }
        }
        val deleted = JSONArray().also { arr ->
            state.deleted.forEach {
                arr.put(JSONObject().put("playlistId", it.playlistId)
                    .put("name", it.name).put("deletedAt", it.deletedAt))
            }
        }
        val content = JSONObject().put("storage_version", 1)
            .put("playlists", lists).put("deleted_playlists", deleted)
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
