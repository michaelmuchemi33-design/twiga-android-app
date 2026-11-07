# TWIGA live workspace

Deployed at https://app.twiga.co.ke (Vercel project `twiga-live`). The website visual redesign is intentionally pending during this integration phase; existing TWIGA screens remain in place while workflows are connected.

## Google Workspace

The dashboard requests Calendar, Contacts, Sheets and restricted Drive file scopes. Google API calls are routed through the authenticated Supabase Edge Function `google-workspace`, rather than directly from the browser. Google access and refresh tokens are stored encrypted in `twiga_google_connections` and refreshed server-side.

Deployment prerequisites and commands are in [Google Workspace integration deployment](../docs/GOOGLE-WORKSPACE-INTEGRATION.md). Apply the migration and deploy the Edge Function before customer use of Google connection, calendar, contacts or Sheets export.

## Existing business workflows

Orders, items/pricing, customer records, call logs and phone commands use the existing TWIGA ledger/Supabase services. The order form supports multiple line items, validates quantities and prices, keeps M-Pesa orders unpaid until payment confirmation, and supports completion/cancellation. M-Pesa still depends on the configured external ledger Worker and Safaricom Daraja credentials.

## Voice

`ai.html` is the Vapi WebRTC microphone test. It does not bridge normal Safaricom/Airtel cellular-call audio. `vapi-call.js` builds the AI call from the business prompt. Keep private provider credentials and privileged keys on the server; only browser-safe public settings belong in `config.js`.
