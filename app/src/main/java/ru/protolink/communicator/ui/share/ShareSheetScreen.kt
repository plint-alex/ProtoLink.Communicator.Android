@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package ru.protolink.communicator.ui.share

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.protolink.communicator.data.api.ShareLinkListItemDto
import ru.protolink.communicator.util.ShareAddPeople
import ru.protolink.communicator.util.ShareSheetLogic
import ru.protolink.communicator.util.ShareSheetStrings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareSheetScreen(
    sheet: ShareSheetUiState,
    lang: String?,
    onDismiss: () -> Unit,
    onCopyCanonical: () -> Unit,
    onBeginCreate: () -> Unit,
    onCancelCreate: () -> Unit,
    onNewLabelChange: (String) -> Unit,
    onExpiryChoice: (Int) -> Unit,
    onCreateLink: () -> Unit,
    onCopyCreated: () -> Unit,
    onDoneCreated: () -> Unit,
    onCopyAndClose: () -> Unit,
    onCloseAnyway: () -> Unit,
    onConfirmTurnOff: (String) -> Unit,
    onCancelTurnOff: () -> Unit,
    onTurnOff: (String) -> Unit,
    onExtend: (String, Int) -> Unit,
    onToggleExtendMenu: (String?) -> Unit,
    onStartEditLabel: (String, String) -> Unit,
    onEditingLabelChange: (String) -> Unit,
    onSaveLabel: () -> Unit,
    onCancelEditLabel: () -> Unit,
    onRemovePerson: (String) -> Unit,
    onToggleInactive: () -> Unit,
    onRetry: () -> Unit,
    onBeginAddPeople: () -> Unit,
    onCancelAddPeople: () -> Unit,
    onContactSearchChange: (String) -> Unit,
    onToggleContact: (String) -> Unit,
    onPasteIdChange: (String) -> Unit,
    onResolvePaste: () -> Unit,
    onAddCanWriteChange: (Boolean) -> Unit,
    onAddPeople: () -> Unit,
    onDismissAddedBanner: () -> Unit
) {
    if (!sheet.isOpen) return
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val canMutate = ShareSheetLogic.canMutate(sheet.isOnline, sheet.canEdit, sheet.showCreatedPanel)
    val muted = Color(0xFF8E8E93)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (sheet.showAddPeople) ShareSheetStrings.addPeopleTitle(lang)
                    else ShareSheetStrings.sheetTitle(lang, sheet.entityName),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = ShareSheetStrings.done(lang))
                }
            }
            if (!sheet.showAddPeople) {
                Text(
                    ShareSheetStrings.subtitle(lang, sheet.isFolder, sheet.childCount, sheet.isNote),
                    color = muted,
                    fontSize = 13.sp
                )
            }

            if (!sheet.isOnline) {
                Spacer(Modifier.height(8.dp))
                Text(ShareSheetStrings.offlineBanner(lang), color = Color(0xFFFF9500), fontSize = 13.sp)
            }
            if (!sheet.errorText.isNullOrBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(sheet.errorText, color = Color(0xFFFF3B30), fontSize = 13.sp)
                if (sheet.isOnline) {
                    TextButton(onClick = onRetry) { Text("Retry") }
                }
            }

            if (sheet.showAddedBanner && !sheet.addedBannerText.isNullOrBlank()) {
                Spacer(Modifier.height(12.dp))
                Column(Modifier.padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(sheet.addedBannerText, fontSize = 13.sp)
                    Row {
                        TextButton(onClick = onCopyCanonical) {
                            Text(ShareSheetStrings.copyLink(lang))
                        }
                        TextButton(onClick = onDismissAddedBanner) {
                            Text(ShareSheetStrings.done(lang))
                        }
                    }
                }
            }

            if (sheet.showAddPeople) {
                AddPeoplePanel(
                    sheet = sheet,
                    lang = lang,
                    canMutate = canMutate,
                    muted = muted,
                    onContactSearchChange = onContactSearchChange,
                    onToggleContact = onToggleContact,
                    onPasteIdChange = onPasteIdChange,
                    onResolvePaste = onResolvePaste,
                    onAddCanWriteChange = onAddCanWriteChange,
                    onCancelAddPeople = onCancelAddPeople,
                    onAddPeople = onAddPeople
                )
            } else {
                Spacer(Modifier.height(16.dp))
                SectionLabel(ShareSheetStrings.linkSection(lang))
                Text(ShareSheetStrings.linkHelper(lang), color = muted, fontSize = 13.sp)
                TextButton(onClick = onCopyCanonical) {
                    Text(ShareSheetStrings.copyLink(lang))
                }

                Spacer(Modifier.height(12.dp))
                SectionLabel(ShareSheetStrings.guestLinksSection(lang))
                Text(ShareSheetStrings.guestLinksHelper(lang), color = muted, fontSize = 13.sp)
                if (ShareSheetLogic.showSubtreeLine(sheet.isFolder)) {
                    Text(
                        ShareSheetStrings.subtreeLine(lang),
                        color = muted,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }

                if (sheet.isLoading) {
                    Text("…", color = muted, modifier = Modifier.padding(vertical = 8.dp))
                }

                if (sheet.showCreatedPanel) {
                    CreatedPanel(
                        sheet = sheet,
                        lang = lang,
                        onCopyCreated = onCopyCreated,
                        onDoneCreated = onDoneCreated,
                        onCopyAndClose = onCopyAndClose,
                        onCloseAnyway = onCloseAnyway
                    )
                } else if (sheet.showCreateForm) {
                    CreateForm(
                        sheet = sheet,
                        lang = lang,
                        enabled = canMutate && !sheet.busy,
                        onNewLabelChange = onNewLabelChange,
                        onExpiryChoice = onExpiryChoice,
                        onCancelCreate = onCancelCreate,
                        onCreateLink = onCreateLink
                    )
                } else {
                    if (sheet.liveLinks.isEmpty() && !sheet.isLoading) {
                        Text(
                            ShareSheetStrings.noGuestLinksYet(lang),
                            color = muted,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                    sheet.liveLinks.forEach { link ->
                        GuestLinkRow(
                            link = link,
                            lang = lang,
                            sheet = sheet,
                            canMutate = canMutate,
                            onConfirmTurnOff = onConfirmTurnOff,
                            onCancelTurnOff = onCancelTurnOff,
                            onTurnOff = onTurnOff,
                            onExtend = onExtend,
                            onToggleExtendMenu = onToggleExtendMenu,
                            onStartEditLabel = onStartEditLabel,
                            onEditingLabelChange = onEditingLabelChange,
                            onSaveLabel = onSaveLabel,
                            onCancelEditLabel = onCancelEditLabel
                        )
                    }
                    if (ShareSheetLogic.showLinkLimit(sheet.liveLinks.size)) {
                        Text(ShareSheetStrings.linkLimit(lang), color = muted, fontSize = 13.sp)
                    }
                    if (ShareSheetLogic.showCreateButton(
                            canMutate,
                            sheet.showCreateForm,
                            sheet.showCreatedPanel,
                            sheet.liveLinks.size
                        )
                    ) {
                        TextButton(
                            onClick = onBeginCreate,
                            enabled = canMutate
                        ) {
                            Text(ShareSheetStrings.createGuestLink(lang))
                        }
                    } else if (!sheet.isOnline || !sheet.canEdit) {
                        Text(
                            if (!sheet.isOnline) ShareSheetStrings.offlineMutationTooltip(lang)
                            else ShareSheetStrings.viewerOnlyFooter(lang),
                            color = muted,
                            fontSize = 12.sp
                        )
                    }
                    if (sheet.inactiveLinks.isNotEmpty()) {
                        TextButton(onClick = onToggleInactive) {
                            Text(ShareSheetStrings.inactiveLinks(lang, sheet.inactiveLinks.size))
                        }
                        if (sheet.showInactiveExpanded) {
                            sheet.inactiveLinks.forEach { link ->
                                Text(
                                    ShareSheetLogic.linkRowTitle(lang, link.label, link.tokenPrefix),
                                    color = muted,
                                    fontSize = 13.sp,
                                    modifier = Modifier.padding(vertical = 4.dp)
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
                SectionLabel(ShareSheetStrings.peopleSection(lang))
                sheet.people.forEach { person ->
                    val name = ShareSheetLogic.personDisplayName(lang, person.grant)
                    val access = if (person.grant.canWrite) {
                        ShareSheetStrings.canEdit(lang)
                    } else {
                        ShareSheetStrings.canView(lang)
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(name, fontSize = 15.sp)
                            if (person.isCreator) {
                                val kind = when {
                                    sheet.isFolder -> ShareSheetStrings.kindFolder(lang)
                                    sheet.isNote -> ShareSheetStrings.kindNote(lang)
                                    else -> ShareSheetStrings.kindFile(lang)
                                }
                                Text(ShareSheetStrings.createdThis(lang, kind), color = muted, fontSize = 12.sp)
                            } else {
                                Text(access, color = muted, fontSize = 12.sp)
                            }
                        }
                        if (!person.isCreator && canMutate &&
                            ShareSheetLogic.isPeopleListPrincipal(person.grant.principalKind)
                        ) {
                            val forId = person.grant.permissionForId
                            if (!forId.isNullOrBlank()) {
                                TextButton(onClick = { onRemovePerson(forId) }) {
                                    Text(ShareSheetStrings.remove(lang), color = Color(0xFFFF3B30))
                                }
                            }
                        }
                    }
                }
                if (canMutate && !sheet.showCreateForm && !sheet.showCreatedPanel) {
                    TextButton(onClick = onBeginAddPeople, enabled = canMutate && !sheet.busy) {
                        Text(ShareSheetStrings.addPeopleButton(lang))
                    }
                }

                Spacer(Modifier.height(8.dp))
                Text(ShareSheetStrings.footerLag(lang), color = muted, fontSize = 12.sp)
                if (!sheet.canEdit) {
                    Spacer(Modifier.height(4.dp))
                    Text(ShareSheetStrings.viewerOnlyFooter(lang), color = muted, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun AddPeoplePanel(
    sheet: ShareSheetUiState,
    lang: String?,
    canMutate: Boolean,
    muted: Color,
    onContactSearchChange: (String) -> Unit,
    onToggleContact: (String) -> Unit,
    onPasteIdChange: (String) -> Unit,
    onResolvePaste: () -> Unit,
    onAddCanWriteChange: (Boolean) -> Unit,
    onCancelAddPeople: () -> Unit,
    onAddPeople: () -> Unit
) {
    val enabled = canMutate && !sheet.busy
    val selectedIds = sheet.contactPicker.filter { it.isSelected }.map { it.userId }
    val canAdd = ShareAddPeople.hasAddTargets(
        selectedIds,
        sheet.pasteResolvedUserId,
        sheet.pastePreviewName
    )
    Spacer(Modifier.height(12.dp))
    Text(ShareSheetStrings.searchContacts(lang), color = muted, fontSize = 12.sp)
    OutlinedTextField(
        value = sheet.contactSearch,
        onValueChange = onContactSearchChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        enabled = enabled
    )
    if (sheet.contactPicker.isEmpty() && sheet.contactSearch.isBlank() && !sheet.isLoading) {
        Text(
            ShareSheetStrings.noContactsYet(lang),
            color = muted,
            fontSize = 13.sp,
            modifier = Modifier.padding(vertical = 8.dp)
        )
    }
    sheet.contactPicker.forEach { row ->
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled) { onToggleContact(row.userId) }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(row.name, modifier = Modifier.weight(1f), fontSize = 15.sp)
            Checkbox(
                checked = row.isSelected,
                onCheckedChange = { if (enabled) onToggleContact(row.userId) },
                enabled = enabled
            )
        }
    }
    Spacer(Modifier.height(8.dp))
    Text(ShareSheetStrings.pasteIdHint(lang), color = muted, fontSize = 12.sp)
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = sheet.pasteId,
            onValueChange = onPasteIdChange,
            modifier = Modifier.weight(1f),
            singleLine = true,
            enabled = enabled,
            placeholder = { Text(ShareSheetStrings.pasteIdPlaceholder(lang), fontSize = 13.sp) }
        )
        TextButton(
            onClick = onResolvePaste,
            enabled = enabled && ShareAddPeople.parseUserIdInput(sheet.pasteId) != null
        ) {
            Text(ShareSheetStrings.resolvePaste(lang))
        }
    }
    if (!sheet.pastePreviewName.isNullOrBlank()) {
        Text(sheet.pastePreviewName, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
    }
    if (!sheet.pastePreviewError.isNullOrBlank()) {
        Text(sheet.pastePreviewError, color = muted, fontSize = 13.sp)
    }
    Spacer(Modifier.height(8.dp))
    SectionLabel(ShareSheetStrings.access(lang))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = !sheet.addCanWrite,
            onClick = { if (enabled) onAddCanWriteChange(false) },
            label = { Text(ShareSheetStrings.canView(lang)) },
            enabled = enabled
        )
        FilterChip(
            selected = sheet.addCanWrite,
            onClick = { if (enabled) onAddCanWriteChange(true) },
            label = { Text(ShareSheetStrings.canEdit(lang)) },
            enabled = enabled
        )
    }
    if (sheet.addCanWrite) {
        Text(
            ShareSheetStrings.canEditHelper(lang),
            color = muted,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
    Row {
        TextButton(onClick = onCancelAddPeople) { Text(ShareSheetStrings.cancel(lang)) }
        Button(onClick = onAddPeople, enabled = enabled && canAdd) {
            Text(ShareSheetStrings.add(lang))
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        color = Color(0xFF8E8E93),
        modifier = Modifier.padding(bottom = 4.dp)
    )
}

@Composable
private fun CreatedPanel(
    sheet: ShareSheetUiState,
    lang: String?,
    onCopyCreated: () -> Unit,
    onDoneCreated: () -> Unit,
    onCopyAndClose: () -> Unit,
    onCloseAnyway: () -> Unit
) {
    Column(Modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(ShareSheetStrings.guestLinkCreated(lang), fontWeight = FontWeight.SemiBold)
        Text(sheet.createdUrl.orEmpty(), fontSize = 13.sp)
        Text(ShareSheetStrings.copyOnceHint(lang), color = Color(0xFF8E8E93), fontSize = 12.sp)
        Text(ShareSheetStrings.lostLinkHint(lang), color = Color(0xFF8E8E93), fontSize = 12.sp)
        Button(onClick = onCopyCreated, modifier = Modifier.fillMaxWidth()) {
            Text(
                if (sheet.createdUrlCopied) "✓ ${ShareSheetStrings.copied(lang)}"
                else ShareSheetStrings.copyLink(lang)
            )
        }
        if (sheet.showCloseWithoutCopy) {
            Text(ShareSheetStrings.closeWithoutCopy(lang), fontSize = 13.sp)
            Row {
                TextButton(onClick = onCopyAndClose) { Text(ShareSheetStrings.copyAndClose(lang)) }
                TextButton(onClick = onCloseAnyway) { Text(ShareSheetStrings.closeAnyway(lang)) }
            }
        } else {
            TextButton(onClick = onDoneCreated) { Text(ShareSheetStrings.done(lang)) }
        }
    }
}

@Composable
private fun CreateForm(
    sheet: ShareSheetUiState,
    lang: String?,
    enabled: Boolean,
    onNewLabelChange: (String) -> Unit,
    onExpiryChoice: (Int) -> Unit,
    onCancelCreate: () -> Unit,
    onCreateLink: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(ShareSheetStrings.nameOptional(lang), fontSize = 13.sp, color = Color(0xFF8E8E93))
        OutlinedTextField(
            value = sheet.newLabel,
            onValueChange = onNewLabelChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text(ShareSheetStrings.namePlaceholder(lang)) },
            enabled = enabled
        )
        Text(ShareSheetStrings.expires(lang), fontSize = 13.sp, color = Color(0xFF8E8E93))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(1 to ShareSheetStrings.expiry1Day(lang),
                7 to ShareSheetStrings.expiry7Days(lang),
                30 to ShareSheetStrings.expiry30Days(lang),
                0 to ShareSheetStrings.expiryNever(lang)
            ).forEach { (days, label) ->
                FilterChip(
                    selected = sheet.expiryChoice == days,
                    onClick = { if (enabled) onExpiryChoice(days) },
                    label = { Text(label, fontSize = 12.sp) },
                    enabled = enabled
                )
            }
        }
        if (sheet.expiryChoice == 0) {
            Text(ShareSheetStrings.neverExpiresHint(lang), color = Color(0xFF8E8E93), fontSize = 12.sp)
        }
        Row {
            TextButton(onClick = onCancelCreate) { Text(ShareSheetStrings.cancel(lang)) }
            Button(onClick = onCreateLink, enabled = enabled) {
                Text(ShareSheetStrings.createLink(lang))
            }
        }
    }
}

@Composable
private fun GuestLinkRow(
    link: ShareLinkListItemDto,
    lang: String?,
    sheet: ShareSheetUiState,
    canMutate: Boolean,
    onConfirmTurnOff: (String) -> Unit,
    onCancelTurnOff: () -> Unit,
    onTurnOff: (String) -> Unit,
    onExtend: (String, Int) -> Unit,
    onToggleExtendMenu: (String?) -> Unit,
    onStartEditLabel: (String, String) -> Unit,
    onEditingLabelChange: (String) -> Unit,
    onSaveLabel: () -> Unit,
    onCancelEditLabel: () -> Unit
) {
    val id = link.id ?: return
    val muted = Color(0xFF8E8E93)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        if (sheet.editingLabelId == id) {
            OutlinedTextField(
                value = sheet.editingLabelDraft,
                onValueChange = onEditingLabelChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = canMutate
            )
            Row {
                TextButton(onClick = onSaveLabel, enabled = canMutate) { Text(ShareSheetStrings.done(lang)) }
                TextButton(onClick = onCancelEditLabel) { Text(ShareSheetStrings.cancel(lang)) }
            }
        } else {
            Text(
                ShareSheetLogic.linkRowTitle(lang, link.label, link.tokenPrefix),
                fontWeight = FontWeight.Medium,
                modifier = Modifier.clickable(enabled = canMutate) {
                    onStartEditLabel(id, link.label.orEmpty())
                }
            )
        }
        Text(
            ShareSheetLogic.expiryLine(lang, link.expiresAt, link.revokedAt),
            color = muted,
            fontSize = 12.sp
        )
        Text(usageLine(lang, link), color = muted, fontSize = 12.sp)
        Text(
            ShareSheetStrings.createdBy(lang, link.createdByDisplayName?.takeIf { it.isNotBlank() } ?: "—"),
            color = muted,
            fontSize = 12.sp
        )
        if (sheet.confirmTurnOffId == id) {
            Text(ShareSheetStrings.turnOffConfirm(lang), fontSize = 13.sp)
            Row {
                TextButton(onClick = onCancelTurnOff) { Text(ShareSheetStrings.cancel(lang)) }
                TextButton(onClick = { onTurnOff(id) }, enabled = canMutate) {
                    Text(ShareSheetStrings.turnOff(lang), color = Color(0xFFFF3B30))
                }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (canMutate && ShareSheetLogic.isExpiringSoon(link.expiresAt)) {
                    if (sheet.extendMenuId == id) {
                        TextButton(onClick = { onExtend(id, 7) }) { Text(ShareSheetStrings.expiry7Days(lang)) }
                        TextButton(onClick = { onExtend(id, 30) }) { Text(ShareSheetStrings.expiry30Days(lang)) }
                        TextButton(onClick = { onExtend(id, 0) }) { Text(ShareSheetStrings.expiryNever(lang)) }
                        TextButton(onClick = { onToggleExtendMenu(null) }) {
                            Text(ShareSheetStrings.cancel(lang))
                        }
                    } else {
                        TextButton(onClick = { onToggleExtendMenu(id) }) {
                            Text(ShareSheetStrings.extend(lang))
                        }
                    }
                }
                TextButton(
                    onClick = { onConfirmTurnOff(id) },
                    enabled = canMutate
                ) {
                    Text(ShareSheetStrings.turnOff(lang))
                }
            }
        }
    }
}

private fun usageLine(lang: String?, link: ShareLinkListItemDto): String {
    if (link.useCount <= 0 || link.lastUsedAt.isNullOrBlank()) {
        return ShareSheetStrings.neverOpened(lang)
    }
    return if (ShareSheetCloudStringsIsRu(lang))
        "Открыта ${link.useCount} раз"
    else
        "Opened ${link.useCount} times"
}

private fun ShareSheetCloudStringsIsRu(lang: String?): Boolean =
    ru.protolink.communicator.util.ShareSheetCloudStrings.isRussian(lang)
