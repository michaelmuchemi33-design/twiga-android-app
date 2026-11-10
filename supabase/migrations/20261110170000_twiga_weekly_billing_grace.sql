create schema if not exists private;
grant usage on schema private to authenticated;

alter table public.twiga_subscriptions
  drop constraint if exists twiga_subscriptions_billing_interval_check;
alter table public.twiga_subscriptions
  add constraint twiga_subscriptions_billing_interval_check
  check (billing_interval in ('weekly','monthly','annual'));
alter table public.twiga_subscriptions
  alter column billing_interval set default 'weekly',
  alter column amount set default 375;
alter table public.twiga_subscriptions
  add column if not exists grace_period_days integer not null default 3
  check (grace_period_days between 0 and 14);

update public.twiga_subscriptions
set billing_interval = 'weekly',
    amount = 375,
    grace_period_days = 3,
    grace_period_ends_at = case
      when current_period_end is not null
      then current_period_end + interval '3 days'
      else grace_period_ends_at
    end,
    updated_at = now()
where plan_code = 'starter' and status = 'trialing' and current_period_end is null;

create or replace function private.twiga_subscription_set_grace()
returns trigger
language plpgsql
security definer
set search_path = pg_catalog, public
as $$
begin
  if new.current_period_end is null then
    new.grace_period_ends_at := null;
  else
    new.grace_period_ends_at := new.current_period_end
      + make_interval(days => coalesce(new.grace_period_days, 3));
  end if;
  return new;
end;
$$;
revoke all on function private.twiga_subscription_set_grace() from public, anon, authenticated;

drop trigger if exists twiga_subscription_set_grace on public.twiga_subscriptions;
create trigger twiga_subscription_set_grace
before insert or update of current_period_end, grace_period_days
on public.twiga_subscriptions
for each row execute function private.twiga_subscription_set_grace();

create or replace function private.twiga_has_paid_access(p_business_id uuid)
returns boolean
language plpgsql
stable
security definer
set search_path = pg_catalog, public, private
as $$
declare
  sub public.twiga_subscriptions%rowtype;
  biz public.businesses%rowtype;
begin
  if auth.uid() is null then return false; end if;
  if not exists (
    select 1 from public.business_members bm
    where bm.business_id = p_business_id and bm.user_id = auth.uid()
  ) then return false; end if;

  select * into sub
  from public.twiga_subscriptions
  where business_id = p_business_id
  order by created_at desc
  limit 1;

  if found then
    if sub.status = 'trialing' then
      if sub.trial_ends_at is not null then return now() < sub.trial_ends_at; end if;
      select * into biz from public.businesses where id = p_business_id;
      return found and biz.status = 'TRIAL'
        and (now() at time zone 'Africa/Nairobi')::date <= biz.paid_through + 3;
    end if;
    if sub.status in ('active', 'past_due') then
      return sub.grace_period_ends_at is not null and now() < sub.grace_period_ends_at;
    end if;
    return false;
  end if;

  select * into biz from public.businesses where id = p_business_id;
  return found
    and biz.status in ('TRIAL', 'ACTIVE')
    and (now() at time zone 'Africa/Nairobi')::date <= biz.paid_through + 3;
end;
$$;
revoke all on function private.twiga_has_paid_access(uuid) from public, anon;
grant execute on function private.twiga_has_paid_access(uuid) to authenticated;

-- Preserve member read access to existing business records, including after expiry.
-- New/changed business data is the paid feature that pauses after the three-day grace window.
do $$
declare
  t text;
  p text;
begin
  foreach t in array array['twiga_customers','twiga_orders','twiga_appointments','twiga_tasks'] loop
    p := replace(t, 'twiga_', '') || '_member_access';
    execute format('drop policy if exists %I on public.%I', p, t);
    execute format('drop policy if exists %I on public.%I', t || '_member_access', t);
    execute format('drop policy if exists %I on public.%I', t || '_member_read', t);
    execute format('drop policy if exists %I on public.%I', t || '_member_insert', t);
    execute format('drop policy if exists %I on public.%I', t || '_member_update', t);
    execute format('drop policy if exists %I on public.%I', t || '_member_delete', t);
    execute format(
      'create policy %I on public.%I for select to authenticated using (private.twiga_is_business_member(business_id))',
      t || '_member_read', t
    );
    execute format(
      'create policy %I on public.%I for insert to authenticated with check (private.twiga_is_business_member(business_id) and private.twiga_has_paid_access(business_id))',
      t || '_member_insert', t
    );
    execute format(
      'create policy %I on public.%I for update to authenticated using (private.twiga_is_business_member(business_id) and private.twiga_has_paid_access(business_id)) with check (private.twiga_is_business_member(business_id) and private.twiga_has_paid_access(business_id))',
      t || '_member_update', t
    );
    execute format(
      'create policy %I on public.%I for delete to authenticated using (private.twiga_is_business_member(business_id) and private.twiga_has_paid_access(business_id))',
      t || '_member_delete', t
    );
  end loop;
