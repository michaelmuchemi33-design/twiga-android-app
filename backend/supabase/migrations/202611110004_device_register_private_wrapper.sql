-- Keep privileged device registration implementation in the private schema.
-- The public RPC remains SECURITY INVOKER so the signed-in TWIGA user context is preserved.
alter function public.device_register(uuid,text) set schema private;
revoke all on function private.device_register(uuid,text) from public, anon;
grant usage on schema private to authenticated;
grant execute on function private.device_register(uuid,text) to authenticated;

create or replace function public.device_register(
  p_business_id uuid,
  p_name text
)
returns jsonb
language sql
security invoker
set search_path = public, private, pg_temp
as $function$
  select private.device_register(p_business_id,p_name);
$function$;

revoke all on function public.device_register(uuid,text) from public, anon;
grant execute on function public.device_register(uuid,text) to authenticated;
