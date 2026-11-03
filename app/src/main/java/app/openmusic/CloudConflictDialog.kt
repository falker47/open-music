package app.openmusic

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

/** One scrollable, vertically stacked dialog: actions never share AlertDialog's footer row. */
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
    Dialog(onDismissRequest = onCancel) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(
                Modifier.heightIn(max = 550.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                Text("Versioni diverse", style = MaterialTheme.typography.titleLarge)
                Text(conflict.title, style = MaterialTheme.typography.titleMedium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(conflict.reason, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (conflict.remoteHeads.isNotEmpty()) {
                    Text("Versioni su Drive", style = MaterialTheme.typography.labelLarge)
                    conflict.remoteHeads.forEachIndexed { index, head ->
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                .clickable { selectedRemote = head.revisionId },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = selectedRemote == head.revisionId,
                                onClick = { selectedRemote = head.revisionId },
                            )
                            Text("Versione " + (index + 1) + " · " +
                                head.revisionId.take(8),
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    if (conflict.hasLocal) {
                        OutlinedButton(
                            onClick = { choose(CloudConflictChoice.KEEP_LOCAL) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        ) { Text("Usa playlist del telefono") }
                    }
                    OutlinedButton(
                        onClick = { choose(CloudConflictChoice.USE_REMOTE) },
                        enabled = selectedRemote != null,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) { Text("Usa Drive (sostituisce sul telefono)") }
                    if (conflict.hasLocal) {
                        Button(
                            onClick = { choose(CloudConflictChoice.DUPLICATE_LOCAL) },
                            enabled = selectedRemote != null,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        ) { Text("Conserva entrambe") }
                    }
                } else {
                    Text("Cronologia cloud da verificare: nessuna modifica verrà applicata.",
                        style = MaterialTheme.typography.bodySmall)
                }
                TextButton(
                    onClick = onCancel,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) { Text("Più tardi") }
            }
        }
    }
}
