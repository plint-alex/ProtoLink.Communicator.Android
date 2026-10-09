# ТЗ: удаление заметки (Android)

> Цель: дать безопасный способ удалить страницу-заметку (папку) с телефона.  
> Паритет с Windows: recursive delete каталога страницы; confirm; корень notes не удалять.  
> Вне скоупа v1: rename, trash/undo, swipe-to-delete, multi-select.

## 1. Контекст

Создание заметки уже есть (`docs/notes-create-android-spec.md`): папка + `index.html`, FAB / long-press / editor overflow.

На Windows: context menu **Delete** → confirm → `Directory.Delete(path, recursive: true)`; notes root запрещён; если удалили открытую страницу — очистить редактор. Sync подхватывает исчезновение файлов через local-only push / full reconcile.

На Android сейчас: long-press меню только «New note here»; SAF-примитив `SafLocalFileSystem.delete` есть, UI удаления — нет.

## 2. Принципы UX

1. **Destructive = confirm.** Удаление необратимо в v1 (нет trash / Snackbar undo) — всегда диалог подтверждения.
2. **Страница = папка.** Удаляется весь каталог страницы, включая `index.html` и **все вложенные** заметки (как Win `Directory.Delete(..., true)`).
3. **Предупреждение про детей.** Если у узла есть дочерние страницы — текст confirm явно говорит об этом (улучшение относительно лаконичного Win-диалога).
4. **Корень нельзя.** SAF notes root не удаляется из Notes UI.
5. **Два входа, один pipeline.** Long-press в дереве и overflow в редакторе ведут в один и тот же confirm → `deleteNote`.
6. **Без сюрпризов sync.** Notes пишет только диск; Cloud узнаёт об удалении штатным local push — не звать Cloud API из Notes.

## 3. Модель данных (как Windows)

| Понятие UI | На диске |
|------------|----------|
| Страница / раздел | Каталог |
| Содержимое | `index.html` (+ прочие файлы в каталоге) |
| Вложенные заметки | Подкаталоги |
| Удаление | Recursive delete каталога по `relativePath` |

Относительный путь цели: `selectedNoteRelativePath` / `row.relativePath` (не blank, не корень).

## 4. Entry points

### 4.1. Дерево (список)

Long-press на узле → меню:

1. **New note here** (уже есть)
2. **Delete** — открывает confirm для **этого** узла

Не показывать Delete для корня дерева (строка root, если когда-либо отображается как удаляемая; на практике удаляем только узлы с непустым `relativePath`).

### 4.2. Редактор

Overflow / app bar → **Delete note** → confirm для **текущей** открытой заметки (`selectedNoteRelativePath`).  
Disabled / скрыт, если нет открытой заметки или путь пустой.

### 4.3. Вне v1

- Swipe-to-delete в списке  
- Multi-select + bulk delete  
- Кнопка Delete на FAB / рядом с New note  

## 5. Диалог подтверждения

Заголовок: **Delete note**

Текст:

| Случай | Текст |
|--------|--------|
| Leaf (`!hasChildren`) | `Delete “{name}”? This cannot be undone.` |
| С детьми (`hasChildren`) | `Delete “{name}” and all notes inside? This cannot be undone.` |

Кнопки:

- **Cancel** — закрыть, ничего не делать  
- **Delete** — destructive (error/red style Material3)

IME не нужен. Dismiss по outside/back = Cancel.

Опционально в диалоге кратко показать relative path одной строкой (мелким текстом) — не обязательно в v1.

## 6. Поведение после Confirm → Delete

1. Отменить pending autosave текущей заметки (`cancelNotesPendingSave`), если удаляем её или предка открытой (см. §6.1).
2. Recursive SAF delete каталога цели.
3. Если удалили **открытую** заметку **или предка** открытой — `clearSelectedNote()` (редактор пустой / возврат к дереву на phone layout).
4. `refreshNotesTreeSuspend()` — узел исчезает из списка.
5. `requestLocalPush()` — sync удалит remote counterparts по meta (как после create).
6. Status (кратко): `Deleted “{name}”` или ошибка.

### 6.1. Открытая заметка и предки

Пусть открыта `A/B/C`. Удаляем:

| Цель | Редактор |
|------|----------|
| `A/B/C` | clear |
| `A/B` или `A` | clear (открытый файл исчез вместе с предком) |
| сосед / другая ветка | оставить открытой |

Проверка: `openRel == target \|\| openRel.startsWith(target + "/")` (нормализованные `/`).

### 6.2. Dirty editor

Отдельный «Save before delete?» **не** делаем в v1: confirm удаления достаточен; несохранённые правки в удаляемом поддереве теряются (как по сути на Win после Yes).

## 7. Валидация и ошибки

| Случай | Поведение |
|--------|-----------|
| Пустой `relativePath` / попытка удалить root | Не вызывать FS; status / toast «Cannot delete the notes root» |
| Нет notes root URI | Disabled UI / ошибка «Choose a notes folder first» |
| Узел уже отсутствует | Тихий успех после refresh (или краткий status); не крашить UI |
| SAF / IO fail | Диалог или `syncError`: `Could not delete note: {msg}`; дерево refresh всё равно попытаться |
| OEM: `DocumentFile.delete()` не чистит детей | Явный walk снизу вверх через `SafTreeLister.listChildren` + `DocumentsContract.deleteDocument` (см. §9) |

