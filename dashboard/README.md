# TWIGA dashboard and demo site

Static site deployed on Vercel (project `twiga-dashboard`).

- `app.html`  the owner dashboard (Google sign-in with Supabase, dialer, orders, AI settings)
- `demo.html` public demo page for https://demo.twiga.co.ke (voice demo with Vapi and the APK download link)
- `config.js` public settings only (Supabase address and publishable key, backend address)
- `vercel.json` routes `/` to the demo on demo.twiga.co.ke and to the dashboard everywhere else
- `api/health.js` health check

Never put secret keys in these files. Secrets belong in Vercel environment variables.
