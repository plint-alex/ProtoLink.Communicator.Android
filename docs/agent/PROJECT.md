# ProtoLink.Communicator.Android

> Живая документация для Cursor-агента.

## Назначение

Android-клиент ProtoLink Communicator (Messenger, Notes, Cloud) с общим 3-way sync.

## Структура

- `:sync` — platform-agnostic reconcile (FS ↔ metadata ↔ remote)
- `:app` — Compose UI, Retrofit, Room metadata, WorkManager poll, SAF folders
- `app/src/main/assets/notes-editor.js` — копия shared editor shell (канон: Windows `shared/notes-editor/`)

## Как запускать / проверять

```bat
gradlew.bat :sync:test :app:assembleDebug
```

Нужен `sdk.dir` в `local.properties`. API по умолчанию: `http://protolink.ru/`.

Editor JS tests (на машине разработки, из Windows repo):

```bat
cd ..\ProtoLink.Communicator.Windows\shared\notes-editor
node run-tests.mjs
```

## Ключевые решения

- Notes пишет диск; после save и по таймеру 15s Cloud делает **local-only push**.
- Startup / SignalR `data_changed` / manual Sync → **full reconcile**.
- Контракт sync общий с Windows — не расходиться без явного решения.
- Notes editor HTML совместим с Windows (`#editor` innerHTML, `ul.checkbox-list`).
- Notes create/delete v1: папка + `index.html`; FAB / long-press / editor; delete recursive + confirm (ТЗ ниже).
- JWT: `TokenAuthenticator` + `TokenRefresher` (silent refresh, как Windows AuthHandler).
- Sync conflict UI: Take from server / Keep local по файлу (`SyncConflictException.mappingId`).

## Грабли и запреты

- Не смешивать Notes UI со статусами cloud.
- Не запускать параллельные sync (exclusivity) — см. общий док.
- Не создавать новый mapped-root folder при map local folder.
- После правок editor JS — копировать из Windows `shared/notes-editor/notes-editor.js` в `assets/`. Нижний format bar + IME padding; link через Compose dialog.

## Связанные доки

- `README.md` — обзор модулей
- `docs/notes-create-android-spec.md` — ТЗ создания заметки (Android)
- `docs/notes-delete-android-spec.md` — ТЗ удаления заметки (Android)
- `../ProtoLink.Communicator.Windows/docs/notes-and-cloud-sync.md` — канон Notes/Cloud sync
- `../ProtoLink.Communicator.Windows/docs/notes-editor-spec.md` — ТЗ редактора
- `../ProtoLink.Communicator.Windows/shared/notes-editor/README.md` — shared editor shell

## Открытые вопросы / TODO

- Rename заметки на Android — нет (create/delete v1 уже есть).
- Порядок дерева Notes сейчас A–Z по имени; общий sidecar order с Windows — не сделан.
- adb install на Huawei часто виснет — ставить через `adb push` + `pm install -r -t /data/local/tmp/…`.

## Грабли sync (Notes)

- Notes root и Cloud mapping на телефоне оба `primary:ProtoLink` (OK).
- Обычный Sync останавливается на `SyncConflictException` (local≠remote≠meta) и показывает диалог по **конкретному файлу**: Take from server / Keep local; после выбора sync продолжается.
- Bulk-обход по-прежнему: Cloud → mapped folder **Notes** → **Force download** / Force upload.
- adb Force download (надёжно на Huawei):  
  `adb shell run-as ru.protolink.communicator.debug sh -c "echo 1 > files/force_download"`  
  затем `am start -n ru.protolink.communicator.debug/ru.protolink.communicator.MainActivity`  
  (intent `--es protolink_force download` на части прошивок не доходит). Логи: tag `ProtoLinkSync` (Log.e).
- Android раньше не делал silent JWT refresh → после истечения access token sync давал 401, Force download не тянул облако. С 2026-10-09: `TokenAuthenticator` + `TokenRefresher` (как Windows AuthHandler). Если refresh тоже мёртв — нужен re-login на телефоне.
- Локальный Notes на ПК: `%LOCALAPPDATA%\ProtoLinkCommunicator\NotesFromOneNote\` (в т.ч. `вишлист`). 2026-10-09: после сбоев Force download содержимое с ПК залито на телефон в `/storage/emulated/0/ProtoLink/` (Вишлист 1205 байт). Ошибка force пишется в `files/force_last_error.txt`.
- 2026-10-08: format bar редактора перенесена **наверх** (под title).

## История (кратко)

- 2026-10-09 — Notes create + delete v1 (SAF); open-after-create await; conflict dialog; JWT refresh; Force download file trigger
- 2026-10-08 — format bar редактора перенесена **наверх** (под title)
- 2026-10-07 — notes editor assets: Enter mid-item split, Ctrl+1, per-line ☐, checkbox click fix (parity with Windows shared JS)
- 2026-10-06 — notes editor parity: shared JS shell, bottom format bar, paste sanitize, checklist Enter/BS
- 2026-09-26 — создан каркас agent-дока из README + sync-дока
