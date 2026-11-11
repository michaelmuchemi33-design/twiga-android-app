-- Keep the privileged POS transaction implementation out of the exposed public API schema.
alter function public.twiga_pos_checkout(uuid,text,text,text,jsonb) set schema private;
revoke all on function private.twiga_pos_checkout(uuid,text,text,text,jsonb) from public, anon;
grant usage on schema private to authenticated;
grant execute on function private.twiga_pos_checkout(uuid,text,text,text,jsonb) to authenticated;

-- PostgREST continues to expose a SECURITY INVOKER wrapper in public.
create or replace function public.twiga_pos_checkout(
  p_business_id uuid,
  p_customer_name text,
  p_customer_phone text,
  p_payment_method text,
  p_items jsonb
)
returns jsonb
language sql
security invoker
set search_path = public, private, pg_temp
as $function$
  select private.twiga_pos_checkout(
    p_business_id,p_customer_name,p_customer_phone,p_payment_method,p_items
  );
$function$;
revoke all on function public.twiga_pos_checkout(uuid,text,text,text,jsonb) from public, anon;
grant execute on function public.twiga_pos_checkout(uuid,text,text,text,jsonb) to authenticated;

-- Six-digit pairing is no longer used; the Android app registers via authenticated business membership.
revoke all on function public.create_pairing(uuid) from public, anon, authenticated;
revoke all on function public.device_claim(text,text) from public, anon, authenticated;
