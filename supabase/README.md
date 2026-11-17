# TWIGA Supabase functions

- `twiga-businesses` creates a complete business workspace idempotently and stores SautiKit voice preferences.
- `twiga-sautikit` validates and stores the platform SautiKit API key, exposes the live model/voice catalogue, publishes receptionist prompts and sends authorised calls.
- `twiga-platform-secrets` validates and stores the xAI/Grok key and reports Google OAuth credential readiness.
- `twiga-setup-assistant` uses the stored xAI key to help owners complete their business profile.
- `google-workspace` handles the business Google OAuth connection and Google Workspace actions.
- `twiga-scheduled-calls` is a JWT-disabled, HMAC-authenticated worker called by the existing Supabase Cron job `twiga-scheduled-calls-worker` every minute. It dispatches only consented calls through an active business SautiKit line and respects each job's local calling window.

The scheduled-call worker requires the Vault reference `twiga_scheduled_calls_cron_secret` and a SautiKit API key saved through the dashboard Connections page. The user must record consent before a call can be scheduled.
