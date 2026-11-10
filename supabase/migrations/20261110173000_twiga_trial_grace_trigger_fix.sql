create or replace function private.twiga_subscription_set_grace()
returns trigger
language plpgsql
security definer
set search_path = pg_catalog, public
as $$
begin
  if new.current_period_end is not null then
    new.grace_period_ends_at := new.current_period_end
      + make_interval(days => coalesce(new.grace_period_days, 3));
  elsif new.trial_ends_at is not null then
    new.grace_period_ends_at := new.trial_ends_at
      + make_interval(days => coalesce(new.grace_period_days, 3));
  else
    new.grace_period_ends_at := null;
  end if;
  return new;
end;
$$;
revoke all on function private.twiga_subscription_set_grace() from public, anon, authenticated;

update public.twiga_subscriptions
set grace_period_days = coalesce(grace_period_days, 3),
    updated_at = now()
where current_period_end is null and trial_ends_at is not null and status = 'trialing';
