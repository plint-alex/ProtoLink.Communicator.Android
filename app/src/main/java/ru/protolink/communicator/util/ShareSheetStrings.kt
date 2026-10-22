package ru.protolink.communicator.util

/**
 * RU/EN Share sheet strings (share-sheet-ux §§2–3 / AT-S40/S41/S44).
 * Parity with Windows ShareSheetStrings + ViewEditor share-sheet-helpers.
 */
object ShareSheetStrings {
    private fun ru(lang: String?) = ShareSheetCloudStrings.isRussian(lang)

    fun shareButton(lang: String?) = if (ru(lang)) "Поделиться" else "Share"

    /** Notes/Cloud context menu (UX-NS-1). */
    fun shareMenu(lang: String?) = if (ru(lang)) "Поделиться…" else "Share…"

    fun notInCloudYet(lang: String?) =
        if (ru(lang)) "Ещё не в облаке" else "Not in Cloud yet"

    fun sheetTitle(lang: String?, entityName: String) =
        if (ru(lang)) "Доступ · $entityName" else "Share “$entityName”"

    fun linkSection(lang: String?) = if (ru(lang)) "ССЫЛКА" else "LINK"

    fun linkHelper(lang: String?) =
        if (ru(lang)) "Откроется только у тех, у кого уже есть доступ."
        else "Opens only for people who already have access."

    fun copyLink(lang: String?) = if (ru(lang)) "Копировать ссылку" else "Copy link"

    fun guestLinksSection(lang: String?) = if (ru(lang)) "ГОСТЕВЫЕ ССЫЛКИ" else "GUEST LINKS"

    fun guestLinksHelper(lang: String?) =
        if (ru(lang)) "Любой, у кого есть ссылка, сможет посмотреть — без входа в аккаунт."
        else "Anyone with a guest link can view — no account needed."

    fun subtreeLine(lang: String?) =
        if (ru(lang)) "Действует для этой папки и всего, что будет добавлено позже."
        else "Covers this folder and everything added to it later."

    fun createGuestLink(lang: String?) =
        if (ru(lang)) "+ Создать гостевую ссылку" else "+ Create guest link"

    fun noGuestLinksYet(lang: String?) =
        if (ru(lang))
            "Гостевых ссылок пока нет. Создайте ссылку, чтобы показать это без входа в аккаунт."
        else
            "No guest links yet. Create one to let someone view this without an account."

    fun turnOff(lang: String?) = if (ru(lang)) "Отключить" else "Turn off"

    fun turnOffConfirm(lang: String?) =
        if (ru(lang))
            "Отключить эту ссылку? У тех, у кого она открыта, доступ пропадёт в течение 15 минут."
        else
            "Turn off this link? People who have it open lose access within 15 minutes."

    fun cancel(lang: String?) = if (ru(lang)) "Отмена" else "Cancel"
    fun createLink(lang: String?) = if (ru(lang)) "Создать ссылку" else "Create link"
    fun nameOptional(lang: String?) = if (ru(lang)) "Название (необязательно)" else "Name (optional)"
    fun namePlaceholder(lang: String?) =
        if (ru(lang)) "Например: Для подрядчика" else "Add a name, e.g. For contractor"

    fun expires(lang: String?) = if (ru(lang)) "Срок" else "Expires"
    fun expiry1Day(lang: String?) = if (ru(lang)) "1 день" else "1 day"
    fun expiry7Days(lang: String?) = if (ru(lang)) "7 дней" else "7 days"
    fun expiry30Days(lang: String?) = if (ru(lang)) "30 дней" else "30 days"
    fun expiryNever(lang: String?) = if (ru(lang)) "Никогда" else "Never"

    fun neverExpiresHint(lang: String?) =
        if (ru(lang)) "Ссылка действует, пока её не отключат."
        else "The link works until someone turns it off."

    fun guestLinkCreated(lang: String?) =
        if (ru(lang)) "Гостевая ссылка создана" else "Guest link created"

    fun copyOnceHint(lang: String?) =
        if (ru(lang))
            "Скопируйте её сейчас — из соображений безопасности мы показываем её только один раз."
        else
            "Copy it now — for safety we only show it once."

    fun lostLinkHint(lang: String?) =
        if (ru(lang)) "Потеряли? Создайте новую — эта продолжит работать."
        else "Lost it? Create a new one; this one keeps working."

    fun done(lang: String?) = if (ru(lang)) "Готово" else "Done"
    fun copied(lang: String?) = ShareAccountStrings.copiedLabel(lang)

    fun closeWithoutCopy(lang: String?) =
        if (ru(lang)) "Вы не скопировали ссылку. Она больше не будет показана."
        else "You haven't copied the link. It won't be shown again."

    fun copyAndClose(lang: String?) =
        if (ru(lang)) "Скопировать и закрыть" else "Copy and close"

    fun closeAnyway(lang: String?) =
        if (ru(lang)) "Закрыть всё равно" else "Close anyway"

    fun peopleSection(lang: String?) =
        if (ru(lang)) "У КОГО ЕСТЬ ДОСТУП" else "PEOPLE WITH ACCESS"

    fun canView(lang: String?) = ShareSheetCloudStrings.canView(lang)
    fun canEdit(lang: String?) = ShareSheetCloudStrings.canEdit(lang)

    fun anyoneOnInternet(lang: String?) =
        if (ru(lang)) "Любой в интернете" else "Anyone on the internet"

