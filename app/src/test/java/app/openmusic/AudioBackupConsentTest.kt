package app.openmusic

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AudioBackupConsentTest {
    @get:Rule val device = TemporaryFolder()
    private val consent get() = AudioBackupConsent(device.root)

    @Test fun cleanInstallAndLegacyUnboundConsentCannotStartAnAutomaticTransfer() {
        fun assertDisabled() {
            assertNull(consent.account())
            assertThrows(IOException::class.java) {
                consent.resolveFolder("account-A", "account-A", "folder-A", true) {
                    error("Unauthorised automatic backup reached Drive")
                }
            }
        }
        assertDisabled()
        device.newFile("open-music-audio-upload-consent-v1").writeText("enabled")
        assertDisabled()
    }

    @Test fun explicitlyConfirmedAccountSurvivesReopenAndRevocationStopsIt() {
        consent.grant("account-A")
        assertEquals("account-A", consent.account())
        assertEquals("folder-A", consent.resolveFolder("account-A", "account-A", "folder-A", true) { it })
        consent.revoke()
        assertThrows(IOException::class.java) {
            consent.resolveFolder("account-A", "account-A", "folder-A", true) {
                error("Revoked consent reached Drive")
            }
        }
    }

    @Test fun wrongAccountCannotListCreateOrReuseTheSavedFolder() {
        consent.grant("account-A")
        for (automatic in listOf(false, true)) {
            assertThrows(BackupAccountMismatch::class.java) {
                consent.resolveFolder("account-B", "account-A", "folder-A", automatic) {
                    error("Wrong account reached Drive")
                }
            }
        }
        // Even after rebinding to B or losing the binding, A's consent is unusable.
        for (bound in listOf(null, "account-B")) {
            assertThrows(IOException::class.java) {
                consent.resolveFolder("account-B", bound, null, true) {
                    error("Transferred consent between accounts")
                }
            }
        }
    }

    @Test fun manualDiscoveryWorksWithoutGrantingFutureAutomaticTransfers() {
        assertEquals("found", consent.resolveFolder("account-B", null, null, false) {
            assertNull(it)
            "found"
        })
        assertNull(consent.account())
    }
}
