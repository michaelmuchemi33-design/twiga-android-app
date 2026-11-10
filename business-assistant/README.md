# TWIGA Business Assistant (Android)

Build validation is run by the repository's dedicated GitHub Actions workflow.

A separate Android app for business operations. It is not a dialer and does not create, buy, or attach a virtual phone number. The existing TWIGA Phone Bridge remains a separate app for supported SIM/Telecom features.

## What is implemented

- Separate Android application ID: `co.ke.twiga.businessassistant`
- Supabase email/password sign-in
- Business selection from the signed-in user's `business_members` relation
- Customer, order, appointment and task workspaces
- Per-business knowledge profile (what the business does/sells, services, hours, policies, FAQs, tone, language and AI instructions)
- Per-business POS product catalogue (name, SKU, category, KSh price, stock, description and product photo)
- Product photos uploaded directly to private Cloudflare R2 using short-lived upload URLs issued by the TWIGA Reply service
- Create and list actions against tenant-scoped Supabase tables
- Official Vapi Android SDK for real-time in-app voice (WebRTC; no phone number)
- Short-lived voice session ticket issued by an authenticated Supabase Edge Function
- Vapi tool webhook that checks the ticket before writing business records
- Build workflow that produces a debug APK artifact

## Build

From the repository root:

```bash
./gradlew -p business-assistant :app:assembleDebug
```

Optional local settings: create `business-assistant/twiga.properties` (do not commit it):

```properties
TWIGA_SUPABASE_URL=https://YOUR_PROJECT.supabase.co
TWIGA_SUPABASE_ANON_KEY=YOUR_SUPABASE_PUBLISHABLE_KEY
TWIGA_VAPI_PUBLIC_KEY=YOUR_VAPI_PUBLIC_KEY
TWIGA_VAPI_ASSISTANT_ID=YOUR_ASSISTANT_ID
TWIGA_REPLY_API_URL=https://YOUR-RENDER-REPLY-SERVICE
```

The app defaults to the existing **TWIGA AI Receptionist** assistant (`ea3ae8f7-fb6f-430e-9c33-96d9a919ade3`). You can override the assistant ID in the Voice tab if you deliberately want another assistant. No phone number or phone pairing is required: this is in-app voice over the internet. Never put a Supabase service-role key or Vapi private API token in the APK.

## Deploy the backend to the existing Supabase project

Apply the migration and deploy both functions to the TWIGA Supabase project:

```bash
supabase link --project-ref ztyxxikkposfpxsutnfr
supabase db push
supabase functions deploy twiga-vapi-session
supabase functions deploy twiga-vapi-tools
```

The Supabase project must already have the `businesses` and `business_members` tables with `business_members.user_id` and `business_members.business_id`. The migration creates the four TWIGA work tables and tenant RLS policies. It also creates a private table for short-lived voice tickets. The service-role key is used only inside Edge Functions and must never be added to the Android app.

## Connect Vapi so it can perform actions

1. In Vapi, open the TWIGA assistant used by the app (do not attach a phone number).
2. Set the assistant Server URL to `https://ztyxxikkposfpxsutnfr.supabase.co/functions/v1/twiga-vapi-tools`.
3. Add these Function tools to the assistant's model. Set each tool's server URL to the same Edge Function.

The tools endpoint is authorized by the random 256-bit voice-session ticket issued only after Supabase verifies the signed-in user and business membership. The ticket expires after 10 minutes. Do not log or expose call metadata.

### Tool definitions

```json
[
  {"type":"function","async":false,"function":{"name":"create_customer","description":"Create a customer in the signed-in business after confirming the name and phone number.","parameters":{"type":"object","properties":{"name":{"type":"string"},"phone":{"type":"string"},"notes":{"type":"string"}},"required":["name"]}},"server":{"url":"https://ztyxxikkposfpxsutnfr.supabase.co/functions/v1/twiga-vapi-tools"}},
  {"type":"function","async":false,"function":{"name":"create_order","description":"Save a customer order after reading back the customer, items and total and receiving confirmation. Amounts are Kenyan shillings.","parameters":{"type":"object","properties":{"customer_name":{"type":"string"},"total_amount":{"type":"number"},"description":{"type":"string"}},"required":["customer_name","total_amount"]}},"server":{"url":"https://ztyxxikkposfpxsutnfr.supabase.co/functions/v1/twiga-vapi-tools"}},
  {"type":"function","async":false,"function":{"name":"create_appointment","description":"Book an appointment only after confirming the customer, date and time.","parameters":{"type":"object","properties":{"customer_name":{"type":"string"},"phone":{"type":"string"},"scheduled_at":{"type":"string","description":"ISO-8601 date and time with timezone"},"notes":{"type":"string"}},"required":["customer_name","scheduled_at"]}},"server":{"url":"https://ztyxxikkposfpxsutnfr.supabase.co/functions/v1/twiga-vapi-tools"}},
  {"type":"function","async":false,"function":{"name":"create_task","description":"Create a follow-up task for the business.","parameters":{"type":"object","properties":{"title":{"type":"string"},"description":{"type":"string"}},"required":["title"]}},"server":{"url":"https://ztyxxikkposfpxsutnfr.supabase.co/functions/v1/twiga-vapi-tools"}},
  {"type":"function","async":false,"function":{"name":"list_orders","description":"List the latest orders for the current business.","parameters":{"type":"object","properties":{},"required":[]}},"server":{"url":"https://ztyxxikkposfpxsutnfr.supabase.co/functions/v1/twiga-vapi-tools"}},
  {"type":"function","async":false,"function":{"name":"list_appointments","description":"List upcoming appointments for the current business.","parameters":{"type":"object","properties":{},"required":[]}},"server":{"url":"https://ztyxxikkposfpxsutnfr.supabase.co/functions/v1/twiga-vapi-tools"}}
]
```

**Security:** the Android app first calls `twiga-vapi-session` with the signed-in user's Supabase access token. The function verifies the user and business membership, then returns a random ticket that expires after 10 minutes. Vapi receives that ticket as call metadata. The tools endpoint checks the short-lived ticket, then scopes every database write to the ticket's business and user. Metadata values by themselves are not treated as authorization.

## Current limitations / verification boundary

- The source and CI workflow are committed, but the Supabase migration/functions still need to be deployed to the live project and the Vapi assistant still needs its credential, Server URL and Function tools configured.
- The app currently signs in with email/password; Google sign-in is not wired into this separate APK yet.
- The first version creates and lists core records. Calendar/Contacts/Sheets/Drive sync and WhatsApp Business messaging are follow-on integrations, not claimed as working in this APK.
- A GitHub Actions debug build is a test APK, not a permanent signed customer release.


## Business profile, POS products and WhatsApp

After sign-in, open **Business Profile** from the Overview screen and describe the selected business. Each selected business has a separate row in `twiga_business_profiles`. Open **POS Products** to add products, prices, SKUs, stock levels and descriptions. The profile and product tables use RLS policies based on `business_members`; records are scoped by `business_id`.

To upload a product picture, configure `TWIGA_REPLY_API_URL` in the local `twiga.properties` file or in the app settings, and deploy the TWIGA Reply service. The Reply service must have Cloudflare R2 configured; images are stored under a path containing the business UUID and are not public bucket objects. Supported image types are JPG, PNG and WebP up to 5 MB.

The WhatsApp AI reads only the selected WhatsApp connection's business profile and active products when answering customer questions. It does not use a global shared product list.
