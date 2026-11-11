-- Register the Android gateway directly for the authenticated TWIGA business.
-- The phone receives a per-installation token; no six-digit pairing code is required.
create or replace function public.device_register(
  p_business_id uuid,
  p_name text
)
returns jsonb
language plpgsql
security definer
set search_path = public
as $function$
declare
  v_user_id uuid := auth.uid();
  v_token text;
  v_device_id uuid;
  v_business_name text;
begin
  if v_user_id is null then
    raise exception 'Sign in to TWIGA before connecting this phone.';
  end if;

  if p_business_id is null or not exists (
    select 1
    from public.business_members bm
    where bm.business_id = p_business_id
      and bm.user_id = v_user_id
  ) then
    raise exception 'You do not have access to this TWIGA business.';
  end if;

  v_token := replace(gen_random_uuid()::text || gen_random_uuid()::text, '-', '');

  insert into public.devices (business_id, name, token_hash)
  values (
    p_business_id,
    left(coalesce(nullif(trim(p_name), ''), 'Android phone'), 60),
    encode(sha256(convert_to(v_token, 'UTF8')), 'hex')
  )
  returning id into v_device_id;

  select b.name into v_business_name
  from public.businesses b
  where b.id = p_business_id;

  return jsonb_build_object(
    'token', v_token,
    'device_id', v_device_id,
    'business', v_business_name
  );
end
$function$;

revoke all on function public.device_register(uuid, text) from anon, public;
grant execute on function public.device_register(uuid, text) to authenticated;