    fun footerLag(lang: String?) =
        if (ru(lang))
            "Отключение доступа занимает до 15 минут у тех, у кого оно открыто. Уже скачанные файлы остаются у них."
        else
            "Turning off access takes up to 15 minutes for someone who has it open. Files already downloaded stay with them."

    fun offlineBanner(lang: String?) =
        if (ru(lang)) "Ожидание сети…" else "Waiting for network…"

    fun offlineMutationTooltip(lang: String?) =
        if (ru(lang)) "Доступно, когда появится сеть" else "Available when you're back online"

    fun viewerOnlyFooter(lang: String?) =
        if (ru(lang)) "Делиться могут только те, у кого есть право редактирования."
        else "Only people who can edit can share this."

    fun linkLimit(lang: String?) =
        if (ru(lang))
            "Достигнут предел — 20 гостевых ссылок. Отключите одну, чтобы создать новую."
        else
            "You've reached 20 guest links. Turn one off to create another."

    fun neverOpened(lang: String?) =
        if (ru(lang)) "Никогда не открывалась" else "Never opened"

    fun createdBy(lang: String?, name: String) =
        if (ru(lang)) "Создал(а) $name" else "Created by $name"

    fun linkEnding(lang: String?, prefix: String) =
        if (ru(lang)) "Ссылка …$prefix" else "Link ending …$prefix"

    fun inactiveLinks(lang: String?, count: Int) =
        if (ru(lang)) "$count неактивных ссылок" else "$count inactive links"

    fun createFailed(lang: String?) =
        if (ru(lang)) "Не удалось создать ссылку. Повторите." else "Couldn't create the link. Try again."

    fun revokeFailed(lang: String?) =
        if (ru(lang)) "Не удалось отключить ссылку. Повторите." else "Couldn't turn off the link. Try again."

    fun updateFailed(lang: String?) =
        if (ru(lang)) "Не удалось обновить ссылку. Повторите." else "Couldn't update the link. Try again."

    fun loadFailed(lang: String?) =
        if (ru(lang)) "Не удалось загрузить доступ. Повторите." else "Couldn't load sharing. Try again."

    fun guestLinkTurnedOff(lang: String?) =
        if (ru(lang)) "Гостевая ссылка отключена" else "Guest link turned off"

    fun extend(lang: String?) = if (ru(lang)) "Продлить" else "Extend"

    fun remove(lang: String?) = if (ru(lang)) "Убрать" else "Remove"

    // --- Add people (UX §4) ---
    fun addPeopleButton(lang: String?) =
        if (ru(lang)) "+ Добавить людей" else "+ Add people"

    fun addPeopleTitle(lang: String?) =
        if (ru(lang)) "Добавить людей" else "Add people"

    fun searchContacts(lang: String?) =
        if (ru(lang)) "Поиск по контактам…" else "Search your contacts…"

    fun pasteIdHint(lang: String?) =
        if (ru(lang)) "Нет в контактах? Вставьте их ID пользователя"
        else "Not in your contacts? Paste their user ID"

    fun pasteIdPlaceholder(lang: String?) =
        if (ru(lang)) "ID пользователя или ссылка /{id}" else "User ID or /{id} link"

    fun noUserWithId(lang: String?) =
        if (ru(lang)) "Нет пользователя ProtoLink с таким ID"
        else "No ProtoLink user with this ID"

    fun canEditHelper(lang: String?) =
        if (ru(lang)) "Редактирование также позволяет делиться и отключать ссылки."
        else "Can edit also lets them share and turn off links."

    fun access(lang: String?) = if (ru(lang)) "Доступ" else "Access"

    fun add(lang: String?) = if (ru(lang)) "Добавить" else "Add"

    fun addedToast(lang: String?, name: String, canWrite: Boolean): String {
        val level = if (canWrite) {
            if (ru(lang)) "редактировать" else "edit"
        } else {
            if (ru(lang)) "смотреть" else "view"
        }
        return if (ru(lang))
            "$name теперь может $level. Отправьте ссылку, чтобы найти."
        else
            "$name can now $level. Send them the link so they can find it."
    }

    fun addedToastMany(lang: String?, count: Int): String =
        if (ru(lang))
            "$count человек добавлено. Отправьте ссылку, чтобы найти."
        else
            "$count people can now access this. Send them the link so they can find it."

    fun noContactsYet(lang: String?) =
        if (ru(lang)) "Пока нет контактов в Мессенджере." else "No Messenger contacts yet."

    fun resolvePaste(lang: String?) = if (ru(lang)) "Найти" else "Look up"

    fun createdThis(lang: String?, kind: String) =
        if (ru(lang)) "Создал(а) этот $kind" else "Created this $kind"

    fun kindFolder(lang: String?) = if (ru(lang)) "папку" else "folder"
    fun kindFile(lang: String?) = if (ru(lang)) "файл" else "file"
    fun kindNote(lang: String?) = if (ru(lang)) "заметку" else "note"

    fun subtitle(lang: String?, isFolder: Boolean, childCount: Int, isNote: Boolean = false): String {
        if (isFolder) {
            return if (childCount == 1) {
                if (ru(lang)) "Папка · 1 элемент" else "Folder · 1 item"
            } else {
                if (ru(lang)) "Папка · $childCount элементов" else "Folder · $childCount items"
            }
        }
        if (isNote) return if (ru(lang)) "Заметка" else "Note"
        return if (ru(lang)) "Файл" else "File"
    }
}
