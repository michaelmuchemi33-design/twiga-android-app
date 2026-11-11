import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

type Conn = {
  business_id: string;
  connected_by: string;
  google_email: string | null;
  granted_scopes: string;
  access_token_ciphertext: string;
  refresh_token_ciphertext: string | null;
  access_expires_at: string | null;
  spreadsheet_id: string | null;
  spreadsheet_url: string | null;
  last_sync_at: string | null;
};

const env = (name: string) => Deno.env.get(name) ?? "";
const encoder = new TextEncoder();
const decoder = new TextDecoder();

function asArrayBuffer(bytes: Uint8Array): ArrayBuffer {
  const buffer = new ArrayBuffer(bytes.byteLength);
  new Uint8Array(buffer).set(bytes);
  return buffer;
}
const REQUIRED_SCOPES = [
  "https://www.googleapis.com/auth/calendar.events.owned",
  "https://www.googleapis.com/auth/contacts.readonly",
  "https://www.googleapis.com/auth/spreadsheets",
  "https://www.googleapis.com/auth/drive.file",
];

class HttpError extends Error {
  constructor(public status: number, message: string) { super(message); }
}

function b64(bytes: Uint8Array): string {
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary);
}
function unb64(value: string): Uint8Array {
  return Uint8Array.from(atob(value), c => c.charCodeAt(0));
}
let cryptoKey: CryptoKey | null = null;
async function getCryptoKey(): Promise<CryptoKey> {
  if (cryptoKey) return cryptoKey;
  const raw = unb64(env("GOOGLE_TOKEN_ENCRYPTION_KEY"));
  if (raw.length !== 32) throw new HttpError(500, "GOOGLE_TOKEN_ENCRYPTION_KEY must be a base64-encoded 32-byte key.");
  cryptoKey = await crypto.subtle.importKey("raw", asArrayBuffer(raw), { name: "AES-GCM" }, false, ["encrypt", "decrypt"]);
  return cryptoKey;
}
async function encrypt(value: string | null): Promise<string | null> {
  if (!value) return null;
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const cipher = await crypto.subtle.encrypt({ name: "AES-GCM", iv: asArrayBuffer(iv) }, await getCryptoKey(), asArrayBuffer(encoder.encode(value)));
  return "v1." + b64(iv) + "." + b64(new Uint8Array(cipher));
}
async function decrypt(value: string | null): Promise<string | null> {
  if (!value) return null;
  const [version, iv, cipher] = value.split(".");
  if (version !== "v1" || !iv || !cipher) throw new HttpError(500, "Stored Google token has an unsupported format.");
  const plain = await crypto.subtle.decrypt({ name: "AES-GCM", iv: asArrayBuffer(unb64(iv)) }, await getCryptoKey(), asArrayBuffer(unb64(cipher)));
  return decoder.decode(plain);
}

function corsFor(req: Request): Headers {
  const origin = req.headers.get("origin") ?? "";
  const allowed = (env("TWIGA_ALLOWED_ORIGINS") || "https://app.twiga.co.ke")
    .split(",").map(s => s.trim()).filter(Boolean);
  const headers = new Headers({
    "access-control-allow-methods": "POST, OPTIONS",
    "access-control-allow-headers": "authorization, x-client-info, apikey, content-type",
    "vary": "Origin",
    "content-type": "application/json",
  });
  if (!origin || allowed.includes(origin)) headers.set("access-control-allow-origin", origin || allowed[0]);
  return headers;
}
function reply(req: Request, body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: corsFor(req) });
}
async function readJson(res: Response): Promise<any> {
  const text = await res.text();
  let body: any = {};
  try { body = text ? JSON.parse(text) : {}; } catch { body = { message: text.slice(0, 500) }; }
  if (!res.ok) {
    const message = body?.error?.message || body?.error_description || body?.message || "Google API request failed.";
    throw new HttpError(res.status === 401 ? 401 : res.status === 409 ? 409 : 502, String(message));
  }
  return body;
}
async function googleJson(token: string, url: string, init: RequestInit = {}): Promise<any> {
  const headers = new Headers(init.headers || {});
  headers.set("authorization", "Bearer " + token);
  if (init.body && !headers.has("content-type")) headers.set("content-type", "application/json");
  return readJson(await fetch(url, { ...init, headers }));
}