## 8. Синхронизация

- Удаление — обычное исчезновение файлов/папок в notes root (= mapped folder на телефоне).
- Notes UI **не** вызывает `DeleteEntity` / Cloud API.
- После delete: `requestLocalPush()` (local-only). Full reconcile по SignalR на других устройствах подтянет удаления после push + `data_changed` (штатный контракт, см. Windows `docs/notes-and-cloud-sync.md`).
- Не блокировать UI ожиданием sync.
- Конфликт sync по уже удалённому пути не должен оставлять «зомби» в дереве Notes: источник правды UI — диск после refresh.

## 9. Технический набросок

### 9.1. ViewModel / UI state

```text
data class PendingDeleteNote(
  val relativePath: String,
  val name: String,
  val hasChildren: Boolean
)

fun requestDeleteNote(row: NoteTreeRow)   // дерево
fun requestDeleteCurrentNote()            // редактор
fun dismissDeleteNote()
fun confirmDeleteNote()                   // → deleteNote(pending)
suspend/fun deleteNote(relativePath: String)
```

- `request*` только кладёт `pendingDeleteNote` в `UiState` (диалог).
- `confirmDeleteNote` читает pending, чистит pending, запускает IO.

### 9.2. SAF delete

1. Резолв **реального** folder document id (listing родителя по имени, как в create — не только guess `$rootId/$rel`).
2. Recursive delete:
   - Предпочтительно: удалить документ папки целиком, если провайдер удаляет дерево.
   - Fallback (Huawei и др.): DFS — сначала дети (dirs recursive, files), затем сама папка; через `SafTreeLister` + `DocumentsContract.deleteDocument`.
3. Переиспользовать / усилить `SafLocalFileSystem.delete(root, relativePath, isFolder = true)` так, чтобы Notes и sync видели одно поведение.

### 9.3. После FS

- `clearSelectedNote()` при необходимости (§6.1)
- `refreshNotesTreeSuspend()`
- `requestLocalPush()`
- Не вызывать `selectNotesNode` на удалённый путь

### 9.4. UI wiring

- `NotesTreeItem` DropdownMenu: + `Delete` → `onDeleteNote(row)`
- `NotesEditorPane` overflow: + `Delete note` → `onDeleteCurrentNote`
- `AlertDialog` по `state.pendingDeleteNote` (рядом с create/conflict dialogs)

## 10. Локализация / копирайт (EN v1)

| Ключ | Строка |
|------|--------|
| Menu (tree) | `Delete` |
| Menu (editor) | `Delete note` |
| Dialog title | `Delete note` |
| Body leaf | `Delete “{name}”? This cannot be undone.` |
| Body nested | `Delete “{name}” and all notes inside? This cannot be undone.` |
| Actions | `Cancel` / `Delete` |
| Root guard | `Cannot delete the notes root` |
| Status ok | `Deleted “{name}”` |
| Status fail | `Could not delete note: …` |

(RU — отдельная итерация, как create.)

## 11. Acceptance

- [x] Long-press → Delete → Cancel — FS без изменений. (UI)
- [x] Long-press → Delete → Delete на leaf — папка и `index.html` исчезают с диска и из дерева. (adb: `force_delete_note` → `OK delete=…`, папка исчезла)
- [ ] Delete узла с детьми — удаляются все вложенные; confirm текст про «all notes inside». — ручная проверка
- [x] Нельзя удалить notes root.
- [x] Delete открытой заметки — редактор очищается, selected сброшен. (код §6.1 + leaf self-test)
- [ ] Delete предка открытой — редактор очищается. — ручная проверка
- [ ] Delete другой ветки — текущая открытая остаётся. — ручная проверка
- [x] После delete: local push запрошен; Notes не зовёт Cloud API напрямую.
- [x] Editor overflow «Delete note» работает для текущей страницы. (UI wiring)
- [x] Ошибка SAF показывается пользователю; приложение не падает.

## 12. Вне скоупа v1

- Rename / move  
- Trash, Undo, Snackbar restore  
- Swipe-to-delete, multi-select  
- Soft-delete sidecar / `_trash`  
- Отдельный «Delete only index.html, keep folder»  

## 13. Решения

- 2026-10-09 — паритет Windows: recursive folder delete + confirm; без trash/undo/swipe.  
- 2026-10-09 — confirm усиливает Win: явное предупреждение при `hasChildren`.  
- 2026-10-09 — entry points: long-press + editor overflow; один `pendingDeleteNote` pipeline.  
- 2026-10-09 — sync только через диск + `requestLocalPush()`.

## 14. Статус реализации

- 2026-10-09 — ТЗ зафиксировано.
- 2026-10-09 — v1: `PendingDeleteNote`, long-press Delete + editor «Delete note», recursive SAF delete в `SafLocalFileSystem`, `requestLocalPush`. Self-test: `files/force_delete_note` → `files/delete_note_last.txt`.
