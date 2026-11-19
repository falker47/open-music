package app.openmusic

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope

/** Never called by playlist sync: drive.file is a distinct, explicit consent. */
private const val AUDIO_BACKUP_SCOPE = "https://www.googleapis.com/auth/drive.file"

@Composable
internal fun rememberAudioBackupDriveAccess(
    onAuthorized: (String) -> Unit,
    onError: (String) -> Unit,
): () -> Unit {
    val context = LocalContext.current
    val client = remember(context) { Identity.getAuthorizationClient(context) }
    val success by rememberUpdatedState(onAuthorized)
    val failure by rememberUpdatedState(onError)

    fun receive(result: AuthorizationResult) {
        if (result.grantedScopes.orEmpty().contains(AUDIO_BACKUP_SCOPE) &&
            !result.accessToken.isNullOrBlank()) success(result.accessToken!!)
        else failure("Autorizzazione Drive per backup audio non concessa")
    }

    val chooser = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { reply ->
        if (reply.resultCode != Activity.RESULT_OK || reply.data == null)
            failure("Autorizzazione backup audio annullata")
        else try {
            receive(client.getAuthorizationResultFromIntent(reply.data!!))
        } catch (_: Exception) {
            failure("Autorizzazione backup audio non riuscita")
        }
    }

    return {
        client.authorize(
            AuthorizationRequest.builder()
                .setRequestedScopes(listOf(Scope(AUDIO_BACKUP_SCOPE))).build())
            .addOnSuccessListener { result ->
                if (result.hasResolution()) {
                    val sender = result.pendingIntent?.intentSender
                    if (sender == null) failure("Account Drive non disponibile")
                    else chooser.launch(IntentSenderRequest.Builder(sender).build())
                } else receive(result)
            }
            .addOnFailureListener { failure("Google Drive backup non disponibile") }
    }
}
