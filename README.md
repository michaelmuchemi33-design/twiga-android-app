# Unity AI Receptionist (Android MVP)

This repository is based on [OpenDialer](https://github.com/oxcened/opendialer), licensed under Apache-2.0. The upstream dialer is retained and the launcher opens a Unity receptionist setup dashboard.

## MVP included
- Business name and greeting configuration stored locally
- Local employee directory with extension labels and optional phone numbers
- Keyword-to-extension routing rules
- Text-based local receptionist preview
- HTTPS backend URL configuration (no provider secrets embedded in the APK)
- Existing Android dialer UI available from the receptionist dashboard
- Android 10+ support target (minSdk 29); Android SDK 37 compile environment

## Build
Open in Android Studio or run:
```bash
./gradlew assembleDebug
```
APK: `app/build/outputs/apk/debug/app-debug.apk`.

GitHub Actions builds the debug APK and uploads it as an artifact under Actions → latest workflow run → Artifacts.

## Not yet implemented
This is a starter MVP, not a finished live AI phone service. No real-time voice engine, Composio server integration, WhatsApp Business API, POS connector, internal WebRTC calling, call recordings, or live call transfer is wired in yet. The URL field is configuration only.

A normal third-party Android app cannot universally capture and stream incoming cellular SIM-call audio. Being the default dialer does not provide a general-purpose SIM audio bridge. This restriction applies across stock Android devices and must be addressed by a supported carrier/OEM/device call path before promising AI answers on an existing SIM number.

## Security
Never put Composio, ElevenLabs, OpenAI, WhatsApp, or POS API secrets in the APK. Keep credentials on a secured backend and issue short-lived scoped tokens to devices.
