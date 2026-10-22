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

Release builds are signed with **v1 + v2 + v3**: `signingConfigs.release` in `app/build.gradle.kts`, then `assembleRelease` re-signs with `apksigner` (`--min-sdk-version 19`) so OEM scanners see a JAR cert under `META-INF` (AGP alone can omit usable v1 when `minSdk` ≥ 24). Credentials come from a gitignored `keystore.properties` at the repo root (never commit it). The keystore file itself lives **outside** the repo.

On this Agents machine the release keystore path is:

`C:\Users\Plint\.protolink\android\protolink-communicator-release.jks`

(alias `protolink`). Passwords stay only in local `keystore.properties` / Deploy notes (link only — never paste into Agents tasks). Ops: Agents `docs/ops/android-apk-signing`.

```bat
set JAVA_HOME=C:\path\to\jdk-17
gradlew.bat assembleRelease
```

Expected output (Gradle names the APK; then copies it):

- `artifacts/ProtoLink.Communicator.Android-<versionName>.apk` (today `1.0.4`)
- Sibling `artifacts/ProtoLink.Communicator.Android-<versionName>.apk.release.json` (`component`, `version`, `versionCode`, `commit`, `buildDate`)

Verify before publish:

```bat
"%ANDROID_HOME%\build-tools\34.0.0\apksigner.bat" verify --print-certs artifacts\ProtoLink.Communicator.Android-1.0.4.apk
"%ANDROID_HOME%\build-tools\34.0.0\apksigner.bat" verify --verbose --min-sdk-version 19 artifacts\ProtoLink.Communicator.Android-1.0.4.apk
```

The second command must report v1, v2, and v3 as `true` (default verify against the APK’s `minSdk` 28 may show only v3 “used”).

Upload with the Windows publish script, pointing at the versioned file:

```powershell
.\tools\Publish-Release-To-Cloud.ps1 -BaseUrl <stand-or-prod> -SkipWindows `
  -AndroidApkPath .\artifacts\ProtoLink.Communicator.Android-1.0.4.apk
```

(Run that script from `ProtoLink.Communicator.Windows`; default Android path is also the newest `ProtoLink.Communicator.Android-*.apk` under that repo’s `artifacts/` if you copy the APK there.)

## Release tags

After cutting the APK, the person who built it tags `v<versionName>` on the **exact commit that was built**. Deploy does **not** tag this repo.
