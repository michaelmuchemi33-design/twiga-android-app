create table if not exists public.twiga_customers (
  id uuid primary key default gen_random_uuid(),
  business_id uuid not null references public.businesses(id) on delete cascade,
  created_by uuid not null references auth.users(id),
  name text not null,
  phone text,
  notes text,
  created_at timestamptz not null default now()
);
create table if not exists public.twiga_orders (
  id uuid primary key default gen_random_uuid(),
  business_id uuid not null references public.businesses(id) on delete cascade,
  created_by uuid not null references auth.users(id),
  customer_name text not null,
  total_amount numeric(12,2) not null default 0 check (total_amount >= 0),
  description text,
  status text not null default 'pending',
  created_at timestamptz not null default now()
);
create table if not exists public.twiga_appointments (
  id uuid primary key default gen_random_uuid(),
  business_id uuid not null references public.businesses(id) on delete cascade,
  created_by uuid not null references auth.users(id),
  customer_name text not null,
  phone text,
  scheduled_at timestamptz not null default now(),
  notes text,
  status text not null default 'scheduled',
  created_at timestamptz not null default now()
);
create table if not exists public.twiga_tasks (
  id uuid primary key default gen_random_uuid(),
  business_id uuid not null references public.businesses(id) on delete cascade,
  created_by uuid not null references auth.users(id),
  title text not null,
  description text,
  status text not null default 'open',
  created_at timestamptz not null default now()
);
create table if not exists public.twiga_voice_sessions (
  id uuid primary key default gen_random_uuid(),
  token_hash text not null unique,
  business_id uuid not null references public.businesses(id) on delete cascade,
  user_id uuid not null references auth.users(id) on delete cascade,
  expires_at timestamptz not null,
  revoked_at timestamptz,
  created_at timestamptz not null default now()
);
alter table public.twiga_customers enable row level security;
alter table public.twiga_orders enable row level security;
alter table public.twiga_appointments enable row level security;
alter table public.twiga_tasks enable row level security;
alter table public.twiga_voice_sessions enable row level security;

do $$
declare t text;
begin
  foreach t in array array['twiga_customers','twiga_orders','twiga_appointments','twiga_tasks'] loop
    execute format('drop policy if exists %I on public.%I', t || '_member_access', t);
    execute format(
      'create policy %I on public.%I for all to authenticated using (exists (select 1 from public.business_members bm where bm.business_id = %I.business_id and bm.user_id = auth.uid())) with check (exists (select 1 from public.business_members bm where bm.business_id = %I.business_id and bm.user_id = auth.uid()))',
      t || '_member_access', t, t, t
    );
    execute format('grant select, insert, update, delete on public.%I to authenticated', t);
  end loop;
end $$;
revoke all on public.twiga_voice_sessions from anon, authenticated;
grant all on public.twiga_voice_sessions to service_role;
