import { createClient } from "https://esm.sh/@supabase/supabase-js@2.49.1";

const cors = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, apikey, content-type, x-client-info",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};
const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { ...cors, "Content-Type": "application/json" } });
const API = "https://api.sautikit.com";

type Row = Record<string, any>;

async function skRequest(key: string, path: string, method = "GET", body?: unknown) {
  const response = await fetch(API + path, {
    method,
    headers: {
      Authorization: "Bearer " + key,
      Accept: "application/json",
      ...(body === undefined ? {} : { "Content-Type": "application/json" }),
    },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
  });
  const raw = await response.text();
  let data: Row = {};
  try { data = raw ? JSON.parse(raw) : {}; } catch { data = { message: raw.slice(0, 500) }; }
  if (!response.ok) {
    const message = String(data?.error?.message || data?.message || data?.error || "SautiKit request failed (HTTP " + response.status + ").");
    throw Object.assign(new Error(message), { status: response.status, details: data?.error || null });
  }
  return data;
}

function composePrompt(core: string, greeting: string, inbound: string, outbound: string) {
  const marker = "----- TWIGA FRONT DESK SETTINGS -----";
  const cleanCore = String(core || "").split(marker)[0].trim();
  return [
    cleanCore,
    marker,
    "OPENING GREETING:\n" + greeting,
    "INBOUND CALL INSTRUCTIONS:\n" + inbound,
    "OUTBOUND CALL INSTRUCTIONS:\n" + outbound,
    "GLOBAL SAFETY: Use only verified information from this business workspace. Never invent prices, stock, availability, bookings, orders, payments, or integration status. Never ask for passwords, PINs, OTPs, card numbers, or security codes. Respect requests not to be called again. If information is missing, say the team will confirm it.",
  ].filter(Boolean).join("\n\n");
}

Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: cors });
  if (req.method !== "POST") return json({ error: "POST only." }, 405);

  try {
    const auth = req.headers.get("Authorization") || "";
    if (!auth.startsWith("Bearer ")) return json({ error: "Sign in required." }, 401);
    const url = Deno.env.get("SUPABASE_URL") || "";
    const anon = Deno.env.get("SUPABASE_ANON_KEY") || "";
    const service = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") || "";
    if (!url || !anon || !service) return json({ error: "TWIGA server is not configured." }, 503);

    const userClient = createClient(url, anon, { global: { headers: { Authorization: auth } } });
    const { data: authData, error: authError } = await userClient.auth.getUser();
    const user = authData?.user;
    if (authError || !user) return json({ error: "Your sign-in has expired. Sign in again." }, 401);

    const admin = createClient(url, service, { auth: { persistSession: false, autoRefreshToken: false } });
    const body = await req.json().catch(() => ({}));
    const action = String(body.action || "get_config");
    const businessId = String(body.businessId || "").trim();

    const { data: adminRow, error: adminError } = await admin
      .from("twiga_platform_admins").select("user_id").eq("user_id", user.id).maybeSingle();
    if (adminError) throw adminError;
    const isPlatformAdmin = !!adminRow;

    if (action === "connect") {
      if (!isPlatformAdmin) return json({ error: "Only a TWIGA platform administrator can connect the shared SautiKit account." }, 403);
      const apiKey = String(body.apiKey || "").trim();
      if (apiKey.length < 20 || apiKey.length > 4000) return json({ error: "Enter a valid SautiKit API key." }, 400);
      const catalog = await skRequest(apiKey, "/v1/models");
      if (!Array.isArray(catalog?.data) || !catalog.data.length) return json({ error: "SautiKit returned no voice models. Check that this key has model read access." }, 400);
      const { data: last4, error: saveError } = await admin.rpc("twiga_set_platform_secret", {
        p_provider: "sautikit_platform_api_key",
        p_secret: apiKey,
        p_updated_by: user.id,
      });
      if (saveError) {
        console.error("SautiKit Vault save failed:", saveError.message);
        return json({ error: "SautiKit key validated, but could not be stored in encrypted Vault." }, 500);
      }
      return json({
        connected: true,
        keyLast4: last4,
        models: catalog.data,
        message: "SautiKit API key verified and stored securely in TWIGA Vault.",
      });
    }

    if (!businessId) return json({ error: "Choose a business workspace first." }, 400);
    const { data: membership, error: memberError } = await admin
      .from("business_members").select("role").eq("business_id", businessId).eq("user_id", user.id).maybeSingle();
    if (memberError) throw memberError;
    if (!membership && !isPlatformAdmin) return json({ error: "You are not authorised for this business." }, 403);
    const canManage = isPlatformAdmin || ["owner", "admin"].includes(String(membership?.role || ""));

    const { data: line, error: lineError } = await admin
      .from("twiga_phone_lines").select("*").eq("business_id", businessId)
      .order("created_at", { ascending: false }).limit(1).maybeSingle();
    if (lineError) throw lineError;
    const settings: Row = line?.settings && typeof line.settings === "object" ? line.settings : {};
    const agentId = String(settings.inbound_agent_id || "");
    const numberId = String(line?.provider_reference || "");

    const { data: keyRef, error: keyRefError } = await admin
      .from("twiga_platform_secret_refs").select("secret_last4,updated_at")
      .eq("provider", "sautikit_platform_api_key").maybeSingle();
    if (keyRefError) throw keyRefError;
    const { data: apiKey, error: secretError } = await admin.rpc("twiga_get_platform_secret", {
      p_provider: "sautikit_platform_api_key",
    });
    if (secretError) throw secretError;
    const key = String(apiKey || "");

    if (action === "get_config") {
      if (!line) return json({ phoneLine: null, keyConfigured: !!keyRef, isPlatformAdmin });
      let models: Row[] = [];
      let agent: Row | null = null;
      let number: Row | null = null;
      let providerError = "";
      if (key) {
        try {
          const [catalog, agentData, numberData] = await Promise.all([
            skRequest(key, "/v1/models"),
            agentId ? skRequest(key, "/v1/agents/" + encodeURIComponent(agentId)) : Promise.resolve(null),
            numberId ? skRequest(key, "/v1/numbers/" + encodeURIComponent(numberId)) : Promise.resolve(null),
          ]);
          models = Array.isArray(catalog?.data) ? catalog.data : [];
          agent = agentData;
          number = numberData;
        } catch (e) {
          providerError = e instanceof Error ? e.message : "SautiKit connection check failed.";
        }
      }
      const scripts = settings.sautikit_scripts && typeof settings.sautikit_scripts === "object" ? settings.sautikit_scripts : {};
      return json({
        isPlatformAdmin,
        keyConfigured: !!keyRef,
        keyLast4: keyRef?.secret_last4 || "",
        keyUpdatedAt: keyRef?.updated_at || null,
        phoneLine: {
          id: line.id,
          line_number: line.line_number,
          carrier: line.carrier,
          connection_method: line.connection_method,
          inbound_enabled: line.inbound_enabled,
          inbound_status: line.inbound_status,
          outbound_enabled: line.outbound_enabled,
          outbound_status: line.outbound_status,
          provider: line.provider,
          provider_reference: line.provider_reference,
          settings,
        },
        agent,
        number,
        models,
        scripts: {
          greeting: scripts.greeting || "Hello, karibu! Thank you for calling {{business_name}}. How can I help you today?",
          inbound: scripts.inbound || "Answer in the caller's language (Kenyan English or Kiswahili). Identify why they are calling, use verified business information, and capture appointment or order requests for the team to confirm. Never claim that a booking, order or payment is complete until TWIGA confirms it was saved.",
          outbound: scripts.outbound || "Only call a person when the business has authorised the callback. Introduce yourself and the business, explain the reason for the call, ask whether it is a convenient time, keep the conversation brief, and respect requests not to be called again.",
        },
        providerError,
        inboundBound: settings.ai_agent_bound === true
          && settings.inbound_agent_provider === "sautikit-managed"
          && settings.inbound_agent_id === agentId,
        inboundVerified: !!(number?.routing?.inbound_agent?.enabled && number?.routing?.inbound_agent?.agent_id === agentId),
        inboundMode: number?.routing?.inbound_agent?.mode || null,
      });
    }

    if (!key) return json({ error: "Connect the SautiKit API key in Phone & AI before managing voices." }, 409);
    if (!line || !agentId || !numberId) return json({ error: "This business does not yet have a linked SautiKit phone line and published agent." }, 409);
    if (!canManage && ["publish_agent", "send_call"].includes(action)) return json({ error: "Only the business owner, admin or TWIGA platform administrator can change or call from this receptionist." }, 403);

    if (action === "publish_agent") {
      const [catalog, agent, number] = await Promise.all([
        skRequest(key, "/v1/models"),
        skRequest(key, "/v1/agents/" + encodeURIComponent(agentId)),
        skRequest(key, "/v1/numbers/" + encodeURIComponent(numberId)),
      ]);
      const models: Row[] = Array.isArray(catalog?.data) ? catalog.data : [];
      const modelId = String(body.model || agent.model || "");
      const voice = String(body.voice || agent.voice || "");
      const selectedModel = models.find((item) => item.id === modelId);
      if (!selectedModel || !Array.isArray(selectedModel.voices) || !selectedModel.voices.includes(voice)) {
        return json({ error: "That voice is not available on the selected SautiKit model." }, 400);
      }
      const inbound = number?.routing?.inbound_agent;
      if (!inbound?.enabled || inbound.agent_id !== agentId) {
        return json({ error: "The SautiKit number is not currently bound to this receptionist. The live inbound route was left unchanged; check number routing in SautiKit first." }, 409);
      }
      const scripts = {
        greeting: String(body.greeting || "").trim().slice(0, 1200),
        inbound: String(body.inboundPrompt || "").trim().slice(0, 5000),
        outbound: String(body.outboundPrompt || "").trim().slice(0, 5000),
      };
      if (!scripts.greeting || !scripts.inbound || !scripts.outbound) return json({ error: "Complete the greeting, inbound and outbound instructions before publishing." }, 400);
      const basePrompt = composePrompt(String(body.corePrompt || agent.base_prompt || ""), scripts.greeting, scripts.inbound, scripts.outbound);
      const updateBody = {
        name: String(agent.name || "TWIGA Product Demo Guide"),
        base_prompt: basePrompt,
        accent: String(agent.accent || "Kenyan English and Kiswahili"),
        speech_style: String(agent.speech_style || "Warm, natural, clear Kenyan English and Kiswahili. Short turns and natural pauses."),
        model: modelId,
        voice,
        max_call_minutes: Number(agent.max_call_minutes || 8),
      };
      const updated = await skRequest(key, "/v1/agents/" + encodeURIComponent(agentId), "PUT", updateBody);
      const published = await skRequest(key, "/v1/agents/" + encodeURIComponent(agentId) + "/publish", "POST");
      const nextSettings = {
        ...settings,
        inbound_agent_provider: "sautikit-managed",
        inbound_agent_id: agentId,
        inbound_agent_name: String(agent.name || "TWIGA Product Demo Guide"),
        inbound_agent_revision: Number(published.revision || (Number(settings.inbound_agent_revision || 0) + 1)),
        inbound_model_id: modelId,
        inbound_voice: voice,
        ai_agent_bound: true,
        provider_status: "active",
        prompt_profile: "twiga-dashboard-managed-v1",
        sautkit_scripts: scripts,
        last_published_at: new Date().toISOString(),
      };
      const { error: updateLineError } = await admin.from("twiga_phone_lines")
        .update({ settings: nextSettings, updated_at: new Date().toISOString() })
        .eq("id", line.id);
      if (updateLineError) throw updateLineError;
      return json({
        published: true,
        revision: published.revision,
        voice,
        model: modelId,
        aiRatePerMinute: Number(selectedModel.rate_per_minute_minor || 0) / 100,
        agent: updated,
        inboundRoutePreserved: true,
        message: "Voice and receptionist instructions published. The existing inbound number remains bound to this same agent.",
      });
    }

    if (action === "send_call") {
      const to = String(body.to || "").trim();
      const reason = String(body.reason || "").trim().slice(0, 500);
      if (!/^\+[1-9]\d{7,14}$/.test(to)) return json({ error: "Enter the destination in international format, such as +2547XXXXXXXX." }, 400);
      if (!reason) return json({ error: "Enter why this person is being called." }, 400);
      if (body.authorised !== true) return json({ error: "Confirm that the person or business has authorised this callback before placing it." }, 400);
      const scripts = settings.sautikit_scripts && typeof settings.sautikit_scripts === "object" ? settings.sautikit_scripts : {};
      const outbound = String(scripts.outbound || "Identify the business, explain the reason for calling, ask if this is a convenient time, and respect requests not to be contacted again.");
      const business = await admin.from("businesses").select("name").eq("id", businessId).maybeSingle();
      const call = await skRequest(key, "/v1/agents/" + encodeURIComponent(agentId) + "/send", "POST", {
        to,
        prompt_template: "You are calling on behalf of {{business_name}}. Reason for calling: {{call_reason}}. " + outbound,
        variables: { business_name: String(business.data?.name || "the business"), call_reason: reason },
      });
      return json({ submitted: true, call, message: "Your authorised callback was submitted through the published SautiKit receptionist." });
    }

    return json({ error: "Unknown SautiKit action." }, 404);
  } catch (e) {
    const err = e as Error & { status?: number };
    console.error("twiga-sautikit", err.message);
    return json({ error: err.message || "SautiKit operation failed." }, err.status && err.status >= 400 && err.status < 600 ? err.status : 500);
  }
});