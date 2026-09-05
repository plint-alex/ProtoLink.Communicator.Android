# ProtoLink.Communicator.Android

Android client for ProtoLink Communicator (Messenger, Notes, Cloud) with shared 3-way sync engine.

## Modules

- `:sync` — platform-agnostic reconcile (FS ↔ metadata ↔ remote)
- `:app` — Compose UI, Retrofit, Room metadata, WorkManager poll, SAF folders

## Notes vs Cloud

Notes writes disk; after save (and on a 15s timer) Cloud does a **local-only push**.  
Startup / SignalR `data_changed` / manual Sync run a **full reconcile**.  
See [../ProtoLink.Communicator.Windows/docs/notes-and-cloud-sync.md](../../ProtoLink.Communicator.Windows/docs/notes-and-cloud-sync.md)  
(or the same doc in the Windows repo: `docs/notes-and-cloud-sync.md`).

## Build

```bat
gradlew.bat :sync:test :app:assembleDebug
```

Set `sdk.dir` in `local.properties`. API base defaults to `http://protolink.ru/`.
