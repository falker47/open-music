package app.openmusic.core

/** Private, local-only cache identity. Generation/version are scoped to a specific volume. */
data class DigestFingerprint(
    val uri: String,
    val volume: String,
    val sizeBytes: Long,
    val modifiedSeconds: Long,
    val generationModified: Long?,
    val mediaStoreVersion: String?,
)

object HashReusePolicy {
    /** Unknown metadata, API <30 or provider changes force a safe content re-read. */
    fun reusable(saved: DigestFingerprint, current: DigestFingerprint): Boolean =
        saved == current && current.uri.isNotBlank() && current.volume.isNotBlank() &&
            current.sizeBytes > 0 && current.modifiedSeconds > 0 &&
            current.generationModified != null && current.generationModified > 0 &&
            !current.mediaStoreVersion.isNullOrBlank()

    fun <T> retain(current: Map<String, T>, scannedUris: Set<String>): Map<String, T> =
        current.filterKeys { it in scannedUris }
}
