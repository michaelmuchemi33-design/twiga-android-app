import { createClient } from "https://esm.sh/@supabase/supabase-js@2.49.1";

const cors = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, apikey, content-type",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};
const reply = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { ...cors, "Content-Type": "application/json" } });
const hex = (bytes: ArrayBuffer) => Array.from(new Uint8Array(bytes)).map(b => b.toString(16).padStart(2, "0")).join("");
const bounded = (v: unknown, max: number) => String(v ?? "").trim().slice(0, max);

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: cors });
  if (req.method !== "POST") return reply({ error: "POST only" }, 405);
  try {
    const url = Deno.env.get("SUPABASE_URL") || "";
    const anon = Deno.env.get("SUPABASE_ANON_KEY") || "";
    const service = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") || "";
    const xaiKey = Deno.env.get("XAI_API_KEY") || "";
    if (!url || !anon || !service) return reply({ error: "Support service is not configured." }, 503);

    const admin = createClient(url, service);
    const ip = req.headers.get("cf-connecting-ip") || req.headers.get("x-forwarded-for")?.split(",")[0]?.trim() || "unknown";
    const agent = req.headers.get("user-agent") || "";
    const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(ip + "|" + agent));
    const fingerprint = hex(digest);
    const { data: allowed, error: rateError } = await admin.rpc("twiga_support_consume_rate", { p_fingerprint: fingerprint });
    if (rateError) throw rateError;
    if (!allowed) return reply({ error: "You have sent several messages. Please wait a minute and try again." }, 429);

    const payload = await req.json().catch(() => ({}));
    const action = bounded(payload.action || "chat", 30);
    let userId: string | null = null;
    let businessId: string | null = null;
    const auth = req.headers.get("Authorization") || "";
    if (auth.startsWith("Bearer ")) {
      const userClient = createClient(url, anon, { global: { headers: { Authorization: auth } } });
      const { data: userData } = await userClient.auth.getUser();
      const user = userData?.user;
      if (user) {
        userId = user.id;
        const requestedBusiness = bounded(payload.businessId, 64);
        if (requestedBusiness) {
          const { data: membership } = await admin.from("business_members").select("business_id")
            .eq("business_id", requestedBusiness).eq("user_id", user.id).maybeSingle();
          if (!membership) return reply({ error: "You are not a member of that business." }, 403);
          businessId = requestedBusiness;
        }
      }
    }

    if (action === "ticket") {
      const subject = bounded(payload.subject, 120);
      const message = bounded(payload.message, 3000);
      const contact = bounded(payload.contact, 160);
      const channel = ["app", "website", "whatsapp"].includes(payload.channel) ? payload.channel : "app";
      if (!subject || !message) return reply({ error: "Add a short subject and describe the issue." }, 400);
      const { data, error } = await admin.from("twiga_support_tickets").insert({
        user_id: userId, business_id: businessId, channel, subject, message,
        contact: contact || null, status: "open",
      }).select("id,status,created_at").single();
      if (error) throw error;
      return reply({ ticket: data, saved: true });
    }

    if (!xaiKey) return reply({ error: "AI support is not activated yet. Please contact TWIGA support.", code: "support_ai_not_configured" }, 503);
    const incoming = Array.isArray(payload.messages) ? payload.messages.slice(-8) : [];
    const messages = incoming.map((m: any) => ({
      role: m?.role === "assistant" ? "assistant" : "user",
      content: bounded(m?.content, 2000),
    })).filter((m: any) => m.content.length > 0);
    if (!messages.length || messages[messages.length - 1].role !== "user")
      return reply({ error: "Enter a support question first." }, 400);
    const system = `You are TWIGA Support, the practical support assistant for the TWIGA Business Assistant app and TWIGA website. Reply in clear, friendly English, unless the customer writes in Kiswahili; then respond naturally in Kiswahili. Keep answers short and give numbered steps only when needed.

Use only these facts:
- TWIGA Business Assistant is an Android app for business owners and staff. It includes business selection, customers, orders, appointments, tasks, product catalogue, business profile and in-app voice assistant.
- In-app Vapi voice works over the internet. Do not promise that this separate Business Assistant app answers SIM cellular calls or replaces the phone dialer.
- The website download page is https://twiga.co.ke/#download. Current website release claims Android 7.0+ compatibility, but device-specific install problems must be diagnosed from the exact Android version and install error.
- WhatsApp inbox connection requires the TWIGA WhatsApp service to be deployed and the business WhatsApp account to be paired. Do not claim it is online unless the customer says it shows connected.
- Support can record a ticket for a human. Never claim that a ticket was opened unless the ticket API confirms it.
- Payment is currently handled as a one-off/manual payment arrangement. Do not say automatic subscriptions or renewals are active, do not invent a price, paybill, till number, phone number, or payment link, and never ask for an M-Pesa PIN or OTP.
- Do not claim that you can see someone's personal account, subscription or payment status. Direct account-specific issues to the support form.
- Never ask for passwords, OTPs, M-Pesa PINs, full card details or secrets.
If unsure, say so clearly and suggest opening a support ticket. Do not invent features, account data or support contacts. `;
    const apiResponse = await fetch("https://api.x.ai/v1/responses", {
      method: "POST",
      headers: { "Authorization": "Bearer " + xaiKey, "Content-Type": "application/json" },
      body: JSON.stringify({
        model: "grok-4.3",
        input: [{ role: "system", content: system }, ...messages],
        reasoning: { effort: "none" },
        max_output_tokens: 420,
        store: false,
      }),
      signal: AbortSignal.timeout(30000),
    });
    const raw = await apiResponse.json().catch(() => ({}));
    if (!apiResponse.ok) {
      console.error("xAI support error", apiResponse.status, JSON.stringify(raw).slice(0, 600));
      return reply({ error: "The AI support service is temporarily unavailable. Please try again shortly." }, 502);
    }
    const textParts: string[] = [];
    for (const item of (raw.output || [])) {
      if (item.type === "message" && Array.isArray(item.content)) {
        for (const part of item.content) if (part.type === "output_text" && part.text) textParts.push(part.text);
      }
    }
    const answer = (textParts.join("\n").trim() || bounded(raw.output_text, 3000)).slice(0, 3000);
    if (!answer) return reply({ error: "I could not create an answer just now. Please try once more." }, 502);
    return reply({ answer, model: "grok-4.3" });
  } catch (error) {
    console.error("twiga-ai-support", error);
    return reply({ error: "Support could not be reached. Please try again." }, 500);
  }
});
