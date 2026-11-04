package app.openmusic

import app.openmusic.core.AudioBackupFile
import app.openmusic.core.AudioBackupManifest
import app.openmusic.core.AudioBackupPlanner
import org.json.JSONArray
import org.json.JSONObject

/** Versioned inventory for visible Drive storage: never export local URIs or tokens. */
internal object AudioBackupManifestCodec {
    fun encode(manifest: AudioBackupManifest): String {
        AudioBackupPlanner.requireValid(manifest, manifest.folderId, manifest.accountMarker)
        val files = JSONArray()
        manifest.files.forEach { file ->
            files.put(JSONObject()
                .put("name", file.name)
                .put("sha256", file.sha256)
                .put("sizeBytes", file.sizeBytes)
                .put("mimeType", file.mimeType)
                .put("remoteFileId", file.remoteFileId))
        }
        return JSONObject()
            .put("version", manifest.version)
            .put("folderId", manifest.folderId)
            .put("accountMarker", manifest.accountMarker)
            .put("files", files).toString()
    }

    fun decode(source: String, folderId: String, accountMarker: String): AudioBackupManifest {
        require(source.length <= 2_500_000) { "Oversized audio backup manifest" }
        val json = JSONObject(source)
        val array = json.getJSONArray("files")
        require(array.length() <= 50_000) { "Oversized audio backup inventory" }
        val records = (0 until array.length()).map { i ->
            val item = array.getJSONObject(i)
            AudioBackupFile(
                name = item.getString("name"),
                sha256 = item.getString("sha256"),
                sizeBytes = item.getLong("sizeBytes"),
                mimeType = item.getString("mimeType"),
                remoteFileId = item.getString("remoteFileId"),
            )
        }
        val manifest = AudioBackupManifest(
            version = json.getInt("version"),
            folderId = json.getString("folderId"),
            accountMarker = json.getString("accountMarker"),
            files = records)
        AudioBackupPlanner.requireValid(manifest, folderId, accountMarker)
        return manifest
    }
}
