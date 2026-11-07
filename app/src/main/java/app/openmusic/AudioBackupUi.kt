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
import app.openmusic.core.AudioClassification
import app.openmusic.core.ManualAudioOverride
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Contextual actions only: no new home screen or automatic background upload. */
internal data class AudioBackupActions(
    val onSelectForBackup: () -> Unit,
    val onBackupLibrary: () -> Unit,
    val onBrowseRestores: () -> Unit,
    val onDisableAutoBackup: () -> Unit,
    val autoBackupEnabled: Boolean,
    val busy: Boolean,
)

private enum class BackupCommand {
    NONE, UPLOAD, UPLOAD_LIBRARY, AUTO_UPLOAD, LIST_REMOTE, STAGE_RESTORE, RESTORE_LIBRARY
}

private fun librarySignature(items: List<AudioFile>): String {
    val fields = items.sortedBy { it.uri }.joinToString("\u0000") { item ->
        item.uri + ":" + item.sizeBytes + ":" + item.modifiedSeconds
    }
    return MessageDigest.getInstance("SHA-256")
        .digest(fields.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

@Composable
internal fun rememberAudioBackupActions(
    audioFiles: List<AudioFile>,
    manualKinds: Map<String, ManualAudioOverride>,
    scanGeneration: Int,
    cloudLinked: Boolean,
    onMessage: (String) -> Unit,
): AudioBackupActions {
    val context = LocalContext.current
    val latestMessage by rememberUpdatedState(onMessage)
    val scope = rememberCoroutineScope()
    val preferences = remember(context) {
        context.getSharedPreferences("open-music-audio-library-sync-v1", 0)
    }
    val libraryMusic = remember(audioFiles, manualKinds) {
        audioFiles.filter { file ->
            AudioClassification.isMusic(file.isMusic, file.stamp(), manualKinds)
        }.distinctBy { it.uri }
    }
    val libraryHash = remember(libraryMusic) { librarySignature(libraryMusic) }
    // noBackupFilesDir does not migrate to a new device with Android Auto Backup:
    // an old SharedPreferences toggle alone is NOT consent to upload that device's audio.
    val consentMarker = remember(context) {
        java.io.File(context.noBackupFilesDir, "open-music-audio-upload-consent-v1")
    }
    var autoEnabled by remember {
        mutableStateOf(preferences.getBoolean("uploadOnScan", false) && consentMarker.isFile)
    }
    var enableAutoAfterUpload by remember { mutableStateOf(false) }
    var libraryMode by remember { mutableStateOf(false) }
    var restoreAllTree by remember { mutableStateOf<Uri?>(null) }
    var confirmRestoreAll by remember { mutableStateOf(false) }
    var savedLibraryHash by remember { mutableStateOf("") }
    var lastAutoAttempt by remember { mutableIntStateOf(-1) }

    var selectedUris by remember { mutableStateOf(emptyList<Uri>()) }
    var allowMetered by remember { mutableStateOf(false) }
    var confirmUpload by remember { mutableStateOf(false) }
    var showRemote by remember { mutableStateOf(false) }
    var remoteFiles by remember { mutableStateOf(emptyList<AudioBackupFile>()) }
    var selectedRemote by remember { mutableStateOf<AudioBackupFile?>(null) }
    var stagedTemp by remember { mutableStateOf<java.io.File?>(null) }
    var action by remember { mutableStateOf(BackupCommand.NONE) }
    var busy by remember { mutableStateOf(false) }
    var authorizing by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf("") }
    var pendingJob by remember { mutableStateOf<Job?>(null) }

    val treePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            restoreAllTree = uri
            allowMetered = false
            confirmRestoreAll = true
        }
    }

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
            authorizing = false
            if (requested != BackupCommand.NONE && !busy) {
                pendingJob = scope.launch {
                    busy = true
                    try {
                        val flow = AudioBackupWorkflow(context, DriveAudioBackupGateway(token))
                        when (requested) {
                            BackupCommand.UPLOAD, BackupCommand.UPLOAD_LIBRARY,
                            BackupCommand.AUTO_UPLOAD -> {
                                progress = if (requested == BackupCommand.UPLOAD)
                                    "Preparazione backup selezionato"
                                    else "Preparazione libreria musicale"
                                val summary = if (requested == BackupCommand.UPLOAD)
                                    flow.backupSelected(selectedUris, allowMetered) {
                                        done, total -> progress = "Backup audio: " + done + "/" + total
                                    }
                                else flow.backupLibrary(selectedUris, allowMetered) {
                                    done, total -> progress = "Backup libreria: " + done + "/" + total
                                }
                                if (requested != BackupCommand.UPLOAD) {
                                    if (requested == BackupCommand.UPLOAD_LIBRARY) {
                                        if (enableAutoAfterUpload) {
                                            runCatching { consentMarker.writeText("enabled") }
                                        } else consentMarker.delete()
                                        autoEnabled = enableAutoAfterUpload && consentMarker.isFile
                                    }
                                    preferences.edit()
                                        .putBoolean("uploadOnScan", autoEnabled)
                                        .putString("lastSuccessfulLibraryHash", savedLibraryHash)
                                        .apply()
                                }
                                latestMessage("Backup: " + summary.uploaded +
                                    " caricati, " + summary.deduplicated + " già presenti")
                                selectedUris = emptyList()
                                libraryMode = false
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
                            BackupCommand.RESTORE_LIBRARY -> {
                                val directory = restoreAllTree
                                    ?: throw IllegalStateException("Cartella non selezionata")
                                progress = "Ripristino della libreria musicale"
                                val summary = flow.restoreLibrary(remoteFiles, directory, allowMetered) {
                                    done, total ->
                                    progress = "Ripristino libreria: " + done + "/" + total
                                }
                                latestMessage("Ripristino: " + summary.downloaded +
                                    " scaricati, " + summary.alreadyPresent +
                                    " già presenti. Scansiona la musica per ricollegare le playlist.")
                                restoreAllTree = null
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
        onError = {
            error ->
            action = BackupCommand.NONE
            authorizing = false
            latestMessage(error)
        },
    )

    fun start(command: BackupCommand) {
        if (busy || authorizing) return
        authorizing = true
        action = command
        authorization()
    }

    // Consent is persisted; after a future successful MediaStore scan an
    // opted-in user can upload new or modified tracks while the app is open.
    // Network remains Wi-Fi/unmetered only, without storing OAuth tokens.
    LaunchedEffect(scanGeneration, autoEnabled, cloudLinked) {
        if (scanGeneration > 0 && autoEnabled && cloudLinked &&
            lastAutoAttempt != scanGeneration &&
            libraryMusic.size in 1..5000 &&
            preferences.getString("lastSuccessfulLibraryHash", null) != libraryHash &&
            !busy && !authorizing) {
            lastAutoAttempt = scanGeneration
            selectedUris = libraryMusic.map { Uri.parse(it.uri) }
            savedLibraryHash = libraryHash
            allowMetered = false
            libraryMode = true
            start(BackupCommand.AUTO_UPLOAD)
        }
    }

    val selector = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.size > 40) latestMessage("Seleziona al massimo 40 file per backup")
        else if (uris.isNotEmpty()) {
            selectedUris = uris
            libraryMode = false
            allowMetered = false
            confirmUpload = true
        }
    }

    if (confirmUpload) {
        AlertDialog(
            onDismissRequest = {
                confirmUpload = false
                selectedUris = emptyList()
                libraryMode = false
            },
            title = { Text(if (libraryMode) "Backup dell'intera libreria" else
                "Backup audio su Google Drive") },
            text = {
                Column {
                    Text("Caricare " + selectedUris.size +
                        " file musicali " + (if (libraryMode) "della libreria"
                            else "selezionati") + " nella cartella Drive visibile Open Music Audio Backup? " +
                        "I file già presenti con lo stesso SHA-256 non verranno duplicati. " +
                        "I file utilizzano lo spazio del tuo Google Drive.")
                    if (libraryMode) {
                        Spacer(Modifier.height(8.dp))
                        Row {
                            Checkbox(checked = enableAutoAfterUpload,
                                onCheckedChange = { enableAutoAfterUpload = it })
                            Text("Carica automaticamente i nuovi brani quando apri o scansioni " +
                                "l'app (solo Wi-Fi)", modifier = Modifier
                                .clickable { enableAutoAfterUpload = !enableAutoAfterUpload }
                                .padding(top = 12.dp))
                        }
                    }
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
                    if (libraryMode) {
                        savedLibraryHash = libraryHash
                        start(BackupCommand.UPLOAD_LIBRARY)
                    } else start(BackupCommand.UPLOAD)
                }) { Text("Carica file") }
            },
            dismissButton = {
                TextButton(onClick = {
                    confirmUpload = false
                    selectedUris = emptyList()
                    libraryMode = false
                }) { Text("Annulla") }
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
                                    start(BackupCommand.STAGE_RESTORE)
                                }
                                .padding(vertical = 14.dp),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showRemote = false
                    treePicker.launch(null)
                }) { Text("Ripristina tutti") }
            },
            dismissButton = {
                TextButton(onClick = { showRemote = false }) { Text("Chiudi") }
            },
        )
    }

    if (confirmRestoreAll) {
        AlertDialog(
            onDismissRequest = { confirmRestoreAll = false; restoreAllTree = null },
            title = { Text("Ripristina tutti i brani") },
            text = {
                Column {
                    Text("Scaricare " + remoteFiles.size +
                        " file nella cartella Android selezionata? Le copie esistenti non " +
                        "verranno sovrascritte. Il download usa spazio locale e traffico dati.")
                    Spacer(Modifier.height(12.dp))
                    Row {
                        Checkbox(checked = allowMetered, onCheckedChange = { allowMetered = it })
                        Text("Consenti anche reti mobili/a consumo",
                            modifier = Modifier.clickable { allowMetered = !allowMetered }
                                .padding(top = 12.dp))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmRestoreAll = false
                    start(BackupCommand.RESTORE_LIBRARY)
                }) { Text("Scarica tutti") }
            },
            dismissButton = {
                TextButton(onClick = { confirmRestoreAll = false; restoreAllTree = null }) {
                    Text("Annulla")
                }
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
        onSelectForBackup = {
            if (!busy && !authorizing) selector.launch(arrayOf("audio/*"))
        },
        onBackupLibrary = {
            if (!busy && !authorizing) {
                if (libraryMusic.isEmpty()) latestMessage("Scansiona prima la libreria Musica")
                else if (libraryMusic.size > 5000)
                    latestMessage("Backup intera libreria limitato a 5000 brani per volta")
                else {
                    selectedUris = libraryMusic.map { Uri.parse(it.uri) }
                    savedLibraryHash = libraryHash
                    libraryMode = true
                    enableAutoAfterUpload = autoEnabled
                    allowMetered = false
                    confirmUpload = true
                }
            }
        },
        onBrowseRestores = {
            if (!busy && !authorizing) start(BackupCommand.LIST_REMOTE)
        },
        onDisableAutoBackup = {
            autoEnabled = false
            consentMarker.delete()
            preferences.edit().putBoolean("uploadOnScan", false).apply()
            latestMessage("Caricamento automatico dei nuovi brani disattivato")
        },
        autoBackupEnabled = autoEnabled,
        busy = busy || authorizing,
    )
}
