-- Encrypted, business-scoped Google Workspace connections.
-- All token reads/writes happen inside the google-workspace Edge Function.
create table if not exists public.twiga_google_connections (
  business_id text primary key,
  connected_by uuid not null references auth.users(id) on delete cascade,
  google_email text,
  granted_scopes text not null default '',
  access_token_ciphertext text not null,
  refresh_token_ciphertext text,
  access_expires_at timestamptz,
  spreadsheet_id text,
  spreadsheet_url text,
  last_sync_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

alter table public.twiga_google_connections enable row level security;
revoke all on public.twiga_google_connections from anon, authenticated;
grant all on public.twiga_google_connections to service_role;

create index if not exists twiga_google_connections_user_idx
  on public.twiga_google_connections(connected_by);
