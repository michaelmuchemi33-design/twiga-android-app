-- Twiga billing schema. Run with Supabase CLI before deploying functions.
create extension if not exists pgcrypto;

create table if not exists public.twiga_plans (
  code text primary key check (code in ('monthly','annual')),
  name text not null,
  amount_kes integer not null check (amount_kes > 0),
  duration_days integer not null check (duration_days > 0),
  active boolean not null default true,
  updated_at timestamptz not null default now()
);
insert into public.twiga_plans(code,name,amount_kes,duration_days)
values ('monthly','Monthly',1500,30),('annual','Annual',15000,365)
on conflict (code) do nothing;

create table if not exists public.twiga_customers (
  id uuid primary key default gen_random_uuid(),
  business_name text not null,
  contact_name text,
  phone text not null,
  email text,
  licence_key text not null unique default encode(gen_random_bytes(18),'hex'),
  created_at timestamptz not null default now(),
  trial_started_at timestamptz,
  trial_ends_at timestamptz,
  device_id text,
  status text not null default 'pending' check(status in ('pending','trial','active','expired','suspended'))
);
create table if not exists public.twiga_subscriptions (
  id uuid primary key default gen_random_uuid(),
  customer_id uuid not null references public.twiga_customers(id) on delete cascade,
  plan_code text not null references public.twiga_plans(code),
  status text not null default 'pending' check(status in ('pending','trial','active','expired','cancelled','suspended')),
  starts_at timestamptz,
  ends_at timestamptz,
  created_at timestamptz not null default now()
);
create table if not exists public.twiga_payments (
  id uuid primary key default gen_random_uuid(),
  customer_id uuid not null references public.twiga_customers(id),
  subscription_id uuid references public.twiga_subscriptions(id),
  plan_code text not null references public.twiga_plans(code),
  amount_kes integer not null,
  phone text not null,
  merchant_request_id text,
  checkout_request_id text unique,
  mpesa_receipt text unique,
  result_code integer,
  result_description text,
  status text not null default 'initiated' check(status in ('initiated','pending','paid','failed')),
  raw_callback jsonb,
  created_at timestamptz not null default now(),
  paid_at timestamptz
);
create index if not exists twiga_payments_customer_idx on public.twiga_payments(customer_id,created_at desc);
create index if not exists twiga_subscriptions_expiry_idx on public.twiga_subscriptions(ends_at) where status='active';
create table if not exists public.twiga_reminders (
  id uuid primary key default gen_random_uuid(),
  customer_id uuid not null references public.twiga_customers(id) on delete cascade,
  subscription_id uuid references public.twiga_subscriptions(id) on delete cascade,
  kind text not null,
  scheduled_for timestamptz not null,
  sent_at timestamptz,
  unique(subscription_id,kind)
);

alter table public.twiga_plans enable row level security;
alter table public.twiga_customers enable row level security;
alter table public.twiga_subscriptions enable row level security;
alter table public.twiga_payments enable row level security;
alter table public.twiga_reminders enable row level security;
-- No client-side table access. All writes/reads go through Edge Functions using service role.
revoke all on public.twiga_plans,public.twiga_customers,public.twiga_subscriptions,public.twiga_payments,public.twiga_reminders from anon, authenticated;

create or replace function public.twiga_activate_payment(p_checkout_id text,p_receipt text,p_callback jsonb)
returns void language plpgsql security definer set search_path=public as $$
declare p twiga_payments%rowtype; plan twiga_plans%rowtype; new_end timestamptz;
begin
 select * into p from twiga_payments where checkout_request_id=p_checkout_id for update;
 if not found or p.status='paid' then return; end if;
 select * into plan from twiga_plans where code=p.plan_code and active=true;
 if not found then raise exception 'Plan unavailable'; end if;
 new_end := greatest(now(),coalesce((select max(ends_at) from twiga_subscriptions where customer_id=p.customer_id and status='active'),now())) + make_interval(days=>plan.duration_days);
 update twiga_payments set status='paid',mpesa_receipt=p_receipt,paid_at=now(),raw_callback=p_callback where id=p.id;
 update twiga_subscriptions set status='active',ends_at=new_end where customer_id=p.customer_id and status in ('pending','expired');
 if not found then insert into twiga_subscriptions(customer_id,plan_code,status,starts_at,ends_at) values(p.customer_id,p.plan_code,'active',now(),new_end); end if;
 update twiga_customers set status='active' where id=p.customer_id;
end $$;
revoke all on function public.twiga_activate_payment(text,text,jsonb) from public,anon,authenticated;
