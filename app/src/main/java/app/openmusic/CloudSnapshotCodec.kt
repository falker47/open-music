package app.openmusic

import app.openmusic.core.CloudPlaylistDigest
import app.openmusic.core.CloudRevision
import app.openmusic.core.PortablePlaylist
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Portable JSON v1 is nested unchanged; cloud-only revision metadata remains separate. */
internal object CloudSnapshotCodec {
    const val BINDING_NAME = "om-sync-v1-binding.json"
    private const val PREFIX = "om-sync-v1-"

    fun name(revisionId: String): String = "$PREFIX$revisionId.json"

    fun metadata(revision: CloudRevision): JSONObject = JSONObject()
        .put("schema", 1)
        .put("playlistId", revision.playlistId)
        .put("revisionId", revision.revisionId)
        .put("parents", JSONArray(revision.parents))
        .put("digest", revision.playlistDigest)
        .put("deleted", revision.deleted)

    fun parse(file: DriveAppDataGateway.FileMeta): CloudRevision {
        require(file.name.startsWith(PREFIX) && file.name != BINDING_NAME) {
            "Documento cloud non riconosciuto"
        }
        require(file.description != null && file.description.length <= 4096) {
            "Metadati cloud mancanti"
        }
        val json = JSONObject(file.description)
        require(json.optInt("schema") == 1) { "Versione cloud non supportata" }
        val playlistId = json.getString("playlistId")
        val revisionId = json.getString("revisionId")
        val digest = json.getString("digest")
        require(playlistId.isNotBlank() && playlistId.length <= 250) { "ID playlist cloud non valido" }
        require(revisionId == UUID.fromString(revisionId).toString() &&
            file.name == name(revisionId)) { "Revisione cloud non valida" }
        require(Regex("[a-f0-9]{64}").matches(digest)) { "Impronta cloud non valida" }
        val array = json.getJSONArray("parents")
        require(array.length() <= 20) { "Cronologia cloud troppo articolata" }
        val parents = List(array.length()) { array.getString(it) }
        require(parents.all { p -> p == UUID.fromString(p).toString() }) {
            "Cronologia cloud non valida"
        }
        return CloudRevision(playlistId, revisionId, parents, digest,
            json.optBoolean("deleted", false))
    }

    fun encode(revision: CloudRevision, playlist: PortablePlaylist): String {
        require(!revision.deleted && revision.playlistId == playlist.playlistId &&
            revision.playlistDigest == CloudPlaylistDigest.of(playlist)) {
            "Snapshot locale incongruente"
        }
        return JSONObject().put("schema", 1)
            .put("revision", metadata(revision))
            .put("playlist", PortableJson.toObject(playlist))
            .toString()
    }

    fun decode(content: String, expected: CloudRevision): PortablePlaylist {
        require(content.toByteArray(Charsets.UTF_8).size <= 2_000_000) {
            "Playlist cloud troppo grande"
        }
        val json = JSONObject(content)
        require(json.optInt("schema") == 1) { "Snapshot cloud non supportato" }
        val info = json.getJSONObject("revision")
        val actual = parse(DriveAppDataGateway.FileMeta(
            "_", name(expected.revisionId), info.toString(),
        ))
        require(actual == expected && !actual.deleted) { "Snapshot cloud alterato o differente" }
        val playlist = PortableJson.fromObject(json.getJSONObject("playlist"))
        require(playlist.playlistId == expected.playlistId &&
            CloudPlaylistDigest.of(playlist) == expected.playlistDigest) {
            "Impronta SHA-256 playlist cloud non corrispondente"
        }
        return playlist
    }

    fun binding(id: String): String = JSONObject()
        .put("schema", 1).put("binding", id).toString()

    fun decodeBinding(body: String): String {
        val json = JSONObject(body)
        require(json.optInt("schema") == 1) { "Account cloud incompatibile" }
        val id = json.getString("binding")
        require(id == UUID.fromString(id).toString()) { "Identità cloud non valida" }
        return id
    }
}
