# TWIGA Android QA plan: incoming calls and POS

## Incoming SIM calls
- On first launch, confirm Android shows the system prompt to set TWIGA as the default phone app. Accept it; verify Settings > Default apps > Phone app shows TWIGA.
- Confirm the merged manifest declares TWIGA's InCallService with both in-call UI and ringing metadata enabled.
- With auto-answer OFF, place a test call to the SIM and confirm the incoming-call screen displays Answer and Decline controls.
- With auto-answer ON, place a second test call and confirm the call is answered after approximately 1.2 seconds. Turn it OFF again after testing.
- Repeat with screen locked, app process swiped away, dual SIM, Bluetooth headset, wired headset, Android 10 and Android 15/16 devices.
- Confirm calls still ring normally if the user declines the default-phone role or disables auto-answer.

## POS
- Create a product, add it to a sale, adjust quantity, complete a Cash/M-Pesa/Card sale, reopen POS and verify catalogue stock and recent sale persist.
- POS data in this release is local to the phone; cloud synchronisation and receipt printing are not claimed as complete.

## Critical telephony limitation
Android's public InCallService API lets the default dialer manage and answer calls, but it does not expose carrier-call uplink/downlink audio to an ordinary third-party app. Auto-answer is a phone-call control feature, not proof that the AI can listen and speak on a SIM call. A supported SIP/carrier/voice bridge is still required for a real AI receptionist conversation.

- Verify the auto-answer preference survives an app restart and remains OFF after a fresh install until the owner explicitly enables it.
