# AGENTS.md

Instructions for AI agents working in this repository.

## Language policy

- Every file in the repository — code, comments, `README.md`, `CHANGELOG.md`,
  commit messages and this file — is English only.
- The single exception lives outside the repository: the worklog
  `~/worklogs/wifi-notifier.txt` is written in Russian (see "Worklog" below).
- All user-facing strings live in `res/values/strings.xml` and are English. The
  app ships no other locale on purpose; adding one means adding
  `res/values-<lang>/strings.xml` for every new string.

## About the project

Wi-Fi Notifier is a single-purpose Android app: it notices which Wi-Fi network
the phone has connected to and reports the name of that network with
notifications. There is no UI — the app is its two notifications.

- `WifiWatchService` — a foreground service that watches connectivity, keeps the
  ongoing notification up to date and posts the alerting one. It is the whole
  application logic.
- `WifiSsid` — the only place that asks the system for the SSID. Nothing else in
  the app touches `ConnectivityManager`, `WifiManager` or `WifiInfo`.
- `MainActivity` — a translucent activity with no content that asks for the three
  runtime permissions, starts the service and calls `finish()`.

Three deliberate constraints shape the code:

1. **No third-party dependencies.** The app builds against the Android SDK and
   the Kotlin standard library only: no AndroidX, no Compose. `Notification`
   and `NotificationChannel` are used directly instead of `NotificationCompat`,
   `Activity` instead of `AppCompatActivity`. Adding a dependency needs a reason
   that cannot be met with the platform APIs.
2. **`minSdk` is 33**, so there are no legacy branches: the code may use
   `NotificationChannel`, `NEARBY_WIFI_DEVICES`, runtime permission APIs and
   `startForeground(id, notification, type)` unconditionally. Lowering `minSdk`
   means reintroducing those branches, not just editing the number.
3. **One responsibility.** The app reports the name of the network it is
   connected to. It does not scan, list or rank networks, and it never asks for
   a Wi-Fi scan result.

## Domain rules that must not be broken

Each of these was found by testing on an API 34 emulator; breaking one of them
silently produces `<unknown ssid>` or a stale notification instead of a build
error.

- **Never add `android:usesPermissionFlags="neverForLocation"` to
  `NEARBY_WIFI_DEVICES`.** The flag tells the system the app does not derive
  location from Wi-Fi, and the system then redacts SSID and BSSID.
- **`ACCESS_FINE_LOCATION` is required in practice.** On paper Android 13+ hands
  out the SSID with `NEARBY_WIFI_DEVICES` alone; in reality the emulator and many
  firmware builds return the redacted placeholder until location is granted and
  location services are enabled. `MainActivity` therefore requests both, and
  `hasWifiPermission()` accepts either.
- **The foreground service must be typed `location`.** A "while in use" location
  permission keeps working for a background service only when that service is
  declared as a location service. With `specialUse` the app reads the SSID
  correctly right after the activity closes and then loses it: `refresh()` starts
  returning null while the phone is still connected. `specialUse` is kept in the
  manifest and in `FOREGROUND_SERVICE_TYPES` purely as a fallback for the case
  when a location service cannot be started.
- **Update the ongoing notification with `startForeground`, not with
  `NotificationManager.notify`.** `notify()` with the same id does not update
  the notification of a running foreground service; the shade keeps showing the
  old text. `buildOngoing()` is fed to `startForeground(ONGOING_ID, ...)` every
  time the text changes.
- **Re-register the `NetworkCallback` whenever the permission set changes.** The
  SSID that arrives with a callback is redacted according to the permissions held
  when the callback was registered, and it stays redacted for the lifetime of that
  registration. `refresh()` compares `permissionSnapshot` and calls
  `registerWifiCallback()` (which unregisters the old callback first).
- **The active network is not always Wi-Fi.** A phone can be on Ethernet or
  mobile data with a Wi-Fi network joined alongside it, and an emulator reports
  its virtual Wi-Fi exactly that way: `getNetworkCapabilities(activeNetwork)`
  has `TRANSPORT_WIFI == false` while `WifiManager.getConnectionInfo()` returns a
  perfectly good `"AndroidWifi"`. Hence the three sources in
  `WifiSsid.current()`: active network, callback-reported networks, then
  `WifiManager`. The `WifiManager` path must keep its
  `SupplicantState.COMPLETED` check, otherwise a stale SSID is reported after a
  disconnect.
