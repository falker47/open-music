package app.openmusic

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.openmusic.core.AudioBackupFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Contextual actions only: no new home screen or automatic background upload. */
internal data class AudioBackupActions(
    val onSelectForBackup: () -> Unit,
    val onBrowseRestores: () -> Unit,
    val busy: Boolean,
)

private enum class BackupCommand { NONE, UPLOAD, LIST_REMOTE, STAGE_RESTORE }

@Composable
internal fun rememberAudioBackupActions(
    onMessage: (String) -> Unit,
): AudioBackupActions {
    val context = LocalContext.current
    val latestMessage by rememberUpdatedState(onMessage)
    val scope = rememberCoroutineScope()

    var selectedUris by remember { mutableStateOf(emptyList<Uri>()) }
    var allowMetered by remember { mutableStateOf(false) }
    var confirmUpload by remember { mutableStateOf(false) }
    var showRemote by remember { mutableStateOf(false) }
    var remoteFiles by remember { mutableStateOf(emptyList<AudioBackupFile>()) }
    var selectedRemote by remember { mutableStateOf<AudioBackupFile?>(null) }
    var stagedTemp by remember { mutableStateOf<java.io.File?>(null) }
    var action by remember { mutableStateOf(BackupCommand.NONE) }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf("") }
    var pendingJob by remember { mutableStateOf<Job?>(null) }

    val destinationPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { destination ->
        val temporary = stagedTemp
        val selected = selectedRemote
        stagedTemp = null
        selectedRemote = null
        if (destination == null || temporary == null || selected == null) {
            temporary?.delete()
        } else {
            pendingJob = scope.launch {
                busy = true
                progress = "Verifica e salvataggio del brano"
                try {
                    AudioBackupWorkflow(context, DriveAudioBackupGateway(""))
                        .restoreToDocument(selected, temporary, destination)
                    latestMessage("Brano ripristinato. Scansiona la libreria per ricollegare le playlist.")
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (error: Exception) {
                    latestMessage("Ripristino non riuscito: " +
                        (error.localizedMessage ?: "errore"))
                } finally {
                    busy = false
                    pendingJob = null
                    temporary.delete()
                }
            }
        }
    }

    val authorization = rememberAudioBackupDriveAccess(
        onAuthorized = { token ->
            val requested = action
            action = BackupCommand.NONE
            if (requested != BackupCommand.NONE && !busy) {
                pendingJob = scope.launch {
                    busy = true
                    try {
                        val flow = AudioBackupWorkflow(context, DriveAudioBackupGateway(token))
                        when (requested) {
                            BackupCommand.UPLOAD -> {
                                progress = "Preparazione backup selezionato"
                                val summary = flow.backupSelected(selectedUris, allowMetered) {
                                        done, total -> progress = "Backup audio: " + done + "/" + total
                                }
                                latestMessage("Backup: " + summary.uploaded +
                                    " caricati, " + summary.deduplicated + " già presenti")
                                selectedUris = emptyList()
                            }
                            BackupCommand.LIST_REMOTE -> {
                                progress = "Lettura backup audio da Drive"
                                val items = flow.available()
                                if (items.isEmpty()) latestMessage("Nessun audio salvato su Drive")
                                else {
                                    remoteFiles = items.sortedBy { it.name.lowercase() }
                                    showRemote = true
                                }
                            }
                            BackupCommand.STAGE_RESTORE -> {
                                val file = selectedRemote
                                    ?: throw IllegalStateException("Brano non selezionato")
                                progress = "Download e verifica del brano"
                                val temporary = flow.stageRestore(file)
                                stagedTemp = temporary
                                destinationPicker.launch(file.name)
                            }
                            BackupCommand.NONE -> Unit
                        }
                    } catch (cancel: CancellationException) {
                        latestMessage("Trasferimento interrotto: i file già caricati restano su Drive")
                        throw cancel
                    } catch (error: Exception) {
                        latestMessage("Backup audio: " +
                            (error.localizedMessage ?: "operazione non riuscita"))
                    } finally {
                        busy = false
                        pendingJob = null
                    }
                }
            }
        },
        onError = { error -> action = BackupCommand.NONE; latestMessage(error) },
    )

    val selector = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.size > 40) latestMessage("Seleziona al massimo 40 file per backup")
        else if (uris.isNotEmpty()) {
            selectedUris = uris
            allowMetered = false
            confirmUpload = true
        }
    }

    if (confirmUpload) {
        AlertDialog(
            onDismissRequest = { confirmUpload = false; selectedUris = emptyList() },
            title = { Text("Backup audio su Google Drive") },
            text = {
                Column {
                    Text("Caricare " + selectedUris.size +
                        " file musicali selezionati nella cartella Drive visibile Open Music Audio Backup? " +
                        "I file già presenti con lo stesso SHA-256 non verranno duplicati.")
                    Spacer(Modifier.height(12.dp))
                    Row {
                        Checkbox(
                            checked = allowMetered,
                            onCheckedChange = { allowMetered = it },
                        )
                        Text(
                            "Consenti dati mobili o altre reti a consumo",
                            modifier = Modifier.clickable { allowMetered = !allowMetered }
                                .padding(top = 12.dp),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmUpload = false
                    action = BackupCommand.UPLOAD
                    authorization()
                }) { Text("Carica file") }
            },
            dismissButton = {
                TextButton(onClick = { confirmUpload = false; selectedUris = emptyList() }) {
                    Text("Annulla")
                }
            },
        )
    }

    if (showRemote) {
        AlertDialog(
            onDismissRequest = { showRemote = false },
            title = { Text("Ripristina un brano") },
            text = {
                LazyColumn(modifier = Modifier.heightIn(max = 310.dp)) {
                    items(remoteFiles, key = { it.remoteFileId.orEmpty() }) { file ->
                        Text(
                            file.name,
                            modifier = Modifier.fillMaxWidth()
                                .clickable {
                                    showRemote = false
                                    selectedRemote = file
                                    action = BackupCommand.STAGE_RESTORE
                                    authorization()
                                }
                                .padding(vertical = 14.dp),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showRemote = false }) { Text("Chiudi") }
            },
        )
    }

    if (busy) {
        AlertDialog(
            onDismissRequest = { },
            title = { Text("Trasferimento audio") },
            text = {
                Column {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Spacer(Modifier.height(10.dp))
                    Text(progress)
                }
            },
            confirmButton = {
                TextButton(onClick = { pendingJob?.cancel() }) { Text("Annulla") }
            },
        )
    }

    return AudioBackupActions(
        onSelectForBackup = { if (!busy) selector.launch(arrayOf("audio/*")) },
        onBrowseRestores = {
            if (!busy) {
                action = BackupCommand.LIST_REMOTE
                authorization()
            }
        },
        busy = busy,
    )
}
