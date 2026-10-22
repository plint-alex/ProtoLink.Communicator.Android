package ru.protolink.communicator.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.protolink.communicator.util.MapLocalFolderStrings

data class MapLocalFolderCandidate(
    val id: String,
    val name: String
)

data class MapLocalFolderWizardResult(
    val localUri: String,
    val createNew: Boolean,
    val newFolderName: String? = null,
    val existingFolderId: String? = null,
    val existingFolderName: String? = null
)

/**
 * E6 wizard step 2–3: local URI already chosen → create or pick Cloud destination → confirm.
 */
@Composable
fun MapLocalFolderWizardDialog(
    lang: String?,
    localUri: String,
    localLabel: String,
    candidates: List<MapLocalFolderCandidate>,
    suggestedName: String,
    busy: Boolean,
    onChangeLocal: () -> Unit,
    onDismiss: () -> Unit,
    onConfirm: (MapLocalFolderWizardResult) -> Unit
) {
    var createNew by remember { mutableStateOf(true) }
    var folderName by remember(suggestedName) { mutableStateOf(suggestedName) }
    var selectedId by remember { mutableStateOf(candidates.firstOrNull()?.id) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(MapLocalFolderStrings.wizardTitle(lang)) },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(MapLocalFolderStrings.localFolderLabel(lang), fontSize = 13.sp, color = Color(0xFF8E8E93))
                Text(
                    localLabel.ifBlank { localUri },
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp)
                )
                TextButton(onClick = onChangeLocal, enabled = !busy) {
                    Text(MapLocalFolderStrings.changeFolder(lang))
                }
                Spacer(Modifier.height(12.dp))
                Text(MapLocalFolderStrings.destinationLabel(lang), fontSize = 13.sp, color = Color(0xFF8E8E93))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !busy) { createNew = true }
                ) {
                    RadioButton(selected = createNew, onClick = { createNew = true }, enabled = !busy)
                    Text(MapLocalFolderStrings.createNewOption(lang))
                }
                if (createNew) {
                    OutlinedTextField(
                        value = folderName,
                        onValueChange = { folderName = it },
                        label = { Text(MapLocalFolderStrings.folderNameLabel(lang)) },
                        singleLine = true,
                        enabled = !busy,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 8.dp, bottom = 8.dp)
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !busy && candidates.isNotEmpty()) {
                            if (candidates.isNotEmpty()) createNew = false
                        }
                ) {
                    RadioButton(
                        selected = !createNew,
                        onClick = { if (candidates.isNotEmpty()) createNew = false },
                        enabled = !busy && candidates.isNotEmpty()
                    )
                    Text(MapLocalFolderStrings.pickExistingOption(lang))
                }
                if (!createNew) {
                    if (candidates.isEmpty()) {
                        Text(
                            MapLocalFolderStrings.noUnmappedFolders(lang),
                            fontSize = 13.sp,
                            color = Color(0xFF8E8E93),
                            modifier = Modifier.padding(start = 8.dp, bottom = 8.dp)
                        )
                    } else {
                        candidates.forEach { c ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = !busy) { selectedId = c.id }
                                    .padding(start = 8.dp, top = 2.dp, bottom = 2.dp)
                            ) {
                                RadioButton(
                                    selected = selectedId == c.id,
                                    onClick = { selectedId = c.id },
                                    enabled = !busy
                                )
                                Text(c.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    MapLocalFolderStrings.confirmWarn(lang),
                    fontSize = 12.sp,
                    color = Color(0xFF8E8E93)
                )
                if (!error.isNullOrBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(error!!, color = Color(0xFFFF3B30), fontSize = 13.sp)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy,
                onClick = {
                    if (localUri.isBlank()) {
                        error = MapLocalFolderStrings.localMissing(lang)
                        return@TextButton
                    }
                    if (createNew) {
                        val name = folderName.trim()
                        if (name.isEmpty()) {
                            error = MapLocalFolderStrings.folderNameLabel(lang)
                            return@TextButton
                        }
                        onConfirm(
                            MapLocalFolderWizardResult(
                                localUri = localUri,
                                createNew = true,
                                newFolderName = name
                            )
                        )
                    } else {
                        val pick = candidates.firstOrNull { it.id == selectedId }
                        if (pick == null) {
                            error = MapLocalFolderStrings.noUnmappedFolders(lang)
                            return@TextButton
                        }
                        onConfirm(
                            MapLocalFolderWizardResult(
                                localUri = localUri,
                                createNew = false,
                                existingFolderId = pick.id,
                                existingFolderName = pick.name
                            )
                        )
                    }
                }
            ) { Text(MapLocalFolderStrings.confirm(lang)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) {
                Text(MapLocalFolderStrings.cancel(lang))
            }
        }
    )
}
