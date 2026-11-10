import { createClient } from "https://esm.sh/@supabase/supabase-js@2.49.1";

const json = (body: unknown) => new Response(JSON.stringify(body), { status: 200, headers: { "Content-Type": "application/json" } });
const hex = (bytes: ArrayBuffer) => Array.from(new Uint8Array(bytes)).map(b => b.toString(16).padStart(2, "0")).join("");
const oneLine = (v: unknown) => JSON.stringify(v).replace(/[\r\n]+/g, " ");
Deno.serve(async (req) => {
  if (req.method !== "POST") return json({ error: "POST only" });
  const expected = Deno.env.get("VAPI_SERVER_SECRET");
  const received = req.headers.get("X-Vapi-Secret") || "";
  if (!expected || received !== expected) return json({ results: [] });
  const payload = await req.json().catch(() => ({}));
  const message = payload.message || {};
  if (message.type !== "tool-calls") return json({ received: true });
  const call = message.call || {};
  const metadata = call.metadata || {};
  const ticket = metadata.twigaVoiceSession;
  const tools = message.toolCallList || message.toolCalls || [];
  if (!ticket || typeof ticket !== "string") return json({ results: tools.map((t: any) => ({ toolCallId: t.id, error: "Voice session is not authorized. Reconnect from the TWIGA app." })) });
  const url = Deno.env.get("SUPABASE_URL")!;
  const serviceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
  const admin = createClient(url, serviceKey);
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(ticket));
  const tokenHash = hex(digest);
  const { data: session } = await admin.from("twiga_voice_sessions").select("business_id,user_id,expires_at,revoked_at").eq("token_hash", tokenHash).maybeSingle();
  if (!session || session.revoked_at || new Date(session.expires_at).getTime() < Date.now()) return json({ results: tools.map((t: any) => ({ toolCallId: t.id, error: "Your secure TWIGA voice session expired. Please reconnect." })) });
  const results = [];
  for (const tool of tools) {
    const id = tool.id || tool.toolCallId;
    const name = tool.function?.name || tool.name;
    const args = typeof tool.function?.arguments === "string" ? JSON.parse(tool.function.arguments || "{}") : (tool.function?.arguments || tool.parameters || {});
    try {
      let result: unknown;
      const base = { business_id: session.business_id, created_by: session.user_id };
      switch (name) {
        case "create_customer": {
          if (!args.name) throw new Error("Customer name is required");
          const { data, error } = await admin.from("twiga_customers").insert({ ...base, name: String(args.name).slice(0,120), phone: String(args.phone || "").slice(0,32), notes: String(args.notes || "").slice(0,1000) }).select("id,name,phone").single();
          if (error) throw error; result = { saved: true, customer: data }; break;
        }
        case "create_order": {
          if (!args.customer_name) throw new Error("Customer name is required");
          const amount = Number(args.total_amount || 0); if (!Number.isFinite(amount) || amount < 0) throw new Error("Invalid order amount");
          const { data, error } = await admin.from("twiga_orders").insert({ ...base, customer_name: String(args.customer_name).slice(0,120), total_amount: amount, description: String(args.description || "").slice(0,1000), status: "pending" }).select("id,customer_name,total_amount,status").single();
          if (error) throw error; result = { saved: true, order: data }; break;
        }
        case "create_appointment": {
          if (!args.customer_name || !args.scheduled_at) throw new Error("Customer name and appointment date/time are required");
          const when = new Date(args.scheduled_at); if (Number.isNaN(when.getTime())) throw new Error("Invalid appointment date/time");
          const { data, error } = await admin.from("twiga_appointments").insert({ ...base, customer_name: String(args.customer_name).slice(0,120), phone: String(args.phone || "").slice(0,32), scheduled_at: when.toISOString(), notes: String(args.notes || "").slice(0,1000), status: "scheduled" }).select("id,customer_name,scheduled_at,status").single();
          if (error) throw error; result = { saved: true, appointment: data }; break;
        }
        case "create_task": {
          if (!args.title) throw new Error("Task title is required");
          const { data, error } = await admin.from("twiga_tasks").insert({ ...base, title: String(args.title).slice(0,160), description: String(args.description || "").slice(0,1000), status: "open" }).select("id,title,status").single();
          if (error) throw error; result = { saved: true, task: data }; break;
        }
        case "list_orders": {
          const { data, error } = await admin.from("twiga_orders").select("id,customer_name,total_amount,status,created_at").eq("business_id",session.business_id).order("created_at",{ascending:false}).limit(10);
          if (error) throw error; result = data; break;
        }
        case "list_appointments": {
          const { data, error } = await admin.from("twiga_appointments").select("id,customer_name,scheduled_at,status").eq("business_id",session.business_id).order("scheduled_at",{ascending:true}).limit(10);
          if (error) throw error; result = data; break;
        }
        default: throw new Error("This TWIGA action is not enabled: " + String(name));
      }
      results.push({ toolCallId: id, result: oneLine(result) });
    } catch (e) {
      results.push({ toolCallId: id, error: String((e as Error).message || "Action failed").slice(0,300) });
    }
  }
  return json({ results });
});
