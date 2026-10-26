package app.openmusic.core

/** A local MediaStore item stamp. Never exported with portable playlist JSON. */
data class AudioItemStamp(val uri: String, val sizeBytes: Long, val modifiedSeconds: Long)
data class ManualAudioOverride(val stamp: AudioItemStamp, val music: Boolean)

/** Two complementary UI views; matching always receives the unfiltered inventory. */
object AudioClassification {
    fun isMusic(
        automatic: Boolean,
        item: AudioItemStamp,
        overrides: Map<String, ManualAudioOverride>,
    ): Boolean = overrides[item.uri]?.takeIf { it.stamp == item }?.music ?: automatic

    fun set(
        existing: Map<String, ManualAudioOverride>,
        item: AudioItemStamp,
        music: Boolean,
    ): Map<String, ManualAudioOverride> = existing + (item.uri to ManualAudioOverride(item, music))

    fun reset(existing: Map<String, ManualAudioOverride>, uri: String) = existing - uri

    /** Removed/reused/modified MediaStore IDs must not inherit a manual decision. */
    fun retain(
        existing: Map<String, ManualAudioOverride>,
        scanned: Collection<AudioItemStamp>,
    ): Map<String, ManualAudioOverride> {
        val present = scanned.associateBy { it.uri }
        return existing.filter { (uri, override) -> present[uri] == override.stamp }
    }
}
