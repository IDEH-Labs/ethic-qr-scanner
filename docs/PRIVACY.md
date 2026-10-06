# Privacy design record

Last reviewed: 2026-09-12, against version 1.0.3 (`versionCode 4`).

## Data flow

1. The scanner opens as the app's primary function.
2. Android asks for the camera permission when necessary.
3. Camera frames are supplied to ZXing in memory for local decoding.
4. The decoded string is displayed locally. It can be copied or, for a valid
   HTTP(S) URL only, passed to the device's browser after another explicit tap.

No application network permission is declared. Ethic QR Scanner does not store a
scan history, camera frame, account identifier, analytics event or advertisement
identifier.

The optional photo feature receives one image URI from Android's system picker,
decodes it in memory, and releases it after the result. It does not request
storage permission or retain a copy.

## Safety boundary

An HTTP(S) link can still lead to a malicious website. This application does
not claim to determine whether a link is safe, does not query reputation
services, and does not open it automatically. The final decision belongs to the
person using the device.

Before a link can be offered for opening, an offline syntactic review
(`LinkSafety`) runs on the device:

- The confirmation names the host the browser will contact, in the ASCII form
  browsers use. An international (IDN) or `xn--` host is also shown as written
  and flagged, because its letters can imitate a well-known site.
- Text before an `@`, a numeric (IP) host and plain HTTP are flagged.
- A link stays copy-only when it is longer than 2,048 characters or contains a
  backslash, whitespace, invisible or text-direction characters, a
  percent-encoded host, or letters (`ß`, `ς`) that IDNA2003 and browsers convert
  differently. In each case the host shown could differ from the one contacted.

Invisible and text-direction characters in any decoded content are displayed as
`⟨U+XXXX⟩` markers; Copy keeps the exact original payload. This review cannot
detect look-alike names written only in ASCII (for example `paypa1.com`).

Rotating the device keeps a result under review without recreating the
activity; decoded content is not written to Android's saved-instance state.

## Permission

`android.permission.CAMERA` is required only to use the scanning feature. It
is declared in the manifest and requested at runtime when the scanner opens.
The release APK also declares the app-scoped
`org.jpi59.ethicqrscanner.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`, generated
by AndroidX to protect dynamic receivers. It is not a user-grantable sensitive
permission and does not provide network, storage, location or microphone access.