end $$;

drop policy if exists "Business members manage products" on public.twiga_products;
create policy "Business members insert products with paid access"
on public.twiga_products for insert to authenticated
with check (
  exists (select 1 from public.business_members bm where bm.business_id = twiga_products.business_id and bm.user_id = auth.uid())
  and private.twiga_has_paid_access(business_id)
);
create policy "Business members update products with paid access"
on public.twiga_products for update to authenticated
using (
  exists (select 1 from public.business_members bm where bm.business_id = twiga_products.business_id and bm.user_id = auth.uid())
  and private.twiga_has_paid_access(business_id)
)
with check (
  exists (select 1 from public.business_members bm where bm.business_id = twiga_products.business_id and bm.user_id = auth.uid())
  and private.twiga_has_paid_access(business_id)
);
create policy "Business members delete products with paid access"
on public.twiga_products for delete to authenticated
using (
  exists (select 1 from public.business_members bm where bm.business_id = twiga_products.business_id and bm.user_id = auth.uid())
  and private.twiga_has_paid_access(business_id)
);

create or replace function public.twiga_record_platform_payment(
  p_business_id uuid,
  p_amount numeric,
  p_method text,
  p_reference text default null,
  p_notes text default null,
  p_period_start date default null,
  p_period_end date default null,
  p_recorded_by uuid default null
)
returns public.twiga_platform_payments
language plpgsql
security definer
set search_path = pg_catalog, public
as $$
declare
  v_payment public.twiga_platform_payments;
  v_business public.businesses;
  v_period_start date;
  v_period_end date;
  v_weeks integer;
begin
  if p_amount is null or p_amount < 375 or mod(p_amount, 375) <> 0 then
    raise exception 'Payment must be in weekly KSh 375 increments' using errcode = '22023';
  end if;
  if p_method not in ('mpesa','bank','cash','card','other') then
    raise exception 'Unsupported payment method' using errcode = '22023';
  end if;
  select * into v_business from public.businesses where id = p_business_id for update;
  if not found then raise exception 'Client not found' using errcode = 'P0002'; end if;

  v_weeks := (p_amount / 375)::integer;
  v_period_start := coalesce(p_period_start, greatest(current_date, v_business.paid_through + 1));
  v_period_end := coalesce(p_period_end, v_period_start + (7 * v_weeks) - 1);
  if v_period_end < v_period_start then
    raise exception 'Payment period end cannot precede its start' using errcode = '22023';
  end if;

  insert into public.twiga_platform_payments(
    business_id, amount, currency, method, provider_reference, notes,
    period_start, period_end, recorded_by
  ) values (
    p_business_id, p_amount, 'KES', p_method,
    nullif(trim(coalesce(p_reference,'')), ''),
    nullif(trim(coalesce(p_notes,'')), ''),
    v_period_start, v_period_end, p_recorded_by
  )
  returning * into v_payment;

  update public.businesses
  set status = 'ACTIVE', paid_through = v_period_end
  where id = p_business_id;

  update public.twiga_subscriptions
  set plan_code = 'starter',
      billing_interval = 'weekly',
      amount = p_amount,
      currency = 'KES',
      status = 'active',
      current_period_start = (v_period_start::timestamp at time zone 'Africa/Nairobi'),
      current_period_end = ((v_period_end + 1)::timestamp at time zone 'Africa/Nairobi'),
      grace_period_days = 3,
      grace_period_ends_at = ((v_period_end + 4)::timestamp at time zone 'Africa/Nairobi'),
      last_payment_at = now(),
      updated_at = now()
  where id = (
    select id from public.twiga_subscriptions
    where business_id = p_business_id
    order by created_at desc limit 1
  );

  if not found then
    insert into public.twiga_subscriptions(
      business_id, plan_code, billing_interval, currency, amount, status,
      current_period_start, current_period_end, grace_period_days,
      grace_period_ends_at, last_payment_at, updated_at
    ) values (
      p_business_id, 'starter', 'weekly', 'KES', p_amount, 'active',
      (v_period_start::timestamp at time zone 'Africa/Nairobi'),
      ((v_period_end + 1)::timestamp at time zone 'Africa/Nairobi'),
      3, ((v_period_end + 4)::timestamp at time zone 'Africa/Nairobi'),
      now(), now()
    );
  end if;
  return v_payment;
end;
$$;

revoke all on function public.twiga_record_platform_payment(uuid,numeric,text,text,text,date,date,uuid) from public, anon, authenticated;
grant execute on function public.twiga_record_platform_payment(uuid,numeric,text,text,text,date,date,uuid) to service_role;

