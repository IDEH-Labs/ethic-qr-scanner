# Ethic QR Scanner

[![Get it on F-Droid](https://fdroid.gitlab.io/artwork/badge/get-it-on.png)](https://f-droid.org/packages/org.jpi59.ethicqrscanner/)
[![F-Droid Version](https://img.shields.io/f-droid/v/org.jpi59.ethicqrscanner?logo=f-droid&logoColor=white&label=F-Droid)](https://f-droid.org/packages/org.jpi59.ethicqrscanner/)
[![License: GPL-3.0-or-later](https://img.shields.io/badge/License-GPL--3.0--or--later-blue.svg)](LICENSE)

Ethic QR Scanner is an original Android QR and barcode scanner by [IDEH Labs](https://ideh.top/), built around a
simple rule: decoding a code must not silently trigger an external action.

It scans using the device camera, decodes locally, and presents the result for
the person to review. HTTP(S) links are rendered as inactive text; opening one
requires a separate confirmation that shows the complete destination before
Android is asked to open a browser.

## Privacy commitments

- No account, analytics, advertising, network permission, or embedded web view.
- The scanner opens directly; Android requests camera access when the scanner
  starts because the camera is its primary function.
- A photo can be selected through Android's system picker; it is decoded in
  memory without storage permission and is not saved by the app.
- Frames are decoded in memory and are not saved or transmitted.
- A decoded HTTP(S) link is never made tappable inline. The app can delegate
  only URLs up to 2,048 characters after an explicit confirmation that names
  the host the browser will contact and flags look-alike (international),
  numeric, `@`-prefixed and unencrypted links. Links that could hide their real
  destination, longer links and other payloads remain available to copy, not to
  open from the app. See [docs/PRIVACY.md](docs/PRIVACY.md#safety-boundary).
- Invisible and text-direction characters in decoded content are shown as
  visible markers instead of silently altering what is displayed.
- No scan history is included in version 1.0.3.
- Copying a result uses Android's local clipboard service; the system may expose
  clipboard data to the current keyboard or other system components according
  to the device's own privacy rules.
- The packaged APK also contains AndroidX's app-scoped
  `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`. It protects internally generated
  dynamic receivers; it is not a user-grantable runtime permission and does not
  add network, camera, storage or location access.

## Provenance and licensing

This is an independent implementation. It does not include source code, visual
assets, brand, text, screenshots or icons from SECUSO's Privacy Friendly QR
Scanner or another QR scanner.

Application code is GPL-3.0-or-later. It uses CameraX (AndroidX) and ZXing Core
for local decoding. The direct runtime dependencies and their licence-review
scope are documented in [docs/DEPENDENCIES.md](docs/DEPENDENCIES.md). Their
permissive licences are GPLv3-compatible; retained notices remain applicable.

The name **Ethic QR Scanner** is provisional. A basic web search found no exact
application-name collision, but that is not a trademark clearance.

## Build from source

This repository includes its Gradle wrapper and can be built without relying on
another project in the workspace:

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
./gradlew :app:assembleRelease :app:lintVitalRelease
```

Version `1.0.3` is officially published and verified in the [F-Droid main repository](https://f-droid.org/packages/org.jpi59.ethicqrscanner/).

Production-signed APKs and SHA-256 checksums are also available on [GitHub Releases](https://github.com/IDEH-Labs/ethic-qr-scanner/releases).