- **Do not use `ConnectivityManager.getAllNetworks()`** — deprecated in API 36.
  Use the networks delivered to the `NetworkCallback` and validate them with
  `getNetworkCapabilities(network)`, which is not deprecated.
- **Do not call `finish()` before the permission result arrives**, and do not set
  `android:noHistory="true"` on the activity: the activity would be finished
  while the permission dialog is up and `onRequestPermissionsResult` would never
  be delivered, so the service would never start.

## Commands

Run Gradle from the repository root:

```sh
./gradlew assembleDebug          # debug APK, no signing needed
./gradlew assembleRelease        # unsigned release APK
./gradlew lint                   # worth running after touching the manifest
```

`local.properties` must point at the SDK (`sdk.dir=/path/to/Android/Sdk`);
`ANDROID_HOME` works as an alternative. Any installed JDK 17 runs the build.

Install and drive the app on a running emulator or device:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.buran.wifinotifier/.MainActivity
adb logcat -s WifiWatchService:*          # the whole story of the app
```

## Environment notes

- Android Gradle Plugin 9.4.1 with Gradle 9.6.0 (both pinned in `build.gradle.kts`
  and `gradle/wrapper/gradle-wrapper.properties`). AGP 9 requires Gradle 9.6.0 or
  newer; AGP 9.3.1 was the previous pairing with Gradle 9.5.0.
- **Kotlin support is built into AGP 9**: applying
  `org.jetbrains.kotlin.android` fails the build with "no longer required", and
  the `kotlin { compilerOptions { ... } }` block does not exist either. Configure
  the compiler through `android { compileOptions { ... } }` and the `android`
  block DSL instead.
- `compileSdk` and `targetSdk` are 36. There is no `platforms;android-37` in the
  SDK repository yet (`sdkmanager --install "platforms;android-37"` answers
  "Package platforms/android-37 not found"), so 36 is the newest usable target.
- For emulator runs the AVD must be found by the emulator binary:

  ```sh
  ANDROID_AVD_HOME=/home/buran/.config/.android/avd \
    ~/Android/Sdk/emulator/emulator -avd wifi34 -no-window -no-audio \
    -no-boot-anim -gpu swiftshader_indirect
  ```

  `avdmanager` writes AVDs to `~/.config/.android/avd` while the emulator looks
  in `~/.android/avd`, so the variable is required. The AVD `wifi34` is
  android-34/google_apis_playstore/x86_64 and its virtual network is called
  `AndroidWifi`.

## Verification

The app is small but its behaviour depends on Android's permission and
foreground-service rules, so verify on an emulator instead of trusting a
successful build:

```sh
# 1. clean install, then answer the three permission dialogs in the UI
adb uninstall com.buran.wifinotifier
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb logcat -c && adb shell am start -n com.buran.wifinotifier/.MainActivity

# 2. the service must report the network right away
adb logcat -d -s WifiWatchService:*     # "Wi-Fi state: connected:AndroidWifi"

# 3. disconnecting must not leave the old name behind
adb shell svc wifi disable && sleep 20
adb logcat -d -s WifiWatchService:*     # "Wi-Fi state: disconnected (onLost)"

