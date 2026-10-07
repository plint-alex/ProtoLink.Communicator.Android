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

## Грабли и запреты

- Не смешивать Notes UI со статусами cloud.
- Не запускать параллельные sync (exclusivity) — см. общий док.
- Не создавать новый mapped-root folder при map local folder.
- После правок editor JS — копировать из Windows `shared/notes-editor/notes-editor.js` в `assets/`. Нижний format bar + IME padding; link через Compose dialog.

## Связанные доки

- `README.md` — обзор модулей
- `../ProtoLink.Communicator.Windows/docs/notes-and-cloud-sync.md` — канон Notes/Cloud sync
- `../ProtoLink.Communicator.Windows/docs/notes-editor-spec.md` — ТЗ редактора
- `../ProtoLink.Communicator.Windows/shared/notes-editor/README.md` — shared editor shell

## Открытые вопросы / TODO

- Порядок дерева Notes сейчас A–Z по имени; общий sidecar order с Windows — не сделан.

## История (кратко)

- 2026-10-07 — notes editor assets: Enter mid-item split, Ctrl+1, per-line ☐, checkbox click fix (parity with Windows shared JS)
- 2026-10-06 — notes editor parity: shared JS shell, bottom format bar, paste sanitize, checklist Enter/BS
- 2026-09-26 — создан каркас agent-дока из README + sync-дока
