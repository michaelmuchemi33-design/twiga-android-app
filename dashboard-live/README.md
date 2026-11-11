# TWIGA live workspace

Deployed at https://app.twiga.co.ke (Vercel project `twiga-live`).

The Android app opens this same production workspace at `https://app.twiga.co.ke/` from its normal entry route. The website remains the single source of truth for the business interface: logo, colours, navigation, responsive layout, POS/orders, customers, AI settings, and account settings. Android adds native phone and Telecom capabilities behind that interface rather than presenting a second dashboard.

The Android app opens this workspace and signs in using the same TWIGA business account. Once the authenticated business membership is loaded, the Android bridge automatically registers that handset with Supabase and stores its private device token. There is no six-digit pairing code. The dashboard then shows device status, call activity and remote SIM-call controls. Android still requires the user to approve the default phone role and phone permissions.

## Handset SIM gateway and AI providers

- The Android handset running TWIGA, with the owner's existing physical SIM, is the standard call gateway. The cellular call uses the person's SIM and their normal number.
- Vapi or Retell is the AI provider layer (speech, reasoning and voice). Neither provider is a required business phone number, a SIP trunk, or a second SIM for standard handset mode.
- No virtual number is required for the normal TWIGA handset/SIM setup. A virtual business number is a separate optional paid add-on and must never be provisioned as part of the core setup.
- The Android app registers the handset, reads call state, and can place/answer/end calls through Android Telecom permissions. The dashboard is the control surface for that handset.
- **Current implementation limitation:** this repository currently records and controls SIM call state but does not capture and stream the protected cellular call audio to Vapi or Retell. The earlier carrier-conference/second-number bridge is not the intended standard architecture. Do not represent SIM-call AI answering as operational until a compliant on-device audio path has been implemented and tested on physical devices and SIM networks.
- The browser microphone test is a separate provider test over the internet; passing it does not prove that a SIM call is connected to the AI.


## Files

- `app.html` is the full business workspace.
- `config.js` contains browser-safe public settings only.
- `vapi-call.js` builds the browser microphone test from the saved business prompt and normalizes transcript payloads before display.
- `ai.html` is retained as a legacy standalone AI test page; it is not the Android app home.
