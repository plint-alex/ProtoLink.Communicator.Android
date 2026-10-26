package ru.protolink.communicator.ui.share

import ru.protolink.communicator.data.api.ShareGrantDto
import ru.protolink.communicator.data.api.ShareLinkListItemDto

/** UI state for Cloud Share sheet (AT-S40/S41/S44 + UX §4 Add people). */
data class ShareSheetUiState(
    val isOpen: Boolean = false,
    val entityId: String = "",
    val entityName: String = "",
    val isFolder: Boolean = true,
    val isNote: Boolean = false,
    val childCount: Int = 0,
    val creatorUserId: String? = null,
    val canEdit: Boolean = true,
    val isOnline: Boolean = true,
    val isLoading: Boolean = false,
    val busy: Boolean = false,
    val errorText: String? = null,
    val liveLinks: List<ShareLinkListItemDto> = emptyList(),
    val inactiveLinks: List<ShareLinkListItemDto> = emptyList(),
    val people: List<SharePersonUi> = emptyList(),
    /** Shown inline under People: the sheet-level errorText is scrolled out of view there. */
    val peopleErrorText: String? = null,
    val showCreateForm: Boolean = false,
    val showCreatedPanel: Boolean = false,
    val showCloseWithoutCopy: Boolean = false,
    val createdUrl: String? = null,
    val createdUrlCopied: Boolean = false,
    val newLabel: String = "",
    /** 1 / 7 / 30 / 0=never */
    val expiryChoice: Int = 30,
    val showInactiveExpanded: Boolean = false,
    val confirmTurnOffId: String? = null,
    val editingLabelId: String? = null,
    val editingLabelDraft: String = "",
    val extendMenuId: String? = null,
    // --- Add people (UX §4) ---
    val showAddPeople: Boolean = false,
    val contactSearch: String = "",
    val contactPicker: List<ShareContactPickerItem> = emptyList(),
    val pasteId: String = "",
    val pastePreviewName: String? = null,
    val pastePreviewError: String? = null,
    val pasteResolvedUserId: String? = null,
    val addCanWrite: Boolean = false,
    val showAddedBanner: Boolean = false,
    val addedBannerText: String? = null
)

data class SharePersonUi(
    val grant: ShareGrantDto,
    val isCreator: Boolean
)

data class ShareContactPickerItem(
    val userId: String,
    val name: String,
    val isSelected: Boolean = false
)
