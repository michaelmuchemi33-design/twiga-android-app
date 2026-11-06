# TWIGA live workspace

Deployed at https://app.twiga.co.ke (Vercel project `twiga-live`). No sample data. `ai.html` is the page the Android app opens for the AI receptionist test. `vapi-call.js` builds the AI call from the business prompt. Public settings only in `config.js`.

## Google Workspace authorization

The dashboard requests these Google OAuth scopes when the business owner chooses **Connect Google Workspace**:

- Calendar: `https://www.googleapis.com/auth/calendar.events`
- Contacts: `https://www.googleapis.com/auth/contacts.readonly`
- Sheets and Drive: `https://www.googleapis.com/auth/drive.file` (TWIGA-created or app-selected files only)

In Google Cloud, enable Google Calendar API, People API, Google Sheets API and Google Drive API. Configure the OAuth consent screen, add the exact scopes, add the account as a test user while testing, and complete Google's verification before public rollout where required.

In Supabase Auth > URL Configuration, add `twiga://auth/callback` to the Additional Redirect URLs list for the Android OAuth handoff. Keep the Supabase Google provider callback URL configured as the redirect URI in Google Cloud.

Google access tokens are currently held only in the active web session. Production background sync must first store and refresh provider tokens through a protected server-side token vault; do not persist Google refresh tokens in the APK or in browser local storage.
