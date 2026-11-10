
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
begin
  if p_amount is null or p_amount <= 0 then
    raise exception 'Payment amount must be positive' using errcode = '22023';
  end if;
  if p_method not in ('mpesa','bank','cash','card','other') then
    raise exception 'Unsupported payment method' using errcode = '22023';
  end if;
  if p_period_start is not null and p_period_end is not null and p_period_end < p_period_start then
    raise exception 'Payment period end cannot precede its start' using errcode = '22023';
  end if;

  insert into public.twiga_platform_payments(
    business_id, amount, currency, method, provider_reference, notes,
    period_start, period_end, recorded_by
  ) values (
    p_business_id, p_amount, 'KES', p_method, nullif(trim(coalesce(p_reference,'')), ''),
    nullif(trim(coalesce(p_notes,'')), ''), p_period_start, p_period_end, p_recorded_by
  )
  returning * into v_payment;

  if p_period_end is not null then
    update public.businesses
    set status = 'ACTIVE', paid_through = p_period_end
    where id = p_business_id;
    if not found then
      raise exception 'Client not found' using errcode = 'P0002';
    end if;
  end if;

  return v_payment;
end;
$$;
revoke all on function public.twiga_record_platform_payment(uuid,numeric,text,text,text,date,date,uuid)
  from public, anon, authenticated;
grant execute on function public.twiga_record_platform_payment(uuid,numeric,text,text,text,date,date,uuid)
  to service_role;
