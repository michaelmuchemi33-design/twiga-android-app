create table if not exists public.twiga_business_voice_configs (
  business_id uuid primary key references public.businesses(id) on delete cascade,
  provider text not null default 'vapi' check (provider in ('vapi','retell')),
  retell_agent_id text,
  retell_llm_id text,
  retell_voice_id text not null default 'fish_audio-Willa',
  retell_voice_model text not null default 's2.1-pro',
  retell_model text not null default 'gpt-6-luna',
  retell_language_codes text[] not null default array['en-GB','sw-KE']::text[],
  retell_ambient_sound text not null default 'call-center',
  retell_ambient_sound_volume numeric(3,2) not null default 0.12 check (retell_ambient_sound_volume between 0 and 0.3),
  inbound_prompt text not null default '',
  outbound_prompt text not null default 'You are calling on behalf of {{business_name}}. Your reason for calling is {{call_reason}}. Identify yourself and the business, ask whether it is a convenient time, speak naturally in English or Kiswahili according to the customer, never invent facts or prices, and confirm any booking or order only after TWIGA confirms it was saved. If the person asks not to be called again, politely acknowledge and end the call.',
  last_provisioned_at timestamptz,
  updated_by uuid,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create table if not exists public.twiga_provider_secret_refs (
  business_id uuid not null references public.businesses(id) on delete cascade,
  provider text not null check (provider in ('retell')),
  vault_secret_id uuid not null,
  secret_last4 text not null,
  updated_by uuid,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  primary key (business_id, provider)
);

alter table public.twiga_business_voice_configs enable row level security;
alter table public.twiga_provider_secret_refs enable row level security;
revoke all on public.twiga_business_voice_configs from anon, authenticated;
revoke all on public.twiga_provider_secret_refs from anon, authenticated;
grant all on public.twiga_business_voice_configs to service_role;
grant all on public.twiga_provider_secret_refs to service_role;

create or replace function public.twiga_set_provider_secret(
  p_business_id uuid,
  p_provider text,
  p_secret text,
  p_updated_by uuid default null
) returns text
language plpgsql
security definer
set search_path = public, vault
as $$
declare
  v_secret_id uuid;
  v_name text;
begin
  if p_provider <> 'retell' or length(coalesce(p_secret,'')) < 20 then
    raise exception 'Invalid provider credential';
  end if;
  v_name := 'twiga-' || p_provider || '-' || p_business_id::text;
  select vault_secret_id into v_secret_id
  from public.twiga_provider_secret_refs
  where business_id = p_business_id and provider = p_provider;
  if v_secret_id is null then
    v_secret_id := vault.create_secret(p_secret, v_name, 'Encrypted TWIGA business provider credential');
    insert into public.twiga_provider_secret_refs(business_id,provider,vault_secret_id,secret_last4,updated_by)
    values (p_business_id,p_provider,v_secret_id,right(p_secret,4),p_updated_by)
    on conflict (business_id,provider) do update
      set vault_secret_id=excluded.vault_secret_id,secret_last4=excluded.secret_last4,updated_by=excluded.updated_by,updated_at=now();
  else
    perform vault.update_secret(v_secret_id,p_secret,v_name,'Encrypted TWIGA business provider credential');
    update public.twiga_provider_secret_refs
       set secret_last4=right(p_secret,4),updated_by=p_updated_by,updated_at=now()
     where business_id=p_business_id and provider=p_provider;
  end if;
  return right(p_secret,4);
end;
$$;

create or replace function public.twiga_get_provider_secret(
  p_business_id uuid,
  p_provider text
) returns text
language sql
security definer
set search_path = public, vault
as $$
  select ds.decrypted_secret
  from public.twiga_provider_secret_refs r
  join vault.decrypted_secrets ds on ds.id = r.vault_secret_id
  where r.business_id = p_business_id and r.provider = p_provider
  limit 1;
$$;

revoke all on function public.twiga_set_provider_secret(uuid,text,text,uuid) from public, anon, authenticated;
revoke all on function public.twiga_get_provider_secret(uuid,text) from public, anon, authenticated;
grant execute on function public.twiga_set_provider_secret(uuid,text,text,uuid) to service_role;
grant execute on function public.twiga_get_provider_secret(uuid,text) to service_role;
