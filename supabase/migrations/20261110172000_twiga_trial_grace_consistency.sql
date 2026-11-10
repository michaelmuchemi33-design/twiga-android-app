update public.twiga_subscriptions
set billing_interval = 'weekly',
    amount = 375,
    grace_period_days = 3,
    grace_period_ends_at = case
      when trial_ends_at is not null then trial_ends_at + interval '3 days'
      when current_period_end is not null then current_period_end + interval '3 days'
      else grace_period_ends_at
    end,
    updated_at = now()
where status = 'trialing';

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
      if sub.trial_ends_at is not null then
        return now() < sub.trial_ends_at + make_interval(days => coalesce(sub.grace_period_days, 3));
      end if;
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
