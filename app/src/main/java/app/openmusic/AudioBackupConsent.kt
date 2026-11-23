package app.openmusic

import java.io.File
import java.io.IOException

internal class BackupAccountMismatch : IOException("Account Drive diverso: backup non modificato")

/** Device-local consent for exactly one Drive permissionId. */
internal class AudioBackupConsent(noBackupDirectory: File) {
    private val marker = File(noBackupDirectory, "open-music-audio-upload-consent-v1")

    fun account(): String? {
        val value = try {
            marker.readText()
        } catch (_: IOException) {
            return null
        }
        // Older installs wrote "enabled", without identifying an account.
        if (!value.startsWith("account=") || !value.endsWith("\n")) return null
        return value.removePrefix("account=").dropLast(1).takeIf { it.isNotBlank() }
    }

    fun grant(account: String) {
        require(account.isNotBlank() && '\n' !in account)
        marker.writeText("account=$account\n")
    }

    fun revoke() {
        if (!marker.delete() && marker.exists())
            throw IOException("Impossibile disattivare il backup automatico")
    }

    fun resolveFolder(
        authorizedAccount: String,
        boundAccount: String?,
        savedFolder: String?,
        automatic: Boolean,
        lookup: (String?) -> String?,
    ): String? {
        if ((boundAccount != null && authorizedAccount != boundAccount) ||
            (savedFolder != null && boundAccount == null))
            throw BackupAccountMismatch()
        if (automatic && (boundAccount == null || account() != authorizedAccount))
            throw IOException("Backup automatico non autorizzato per questo account")
        return lookup(savedFolder)
    }
}