function getAdmin() {
  const url = env("SUPABASE_URL"), key = env("SUPABASE_SERVICE_ROLE_KEY");
  if (!url || !key) throw new HttpError(500, "Supabase server credentials are not configured.");
  return createClient(url, key, { auth: { persistSession: false, autoRefreshToken: false } });
}
async function authenticate(req: Request, businessId: string) {
  const authorization = req.headers.get("authorization") || "";
  if (!authorization.startsWith("Bearer ")) throw new HttpError(401, "Sign in to TWIGA again.");
  const anon = env("SUPABASE_ANON_KEY") || env("SUPABASE_PUBLISHABLE_KEY");
  if (!anon) throw new HttpError(500, "Supabase publishable key is not configured for this function.");
  const accessToken = authorization.slice("Bearer ".length).trim();
  if (!accessToken) throw new HttpError(401, "Sign in to TWIGA again.");
  const admin = getAdmin();
  // Validate the user's session with Supabase Auth. The user JWT is supplied
  // explicitly; the service-role client is used only after this validation.
  const { data, error } = await admin.auth.getUser(accessToken);
  if (error || !data.user) throw new HttpError(401, "Your TWIGA session has expired. Sign in again.");
  // The privileged lookup is constrained to both the authenticated user ID and
  // requested business ID, so another business cannot be accessed by guessing an ID.
  const { data: member, error: memberError } = await admin
    .from("business_members")
    .select("business_id,role")
    .eq("business_id", businessId)
    .eq("user_id", data.user.id)
    .maybeSingle();
  if (memberError || !member) throw new HttpError(403, "You do not have access to this TWIGA business.");
  return { user: data.user, role: String(member.role || "member").toLowerCase(), admin };
}
async function getConnection(admin: any, businessId: string): Promise<Conn | null> {
  const { data, error } = await admin.from("twiga_google_connections").select("*")
    .eq("business_id", businessId).maybeSingle();
  if (error) throw new HttpError(500, "Could not read the Google connection record.");
  return data as Conn | null;
}
async function saveConnection(admin: any, row: Partial<Conn> & { business_id: string }) {
  const { error } = await admin.from("twiga_google_connections").upsert(
    { ...row, updated_at: new Date().toISOString() }, { onConflict: "business_id" }
  );
  if (error) throw new HttpError(500, "Could not save the encrypted Google connection.");
}
async function tokenFor(admin: any, connection: Conn): Promise<string> {
  const access = await decrypt(connection.access_token_ciphertext);
  const refresh = await decrypt(connection.refresh_token_ciphertext);
  const expires = connection.access_expires_at ? new Date(connection.access_expires_at).getTime() : 0;
  if (access && expires > Date.now() + 90000) return access;
  if (!refresh) throw new HttpError(409, "Google access expired. Reconnect Google Workspace to continue.");
  const clientId = env("GOOGLE_CLIENT_ID"), clientSecret = env("GOOGLE_CLIENT_SECRET");
  if (!clientId || !clientSecret) throw new HttpError(500, "Add GOOGLE_CLIENT_ID and GOOGLE_CLIENT_SECRET to Supabase function secrets.");
  const form = new URLSearchParams({ client_id: clientId, client_secret: clientSecret, refresh_token: refresh, grant_type: "refresh_token" });
  const refreshed = await readJson(await fetch("https://oauth2.googleapis.com/token", {
    method: "POST", headers: { "content-type": "application/x-www-form-urlencoded" }, body: form,
  }));
  const next = String(refreshed.access_token || "");
  if (!next) throw new HttpError(502, "Google did not return a refreshed access token. Reconnect Google Workspace.");
  const expiresAt = new Date(Date.now() + Math.max(60, Number(refreshed.expires_in || 3600)) * 1000).toISOString();
  const { error } = await admin.from("twiga_google_connections").update({
    access_token_ciphertext: (await encrypt(next))!,
    access_expires_at: expiresAt,
    updated_at: new Date().toISOString(),
  }).eq("business_id", connection.business_id);
  if (error) throw new HttpError(500, "Could not update the refreshed Google token.");
  return next;
}
async function authenticateLine(req: Request, body: any) {
  const secret = req.headers.get("x-twiga-line-secret") || "";
  const aiNumber = String(body.aiNumber || "");
  if (!secret || !aiNumber) throw new HttpError(401, "AI line authorization is required.");
  const admin = getAdmin();
  const { data, error } = await admin.rpc("line_get_ai", { p_secret: secret, p_number: aiNumber });
  if (error || !data?.business_id) throw new HttpError(401, "AI line authorization failed.");
  const businessId = String(data.business_id);
  const connection = await getConnection(admin, businessId);
  if (!connection) return { admin, businessId, connection: null as Conn | null, token: "" };
  const token = await tokenFor(admin, connection);
  return { admin, businessId, connection, token };
}
async function connectGoogle(admin: any, userId: string, businessId: string, body: any) {
  let accessToken = String(body.accessToken || "");
  let exchangedRefreshToken = String(body.refreshToken || "");
  if (!accessToken && body.authorizationCode) {
    const clientId = env("GOOGLE_CLIENT_ID"), clientSecret = env("GOOGLE_CLIENT_SECRET");
    if (!clientId || !clientSecret) throw new HttpError(500, "Google OAuth client credentials are not configured.");
    const form = new URLSearchParams({
      code: String(body.authorizationCode),
      client_id: clientId,
      client_secret: clientSecret,
      grant_type: "authorization_code",
      redirect_uri: String(body.redirectUri || "postmessage"),
    });
    const exchange = await fetch("https://oauth2.googleapis.com/token", {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body: form,
    });
    const tokens = await exchange.json().catch(() => ({}));
    if (!exchange.ok || !tokens.access_token) throw new HttpError(400, String(tokens.error_description || tokens.error || "Google authorization code exchange failed."));
    accessToken = String(tokens.access_token);
    exchangedRefreshToken = String(tokens.refresh_token || exchangedRefreshToken);
  }
  if (!accessToken) throw new HttpError(400, "Google did not return an access token. Try Connect Google again.");
  const tokenResponse = await fetch("https://oauth2.googleapis.com/tokeninfo?access_token=" + encodeURIComponent(accessToken));
  const tokenInfo = await readJson(tokenResponse);
  const granted = new Set(String(tokenInfo.scope || "").split(" "));
  const hasCalendar = granted.has(REQUIRED_SCOPES[0]) || granted.has("https://www.googleapis.com/auth/calendar.events");
  const hasContacts = granted.has(REQUIRED_SCOPES[1]) || granted.has("https://www.googleapis.com/auth/contacts");
  const missing = REQUIRED_SCOPES.filter(scope => {
    if (scope === REQUIRED_SCOPES[0]) return !hasCalendar;
    if (scope === REQUIRED_SCOPES[1]) return !hasContacts;
    return !granted.has(scope);
  });
  if (missing.length) return { connected: false, missingScopes: missing, message: "Google did not grant every requested permission. Reconnect and approve the missing access." };
  const profile = await googleJson(accessToken, "https://www.googleapis.com/oauth2/v3/userinfo").catch(() => ({}));
  const old = await getConnection(admin, businessId);
  const refreshToken = exchangedRefreshToken || await decrypt(old?.refresh_token_ciphertext || null);
  const expiry = new Date(Date.now() + Math.max(60, Number(tokenInfo.expires_in || 3600)) * 1000).toISOString();
  await saveConnection(admin, {
    business_id: businessId,
    connected_by: userId,
    google_email: String(profile.email || body.googleEmail || "").slice(0, 254) || null,
    granted_scopes: Array.from(granted).join(" "),
    access_token_ciphertext: (await encrypt(accessToken))!,
    refresh_token_ciphertext: await encrypt(refreshToken),
    access_expires_at: expiry,
  });
  return {
    connected: true,
    googleEmail: String(profile.email || body.googleEmail || ""),
    scopes: Array.from(granted),
    refreshReady: !!refreshToken,
    expiresAt: expiry,
  };
}
async function ensureSheet(admin: any, connection: Conn, token: string) {
  let id = connection.spreadsheet_id || "";
  let url = connection.spreadsheet_url || "";
  if (!id) {
    const q = encodeURIComponent("name='TWIGA Business Records' and mimeType='application/vnd.google-apps.spreadsheet' and trashed=false");
    const found = await googleJson(token, "https://www.googleapis.com/drive/v3/files?q=" + q + "&pageSize=5&fields=files(id,name,webViewLink)");
    const file = found.files?.[0];
    if (file?.id) { id = file.id; url = file.webViewLink || "https://docs.google.com/spreadsheets/d/" + id + "/edit"; }
  }
  if (!id) {
    const created = await googleJson(token, "https://sheets.googleapis.com/v4/spreadsheets", {
      method: "POST", body: JSON.stringify({ properties: { title: "TWIGA Business Records" } }),
    });
    id = String(created.spreadsheetId || "");
    if (!id) throw new HttpError(502, "Google did not return a spreadsheet ID.");
    url = "https://docs.google.com/spreadsheets/d/" + id + "/edit";
    await googleJson(token, "https://sheets.googleapis.com/v4/spreadsheets/" + encodeURIComponent(id) +
      "/values/Sheet1!A1:J1?valueInputOption=RAW", {
      method: "PUT",
      body: JSON.stringify({ values: [["Exported at", "Order ID", "Customer", "Phone", "Items / service", "Total (KSh)", "Paid (KSh)", "Payment method", "Status", "Created at"]] }),
    });
  }
  await admin.from("twiga_google_connections").update({
    spreadsheet_id: id, spreadsheet_url: url, updated_at: new Date().toISOString(),
  }).eq("business_id", connection.business_id);
  return { spreadsheetId: id, spreadsheetUrl: url };
}

