# Dependency and licence record

Last reviewed: 2026-09-12 for release `1.0.3` (`versionCode 4`).

## Scope

`./gradlew :app:dependencies --configuration releaseRuntimeClasspath` completed
successfully for this release. The full resolved graph includes AndroidX,
Kotlin and CameraX transitive components. This record identifies the direct
runtime dependencies selected by this project; it does not replace the notices
and licence files distributed by each upstream component.

## Direct runtime dependencies

| Dependency | Version declared | Licence | Use |
| --- | --- | --- | --- |
| `androidx.activity:activity` | `1.13.0` | Apache-2.0 | Activity integration |
| `androidx.camera:camera-core` | `1.6.2` | Apache-2.0 | Camera pipeline |
| `androidx.camera:camera-camera2` | `1.6.2` | Apache-2.0 | Camera2 implementation |
| `androidx.camera:camera-lifecycle` | `1.6.2` | Apache-2.0 | Lifecycle binding |
| `androidx.camera:camera-view` | `1.6.2` | Apache-2.0 | Preview view |
| `com.google.zxing:core` | `3.5.4` | Apache-2.0 | Local QR/barcode decoding |

Apache License 2.0 is compatible with GPLv3. The application itself is offered
under GPL-3.0-or-later; this does not erase upstream copyright, notice or
licence obligations. Any dependency change requires a fresh resolved-graph and
licence review before a new release.

## Test-only dependency

| Dependency | Version declared | Licence | Use |
| --- | --- | --- | --- |
| `junit:junit` | `4.13.2` | EPL-1.0 | JVM unit tests (`testImplementation`) |

It is used only to run the unit tests and is not packaged in the APK.
