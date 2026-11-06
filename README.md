# TWIGA for Android

The TWIGA Android app combines the native Android phone dialer with the live TWIGA business workspace at **https://app.twiga.co.ke**.

## What is included

- Native dialer, recent calls and contacts, designed to remain the phone's default dialer when the user grants that role.
- A TWIGA tab that loads the live business app, including its sign-in, dashboard, calls, calendar, customers, orders and business settings.
- The Vapi-powered AI receptionist test page at `https://app.twiga.co.ke/ai`, opened inside the app for a microphone-enabled test call.
- A native AI on/off control that syncs with a paired TWIGA phone account.
- Android 10+ support (minSdk 29) and the app's phone permission/setup flow.

## Build

Open the project in Android Studio or run:

```bash
./gradlew testDebugUnitTest assembleDebug
```

Debug APK: `app/build/outputs/apk/debug/app-debug.apk`.

GitHub Actions builds debug and optimized release APKs. The Android application ID is `ke.co.twiga.receptionist`.

## Voice and cellular-call limits

The Vapi receptionist test is an internet/WebRTC call from the app using the phone microphone and speaker. It is not an audio bridge for a normal Safaricom or Airtel cellular call. Android's default-dialer role does not grant third-party apps universal access to cellular call audio. Automatic AI answering on an existing SIM number still requires a supported carrier, OEM or telephony audio route.

The live web app owns the business dashboard and Vapi web client. Keep private provider credentials and privileged API keys on the server; do not put them in the APK. Only browser-safe public configuration may be delivered to the web client.

## Sign-in note

The TWIGA dashboard currently uses Google OAuth through Supabase. Google may restrict authentication from embedded WebViews on some devices or OAuth configurations. The embedded sign-in flow must be tested with the production Google OAuth configuration; when an embedded sign-in is rejected, a native OAuth/browser handoff is required.

## Upstream notices

The native dialer code retains its upstream Apache-2.0 license notices. See [LICENSE](LICENSE).
