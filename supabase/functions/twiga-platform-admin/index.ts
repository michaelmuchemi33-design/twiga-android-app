import { createClient } from "https://esm.sh/@supabase/supabase-js@2.49.1";

const cors = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, apikey, content-type",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};
const reply = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { ...cors, "Content-Type": "application/json" } });

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: cors });
  if (req.method !== "POST") return reply({ error: "POST only" }, 405);
  try {
    const auth = req.headers.get("Authorization") || "";
    if (!auth.startsWith("Bearer ")) return reply({ error: "Sign in required" }, 401);
    const url = Deno.env.get("SUPABASE_URL") || "";
    const anon = Deno.env.get("SUPABASE_ANON_KEY") || "";
    const service = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") || "";
    if (!url || !anon || !service) return reply({ error: "Admin service is not configured" }, 503);

    const userClient = createClient(url, anon, { global: { headers: { Authorization: auth } } });
    const { data: userData, error: authError } = await userClient.auth.getUser();
    const user = userData?.user;
    if (authError || !user) return reply({ error: "Your sign-in has expired. Sign in again." }, 401);

    const admin = createClient(url, service);
    const { data: allowed, error: allowError } = await admin
      .from("twiga_platform_admins").select("user_id").eq("user_id", user.id).maybeSingle();
    if (allowError || !allowed) return reply({ error: "Platform administrator access required" }, 403);

    const payload = await req.json().catch(() => ({}));
    const action = String(payload.action || "overview");
    if (action === "whoami") return reply({ isAdmin: true, email: user.email || null });

    if (action === "overview") {
      const { data: clientRows, error: clientError } = await admin
        .from("businesses")
        .select("id,name,business_type,status,paid_through,created_at,business_members(user_id,role)")
        .order("created_at", { ascending: false }).limit(100);
      if (clientError) throw clientError;

      const clients = await Promise.all((clientRows || []).map(async (b: any) => {
        const members = Array.isArray(b.business_members) ? b.business_members : [];
        const owners = members.filter((m: any) => ["owner", "admin"].includes(m.role));
        const account = owners[0]?.user_id
          ? await admin.auth.admin.getUserById(owners[0].user_id).catch(() => ({ data: { user: null } }))
          : { data: { user: null } };
        return {
          id: b.id, name: b.name, businessType: b.business_type,
          status: b.status, paidThrough: b.paid_through, createdAt: b.created_at,
          memberCount: members.length, ownerEmail: account.data?.user?.email || null,
        };
      }));

      const { data: payments, error: payError } = await admin
        .from("twiga_platform_payments")
        .select("id,business_id,amount,currency,method,provider_reference,notes,paid_at,period_start,period_end,businesses(name)")
        .order("paid_at", { ascending: false }).limit(100);
      if (payError) throw payError;

      const { data: tickets, error: ticketError } = await admin
        .from("twiga_support_tickets")
        .select("id,business_id,channel,subject,message,contact,status,created_at,updated_at")
        .order("created_at", { ascending: false }).limit(100);
      if (ticketError) throw ticketError;

      const paidTotal = (payments || []).reduce((n: number, p: any) => n + Number(p.amount || 0), 0);
      return reply({ isAdmin: true, clients, payments: payments || [], tickets: tickets || [],
        metrics: { clientCount: clients.length, paymentCount: (payments || []).length,
          paidTotalKes: Number(paidTotal.toFixed(2)),
          openTickets: (tickets || []).filter((t: any) => t.status !== "resolved").length } });
    }

    if (action === "record_payment") {
      const businessId = String(payload.businessId || "");
      const amount = Number(payload.amount);
      const method = String(payload.method || "").toLowerCase();
      const reference = String(payload.reference || "").trim().slice(0, 100);
      const notes = String(payload.notes || "").trim().slice(0, 1000);
      const periodStart = payload.periodStart ? String(payload.periodStart) : null;
      const periodEnd = payload.periodEnd ? String(payload.periodEnd) : null;
      if (!businessId || !Number.isFinite(amount) || amount <= 0 || amount > 100000000)
        return reply({ error: "Choose a client and enter a valid amount." }, 400);
      if (!["mpesa", "bank", "cash", "card", "other"].includes(method))
        return reply({ error: "Unsupported payment method." }, 400);
      if (periodStart && !/^\d{4}-\d{2}-\d{2}$/.test(periodStart)) return reply({ error: "Invalid period start date." }, 400);
      if (periodEnd && !/^\d{4}-\d{2}-\d{2}$/.test(periodEnd)) return reply({ error: "Invalid period end date." }, 400);
      const { data: business, error: businessError } = await admin.from("businesses")
        .select("id").eq("id", businessId).maybeSingle();
      if (businessError || !business) return reply({ error: "Client not found." }, 404);
      const { data, error } = await admin.from("twiga_platform_payments").insert({
        business_id: businessId, amount, currency: "KES", method,
        provider_reference: reference || null, notes: notes || null,
        period_start: periodStart, period_end: periodEnd, recorded_by: user.id,
      }).select("id,business_id,amount,currency,method,provider_reference,paid_at").single();
      if (error) {
        if (error.code === "23505") return reply({ error: "That payment reference has already been recorded." }, 409);
        throw error;
      }
      return reply({ saved: true, payment: data });
    }

    if (action === "set_access") {
      const businessId = String(payload.businessId || "");
      const status = String(payload.status || "").toUpperCase();
      const paidThrough = payload.paidThrough ? String(payload.paidThrough) : null;
      if (!businessId || !["ACTIVE", "SUSPENDED", "TRIAL"].includes(status))
        return reply({ error: "Choose a client and a valid access status." }, 400);
      if (paidThrough && !/^\d{4}-\d{2}-\d{2}$/.test(paidThrough))
        return reply({ error: "Invalid paid-through date." }, 400);
      const update: Record<string, unknown> = { status };
      if (paidThrough) update.paid_through = paidThrough;
      const { data, error } = await admin.from("businesses").update(update)
        .eq("id", businessId).select("id,name,status,paid_through").single();
      if (error) throw error;
      return reply({ saved: true, client: data });
    }

    if (action === "update_ticket") {
      const ticketId = String(payload.ticketId || "");
      const status = String(payload.status || "");
      if (!ticketId || !["open", "in_progress", "resolved"].includes(status))
        return reply({ error: "Invalid support ticket update." }, 400);
      const { data, error } = await admin.from("twiga_support_tickets")
        .update({ status, updated_at: new Date().toISOString() }).eq("id", ticketId)
        .select("id,status").single();
      if (error) throw error;
      return reply({ saved: true, ticket: data });
    }

    return reply({ error: "Unknown admin action." }, 400);
  } catch (error) {
    console.error("twiga-platform-admin", error);
    return reply({ error: "The admin request could not be completed." }, 500);
  }
});
