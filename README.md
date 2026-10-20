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

JDK 17 must be on `JAVA_HOME`. The repo does not set `org.gradle.java.home`; a machine-specific JDK path belongs in the user Gradle file (`~/.gradle/gradle.properties`), not here. Also set `sdk.dir` in `local.properties` (gitignored). API base defaults to `http://protolink.ru/`.

Windows:

```bat
set JAVA_HOME=C:\path\to\jdk-17
gradlew.bat assembleDebug testDebugUnitTest
```

Linux / macOS (`gradlew` is the Unix wrapper):

```sh
export JAVA_HOME=/path/to/jdk-17
./gradlew assembleDebug testDebugUnitTest
```

`assembleDebug` and `testDebugUnitTest` run for both `:app` and `:sync`.

## Release APK

Version source of truth: `app/build.gradle.kts` (`versionName` + `versionCode` only). Do not duplicate those numbers elsewhere.

```bat
set JAVA_HOME=C:\path\to\jdk-17
gradlew.bat assembleRelease
```

Expected output (Gradle names the APK; then copies it):

- `artifacts/ProtoLink.Communicator.Android-<versionName>.apk` (today `1.0.3`)
- Sibling `artifacts/ProtoLink.Communicator.Android-<versionName>.apk.release.json` (`component`, `version`, `versionCode`, `commit`, `buildDate`)

Upload with the Windows publish script, pointing at the versioned file:

```powershell
.\tools\Publish-Release-To-Cloud.ps1 -BaseUrl <stand-or-prod> -SkipWindows `
  -AndroidApkPath .\artifacts\ProtoLink.Communicator.Android-1.0.3.apk
```

(Run that script from `ProtoLink.Communicator.Windows`; default Android path is also the newest `ProtoLink.Communicator.Android-*.apk` under that repo’s `artifacts/` if you copy the APK there.)

## Release tags

After cutting the APK, the person who built it tags `v<versionName>` on the **exact commit that was built**. Deploy does **not** tag this repo.