# 4. reconnecting must alert again
adb shell svc wifi enable && sleep 25
adb shell dumpsys notification --noredact | grep -E "android.title|android.text"
```

What a healthy run looks like: `Wi-Fi state: connected:AndroidWifi (service
start)`, both notifications present (`id=1` on the `wifi_current` channel with
the `Stop` action, `id=2` on `wifi_events`), and `dumpsys activity services
com.buran.wifinotifier` reporting `types=00000008`
(`FOREGROUND_SERVICE_TYPE_LOCATION`). If the state flips to `disconnected` a few
seconds after the activity closes, the FGS type regressed — see "Domain rules".

Two habits that save time here:

- **Answer the permission dialogs by tapping the UI**, not with
  `pm grant`: a permission granted to an already running process does not always
  un-redact the SSID, and the emulator sends its Wi-Fi through a non-Wi-Fi
  transport, so `pm grant` alone can hide a real bug.
- **Read `dumpsys notification --noredact` rather than the shade**: the shade
  shows what the user sees, the dump shows what was actually posted.

## Design principles

- Keep the watcher one class. `WifiWatchService` is allowed to be a couple of
  hundred lines long; extracting a "manager", a "repository" or an event bus for
  two notifications in a private app adds indirection without a reader.
- State transitions are explicit: `refresh()` computes one state string
  (`connected:<ssid>`, `disconnected`, `no_permission`), compares it with
  `shownState` and returns early when nothing changed. Every notification and
  every log line hangs off that single comparison, so a new source of state must
  go through `refresh()` rather than posting its own notification.
- `WifiSsid` is the single place where the platform is asked for the SSID, and it
  returns a "clean or null" value: quoting, `<unknown ssid>` and `0x…`
  placeholders never leak into the rest of the app.
- Anything the system can throw (a missing service, a denied permission, a
  revoked foreground start) is handled with `runCatching`/`try` around the call
  and logged with the `WifiWatchService` tag — the app must not crash in the
  background, where nobody can see the crash.
- Log messages are English, lowercase, and describe the state rather than the
  code path: `Wi-Fi state: connected:AndroidWifi (onLost)`.

## Coding style

- Kotlin, 4-space indent, one class per file in
  `app/src/main/java/com/buran/wifinotifier/`.
- No wildcard imports, no unused imports, no `TODO` without an issue.
- Keep the build warning-free: the tree currently compiles with zero warnings,
  and a deprecation warning is a signal to look for a supported API
  (`getAllNetworks()` → callbacks, `WifiManager.connectionInfo` → kept, with
  `@Suppress("DEPRECATION")` and a comment explaining why).
- Comments explain *why*, not *what*: the manifest and the service are full of
  platform-behaviour notes that must stay in place, because the "obvious"
  simplification (a different FGS type, `notify()` instead of `startForeground`)
  is exactly what breaks the app.

## Documentation

- `README.md` — what the app does, the permissions it asks for, how to build,
  install and what it does not do. Update it when the user-visible behaviour or
  the permission set changes.
- `CHANGELOG.md` — [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) with
  [Semantic Versioning](https://semver.org/spec/v2.0.0.html): a new change goes
  under `## [Unreleased]`, and a release renames that section to
  `## [vX.Y.Z] - YYYY-MM-DD` and adds the link definitions. `versionName` in
  `app/build.gradle.kts` follows the same version.
- `docs/` — images referenced by the README. `docs/notifications.png` is a real
  screenshot of the notification shade; re-take it from the emulator when the
  notification layout or its text changes.
- `fastlane/metadata/android/en-US/` — the F-Droid listing: title, short and full
  description, `changelogs/<versionCode>.txt` and
  `images/phoneScreenshots/*.png`. Add a changelog file for every new
  `versionCode`, or the store entry keeps showing the previous one.

## Release and publishing

The app ships through a self-hosted F-Droid repository served by GitHub Pages,
not through the official one: `https://1buran.github.io/wifi-notifier/repo`.

### Keys

Both signing keys live in `~/.config/wifi-notifier/` and must never enter the
git tree:

| File | Alias | Signs |
| --- | --- | --- |
| `release.keystore` | `wifi-notifier` | the release APK (Gradle reads it through `keystore.properties`) |
| `release.keystore` | `fdroid` | the repository index (fdroidserver reads it through the generated `config.yml`) |

- `keystore.properties` in the project root is gitignored and holds the store
  path and the passwords; it is the only thing Gradle needs.
- The build must keep working **without** those files: `app/build.gradle.kts`
  only creates the `release` signing config when `keystore.properties` exists,
  so a fresh clone and F-Droid's build server get an unsigned APK instead of
  a failure. Do not make the signing config mandatory.
- Both aliases share one keystore because fdroidserver expects a single
  `keystore` per configuration, with `keyalias` for the app and `repo_keyalias`
  for the index.
- Back these files up: losing the keystore means the published app can never be
  updated, only reinstalled.

### Versioning

- `versionName` must equal the git tag without the `v` (`v1.0.0` → `1.0.0`),
  because the metadata uses `UpdateCheckMode: Tags` with
  `AutoUpdateMode: Version` and the updater compares the two.
