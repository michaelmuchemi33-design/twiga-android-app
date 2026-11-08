-- Secure, idempotent Vapi PSTN call-report writer for the business dashboard.
CREATE OR REPLACE FUNCTION public.line_record_call(p_secret text, p_number text, p_record jsonb)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $function$
DECLARE
  ok boolean;
  l public.ai_lines;
  bname text;
  call_id text;
  row_id uuid;
  dir text;
BEGIN
  SELECT EXISTS (
    SELECT 1 FROM public.app_secrets s
    WHERE s.name = 'line'
      AND s.hash = encode(sha256(convert_to(coalesce(p_secret, ''), 'UTF8')), 'hex')
  ) INTO ok;
  IF NOT ok THEN RAISE EXCEPTION 'Not allowed'; END IF;

  SELECT * INTO l FROM public.ai_lines WHERE number = public.norm_number(p_number);
  IF l.number IS NULL THEN RAISE EXCEPTION 'AI line not found'; END IF;

  SELECT name INTO bname FROM public.businesses
  WHERE id = l.business_id AND status IN ('TRIAL', 'ACTIVE') AND paid_through >= current_date;
  IF bname IS NULL THEN RAISE EXCEPTION 'Business is not active'; END IF;

  call_id := nullif(left(coalesce(p_record->>'provider_call_id', ''), 200), '');
  IF call_id IS NULL THEN RAISE EXCEPTION 'Provider call ID is required'; END IF;

  dir := CASE WHEN p_record->>'direction' IN ('inbound', 'outbound') THEN p_record->>'direction' ELSE 'inbound' END;

  INSERT INTO public.twiga_call_records (
    business_id, provider, provider_call_id, channel, direction,
    from_number, to_number, status, started_at, ended_at,
    duration_seconds, transcript, summary, structured_data, cost_usd, metadata
  ) VALUES (
    l.business_id, 'vapi', call_id, 'pstn', dir,
    nullif(left(coalesce(p_record->>'from_number',''), 40), ''),
    nullif(left(coalesce(p_record->>'to_number',''), 40), ''),
    left(coalesce(p_record->>'status','ended'), 40),
    nullif(p_record->>'started_at','')::timestamptz,
    nullif(p_record->>'ended_at','')::timestamptz,
    CASE WHEN coalesce(p_record->>'duration_seconds','') ~ '^[0-9]+$' THEN greatest(0,(p_record->>'duration_seconds')::integer) ELSE 0 END,
    left(coalesce(p_record->>'transcript',''), 50000),
    left(coalesce(p_record->>'summary',''), 5000),
    coalesce(p_record->'structured_data','{}'::jsonb),
    CASE WHEN coalesce(p_record->>'cost_usd','') ~ '^[0-9]+([.][0-9]+)?$' THEN (p_record->>'cost_usd')::numeric ELSE NULL END,
    coalesce(p_record->'metadata','{}'::jsonb)
  )
  ON CONFLICT (provider, provider_call_id) DO UPDATE SET
    from_number = EXCLUDED.from_number,
    to_number = EXCLUDED.to_number,
    status = EXCLUDED.status,
    started_at = coalesce(EXCLUDED.started_at, public.twiga_call_records.started_at),
    ended_at = coalesce(EXCLUDED.ended_at, public.twiga_call_records.ended_at),
    duration_seconds = greatest(public.twiga_call_records.duration_seconds, EXCLUDED.duration_seconds),
    transcript = CASE WHEN EXCLUDED.transcript <> '' THEN EXCLUDED.transcript ELSE public.twiga_call_records.transcript END,
    summary = CASE WHEN EXCLUDED.summary <> '' THEN EXCLUDED.summary ELSE public.twiga_call_records.summary END,
    structured_data = CASE WHEN EXCLUDED.structured_data <> '{}'::jsonb THEN EXCLUDED.structured_data ELSE public.twiga_call_records.structured_data END,
    cost_usd = coalesce(EXCLUDED.cost_usd, public.twiga_call_records.cost_usd),
    metadata = public.twiga_call_records.metadata || EXCLUDED.metadata
  RETURNING id INTO row_id;

  RETURN jsonb_build_object('ok', true, 'id', row_id, 'business_id', l.business_id);
END;
$function$;

GRANT EXECUTE ON FUNCTION public.line_record_call(text, text, jsonb) TO anon, authenticated, service_role;
