# ProtoLink.Communicator.Android

Android client for ProtoLink Communicator (Messenger, Notes, Cloud) with shared 3-way sync engine.

## Modules

- `:sync` — platform-agnostic reconcile (FS ↔ metadata ↔ remote), local-wins
- `:app` — Compose UI, Retrofit, Room metadata, WorkManager poll, SAF folders

## Build

```bat
gradlew.bat :sync:test :app:assembleDebug
```

Set `sdk.dir` in `local.properties`. API base defaults to `http://protolink.ru/`.
