# TWIGA live workspace

Deployed at https://app.twiga.co.ke (Vercel project `twiga-live`).

The Android app opens the same production business workspace at `https://app.twiga.co.ke/` from its normal entry route, rather than forcing a separate Dialer tab as the home screen. The web phone page handles dashboard pairing, dial commands, device status and recent call activity. The Android app provides the native Telecom layer and securely claims the web-generated six-digit pairing code through its WebView bridge.

- `app.html` is the full business workspace.
- `config.js` contains browser-safe public settings only.
- `vapi-call.js` builds the AI test call from the saved business prompt and normalizes transcript payloads before display.
- `ai.html` is retained as a legacy standalone AI test page; it is not the Android app home.
