# TWIGA Google Workspace integration

This folder contains the server-side Google Workspace token vault used by the live dashboard and Android WebView.

## Features

- Calendar: list the next 30 days of events and create appointments on the business owner's primary calendar.
- Contacts: read Google People connections.
- Sheets: create/reuse a TWIGA Business Records spreadsheet and export business orders.
- Drive: inspect the TWIGA records spreadsheet and list files visible to the app under the restricted `drive.file` scope.
- OAuth tokens are encrypted with AES-256-GCM and never returned to the browser or stored in the APK.
- The server refreshes expired Google access tokens using the OAuth refresh token.

## Deploy once

Run these commands from the Supabase project linked to the TWIGA Supabase URL configured in `dashboard-live/config.js`:

```bash
supabase db push
openssl rand -base64 32
supabase secrets set GOOGLE_CLIENT_ID="YOUR_GOOGLE_WEB_CLIENT_ID" GOOGLE_CLIENT_SECRET="YOUR_GOOGLE_WEB_CLIENT_SECRET" GOOGLE_TOKEN_ENCRYPTION_KEY="PASTE_THE_RANDOM_BASE64_VALUE" TWIGA_ALLOWED_ORIGINS="https://app.twiga.co.ke"
supabase functions deploy google-workspace
```

Use the same Google **Web application** Client ID and Client Secret already configured in Supabase Authentication → Providers → Google. The encryption key is a separate server-only secret. Keep it private and back it up securely; losing it means the stored Google tokens cannot be decrypted and customers must reconnect.

Apply the migration before deploying the function. The migration creates `twiga_google_connections`; it revokes direct browser access and grants access only to the Supabase service role. The Edge Function validates the caller's Supabase session and their `business_members` row before any operation.

## Frontend OAuth scopes

The live dashboard requests:

- `https://www.googleapis.com/auth/calendar.events.owned`
- `https://www.googleapis.com/auth/contacts.readonly`
- `https://www.googleapis.com/auth/spreadsheets`
- `https://www.googleapis.com/auth/drive.file`

Google Cloud must have the matching scopes configured, and the Calendar, People, Sheets and Drive APIs enabled. Google may require consent-screen verification before public rollout.

## Android OAuth

The Android dashboard opens Google's authorization URL in the system browser. The Supabase OAuth callback returns to the app using `twiga://auth/callback`; Android passes the PKCE authorization code to the live WebView, which completes the existing Supabase session exchange. Add that URI to Supabase Authentication → URL Configuration → Additional Redirect URLs. Keep the Supabase callback URL as the authorized redirect URI in Google Cloud.

## API actions

The browser calls the authenticated Supabase Edge Function `google-workspace` with an action and the current `businessId`. Supported actions are `connect`, `status`, `disconnect`, `calendar.list`, `calendar.create`, `contacts.list`, `sheets.ensure`, `sheets.export-orders`, `drive.status`, and `drive.list`.

The function checks membership using the caller's Supabase JWT and the existing RLS policy on `business_members`. It stores only encrypted provider tokens and metadata in the vault table.
