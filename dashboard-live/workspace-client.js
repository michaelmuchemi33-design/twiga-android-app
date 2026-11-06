import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

const config = window.TWIGA_CONFIG || {};
if (!config.supabaseUrl || !config.supabaseKey) {
  throw new Error("TWIGA Supabase configuration is missing.");
}

export const sb = createClient(config.supabaseUrl, config.supabaseKey, {
  auth: {
    persistSession: true,
    autoRefreshToken: true,
    detectSessionInUrl: true,
    flowType: "pkce"
  }
});

export function esc(value) {
  return String(value ?? "").replace(/[&<>"']/g, ch => ({
    "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;"
  })[ch]);
}

export function fmtDate(value) {
  if (!value) return "Not set";
  const d = new Date(value);
  return Number.isNaN(d.getTime()) ? "Not set" :
    d.toLocaleString("en-KE", { dateStyle: "medium", timeStyle: "short", timeZone: "Africa/Nairobi" });
}

export async function getWorkspace() {
  const { data: auth, error: authError } = await sb.auth.getSession();
  if (authError) throw authError;
  const session = auth.session;
  if (!session) return { session: null, member: null, business: null };

  const { data: member, error } = await sb
    .from("business_members")
    .select("role,businesses(id,name,business_type,status,paid_through)")
    .limit(1)
    .maybeSingle();
  if (error) throw error;
  const business = Array.isArray(member?.businesses)
    ? member.businesses[0]
    : member?.businesses || null;
  return { session, member, business };
}

export function appUrl(hash = "overview") {
  return "/app#" + encodeURIComponent(hash);
}
