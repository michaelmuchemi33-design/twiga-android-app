
create table if not exists public.twiga_platform_admins (
  user_id uuid primary key references auth.users(id) on delete cascade,
  created_at timestamptz not null default now()
);
alter table public.twiga_platform_admins enable row level security;
revoke all on table public.twiga_platform_admins from anon, authenticated;
grant all on table public.twiga_platform_admins to service_role;

create table if not exists public.twiga_platform_payments (
  id uuid primary key default gen_random_uuid(),
  business_id uuid not null references public.businesses(id) on delete restrict,
  amount numeric(14,2) not null check (amount > 0),
  currency text not null default 'KES' check (currency = 'KES'),
  method text not null check (method in ('mpesa','bank','cash','card','other')),
  provider_reference text,
  notes text,
  paid_at timestamptz not null default now(),
  period_start date,
  period_end date,
  recorded_by uuid references auth.users(id) on delete set null,
  created_at timestamptz not null default now(),
  check (period_end is null or period_start is null or period_end >= period_start)
);
create unique index if not exists twiga_platform_payments_reference_unique
  on public.twiga_platform_payments(method, provider_reference)
  where provider_reference is not null and length(trim(provider_reference)) > 0;
create index if not exists twiga_platform_payments_business_paid_at_idx
  on public.twiga_platform_payments(business_id, paid_at desc);
alter table public.twiga_platform_payments enable row level security;
revoke all on table public.twiga_platform_payments from anon, authenticated;
grant all on table public.twiga_platform_payments to service_role;

create table if not exists public.twiga_support_tickets (
  id uuid primary key default gen_random_uuid(),
  user_id uuid references auth.users(id) on delete set null,
  business_id uuid references public.businesses(id) on delete set null,
  channel text not null default 'app' check (channel in ('app','website','whatsapp')),
  subject text not null,
  message text not null,
  contact text,
  status text not null default 'open' check (status in ('open','in_progress','resolved')),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create index if not exists twiga_support_tickets_status_created_idx
  on public.twiga_support_tickets(status, created_at desc);
alter table public.twiga_support_tickets enable row level security;
revoke all on table public.twiga_support_tickets from anon, authenticated;
grant all on table public.twiga_support_tickets to service_role;

create table if not exists public.twiga_support_rate_limits (
  fingerprint text primary key,
  window_started_at timestamptz not null default now(),
  request_count integer not null default 0 check (request_count >= 0)
);
alter table public.twiga_support_rate_limits enable row level security;
revoke all on table public.twiga_support_rate_limits from anon, authenticated;
grant all on table public.twiga_support_rate_limits to service_role;

insert into public.twiga_platform_admins(user_id)
select bm.user_id
from public.business_members bm
where bm.role = 'owner'
  and (select count(*) from auth.users) = 1
  and (select count(*) from public.business_members) = 1
on conflict (user_id) do nothing;
