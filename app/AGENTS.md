# TWIGA Android UI design instructions

Use Composio's **Landing Page Design**, **No AI Design Slop**, and **TWIGA Brand & Product Design System** as design references when changing app surfaces.

- Use the supplied giraffe emblem as the official TWIGA logo. Do not substitute a generic T, giraffe outline or safari illustration.
- Palette: orange #F47721, black #111111, white #FFFFFF, neutral #F6F6F5.
- Preserve native Android patterns, telephony behaviour, system bars, accessible touch targets and TalkBack labels.
- Keep call controls immediately visible: answer/end, mute, AI on/off, call state and permission status.
- Business activity, appointments and calendar views should be calm, light and scannable. Use fine neutral dividers, strong time labels, restrained orange appointment emphasis and a compact agenda-first mobile layout.
- Do not hard-code visible strings in Kotlin or Compose; use Android string resources. Keep icons centralised in AppIcons and use AppIcon.
- Respect battery, background execution restrictions, privacy, notification permissions and Android package-install approval. Never promise silent APK updates on devices that require user confirmation.
- Never show fake customer reviews, real-looking call logs or unverified business data as live data. Clearly label demo data.

## Release validation
- For any visual or launcher-resource change, run the Android CI workflow on the exact pull-request head and inspect the signed/optimized release build before merging.
- The stable website link consumes the `latest-apk` release asset; never rename `twiga.apk` or `version.json` without updating both the site and UpdateManager.
