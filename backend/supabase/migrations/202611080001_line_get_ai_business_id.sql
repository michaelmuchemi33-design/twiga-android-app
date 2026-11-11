-- Add business_id to the secured AI line lookup so the Vapi tool webhook
-- can resolve the correct tenant without trusting model-supplied IDs.
CREATE OR REPLACE FUNCTION public.line_get_ai(p_secret text, p_number text)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $function$
DECLARE
  ok boolean;
  l public.ai_lines;
  r public.business_ai;
  bname text;
BEGIN
  SELECT EXISTS (
    SELECT 1
    FROM public.app_secrets s
    WHERE s.name = 'line'
      AND s.hash = encode(sha256(convert_to(coalesce(p_secret, ''), 'UTF8')), 'hex')
  ) INTO ok;

  IF NOT ok THEN
    RAISE EXCEPTION 'Not allowed';
  END IF;

  SELECT * INTO l
  FROM public.ai_lines
  WHERE number = public.norm_number(p_number);

  IF l.number IS NULL THEN
    RETURN jsonb_build_object('business', NULL);
  END IF;

  SELECT name INTO bname
  FROM public.businesses
  WHERE id = l.business_id
    AND status IN ('TRIAL', 'ACTIVE')
    AND paid_through >= current_date;

  IF bname IS NULL THEN
    RETURN jsonb_build_object('business', NULL);
  END IF;

  SELECT * INTO r
  FROM public.business_ai
  WHERE business_id = l.business_id;

  RETURN jsonb_build_object(
    'business_id', l.business_id,
    'business', bname,
    'prompt', coalesce(r.prompt, ''),
    'greeting', coalesce(r.greeting, ''),
    'faq', coalesce(r.faq, ''),
    'rules', coalesce(r.rules, '')
  );
END;
$function$;

GRANT EXECUTE ON FUNCTION public.line_get_ai(text, text) TO anon, authenticated, service_role;
