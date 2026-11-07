# TWIGA Google Workspace integration

This folder contains the server-side Google Workspace token vault used by the live dashboard and Android WebView.

## Features

- Calendar: list, create, reschedule and cancel events on the business owner's primary calendar.
- Contacts: read Google People connections.
- Sheets: create/reuse a TWIGA Business Records spreadsheet and export business orders.
- Drive: inspect the TWIGA records spreadsheet and list files visible to the app under the restricted `drive.file` scope.
- OAuth access and refresh tokens are encrypted with AES-256-GCM and never returned to the browser or stored in the APK.
- The server refreshes expired Google access tokens using the encrypted OAuth refresh token.
- Order changes trigger a best-effort Sheets export when Google Workspace is connected.

## Deploy to the existing TWIGA Supabase project

The Supabase CLI files live under `backend/supabase`, so run these commands from the repository's `backend` directory in Windows PowerShell:

```powershell
cd backend
supabase login
supabase link --project-ref ztyxxikkposfpxsutnfr
supabase db push --dry-run
```

Review the dry-run output. When it shows only the new TWIGA Google connection migration, apply it:

```powershell
supabase db push
```

Generate a one-time encryption key locally (Node.js required):

```powershell
$GOOGLE_TOKEN_KEY = node -e "console.log(require('node:crypto').randomBytes(32).toString('base64'))"
```

In Supabase Dashboard → Authentication → Providers → Google, copy the existing Web Client ID and Client Secret. Set them as Edge Function secrets, together with the encryption key:

```powershell
supabase secrets set GOOGLE_CLIENT_ID="YOUR_GOOGLE_WEB_CLIENT_ID" GOOGLE_CLIENT_SECRET="YOUR_GOOGLE_WEB_CLIENT_SECRET" GOOGLE_TOKEN_ENCRYPTION_KEY="$GOOGLE_TOKEN_KEY" TWIGA_ALLOWED_ORIGINS="https://app.twiga.co.ke"
supabase functions deploy google-workspace
supabase secrets list
```

Use the same Google Web application Client ID and Client Secret already configured in Supabase Authentication → Providers → Google. Do not put the encryption key in the repository, website JavaScript, Android APK or screenshots. Back it up securely; losing it means the stored Google tokens cannot be decrypted and customers must reconnect.

The migration creates `twiga_google_connections`; it revokes direct browser access and grants access only to the Supabase service role. The Edge Function validates each caller's Supabase JWT and business membership before use.

## Google Cloud setup

Enable Google Calendar API, People API, Sheets API and Drive API. Add these scopes to the Google Auth Platform Data Access configuration:

- `https://www.googleapis.com/auth/calendar.events.owned`
- `https://www.googleapis.com/auth/contacts.readonly`
- `https://www.googleapis.com/auth/spreadsheets`
- `https://www.googleapis.com/auth/drive.file`

Add your test Gmail account as a test user while testing. Google may require consent-screen verification before public customer rollout.

## Android OAuth callback

The Android dashboard opens Google's authorization URL in the system browser. Supabase returns to the app using `twiga://auth/callback`; Android passes the PKCE authorization code to the live WebView, which completes the existing Supabase session exchange. Confirm that URI is present in Supabase Authentication → URL Configuration → Additional Redirect URLs. Keep the Supabase callback URL as the authorized redirect URI in Google Cloud.

## Publish the updated website

The changed dashboard source is in `dashboard-live/`. These GitHub commits do not automatically change the live website unless the Vercel project serving `app.twiga.co.ke` is connected to this repository and uses `dashboard-live` as its root directory. Deploy that folder to the existing Vercel project before testing the new Google buttons. The website visual redesign is intentionally pending.

## API actions

The dashboard calls the authenticated Supabase Edge Function `google-workspace`. Supported actions: `connect`, `status`, `disconnect`, `calendar.list`, `calendar.create`, `calendar.update`, `calendar.delete`, `contacts.list`, `sheets.ensure`, `sheets.export-orders`, `drive.status`, and `drive.list`.

The Google Contacts integration is read-only under the selected `contacts.readonly` scope. If two-way Google contact writing is needed later, request the broader Contacts scope with an updated consent review.
