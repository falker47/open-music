package app.openmusic.core

/** Pure cloud-sync presentation state: color decisions never inspect localized text. */
enum class DriveUiTone { NEUTRAL, SUCCESS, ATTENTION, ERROR }
enum class DriveUiPhase {
    LOADING, CONFLICT, ERROR, DISCONNECTED, DELETION_PENDING, MISSING_LOCAL, PENDING, SYNCED
}

data class DriveUiStatus(
    val phase: DriveUiPhase,
    val label: String,
    val tone: DriveUiTone,
)

object DriveUiStatusResolver {
    fun resolve(
        localLoaded: Boolean,
        hasConflict: Boolean,
        syncError: String?,
        linked: Boolean,
        missingLocal: Boolean,
        deletionPending: Boolean,
        pendingChanges: Boolean,
    ): DriveUiStatus = when {
        !localLoaded -> DriveUiStatus(
            DriveUiPhase.LOADING, "Caricamento playlist", DriveUiTone.NEUTRAL)
        hasConflict -> DriveUiStatus(
            DriveUiPhase.CONFLICT, "Conflitto: scegli una versione", DriveUiTone.ERROR)
        syncError != null -> DriveUiStatus(
            DriveUiPhase.ERROR, syncError.ifBlank { "Errore di sincronizzazione" }, DriveUiTone.ERROR)
        !linked -> DriveUiStatus(
            DriveUiPhase.DISCONNECTED, "Non collegato", DriveUiTone.NEUTRAL)
        deletionPending -> DriveUiStatus(
            DriveUiPhase.DELETION_PENDING,
            "Eliminazione playlist da confermare su Drive", DriveUiTone.ATTENTION)
        missingLocal -> DriveUiStatus(
            DriveUiPhase.MISSING_LOCAL, "Playlist locali mancanti · verifica Drive", DriveUiTone.ATTENTION)
        pendingChanges -> DriveUiStatus(
            DriveUiPhase.PENDING, "Da sincronizzare", DriveUiTone.ATTENTION)
        else -> DriveUiStatus(
            DriveUiPhase.SYNCED, "Sincronizzato", DriveUiTone.SUCCESS)
    }
}
