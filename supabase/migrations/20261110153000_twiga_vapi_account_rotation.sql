create table if not exists public.twiga_vapi_accounts (
 id uuid primary key default gen_random_uuid(),
 name text not null check (char_length(name) between 2 and 80),
 vapi_public_key text not null check (char_length(vapi_public_key) between 8 and 255),
 assistant_id text not null check (char_length(assistant_id) between 8 and 255),
 voice_label text not null default '',
 voice_model text not null default '',
 priority integer not null default 100,
 enabled boolean not null default true,
 last_used_at timestamptz,
 created_by uuid references auth.users(id) on delete set null,
 created_at timestamptz not null default now(),
 updated_at timestamptz not null default now(),
 unique (vapi_public_key, assistant_id)
);
create index if not exists twiga_vapi_accounts_rotation_idx
 on public.twiga_vapi_accounts(enabled, last_used_at, priority, created_at);
alter table public.twiga_vapi_accounts enable row level security;
revoke all on table public.twiga_vapi_accounts from anon, authenticated;
grant all on table public.twiga_vapi_accounts to service_role;
create or replace function public.twiga_next_vapi_account()
returns table (id uuid,name text,vapi_public_key text,assistant_id text,voice_label text,voice_model text)
language plpgsql security definer set search_path = pg_catalog, public as $$
begin
 return query
 with selected as (
  select a.id from public.twiga_vapi_accounts a where a.enabled = true
  order by a.last_used_at asc nulls first,a.priority asc,a.created_at asc,a.id asc
  for update skip locked limit 1
 )
 update public.twiga_vapi_accounts a
 set last_used_at=now(),updated_at=now()
 where a.id=(select selected.id from selected)
 returning a.id,a.name,a.vapi_public_key,a.assistant_id,a.voice_label,a.voice_model;
end;
$$;
revoke all on function public.twiga_next_vapi_account() from public,anon,authenticated;
grant execute on function public.twiga_next_vapi_account() to service_role;
insert into public.twiga_vapi_accounts(name,vapi_public_key,assistant_id,voice_label,voice_model,priority,enabled)
values ('TWIGA Primary Vapi','2874d463-b31e-4d5a-84d7-e6cbe0a90b15','ea3ae8f7-fb6f-430e-9c33-96d9a919ade3','Sarah · ElevenLabs','eleven_v3',1,true)
on conflict(vapi_public_key,assistant_id) do update set name=excluded.name,voice_label=excluded.voice_label,voice_model=excluded.voice_model,priority=excluded.priority,enabled=true,updated_at=now();