- `versionCode` must strictly increase with every published build: F-Droid
  refuses two versions with the same code.
- Release procedure: bump `versionCode` (and `versionName` when the version
  changes), add `fastlane/.../changelogs/<versionCode>.txt`, add a `Builds`
  entry to the metadata for the official-track record, commit, tag `vX.Y.Z`,
  push the tag, then run the publish script.

### Publishing

```sh
tools/publish-fdroid.sh              # build, re-index, push gh-pages
tools/publish-fdroid.sh --no-push    # same, but stop before the git step
```

The script keeps everything outside the repository: the fdroidserver workdir is
`~/.cache/wifi-notifier-fdroid` (its `config.yml` contains the password), and
only the resulting `repo/` directory is pushed, to the `gh-pages` branch.
`gh-pages` is generated output: never edit it by hand, and never move the
repository's files into `main`.

GitHub Pages serves the `gh-pages` branch (source: branch `gh-pages`, folder
`/ (root)`, enabled once in the repository settings). The client URL is the
`repo/` subdirectory of the site, which is why `repo_url` in the config ends
with `/repo`. `fdroid/index.html` is copied to the branch root as a landing
page: without it the site answers 404 at `https://1buran.github.io/wifi-notifier`
and a broken repository is the first thing anyone opening that link sees.

### fdroidserver notes

Learned the hard way, with fdroidserver 2.4.5:

- `repo_url` **must** end with `/repo`, otherwise `fdroid update` refuses to
  start.
- `repo_icon` is a bare file name, not a path: fdroidserver looks for that file
  in the workdir root and copies it to `repo/icons/` itself. Putting the icon
  straight into `repo/icons/` results in a generated placeholder instead.
- Categories are read from `config/<locale>/categories.yml` with plain string
  values. A file at `config/categories.yml` with nested `name: {en: ...}` maps
  produces a doubly nested entry in the index, and lint calls the categories
  invalid.
- `Metadata: fastlane` is **not** a valid app field in this version: fdroidserver
  picks `fastlane/metadata/android/<locale>/` up from the source tree on its own.
  The listing is additionally fed in through the classic
  `metadata/<id>/<locale>/` layout by the publish script.
- `fdroid lint` inside the workdir must report nothing before pushing.
- A warning "category defined but not used by any app" appears on every run even
  though the app lists both categories; the generated index is correct, so it is
  noise, not a failure.

Verification after publishing: `git ls-tree -r origin/gh-pages` lists the APK,
`index-v1.jar` and `index-v2.json`, and the fingerprint printed by the client
must match `3D:BC:FB:C0:E4:BC:36:01:E4:50:8A:96:01:E6:51:10:38:7E:F3:1E:D6:87:BA:1D:E5:A5:16:2D:46:82:28:0C`
(the `fdroid` alias of the release keystore).

## Commit & Pull Request Guidelines

- Commit messages follow
  [Conventional Commits](https://www.conventionalcommits.org/en/v1.0.0/#specification):
  `type: description`, lowercase and imperative (`feat: report the joined
  network`, `fix: keep the ssid after the activity closes`).
- Keep the subject within 50 characters and wrap the body at 72.
- Never commit or push without explicit approval from the user.
- Do not commit `local.properties`, APKs, screenshots of the whole desktop, or
  anything from `build/` — `.gitignore` covers those.

## Worklog

Every significant change is recorded in `~/worklogs/wifi-notifier.txt`;
`~/worklogs/ghost-messenger.txt` and `~/worklogs/ghost-rutor-bot.txt` are the
reference for the format. A change that lands a commit, changes behaviour or
produces a finding worth remembering is significant; a typo fix is not.

- One entry per session of work: `@date YYYY-MM-DD HH:MM`, a `@title` line, then
  prose paragraphs; `@pt <heading>` opens a section inside an entry.
- The newest entry goes on top. Older entries are history: never rewrite or
  delete them, even when they describe a state that no longer holds.
- Indent code, command output and error messages by four spaces.
- Close the entry with the `@next` items and the commits the work produced, one
  `<short-hash> <subject>` per line.
- Russian, because the worklog is a personal note that lives outside the
  repository, where the English-only rule does not apply.
- Write the entry in the same session as the work, after the commits are
  approved: the entry quotes their hashes.
