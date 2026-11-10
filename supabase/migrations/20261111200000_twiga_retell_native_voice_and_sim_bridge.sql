-- Retell-native Willa uses Retell's default TTS model. Fish Audio is no longer required.
alter table public.twiga_business_voice_configs
  alter column retell_voice_id set default 'retell-Willa',
  alter column retell_voice_model drop not null,
  alter column retell_voice_model drop default;

-- The SIM bridge number is stored in the existing ai_lines table. It is
-- deliberately a provider phone number, not the customer's public SIM number.
-- The Android InCallService calls this AI number as a second leg and requests
-- a carrier conference merge. It does not capture protected SIM audio.
