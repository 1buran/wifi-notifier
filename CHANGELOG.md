# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [v1.0.0] - 2026-09-26

### Added

- The foreground service that watches Wi-Fi connections and reports the name of
  the network the phone has joined. It posts a silent ongoing notification with
  the current network (and a `Stop` action) plus an alerting notification on
  every new connection; returning to a network that was left earlier alerts
  again.
- `WifiSsid`: the single place that reads the SSID from the system. It tries the
  active network first, then the networks reported by the connectivity
  callbacks, then `WifiManager`, and filters out the placeholders the system
  uses for a redacted name (`<unknown ssid>`, `0x`).
- The launcher activity that asks for the permissions, starts the service and
  closes itself: the app has no screen of its own, the state of the app is its
  notifications.
- Adaptive launcher icon and a monochrome notification icon.
- Release signing: `app/build.gradle.kts` reads `keystore.properties` and signs the
  release build when the key is present, and leaves the APK unsigned when it is
  not, so a fresh clone and F-Droid's build server still build.
- A self-hosted F-Droid repository: `fdroid/metadata/com.buran.wifinotifier.yml`
  is the build recipe, `fdroid/categories.yml` names the categories, the listing
  lives in `fastlane/metadata/android/en-US/`, and `tools/publish-fdroid.sh`
  builds the signed APK, regenerates the index with fdroidserver and publishes it
  to the `gh-pages` branch that GitHub Pages serves.

[Unreleased]: https://github.com/1buran/wifi-notifier/compare/v1.0.0...HEAD
[v1.0.0]: https://github.com/1buran/wifi-notifier/releases/tag/v1.0.0
