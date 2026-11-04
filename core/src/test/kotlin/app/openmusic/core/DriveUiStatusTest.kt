package app.openmusic.core

import org.junit.Assert.*
import org.junit.Test

class DriveUiStatusTest {
    private fun status(
        loaded: Boolean = true, conflict: Boolean = false, error: String? = null,
        linked: Boolean = true, missing: Boolean = false, deletion: Boolean = false,
        pending: Boolean = false,
    ) = DriveUiStatusResolver.resolve(
        loaded, conflict, error, linked, missing, deletion, pending)

    @Test fun linkedAndCleanIsOnlySuccessState() {
        assertEquals(DriveUiTone.SUCCESS, status().tone)
        assertEquals(DriveUiPhase.SYNCED, status().phase)
        assertEquals("Sincronizzato", status().label)
    }

    @Test fun unsyncedAndDeletionPendingRequireAttention() {
        assertEquals(DriveUiTone.ATTENTION, status(pending = true).tone)
        assertEquals(DriveUiPhase.DELETION_PENDING, status(missing = true, deletion = true).phase)
        assertEquals(DriveUiTone.ATTENTION, status(missing = true).tone)
    }

    @Test fun errorsAndConflictsCannotLookSuccessful() {
        assertEquals(DriveUiTone.ERROR, status(error = "Drive HTTP 401").tone)
        assertEquals("Drive HTTP 401", status(error = "Drive HTTP 401").label)
        assertEquals(DriveUiTone.ERROR, status(conflict = true, pending = true).tone)
        assertEquals(DriveUiPhase.CONFLICT, status(conflict = true, error = "bad").phase)
    }

    @Test fun loadingAndDisconnectedAreNeutralEvenWithPendingLocalChanges() {
        assertEquals(DriveUiPhase.LOADING, status(loaded = false, pending = true).phase)
        assertEquals(DriveUiTone.NEUTRAL, status(loaded = false).tone)
        assertEquals(DriveUiPhase.DISCONNECTED, status(linked = false, pending = true).phase)
        assertEquals(DriveUiTone.NEUTRAL, status(linked = false).tone)
    }

    @Test fun statusIsNotInferredFromText() {
        assertEquals(DriveUiTone.ERROR, status(error = "Sincronizzato").tone)
        assertEquals(DriveUiTone.ERROR, status(error = "").tone)
    }
}
