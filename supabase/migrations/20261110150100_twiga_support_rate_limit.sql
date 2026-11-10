
create or replace function public.twiga_support_consume_rate(p_fingerprint text)
returns boolean
language plpgsql
security definer
set search_path = pg_catalog, public
as $$
declare
  v_count integer;
begin
  if p_fingerprint is null or length(p_fingerprint) < 16 or length(p_fingerprint) > 128 then
    return false;
  end if;
  insert into public.twiga_support_rate_limits(fingerprint, window_started_at, request_count)
  values (p_fingerprint, now(), 1)
  on conflict (fingerprint) do update
    set window_started_at = case
          when public.twiga_support_rate_limits.window_started_at < now() - interval '1 minute' then now()
          else public.twiga_support_rate_limits.window_started_at
        end,
        request_count = case
          when public.twiga_support_rate_limits.window_started_at < now() - interval '1 minute' then 1
          else public.twiga_support_rate_limits.request_count + 1
        end
  returning request_count into v_count;
  return v_count <= 12;
end;
$$;
revoke all on function public.twiga_support_consume_rate(text) from public, anon, authenticated;
grant execute on function public.twiga_support_consume_rate(text) to service_role;
