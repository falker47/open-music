package app.openmusic

import app.openmusic.core.PlaylistEntry
import app.openmusic.core.PortablePlaylist
import app.openmusic.core.TrackIdentity
import app.openmusic.core.TrackMetadata
import org.json.JSONArray
import org.json.JSONObject

/**
 * Compatible with the Python POC's portable_playlist schema v1.
 * Never serializes local MediaStore content URIs, absolute paths, credentials or audio bytes.
 */
object PortableJson {
    private fun stringOrNull(json: JSONObject, name: String): String? =
        if (!json.has(name) || json.isNull(name)) null else json.optString(name)
            .takeIf { it.isNotBlank() }

    private fun nullable(value: Any?): Any = value ?: JSONObject.NULL

    fun encode(playlist: PortablePlaylist): String = toObject(playlist).toString(2) + "\n"

    fun toObject(playlist: PortablePlaylist): JSONObject {
        val entries = JSONArray()
        playlist.tracks.forEach { entry ->
            val metadata = entry.identity.metadata
            entries.put(JSONObject().apply {
                put("entry_id", entry.entryId)
                put("identity", JSONObject().apply {
                    put("sha256", nullable(entry.identity.sha256))
                    put("filename_hint", nullable(entry.identity.filenameHint))
                    put("metadata", JSONObject().apply {
                        put("artist", nullable(metadata.artist))
                        put("title", nullable(metadata.title))
                        put("album", nullable(metadata.album))
                        put("duration_ms", nullable(metadata.durationMs))
                        put("source", metadata.source)
                    })
                })
            })
        }
        return JSONObject().apply {
            put("kind", "portable_playlist")
            put("schema_version", 1)
            put("playlist_id", playlist.playlistId)
            put("name", playlist.name)
            put("created_at", playlist.createdAt)
            put("updated_at", playlist.updatedAt)
            put("tracks", entries)
        }
    }

    fun decode(text: String): PortablePlaylist {
        require(text.toByteArray(Charsets.UTF_8).size <= 2_000_000) { "Playlist troppo grande" }
        return fromObject(JSONObject(text))
    }

    fun fromObject(json: JSONObject): PortablePlaylist {
        require(json.optString("kind") == "portable_playlist" && json.optInt("schema_version") == 1) {
            "Formato playlist non supportato"
        }
        val id = json.getString("playlist_id").trim()
        val name = json.getString("name").trim()
        require(id.isNotEmpty() && name.isNotEmpty() && name.length <= 160) { "ID o nome non valido" }
        val tracks = json.getJSONArray("tracks")
        require(tracks.length() <= 20_000) { "Troppi brani nella playlist" }
        val entries = buildList {
            repeat(tracks.length()) { i ->
                val entry = tracks.getJSONObject(i)
                val identity = entry.getJSONObject("identity")
                val meta = identity.getJSONObject("metadata")
                val hash = stringOrNull(identity, "sha256")?.lowercase()
                require(hash == null || Regex("[a-f0-9]{64}").matches(hash)) { "SHA-256 non valido" }
                val entryId = entry.getString("entry_id").trim()
                require(entryId.isNotEmpty()) { "entry_id non valido" }
                val source = meta.optString("source", "filename").takeIf { it == "tags" } ?: "filename"
                val duration = if (meta.isNull("duration_ms") || !meta.has("duration_ms")) null
                    else meta.getLong("duration_ms").takeIf { it >= 0L }
                val filename = stringOrNull(identity, "filename_hint")
                    ?.substringAfterLast('/')?.substringAfterLast('\\')
                add(PlaylistEntry(entryId, TrackIdentity(
                    hash,
                    TrackMetadata(
                        stringOrNull(meta, "artist"), stringOrNull(meta, "title"),
                        stringOrNull(meta, "album"), duration, source,
                    ),
                    filename,
                )))
            }
        }
        return PortablePlaylist(
            id, name, json.optString("created_at", ""),
            json.optString("updated_at", ""), entries,
        )
    }
}
