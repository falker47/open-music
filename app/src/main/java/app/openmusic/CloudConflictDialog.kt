package app.openmusic

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Explicit choice: conflicting snapshots are never overwritten by arrival time. */
@Composable
internal fun CloudConflictDialog(
    conflict: CloudConflict,
    onResolve: (CloudResolution) -> Unit,
    onCancel: () -> Unit,
) {
    var selectedRemote by remember(conflict.playlistId, conflict.remoteHeads) {
        mutableStateOf(conflict.remoteHeads.firstOrNull()?.revisionId)
    }
    val heads = conflict.remoteHeads.map { it.revisionId }.toSet()
    fun choose(choice: CloudConflictChoice) {
        onResolve(CloudResolution(choice, heads, selectedRemote))
    }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Conflitto playlist") },
        text = {
            Column(Modifier.heightIn(max = 350.dp).verticalScroll(rememberScrollState())) {
                Text(conflict.title, style = MaterialTheme.typography.titleMedium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(conflict.reason, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(12.dp))
                Text("Seleziona la versione cloud da usare:",
                    style = MaterialTheme.typography.bodyMedium)
                conflict.remoteHeads.forEachIndexed { index, head ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        RadioButton(
                            selected = selectedRemote == head.revisionId,
                            onClick = { selectedRemote = head.revisionId },
                        )
                        Text("Versione cloud " + (index + 1) + " · " +
                            head.revisionId.take(8),
                            modifier = Modifier.padding(top = 13.dp),
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
                Text("La cronologia sul cloud non viene cancellata. La scelta è esplicita.",
                    style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            Column {
                if (conflict.hasLocal) {
                    TextButton(onClick = { choose(CloudConflictChoice.KEEP_LOCAL) },
                        modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("Mantieni locale e pubblica")
                    }
                }
                if (selectedRemote != null) {
                    TextButton(onClick = { choose(CloudConflictChoice.USE_REMOTE) },
                        modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("Usa versione cloud")
                    }
                    if (conflict.hasLocal) {
                        TextButton(onClick = { choose(CloudConflictChoice.DUPLICATE_LOCAL) },
                            modifier = Modifier.heightIn(min = 48.dp)) {
                            Text("Mantieni entrambe (copia locale)")
                        }
                    }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text("Annulla sincronizzazione") }
        },
    )
}
