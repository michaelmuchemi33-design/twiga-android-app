# TWIGA Android Telephone Bridge

TWIGA's primary product is this Android telephone application. It is the phone-side telephony bridge for a business's existing Android handset and SIM, connected to the TWIGA business workspace and optional AI providers.

## Product architecture

- The Android app is the operational phone: it requests the Android default-dialler role, uses supported Telecom APIs, receives call-state events, manages supported call controls and syncs call activity to the signed-in business workspace.
- The phone remains usable as a normal SIM phone. TWIGA cloud subscription pauses must not disable native cellular calling.
- The app pairs to a business account using a short-lived pairing flow and a device token. Provider API keys remain on the server; they must never be embedded in the APK.
- The business owner configures the business profile, product catalogue, staff, AI provider, inbound/outbound instructions, recording preference, orders, appointments and follow-ups in the workspace.
- The platform administrator manages client businesses and AI provider setup through a password-authenticated admin panel with a server-side platform-admin allowlist.
- Retell and Vapi are alternative AI providers. The business/provider selection must be persisted per business and honoured by the call router. Fish Audio is not a mandatory Retell dependency; the Retell-native Willa voice is the preferred Retell option.

## SIM call and AI bridge: important Android/network constraint

Android does not grant ordinary third-party apps universal access to the protected audio stream of cellular SIM calls. Default-dialler status and the `InCallService` APIs provide call state and supported call controls, but do not create a general-purpose cloud audio stream.

Therefore, AI participation in a SIM call must use an explicitly supported carrier/network bridge path (for example, carrier-supported conference calling with an AI leg, or a carrier/OEM-approved audio bridge). It must be capability-checked per device and carrier. If no supported bridge exists, the app may still manage call events and controls, but must not claim that the AI is hearing or speaking on the cellular call.

A browser/WebRTC voice session inside the app is a separate internet call and is not proof that SIM calls are bridged. Test real inbound and outbound SIM calls on physical devices and networks before enabling AI auto-answer for customers.

## Current Android capabilities and limitations

- Default phone app request and Telecom call UI integration.
- Call-state event capture and offline event queue with later sync.
- Remote device commands for supported dial, answer, decline and hang-up actions.
- Business workspace WebView with a restricted trusted-origin microphone permission.
- Business/customer/order UI and provider settings in the workspace.
- AI in-app voice test via a web voice session.
- Not a universal SIM-audio capture implementation. Do not enable an AI auto-answer claim unless a tested carrier bridge is configured.
- A remote-control heartbeat is a control channel, not a telephony media channel.

## Required production verification

1. Verify incoming cellular call rings and can be answered through TWIGA as default dialler.
2. Verify outgoing cellular call is initiated through the selected SIM and displays correct caller ID.
3. Verify call-state events and caller numbers sync correctly, including missed calls and multi-SIM devices.
4. Verify carrier conference support and audio path before attempting AI participation.
5. Verify AI can hear the caller and the caller can hear AI on a real SIM call, in both inbound and outbound scenarios.
6. Verify call recording consent, recording-off behaviour, tenant separation and retention.
7. Verify device battery behaviour and background restrictions on supported Android versions and OEMs.

## Public legal documents

- Privacy Policy: https://twiga.co.ke/privacy.html
- User Agreement: https://twiga.co.ke/terms.html
- Support: support@twiga.co.ke

## Build

Open the Android project in Android Studio and build the debug APK with the repository's Gradle wrapper:

```bash
./gradlew assembleDebug
```

Never place private Retell, Vapi, Supabase service-role, Cloudflare or payment-provider secrets in the app source or APK.
