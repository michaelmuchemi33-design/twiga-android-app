# TWIGA Android Telephone App

The TWIGA Android app is the phone-side product and telephony bridge for a business using its existing Android handset and SIM. It is built on the OpenDialer project ([Apache-2.0 licence](https://github.com/oxcened/opendialer)).

## Current app capabilities

- Android phone/dialler experience with incoming and outgoing cellular call UI through Android Telecom.
- Call notifications, call controls, call history and contact lookup (subject to user-granted permissions and Android role/default-dialler settings).
- Optional, user-enabled auto-answer setting. It is not silently enabled.
- Business workspace entry points for pairing the phone to its TWIGA business account.
- Device pairing and authenticated event sync with the TWIGA Supabase backend.
- Offline queue for recent device call events while the phone has no data connection.
- POS and business assistant screens included in the app shell.
- An in-app AI voice test page using the device microphone and internet connection.

## Important SIM-call / AI boundary

The Android app is the telephony bridge, but Android does not provide ordinary third-party apps with unrestricted access to the protected audio stream of a carrier cellular call. Being the default dialler or implementing `InCallService` gives supported call-control and call-state access; it does **not** grant universal cellular audio capture or injection.

The in-app AI voice test is an internet voice session and is not proof that a cellular SIM call is connected to AI. AI participation in an existing SIM call needs a compatible carrier conference-call / network bridge path. That path must be tested with the actual SIM, carrier, handset and Android build. If the carrier cannot conference a second call, the app cannot transparently merge the AI into that SIM call. The app must not report an AI-connected state until the bridge is actually established.

## Build locally

```bash
./gradlew testDebugUnitTest assembleDebug
```

Debug APK output: `app/build/outputs/apk/debug/app-debug.apk`.

## GitHub Actions build

The TWIGA Android CI workflow runs tests and builds a debug APK on pull requests and main-branch pushes. It uploads the debug APK as a downloadable Actions artifact for installation/testing.

A production release is published only when the repository has a permanent signing keystore configured through GitHub Actions secrets:

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

Without that permanent key, CI deliberately does not publish a release-signed APK. This avoids shipping an APK signed with a temporary debug key that cannot reliably receive future app updates.

## Security

- The APK contains only the public Supabase project URL and publishable client key.
- Device pairing uses a per-device token; do not expose or log it.
- Retell/Vapi private API keys and other server secrets belong in server-side secret storage, never in the APK or public website.
- Keep the phone paired only to the correct business and revoke its device token if it is lost.

## Release checks still required

Before describing the cellular AI bridge as production-ready, test incoming SIM calls, outgoing SIM calls, carrier conference/merge, Bluetooth and speaker routing, device reboot/background behaviour, permission revocation, offline event replay, and the full provider call lifecycle on supported Android 10+ handsets and Kenyan networks. Record which carriers/devices pass. Do not treat a successful APK compilation as proof of those real-world telephony tests.
