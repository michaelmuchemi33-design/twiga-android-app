# TWIGA live workspace

Deployed at https://app.twiga.co.ke (Vercel project `twiga-live`).

The Android app opens this same production workspace at `https://app.twiga.co.ke/` from its normal entry route. The website remains the single source of truth for the business interface: logo, colours, navigation, responsive layout, POS/orders, customers, AI settings, and account settings. Android adds native phone and Telecom capabilities behind that interface rather than presenting a second dashboard.

The Android app opens this workspace and signs in using the same TWIGA business account. Once the authenticated business membership is loaded, the Android bridge automatically registers that handset with Supabase and stores its private device token. There is no six-digit pairing code. The dashboard then shows device status, call activity and remote SIM-call controls. Android still requires the user to approve the default phone role and phone permissions.

## SIM AI call path

- The business saves its Vapi inbound AI number in `ai_lines` from the AI Receptionist page.
- The paired Android service reads that number using the authenticated `device_get_ai(p_token)` RPC and receives the AI-enabled state using `device_sync(p_token,p_state)`.
- When AI is enabled and a number is configured, Android's `InCallService` auto-answers an incoming SIM call, dials the configured Vapi number as a second call using the same SIM, and requests a Telecom conference merge. Active outgoing calls can also be joined to the AI number.
- This uses the carrier's conference-call feature as the audio path. It does not capture or inject protected SIM-call audio.
- Conference support, call waiting, default SIM selection, Vapi number reachability, call costs, and real handset behavior must be validated with the actual Safaricom/Airtel SIM. If merge is not available, the app attempts to disconnect the AI leg and restore the customer call.

## Files

- `app.html` is the full business workspace.
- `config.js` contains browser-safe public settings only.
- `vapi-call.js` builds the browser microphone test from the saved business prompt and normalizes transcript payloads before display.
- `ai.html` is retained as a legacy standalone AI test page; it is not the Android app home.
