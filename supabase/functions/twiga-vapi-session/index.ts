import { createClient } from "https://esm.sh/@supabase/supabase-js@2.49.1";

const cors = { "Access-Control-Allow-Origin": "*", "Access-Control-Allow-Headers": "authorization, apikey, content-type", "Access-Control-Allow-Methods": "POST, OPTIONS" };
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { ...cors, "Content-Type": "application/json" } });
const hex = (bytes: ArrayBuffer) => Array.from(new Uint8Array(bytes)).map(b => b.toString(16).padStart(2, "0")).join("");

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: cors });
  if (req.method !== "POST") return json({ error: "Method not allowed" }, 405);
  const url = Deno.env.get("SUPABASE_URL")!;
  const anon = Deno.env.get("SUPABASE_ANON_KEY")!;
  const serviceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
  const auth = req.headers.get("Authorization") || "";
  if (!auth.startsWith("Bearer ")) return json({ error: "Sign in required" }, 401);
  const userClient = createClient(url, anon, { global: { headers: { Authorization: auth } } });
  const { data: { user }, error: userError } = await userClient.auth.getUser();
  if (userError || !user) return json({ error: "Invalid login session" }, 401);
  const { businessId } = await req.json();
  if (typeof businessId !== "string") return json({ error: "businessId is required" }, 400);
  const admin = createClient(url, serviceKey);
  const { data: membership } = await admin.from("business_members").select("business_id").eq("business_id", businessId).eq("user_id", user.id).maybeSingle();
  if (!membership) return json({ error: "You are not a member of this business" }, 403);
  const { data: accountRows, error: accountError } = await admin.rpc("twiga_next_vapi_account");
  const account = Array.isArray(accountRows) ? accountRows[0] : null;
  if (accountError || !account) return json({ error: "No active TWIGA voice account is available. Ask the platform administrator to enable one." }, 503);

  const token = crypto.randomUUID() + crypto.randomUUID().replaceAll("-", "");
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(token));
  const expiresAt = new Date(Date.now() + 10 * 60 * 1000).toISOString();
  const { error } = await admin.from("twiga_voice_sessions").insert({ token_hash: hex(digest), business_id: businessId, user_id: user.id, expires_at: expiresAt });
  if (error) return json({ error: "Could not create secure voice session" }, 500);
  return json({
    voiceSessionToken: token,
    expiresAt,
    vapiAccount: {
      name: account.name,
      publicKey: account.vapi_public_key,
      assistantId: account.assistant_id,
      voiceLabel: account.voice_label || "",
      voiceModel: account.voice_model || ""
    }
  });
});
