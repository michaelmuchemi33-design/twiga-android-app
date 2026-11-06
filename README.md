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

## Google Workspace permissions

The TWIGA dashboard requests the following Google permissions when the owner selects **Connect Google Workspace**:

| Service | OAuth scope | Access |
|---|---|---|
| Google Calendar | `calendar.events` | Read and manage calendar events |
| Google Contacts | `contacts.readonly` | Read Google contacts |
| Google Sheets | `spreadsheets` | Create and manage TWIGA spreadsheets |
| Google Drive | `drive.file` | Work with files created or selected for TWIGA |

To enable this for your Google project:

1. In Google Cloud Console, enable **Google Calendar API**, **People API**, **Google Sheets API** and **Google Drive API**.
2. Configure the OAuth consent screen with the TWIGA app name, support email, privacy policy and the requested scopes.
3. Add your own Google account as a test user while testing. Google may require OAuth app verification before public customers can grant sensitive scopes.
4. In Supabase Dashboard, open **Authentication → URL Configuration** and add `twiga://auth/callback` to **Additional Redirect URLs**.
5. In Supabase Authentication → Providers → Google, set the Google Web client ID and secret. In Google Cloud, set the Supabase callback URL as an authorized redirect URI.

The Android TWIGA tab opens Google's authorization page in the system browser, then returns the authorization code to the live dashboard through the `twiga://auth/callback` app link. Google sign-in must not be embedded inside Android WebView.

Google provider access tokens are currently session-scoped. Before relying on background synchronization, the live backend still needs a protected token vault that stores and refreshes each business owner's Google provider tokens. Do not put Google client secrets or refresh tokens in the APK. The contacts scope above is read-only; two-way write access to Google Contacts requires the broader `contacts` scope and an updated consent/verification review.

## Upstream notices

The native dialer code retains its upstream Apache-2.0 license notices. See [LICENSE](LICENSE).