Deno.serve(async (req: Request) => {
  const headers = corsFor(req);
  if (req.method === "OPTIONS") return new Response("ok", { headers });
  if (!headers.get("access-control-allow-origin")) return reply(req, { error: "Origin not allowed." }, 403);
  if (req.method !== "POST") return reply(req, { error: "Use POST." }, 405);
  try {
    const body = await req.json();
    const action = String(body.action || "");
    if (action === "calendar.ai.list" || action === "calendar.ai.create") {
      const { connection, token } = await authenticateLine(req, body);
      if (!connection || !token) return reply(req, { connected: false, items: [] });
      if (action === "calendar.ai.list") {
        const start = String(body.timeMin || new Date().toISOString());
        const end = String(body.timeMax || new Date(Date.now() + 30 * 86400000).toISOString());
        const q = new URLSearchParams({ singleEvents: "true", orderBy: "startTime", maxResults: "100", timeMin: start, timeMax: end });
        const data = await googleJson(token, "https://www.googleapis.com/calendar/v3/calendars/primary/events?" + q);
        return reply(req, { connected: true, items: data.items || [], nextPageToken: data.nextPageToken || null });
      }
      const event = body.event;
      if (!event?.summary || !event?.start?.dateTime || !event?.end?.dateTime) {
        throw new HttpError(400, "Appointment title, start and end are required.");
      }
      const url = "https://www.googleapis.com/calendar/v3/calendars/primary/events";
      try {
        const data = await googleJson(token, url, { method: "POST", body: JSON.stringify(event) });
        return reply(req, { connected: true, event: data });
      } catch (error) {
        if (error instanceof HttpError && error.status === 409 && event.id) {
          const existing = await googleJson(token, url + "/" + encodeURIComponent(String(event.id)));
          return reply(req, { connected: true, event: existing, alreadyExists: true });
        }
        throw error;
      }
    }
    const businessId = String(body.businessId || "");
    if (!businessId || businessId.length > 100) throw new HttpError(400, "A valid TWIGA business is required.");
    const { user, role, admin } = await authenticate(req, businessId);
    if ((action === "connect" || action === "disconnect") && !["owner", "admin"].includes(role)) {
      throw new HttpError(403, "Only a TWIGA business owner or administrator can connect or disconnect Google Workspace.");
    }

    if (action === "oauth.config") return reply(req, { clientId: env("GOOGLE_CLIENT_ID"), scopes: REQUIRED_SCOPES });
    if (action === "connect") return reply(req, await connectGoogle(admin, user.id, businessId, body));
    if (action === "status") {
      const connection = await getConnection(admin, businessId);
      if (!connection) return reply(req, { connected: false, missingScopes: REQUIRED_SCOPES });
      return reply(req, {
        connected: true,
        googleEmail: connection.google_email,
        scopes: connection.granted_scopes.split(" ").filter(Boolean),
        refreshReady: !!connection.refresh_token_ciphertext,
        expiresAt: connection.access_expires_at,
        spreadsheetId: connection.spreadsheet_id,
        spreadsheetUrl: connection.spreadsheet_url,
      });
    }
    if (action === "disconnect") {
      const { error } = await admin.from("twiga_google_connections").delete().eq("business_id", businessId);
      if (error) throw new HttpError(500, "Could not disconnect Google Workspace.");
      return reply(req, { connected: false, disconnected: true });
    }

    const connection = await getConnection(admin, businessId);
    if (!connection) throw new HttpError(409, "Connect Google Workspace first.");
    const token = await tokenFor(admin, connection);

    if (action === "calendar.list") {
      const start = String(body.timeMin || new Date().toISOString());
      const end = String(body.timeMax || new Date(Date.now() + 30 * 86400000).toISOString());
      const q = new URLSearchParams({ singleEvents: "true", orderBy: "startTime", maxResults: "100", timeMin: start, timeMax: end });
      const data = await googleJson(token, "https://www.googleapis.com/calendar/v3/calendars/primary/events?" + q);
      return reply(req, { items: data.items || [], nextPageToken: data.nextPageToken || null });
    }
    if (action === "calendar.create") {
      const event = body.event;
      if (!event?.summary || !event?.start?.dateTime || !event?.end?.dateTime) throw new HttpError(400, "Appointment title, start and end are required.");
      const data = await googleJson(token, "https://www.googleapis.com/calendar/v3/calendars/primary/events", {
        method: "POST", body: JSON.stringify(event),
      });
      return reply(req, { event: data });
    }
    if (action === "calendar.update") {
      const eventId = String(body.eventId || "");
      const event = body.event;
      if (!eventId || !event?.summary || !event?.start?.dateTime || !event?.end?.dateTime) {
        throw new HttpError(400, "Appointment ID, title, start and end are required.");
      }
      const data = await googleJson(token, "https://www.googleapis.com/calendar/v3/calendars/primary/events/" + encodeURIComponent(eventId), {
        method: "PATCH", body: JSON.stringify(event),
      });
      return reply(req, { event: data });
    }
    if (action === "calendar.delete") {
      const eventId = String(body.eventId || "");
      if (!eventId) throw new HttpError(400, "Appointment ID is required.");
      await googleJson(token, "https://www.googleapis.com/calendar/v3/calendars/primary/events/" + encodeURIComponent(eventId), {
        method: "DELETE",
      });
      return reply(req, { deleted: true, eventId });
    }
    if (action === "appointments.sync") {
      const { data: business, error: businessError } = await admin.from("businesses")
        .select("name").eq("id", businessId).maybeSingle();
      if (businessError) throw new HttpError(500, "Could not read the TWIGA business name.");
      const { data: appointments, error: appointmentError } = await admin.from("twiga_appointments")
        .select("id,customer_name,phone,scheduled_at,notes,status,google_event_id")
        .eq("business_id", businessId).is("google_event_id", null)
        .order("scheduled_at", { ascending: true }).limit(100);
      if (appointmentError) throw new HttpError(500, "Could not load TWIGA appointments to sync.");
      let synced = 0;
      for (const appointment of appointments || []) {
        const start = new Date(appointment.scheduled_at);
        if (!Number.isFinite(start.getTime())) continue;
        const durationMatch = String(appointment.notes || "").match(/Requested duration minutes:\\s*(\\d+)/i);
        const durationMinutes = Math.min(180, Math.max(15, Number(durationMatch?.[1] || 30)));
        const end = new Date(start.getTime() + durationMinutes * 60000);
        const pendingRequest = String(appointment.status || "").toLowerCase() === "requested";
        const eventId = "a" + String(appointment.id).replace(/-/g, "").toLowerCase();
        const event = {
          id: eventId,
          summary: (pendingRequest ? "REQUEST — CONFIRM: " : "") +
            (business?.name ? String(business.name) + " — " : "") +
            String(appointment.customer_name || "TWIGA appointment"),
          description: [
            pendingRequest ? "STATUS: Pending request — contact customer to confirm before treating this as booked." :
              "STATUS: " + String(appointment.status || "scheduled"),
            appointment.phone ? "Phone: " + appointment.phone : "",
            appointment.notes || "",
            "TWIGA appointment " + appointment.id,
          ].filter(Boolean).join("\\n"),
          start: { dateTime: start.toISOString(), timeZone: "Africa/Nairobi" },
          end: { dateTime: end.toISOString(), timeZone: "Africa/Nairobi" },
        };
        const url = "https://www.googleapis.com/calendar/v3/calendars/primary/events";
        let created: any;
        try {
          created = await googleJson(token, url, { method: "POST", body: JSON.stringify(event) });
        } catch (error) {
          if (error instanceof HttpError && error.status === 409) {
            created = await googleJson(token, url + "/" + encodeURIComponent(eventId));
          } else {
            throw error;
          }
        }
        const { error: saveError } = await admin.from("twiga_appointments").update({
          google_event_id: created.id || eventId,
          google_event_url: created.htmlLink || null,
          google_synced_at: new Date().toISOString(),
        }).eq("id", appointment.id).eq("business_id", businessId);
        if (saveError) throw new HttpError(500, "A Google Calendar event was created but TWIGA could not save its sync reference. Retry to reconcile it.");
        synced += 1;
      }
      return reply(req, { synced, remaining: Math.max(0, (appointments || []).length - synced) });
    }
    if (action === "contacts.list") {
      const q = new URLSearchParams({ pageSize: String(Math.min(100, Math.max(1, Number(body.pageSize || 100)))), personFields: "names,emailAddresses,phoneNumbers" });
      if (body.pageToken) q.set("pageToken", String(body.pageToken));
      const data = await googleJson(token, "https://people.googleapis.com/v1/people/me/connections?" + q);
      return reply(req, { connections: data.connections || [], nextPageToken: data.nextPageToken || null });
    }
    if (action === "sheets.ensure") {
      const sheet = await ensureSheet(admin, connection, token);
      return reply(req, { ...sheet, ready: true });
    }
    if (action === "sheets.export-orders") {
      const { data: orders, error: orderError } = await admin.from("twiga_orders")
        .select("id,order_number,customer_name,customer_phone,total_amount,description,status,payment_status,payment_method,created_at,items")
        .eq("business_id", businessId).order("created_at", { ascending: false }).limit(1000);
      if (orderError) throw new HttpError(500, "Could not load TWIGA orders for Google Sheets export.");
      const sheet = await ensureSheet(admin, connection, token);
      const values: unknown[][] = [["Exported at", "Order ID", "Customer", "Phone", "Items / service", "Total (KSh)", "Paid (KSh)", "Payment method", "Status", "Created at"]];
      for (const o of orders || []) {
        const lines = Array.isArray(o.items)
          ? o.items.map((item: any) => [String(item.name || item.product_name || "Item"), String(item.quantity ?? 1)].join(" x")).join(", ")
          : "";
        values.push([
          new Date().toISOString(),
          String(o.order_number || o.id || "").slice(0, 80),
          String(o.customer_name || "").slice(0, 120),
          String(o.customer_phone || "").slice(0, 40),
          String(lines || o.description || "").slice(0, 500),
          Number(o.total_amount || 0),
          String(o.payment_status || "").toLowerCase() === "paid" ? Number(o.total_amount || 0) : 0,
          String(o.payment_method || "").slice(0, 40),
          String(o.status || "").slice(0, 40),
          String(o.created_at || ""),
        ]);
      }
      await googleJson(token, "https://sheets.googleapis.com/v4/spreadsheets/" + encodeURIComponent(sheet.spreadsheetId) +
        "/values:batchClear", { method: "POST", body: JSON.stringify({ ranges: ["Sheet1!A1:J1001"] }) });
      await googleJson(token, "https://sheets.googleapis.com/v4/spreadsheets/" + encodeURIComponent(sheet.spreadsheetId) +
        "/values/Sheet1!A1:J" + values.length + "?valueInputOption=RAW", {
        method: "PUT", body: JSON.stringify({ values }),
      });
      const { error: syncError } = await admin.from("twiga_google_connections")
        .update({ last_sync_at: new Date().toISOString() }).eq("business_id", businessId);
      if (syncError) throw new HttpError(500, "The sheet was exported but the sync timestamp could not be saved.");
      return reply(req, { exported: (orders || []).length, spreadsheetUrl: sheet.spreadsheetUrl });
    }
    if (action === "drive.export-calls") {
      const { data: settings, error: settingsError } = await admin.from("twiga_business_settings")
        .select("call_recording_enabled").eq("business_id", businessId).maybeSingle();
      if (settingsError) throw new HttpError(500, "Could not read the business call-recording setting.");
      const includeTranscript = settings?.call_recording_enabled === true;
      const { data: calls, error: callsError } = await admin.from("twiga_call_records")
        .select("id,channel,direction,from_number,to_number,status,started_at,ended_at,duration_seconds,summary,transcript")
        .eq("business_id", businessId).order("created_at", { ascending: false }).limit(500);
      if (callsError) throw new HttpError(500, "Could not load TWIGA call records for Drive export.");
      const csvCell = (value: unknown) => '"' + String(value ?? "").replace(/"/g, '""') + '"';
      const headers = [
        "Call ID", "Channel", "Direction", "From", "To", "Status", "Started", "Ended", "Duration seconds", "Summary",
        ...(includeTranscript ? ["Transcript"] : []),
      ];
      const rows: unknown[][] = [headers];
      for (const call of calls || []) {
        rows.push([
          call.id, call.channel, call.direction, call.from_number, call.to_number, call.status,
          call.started_at, call.ended_at, call.duration_seconds, call.summary,
          ...(includeTranscript ? [call.transcript] : []),
        ]);
      }
      const csv = rows.map(row => row.map(csvCell).join(",")).join("\\r\\n");
      const boundary = "twiga_" + crypto.randomUUID().replace(/-/g, "");
      const stamp = new Date().toISOString().replace(/[:.]/g, "-");
      const metadata = { name: "TWIGA-call-records-" + stamp + ".csv", mimeType: "text/csv" };
      const body = new Blob([
        "--" + boundary + "\\r\\nContent-Type: application/json; charset=UTF-8\\r\\n\\r\\n",
        JSON.stringify(metadata),
        "\\r\\n--" + boundary + "\\r\\nContent-Type: text/csv; charset=UTF-8\\r\\n\\r\\n",
        csv,
        "\\r\\n--" + boundary + "--",
      ]);
      const uploaded = await readJson(await fetch(
        "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id,name,mimeType,webViewLink",
        { method: "POST", headers: { authorization: "Bearer " + token, "content-type": "multipart/related; boundary=" + boundary }, body },
      ));
      const { error: syncError } = await admin.from("twiga_google_connections")
        .update({ last_sync_at: new Date().toISOString() }).eq("business_id", businessId);
      if (syncError) throw new HttpError(500, "The call export was uploaded but TWIGA could not save the sync timestamp.");
      return reply(req, {
        exported: calls?.length || 0, file: uploaded, transcriptIncluded: includeTranscript,
        note: includeTranscript ? "Transcript export was included because call-recording is enabled." :
          "Only call metadata and summaries were exported because call transcript export is disabled in business privacy settings.",
      });
    }
    if (action === "drive.status") {
      const sheet = await ensureSheet(admin, connection, token);
      const file = await googleJson(token, "https://www.googleapis.com/drive/v3/files/" + encodeURIComponent(sheet.spreadsheetId) +
        "?fields=id,name,mimeType,webViewLink,modifiedTime");
      return reply(req, { file, spreadsheetUrl: sheet.spreadsheetUrl });
    }
    if (action === "drive.list") {
      const q = encodeURIComponent("trashed=false");
      const data = await googleJson(token, "https://www.googleapis.com/drive/v3/files?q=" + q +
        "&pageSize=50&orderBy=modifiedTime desc&fields=files(id,name,mimeType,webViewLink,modifiedTime)");
      return reply(req, { files: data.files || [] });
    }
    throw new HttpError(404, "Unknown Google Workspace action.");
  } catch (error) {
    const status = error instanceof HttpError ? error.status : 500;
    const message = error instanceof Error ? error.message : "Google Workspace request failed.";
    return reply(req, { error: message }, status);
  }
});
