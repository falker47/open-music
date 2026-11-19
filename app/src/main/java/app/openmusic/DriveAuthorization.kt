package app.openmusic

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.google.android.gms.auth.api.identity.*
import com.google.android.gms.common.Scopes
import com.google.android.gms.common.api.Scope

@Composable
internal fun rememberDriveAccess(
    onAuthorized: (String) -> Unit,
    onError: (String) -> Unit,
): () -> Unit {
    val context = LocalContext.current
    val client = remember(context) { Identity.getAuthorizationClient(context) }
    val success by rememberUpdatedState(onAuthorized)
    val failure by rememberUpdatedState(onError)

    fun receive(result: AuthorizationResult) {
        val granted = result.grantedScopes.orEmpty()
        val access = result.accessToken.orEmpty()
        if (Scopes.DRIVE_APPFOLDER in granted && access.isNotBlank()) success(access)
        else failure("Autorizzazione Drive appData non concessa")
    }

    val chooser = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { reply ->
        val intent = reply.data
        if (reply.resultCode != Activity.RESULT_OK || intent == null) {
            failure("Autorizzazione cloud annullata")
        } else try {
            receive(client.getAuthorizationResultFromIntent(intent))
        } catch (_: Exception) {
            failure("Autorizzazione Google Drive non riuscita")
        }
    }

    return {
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(Scopes.DRIVE_APPFOLDER))).build()
        client.authorize(request)
            .addOnSuccessListener { result ->
                if (result.hasResolution()) {
                    val sender = result.pendingIntent?.intentSender
                    if (sender != null) chooser.launch(
                        IntentSenderRequest.Builder(sender).build())
                    else failure("Account Google non disponibile")
                } else receive(result)
            }
            .addOnFailureListener {
                failure("Google Drive non disponibile: verifica autorizzazioni OAuth")
            }
    }
}


/** Disconnect revokes the app's Drive grant; it never deletes remote or local playlists. */
@Composable
internal fun rememberDriveRevocation(
    onRevoked: () -> Unit,
    onError: (String) -> Unit,
): () -> Unit {
    val context = LocalContext.current
    val client = remember(context) { Identity.getAuthorizationClient(context) }
    val success by rememberUpdatedState(onRevoked)
    val failure by rememberUpdatedState(onError)
    return {
        val request = RevokeAccessRequest.builder()
            .setScopes(listOf(Scope(Scopes.DRIVE_APPFOLDER))).build()
        client.revokeAccess(request)
            .addOnSuccessListener { success() }
            .addOnFailureListener { failure("Impossibile scollegare Google Drive") }
    }
}
