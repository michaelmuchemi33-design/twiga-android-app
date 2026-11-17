import { createClient } from "https://esm.sh/@supabase/supabase-js@2.49.1";

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
const enc = new TextEncoder();
const safe = (v: unknown, max = 2000) => String(v ?? "").trim().slice(0, max);

function equalHex(a: string, b: string) {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}
async function hmacHex(secret: string, message: string) {
  const key = await crypto.subtle.importKey("raw", enc.encode(secret), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  const sig = new Uint8Array(await crypto.subtle.sign("HMAC", key, enc.encode(message)));
  return Array.from(sig, b => b.toString(16).padStart(2, "0")).join("");
}
function partsInZone(date: Date, timezone: string) {
  const parts = new Intl.DateTimeFormat("en-GB", {
    timeZone: timezone, year: "numeric", month: "2-digit", day: "2-digit",
    hour: "2-digit", minute: "2-digit", hourCycle: "h23",
  }).formatToParts(date);
  const out: Record<string, string> = {};
  for (const p of parts) if (p.type !== "literal") out[p.type] = p.value;
  return { year: Number(out.year), month: Number(out.month), day: Number(out.day), hour: Number(out.hour), minute: Number(out.minute) };
}
function localToUtc(year: number, month: number, day: number, hour: number, minute: number, timezone: string) {
  const target = Date.UTC(year, month - 1, day, hour, minute);
  let guess = new Date(target);
  for (let i = 0; i < 3; i++) {
    const p = partsInZone(guess, timezone);
    const represented = Date.UTC(p.year, p.month - 1, p.day, p.hour, p.minute);
    guess = new Date(guess.getTime() + target - represented);
  }
  return guess;
}
function nextWindow(date: Date, timezone: string, start: string, end: string) {
  const p = partsInZone(date, timezone);
  const hm = (v: string) => {
    const [h, m] = String(v || "08:00").split(":").map(Number);
    return h * 60 + m;
  };
  const nowMin = p.hour * 60 + p.minute, startMin = hm(start), endMin = hm(end);
  if (nowMin >= startMin && nowMin < endMin) return null;
  let y = p.year, mo = p.month, d = p.day;
  if (nowMin >= endMin) {
    const next = new Date(Date.UTC(y, mo - 1, d + 1));
    y = next.getUTCFullYear(); mo = next.getUTCMonth() + 1; d = next.getUTCDate();
  }
  const sh = Math.floor(startMin / 60), sm = startMin % 60;
  return localToUtc(y, mo, d, sh, sm, timezone);
}
async function setJob(admin: any, id: string, values: Record<string, unknown>) {
  const { error } = await admin.from("twiga_scheduled_calls").update({ ...values, updated_at: new Date().toISOString() }).eq("id", id);
  if (error) throw error;
}
Deno.serve(async (req: Request) => {
  if (req.method !== "POST") return json({ error: "POST only." }, 405);
  try {
    const url = Deno.env.get("SUPABASE_URL") || "";
    const service = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") || "";
    if (!url || !service) return json({ error: "TWIGA server is not configured." }, 503);
    const admin = createClient(url, service, { auth: { persistSession: false, autoRefreshToken: false } });

    const timestamp = safe(req.headers.get("x-timestamp"), 32);
    const signature = safe(req.headers.get("x-signature"), 128).toLowerCase();
    const seconds = Number(timestamp);
    if (!/^\d{10,13}$/.test(timestamp) || !Number.isFinite(seconds) || Math.abs(Date.now() / 1000 - seconds) > 300) {
      return json({ error: "Invalid or expired scheduler timestamp." }, 401);
    }
    const { data: cronSecret, error: cronSecretError } = await admin.rpc("twiga_get_platform_secret", { p_provider: "twiga_scheduled_calls_cron_secret" });
    if (cronSecretError || !cronSecret) return json({ error: "Scheduled-call signing secret is not configured." }, 503);
    const expected = await hmacHex(String(cronSecret), timestamp);
    if (!equalHex(signature, expected)) return json({ error: "Invalid scheduler signature." }, 401);

    const body = await req.json().catch(() => ({}));
    if (String(body.action || "") !== "process_due_calls") return json({ error: "Unknown scheduler action." }, 400);

    const { data: keyData, error: keyError } = await admin.rpc("twiga_get_platform_secret", { p_provider: "sautikit_platform_api_key" });
    const apiKey = String(keyData || "");
    if (keyError || !apiKey) {
      await admin.from("twiga_scheduled_calls").update({
        status: "waiting_for_provider",
        last_error: "Connect the SautiKit API key in Connections to enable scheduled calls.",
        updated_at: new Date().toISOString(),
      }).eq("status", "scheduled").lte("scheduled_at", new Date().toISOString());
      return json({ ok: true, processed: 0, waitingForProvider: true });
    }
    // Poll previously accepted calls so the queue and call history show their real outcome.
    const { data: submittedCalls } = await admin.from("twiga_scheduled_calls")
      .select("id,business_id,provider_call_id,metadata")
      .eq("status", "submitted").not("provider_call_id", "is", null)
      .order("updated_at", { ascending: true }).limit(20);
    let polledCalls = 0;
    for (const item of submittedCalls || []) {
      try {
        const statusResponse = await fetch("https://api.sautikit.com/v1/calls/" + encodeURIComponent(String(item.provider_call_id)), {
          headers: { Authorization: "Bearer " + apiKey, Accept: "application/json" },
          signal: AbortSignal.timeout(12000),
        });
        if (!statusResponse.ok) continue;
        const rawStatus = await statusResponse.text();
        let callStatus: any = {};
        try { callStatus = rawStatus ? JSON.parse(rawStatus) : {}; } catch { callStatus = {}; }
        const callData = callStatus.data && typeof callStatus.data === "object" ? callStatus.data : callStatus;
        const providerStatus = String(callData.status || "").toLowerCase();
        if (!["completed", "failed", "no_answer", "busy", "canceled", "cancelled"].includes(providerStatus)) continue;
        const finalStatus = providerStatus === "completed" ? "completed" : "failed";
        const finishedAt = callData.ended_at || callData.completed_at || new Date().toISOString();
        await setJob(admin, item.id, { status: finalStatus, completed_at: finishedAt, last_error: finalStatus === "failed" ? (providerStatus === "no_answer" ? "The contact did not answer." : providerStatus === "busy" ? "The contact's line was busy." : "The provider did not complete the call.") : null });
        await admin.from("twiga_call_records").update({
          status: providerStatus,
          ended_at: finishedAt,
          duration_seconds: Number(callData.duration_seconds || callData.duration_s || 0),
          transcript: typeof callData.transcript === "string" ? callData.transcript.slice(0, 30000) : undefined,
          summary: typeof callData.summary === "string" ? callData.summary.slice(0, 4000) : undefined,
          metadata: { scheduled_call_id: item.id, provider_status: providerStatus },
        }).eq("provider_call_id", item.provider_call_id).eq("business_id", item.business_id);
        polledCalls++;
      } catch (pollError) { console.error("Could not refresh SautiKit call status:", pollError instanceof Error ? pollError.message : "unknown error"); }
    }
    await admin.from("twiga_scheduled_calls").update({ status: "scheduled", last_error: null, updated_at: new Date().toISOString() })
      .eq("status", "waiting_for_provider");

    const { data: jobs, error: claimError } = await admin.rpc("twiga_claim_scheduled_calls", { p_limit: 10 });
    if (claimError) throw claimError;
    const claimed = Array.isArray(jobs) ? jobs : [];
    const summary = { ok: true, claimed: claimed.length, submitted: 0, polled: polledCalls, deferred: 0, skipped: 0, failed: 0 };

    for (const job of claimed) {
      try {
        const conditions = job.trigger_conditions && typeof job.trigger_conditions === "object" ? job.trigger_conditions : {};
        if (job.trigger_type === "missed_call" || conditions.only_if_missed_call === true) {
          if (!job.source_call_id) {
            await setJob(admin, job.id, { status: "skipped", last_error: "A source missed-call record is required for this trigger." });
            summary.skipped++; continue;
          }
          const { data: sourceCall } = await admin.from("twiga_call_records").select("status").eq("id", job.source_call_id).eq("business_id", job.business_id).maybeSingle();
          if (!sourceCall || !["missed", "no_answer", "failed"].includes(String(sourceCall.status || "").toLowerCase())) {
            await setJob(admin, job.id, { status: "skipped", last_error: "The source call is no longer a missed or unanswered call." });
            summary.skipped++; continue;
          }
        }
        if (conditions.only_if_unpaid === true) {
          const orderId = safe(conditions.order_id, 80);
          if (!orderId) {
            await setJob(admin, job.id, { status: "skipped", last_error: "An order ID is required for the unpaid-order condition." });
            summary.skipped++; continue;
          }
          const { data: order } = await admin.from("twiga_orders").select("payment_status").eq("id", orderId).eq("business_id", job.business_id).maybeSingle();
          if (!order || String(order.payment_status || "").toLowerCase() === "paid") {
            await setJob(admin, job.id, { status: "skipped", last_error: "The order is paid or no longer exists; no call was placed." });
            summary.skipped++; continue;
          }
        }

        const timezone = safe(job.timezone || "Africa/Nairobi", 80);
        const deferredUntil = nextWindow(new Date(), timezone, String(job.calling_window_start || "08:00"), String(job.calling_window_end || "20:00"));
        if (deferredUntil) {
          await setJob(admin, job.id, { status: "scheduled", scheduled_at: deferredUntil.toISOString(), last_error: "Deferred to the next permitted calling window." });
          summary.deferred++; continue;
        }

        const { data: line, error: lineError } = await admin.from("twiga_phone_lines").select("*")
          .eq("business_id", job.business_id).order("created_at", { ascending: false }).limit(1).maybeSingle();
        if (lineError) throw lineError;
        const settings = line?.settings && typeof line.settings === "object" ? line.settings : {};
        const agentId = safe(settings.inbound_agent_id, 100);
        if (!line || line.provider !== "sautikit" || line.outbound_enabled !== true || line.outbound_status !== "active" || !agentId) {
          await setJob(admin, job.id, { status: "waiting_for_provider", last_error: "This business needs an active SautiKit outbound line and a published agent before calls can be sent." });
          summary.deferred++; continue;
        }
        const { data: business, error: businessError } = await admin.from("businesses").select("name").eq("id", job.business_id).maybeSingle();
        if (businessError) throw businessError;
        const scripts = settings.sautikit_scripts && typeof settings.sautikit_scripts === "object" ? settings.sautikit_scripts : {};
        const outbound = safe(scripts.outbound || "Identify the business, explain the reason for calling, ask if this is a convenient time, keep the call brief, and respect requests not to be contacted again.", 5000);
        const to = safe(job.phone_number, 24);
        if (!/^\+[1-9]\d{7,14}$/.test(to)) throw new Error("The destination number is not in international format.");
        const response = await fetch("https://api.sautikit.com/v1/agents/" + encodeURIComponent(agentId) + "/send", {
          method: "POST",
          headers: { Authorization: "Bearer " + apiKey, "Content-Type": "application/json", Accept: "application/json" },
          body: JSON.stringify({
            to,
            prompt_template: "You are calling on behalf of {{business_name}}. Reason for calling: {{call_reason}}. " + outbound,
            variables: { business_name: String(business?.name || "the business"), call_reason: safe(job.reason, 500) },
          }),
          signal: AbortSignal.timeout(20000),
        });
        const raw = await response.text();
        let call: any = {};
        try { call = raw ? JSON.parse(raw) : {}; } catch { call = {}; }
        if (!response.ok) throw new Error("SautiKit call request failed (HTTP " + response.status + ").");
        const providerCallId = safe(call.call_id || call.id || call.data?.call_id || call.data?.id, 120);
        await setJob(admin, job.id, { status: "submitted", provider_call_id: providerCallId || null, last_error: null });
        const { error: recordError } = await admin.from("twiga_call_records").insert({
          business_id: job.business_id,
          provider: "sautikit",
          provider_call_id: providerCallId || null,
          channel: "ai",
          direction: "outbound",
          from_number: line.line_number,
          to_number: to,
          status: "queued",
          created_by: job.created_by || null,
          metadata: { scheduled_call_id: job.id, trigger_type: job.trigger_type, consent_status: job.consent_status },
        });
        if (recordError) console.error("Scheduled call record insert failed:", recordError.message);
        summary.submitted++;
      } catch (e) {
        const message = e instanceof Error ? e.message : "Scheduled call failed.";
        const attempt = Number(job.attempt_count || 1), maxAttempts = Number(job.max_attempts || 1);
        const retryable = attempt < maxAttempts && !/HTTP 4\d\d|not in international format/i.test(message);
        if (retryable) {
          const retryAt = new Date(Date.now() + Number(job.retry_interval_minutes || 30) * 60000).toISOString();
          await setJob(admin, job.id, { status: "scheduled", scheduled_at: retryAt, last_error: message });
        } else {
          await setJob(admin, job.id, { status: "failed", last_error: message });
        }
        summary.failed++;
      }
    }
    return json(summary);
  } catch (e) {
    console.error("twiga-scheduled-calls", e);
    return json({ error: e instanceof Error ? e.message : "Scheduled-call worker failed." }, 500);
  }
});