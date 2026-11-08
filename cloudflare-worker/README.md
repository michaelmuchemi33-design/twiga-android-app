# TWIGA API Cloudflare Worker

This directory mirrors the deployed Cloudflare Worker named `twiga-api`. It serves the shared TWIGA web/POS ledger and the Vapi AI receptionist webhook.

## Routes

- `GET /` — non-sensitive health response and enabled feature names.
- `POST /vapi` — Vapi assistant requests, function-tool calls, status updates and end-of-call reports.
- Other `POST` requests — authenticated business ledger actions used by the web workspace and Android app.

## Vapi business tools

The dynamically returned assistant exposes:

- `list_products` — active catalogue items, prices and stock.
- `create_order` — stock-checked sales saved to the business ledger after caller confirmation.
- `check_appointment_availability` — checks existing calendar events and appointments.
- `book_appointment` — saves a confirmed appointment to both the order ledger and calendar.

AI-created orders are recorded as unpaid. The receptionist must never claim payment was received. M-Pesa checkout remains disabled until its provider integration is enabled.

Vapi end-of-call reports are written to Supabase `twiga_call_records` through the secured `line_record_call` RPC. The dashboard Call History page reads those records alongside native SIM call events.

## Required Cloudflare bindings

| Binding | Type | Purpose |
|---|---|---|
| `DB` | D1 | `twiga-db` business ledger |
| `SUPABASE_URL` | Variable | Supabase project URL |
| `SUPABASE_KEY` | Variable/secret | Supabase API key used for RPC requests |
| `LINE_SECRET` | Secret | Authenticates the phone-line RPCs |
| `VAPI_SECRET` | Secret | Must match the Vapi phone-number Server URL Secret |

Never commit `LINE_SECRET`, `VAPI_SECRET`, or private API keys.

## Deploy

Copy `wrangler.toml.example` to `wrangler.toml`, set the required variables/secrets, then deploy:

```bash
wrangler secret put SUPABASE_KEY
wrangler secret put LINE_SECRET
wrangler secret put VAPI_SECRET
wrangler deploy
```

Set the Vapi phone number Server URL to:

```
https://twiga-api.twiga-ke.workers.dev/vapi
```

Its Server URL Secret must match the Cloudflare `VAPI_SECRET` binding.

## SIM-call architecture

The Android phone remains on the business's existing SIM. Incoming calls are answered by the default-dialer/Telecom integration, and outgoing calls can be initiated from the TWIGA Dialer page or Android dialer. The paired phone places the customer call on its SIM; the native InCallService joins the Vapi AI line as a second call using the carrier's conference-call feature. This is not direct Android SIM-audio capture, and autonomous outbound campaign scheduling is not enabled yet. The carrier must support call waiting/conference calling on the target handset and line.
