
const QUOTA = 524288000;
const cors = {'Access-Control-Allow-Origin': '*', 'Access-Control-Allow-Methods': 'POST, GET, OPTIONS', 'Access-Control-Allow-Headers': 'Content-Type, Authorization', 'Access-Control-Max-Age': '86400'};
const json = (o, s) => new Response(JSON.stringify(o), {status: s || 200, headers: Object.assign({'Content-Type': 'application/json'}, cors)});
const cache = new Map();
const today = () => new Date(Date.now() + 3 * 3600 * 1000).toISOString().slice(0, 10);
const now = () => new Date().toISOString();
const txt = (v, n) => String(v == null ? '' : v).trim().slice(0, n || 200);
const num = (v, label, min) => { const n = Number(v); if (!isFinite(n) || n < (min == null ? 0 : min)) throw new Error('Invalid ' + label); return Math.round(n * 100) / 100; };
const rid = p => p + '-' + today().slice(2).replace(/-/g, '') + '-' + Math.random().toString(16).slice(2, 7).toUpperCase();
const size = (...s) => 120 + s.reduce((a, x) => a + String(x == null ? '' : x).length, 0);

export default {
  async fetch(req, env) {
    if (req.method === 'OPTIONS') return new Response(null, {status: 204, headers: cors});
    if (req.method === 'POST' && new URL(req.url).pathname === '/vapi') return vapiHook(req, env);
    if (req.method === 'GET') return json({ok: true, app: 'twiga-api', features: ['vapi-tools', 'order-create', 'appointment-booking', 'call-report-logging']});
    try {
      const b = await req.json();
      const ctx = await authorize(env, b.token, b.business_id);
      const fn = ACTIONS[b.action];
      if (!fn) throw new Error('Unknown action');
      return json(await fn(env, ctx, b.data || {}, txt(b.txn, 100)));
    } catch (e) {
      return json({ok: false, error: String(e.message || e)});
    }
  }
};

async function authorize(env, token, biz) {
  if (!token || !/^[0-9a-f-]{36}$/i.test(String(biz || ''))) throw new Error('LOGIN_REQUIRED');
  const k = token.slice(-24) + biz, hit = cache.get(k);
  if (hit && hit.exp > Date.now()) return hit.ctx;
  const h = {apikey: env.SUPABASE_KEY, Authorization: 'Bearer ' + token};
  const u = await fetch(env.SUPABASE_URL + '/auth/v1/user', {headers: h});
  if (!u.ok) throw new Error('LOGIN_REQUIRED');
  const user = await u.json();
  const m = await fetch(env.SUPABASE_URL + '/rest/v1/business_members?select=role,businesses(id,name,business_type,status,paid_through)&business_id=eq.' + biz, {headers: h});
  const mem = m.ok ? await m.json() : [];
  if (!user.id || !Array.isArray(mem) || !mem.length || !mem[0].businesses) throw new Error('NO_ACCESS');
  const b = mem[0].businesses;
  if (b.status === 'INACTIVE' || b.status === 'SUSPENDED' || String(b.paid_through) < today()) throw new Error('INACTIVE|Your TWIGA subscription is not active. Please renew to restore access.');
  const ctx = {userId: user.id, email: user.email, role: mem[0].role, biz: b};
  cache.set(k, {ctx: ctx, exp: Date.now() + 300000});
  return ctx;
}

async function used(env, biz) {
  const r = await env.DB.prepare('SELECT bytes_used FROM usage WHERE business_id = ?').bind(biz).first();
  return r ? r.bytes_used : 0;
}
async function room(env, biz, add) {
  if ((await used(env, biz)) + add > QUOTA) throw new Error('Your 500 MB workspace is full. Remove old data or add storage.');
}
const charge = (env, biz, n) => env.DB.prepare('INSERT INTO usage (business_id, bytes_used) VALUES (?, ?) ON CONFLICT(business_id) DO UPDATE SET bytes_used = bytes_used + excluded.bytes_used').bind(biz, n);

async function once(env, biz, txn) {
  if (!txn) return null;
  const r = await env.DB.prepare('SELECT response_json FROM sync WHERE business_id = ? AND txn_id = ?').bind(biz, txn).first();
  return r ? JSON.parse(r.response_json) : null;
}
const remember = (env, biz, txn, out) => env.DB.prepare('INSERT OR IGNORE INTO sync (business_id, txn_id, response_json, created_at) VALUES (?, ?, ?, ?)').bind(biz, txn, JSON.stringify(out), now());

const ACTIONS = {
  init: async (env, ctx) => {
    const items = await env.DB.prepare('SELECT item_id AS ItemId, name AS Name, kind AS Kind, category AS Category, price AS Price, cost AS Cost, stock AS Stock, track_stock AS TrackStock FROM items WHERE business_id = ? AND active = 1 ORDER BY name').bind(ctx.biz.id).all();
    return {ok: true, business: ctx.biz, role: ctx.role, items: items.results, usage: {used: await used(env, ctx.biz.id), quota: QUOTA}};
  },
  usage: async (env, ctx) => ({ok: true, used: await used(env, ctx.biz.id), quota: QUOTA}),

  listOrders: async (env, ctx, d) => {
    const lim = Math.min(Number(d.limit) || 100, 300);
    const q = d.status
      ? env.DB.prepare('SELECT order_id AS OrderId, created_at AS CreatedAt, date AS Date, customer AS Customer, phone AS Phone, type AS Type, status AS Status, total AS Total, paid AS Paid, method AS Method, notes AS Notes FROM orders WHERE business_id = ? AND status = ? ORDER BY created_at DESC LIMIT ?').bind(ctx.biz.id, d.status, lim)
      : env.DB.prepare('SELECT order_id AS OrderId, created_at AS CreatedAt, date AS Date, customer AS Customer, phone AS Phone, type AS Type, status AS Status, total AS Total, paid AS Paid, method AS Method, notes AS Notes FROM orders WHERE business_id = ? ORDER BY created_at DESC LIMIT ?').bind(ctx.biz.id, lim);
    return {ok: true, orders: (await q.all()).results};
  },

  createOrder: async (env, ctx, d, txn) => {
    const biz = ctx.biz.id, prev = await once(env, biz, txn);
    if (prev) return prev;
    const items = (d.items || []).map(it => {
      const qty = num(it.qty || 1, 'quantity', 0.01), price = num(it.unitPrice, 'price', 0);
      return {itemId: txt(it.itemId, 40), name: txt(it.name, 120), qty: qty, price: price, total: Math.round(qty * price * 100) / 100};
    }).filter(it => it.name);
    if (!items.length) throw new Error('Add at least one item.');
    for (const it of items) {
      if (!it.itemId) continue;
      const c = await env.DB.prepare('SELECT stock, track_stock FROM items WHERE business_id = ? AND item_id = ?').bind(biz, it.itemId).first();
      if (c && c.track_stock === 1 && c.stock < it.qty) throw new Error('Not enough stock for "' + it.name + '".');
    }
    const type = ['Sale', 'Appointment', 'Service', 'Quote'].indexOf(d.type) >= 0 ? d.type : 'Sale';
    const total = Math.round(items.reduce((s, i) => s + i.total, 0) * 100) / 100;
    const orderId = rid('ORD'), method = txt(d.method || 'Pay later', 30);
    const paid = d.paid === true && method !== 'M-Pesa STK' ? total : 0;
    const status = paid >= total ? 'Paid' : (type === 'Quote' ? 'Quote' : 'New');
    const customer = txt(d.customer, 120) || 'Walk-in', t = now();
    await room(env, biz, size(customer, d.notes) + items.length * 160);
    const out = {ok: true, orderId: orderId, total: total, status: status};
    const st = [env.DB.prepare('INSERT INTO orders (business_id, order_id, created_at, date, customer, phone, type, status, total, paid, method, notes, staff, branch, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)').bind(biz, orderId, t, txt(d.date, 20) || today(), customer, txt(d.phone, 30), type, status, total, paid, method, txt(d.notes, 300), txt(d.staff, 80), txt(d.branch, 80), t)];
    items.forEach((it, i) => {
      st.push(env.DB.prepare('INSERT INTO order_items (business_id, order_id, line, item_id, name, qty, unit_price, line_total) VALUES (?,?,?,?,?,?,?,?)').bind(biz, orderId, i + 1, it.itemId, it.name, it.qty, it.price, it.total));
      if (it.itemId) st.push(env.DB.prepare('UPDATE items SET stock = stock - ?, updated_at = ? WHERE business_id = ? AND item_id = ? AND track_stock = 1 AND stock >= ?').bind(it.qty, t, biz, it.itemId, it.qty));
    });
    st.push(charge(env, biz, size(customer, d.notes) + items.length * 160));
    if (txn) st.push(remember(env, biz, txn, out));
    await env.DB.batch(st);
    return out;
  },

  updateOrder: async (env, ctx, d, txn) => {
    const biz = ctx.biz.id, o = await env.DB.prepare('SELECT total, method FROM orders WHERE business_id = ? AND order_id = ?').bind(biz, txt(d.orderId, 40)).first();
    if (!o) throw new Error('Order not found.');
    const ok = ['New', 'Confirmed', 'In progress', 'Completed', 'Paid', 'Cancelled', 'Quote'];
    if (d.status && ok.indexOf(d.status) < 0) throw new Error('Invalid status.');
    const st = [];
    if (d.status) st.push(env.DB.prepare('UPDATE orders SET status = ?, updated_at = ? WHERE business_id = ? AND order_id = ?').bind(d.status, now(), biz, d.orderId));
    if (d.paid === true) st.push(env.DB.prepare('UPDATE orders SET paid = total, method = ?, status = CASE WHEN ? IS NULL THEN \'Paid\' ELSE status END, updated_at = ? WHERE business_id = ? AND order_id = ?').bind(txt(d.method || o.method, 30), d.status || null, now(), biz, d.orderId));
    if (st.length) await env.DB.batch(st);
    return {ok: true};
  },

  listItems: async (env, ctx) => ({ok: true, items: (await env.DB.prepare('SELECT item_id AS ItemId, name AS Name, kind AS Kind, category AS Category, price AS Price, cost AS Cost, stock AS Stock, track_stock AS TrackStock, active AS Active FROM items WHERE business_id = ? ORDER BY name').bind(ctx.biz.id).all()).results}),

  saveItem: async (env, ctx, d) => {
    if (ctx.role === 'staff') throw new Error('Only owners and managers can edit the catalogue.');
    const biz = ctx.biz.id, name = txt(d.name, 120);
    if (!name) throw new Error('Item name needed.');
    const id = txt(d.itemId, 40) || rid('ITM'), kind = d.kind === 'product' ? 'product' : 'service';
    await room(env, biz, size(name, d.category));
    await env.DB.batch([
      env.DB.prepare('INSERT INTO items (business_id, item_id, name, kind, category, price, cost, stock, track_stock, active, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(business_id, item_id) DO UPDATE SET name=excluded.name, kind=excluded.kind, category=excluded.category, price=excluded.price, cost=excluded.cost, stock=excluded.stock, track_stock=excluded.track_stock, active=excluded.active, updated_at=excluded.updated_at').bind(biz, id, name, kind, txt(d.category, 60), num(d.price, 'price'), num(d.cost || 0, 'cost'), num(d.stock || 0, 'stock'), d.trackStock === true ? 1 : 0, d.active === false ? 0 : 1, now()),
      charge(env, biz, size(name, d.category))
    ]);
    return {ok: true, itemId: id};
  },

  addExpense: async (env, ctx, d) => {
    const biz = ctx.biz.id, n = size(d.category, d.notes);
    await room(env, biz, n);
    await env.DB.batch([
      env.DB.prepare('INSERT INTO expenses (business_id, ref, date, category, amount, method, notes, created_at) VALUES (?,?,?,?,?,?,?,?)').bind(biz, rid('EXP'), txt(d.date, 20) || today(), txt(d.category, 60), num(d.amount, 'amount', 0.01), txt(d.method || 'Cash', 30), txt(d.notes, 300), now()),
      charge(env, biz, n)
    ]);
    return {ok: true};
  },

  summary: async (env, ctx) => {
    const biz = ctx.biz.id, t = today();
    const day = await env.DB.prepare("SELECT COUNT(*) AS n, COALESCE(SUM(paid),0) AS rev FROM orders WHERE business_id = ? AND date = ? AND status NOT IN ('Cancelled','Quote')").bind(biz, t).first();
    const unpaid = await env.DB.prepare("SELECT COUNT(*) AS n FROM orders WHERE business_id = ? AND paid < total AND status NOT IN ('Cancelled','Quote')").bind(biz).first();
    const exp = await env.DB.prepare('SELECT COALESCE(SUM(amount),0) AS s FROM expenses WHERE business_id = ? AND date = ?').bind(biz, t).first();
    const week = await env.DB.prepare("SELECT date, COALESCE(SUM(paid),0) AS rev FROM orders WHERE business_id = ? AND date >= date(?, '-6 days') AND status NOT IN ('Cancelled','Quote') GROUP BY date").bind(biz, t).all();
    const last7 = {}; for (let i = 6; i >= 0; i--) last7[new Date(Date.now() + 3 * 3600 * 1000 - i * 864e5).toISOString().slice(0, 10)] = 0;
    week.results.forEach(r => { if (r.date in last7) last7[r.date] = r.rev; });
    return {ok: true, ordersToday: day.n, revenueToday: day.rev, unpaid: unpaid.n, expensesToday: exp.s, last7: last7, used: await used(env, biz), quota: QUOTA};
  },

  saveContacts: async (env, ctx, d) => {
    const biz = ctx.biz.id, list = (d.contacts || []).slice(0, 500).map(c => ({name: txt(c.name, 120), phone: txt(c.phone, 30).replace(/[^0-9+]/g, ''), email: txt(c.email, 120)})).filter(c => c.phone.length >= 7);
    if (!list.length) return {ok: true, saved: 0};
    const n = list.reduce((a, c) => a + size(c.name, c.email), 0);
    await room(env, biz, n);
    const t = now();
    await env.DB.batch(list.map(c => env.DB.prepare("INSERT INTO contacts (business_id, phone, name, email, source, updated_at) VALUES (?,?,?,?,'phone',?) ON CONFLICT(business_id, phone) DO UPDATE SET name=excluded.name, email=excluded.email, updated_at=excluded.updated_at").bind(biz, c.phone, c.name, c.email, t)).concat([charge(env, biz, n)]));
    return {ok: true, saved: list.length};
  },
  listContacts: async (env, ctx, d) => {
    const like = '%' + txt(d.q, 40) + '%';
    return {ok: true, contacts: (await env.DB.prepare('SELECT phone AS Phone, name AS Name, email AS Email FROM contacts WHERE business_id = ? AND (name LIKE ? OR phone LIKE ?) ORDER BY name LIMIT ?').bind(ctx.biz.id, like, like, Math.min(Number(d.limit) || 100, 500)).all()).results};
  },

  saveEvents: async (env, ctx, d) => {
    const biz = ctx.biz.id, list = (d.events || []).slice(0, 300).map(e => ({id: txt(e.id, 80), title: txt(e.title, 160), start: txt(e.start, 40), end: txt(e.end, 40), notes: txt(e.notes, 300)})).filter(e => e.id && e.start);
    if (!list.length) return {ok: true, saved: 0};
    const n = list.reduce((a, e) => a + size(e.title, e.notes), 0);
    await room(env, biz, n);
    await env.DB.batch(list.map(e => env.DB.prepare("INSERT INTO events (business_id, event_id, title, start_at, end_at, notes, source, updated_at) VALUES (?,?,?,?,?,?,'phone',?) ON CONFLICT(business_id, event_id) DO UPDATE SET title=excluded.title, start_at=excluded.start_at, end_at=excluded.end_at, notes=excluded.notes, updated_at=excluded.updated_at").bind(biz, e.id, e.title, e.start, e.end, e.notes, now())).concat([charge(env, biz, n)]));
    return {ok: true, saved: list.length};
  },
  listEvents: async (env, ctx, d) => ({ok: true, events: (await env.DB.prepare('SELECT event_id AS Id, title AS Title, start_at AS Start, end_at AS End, notes AS Notes FROM events WHERE business_id = ? AND start_at >= ? ORDER BY start_at LIMIT 300').bind(ctx.biz.id, txt(d.from, 40) || today()).all()).results}),

  stkStart: async () => { throw new Error('M-Pesa payments are not switched on yet.'); },
  stkCheck: async () => { throw new Error('M-Pesa payments are not switched on yet.'); }
};



function twigaTools() {
  return [
    {type: 'function', function: {name: 'list_products', description: 'List the active products and services with their current prices and stock. Always call this before quoting a price or stock level.', parameters: {type: 'object', properties: {}, additionalProperties: false}}},
    {type: 'function', function: {name: 'create_order', description: 'Save a customer sale/order after the customer has confirmed the items, quantities, name and phone. The order is recorded as unpaid for staff to confirm payment.', parameters: {type: 'object', properties: {customerName: {type: 'string', description: 'Customer full name'}, customerPhone: {type: 'string', description: 'Customer phone number'}, items: {type: 'array', minItems: 1, items: {type: 'object', properties: {itemId: {type: 'string'}, quantity: {type: 'number', minimum: 0.01}}, required: ['itemId', 'quantity'], additionalProperties: false}}, notes: {type: 'string'}}, required: ['customerName', 'customerPhone', 'items'], additionalProperties: false}}},
    {type: 'function', function: {name: 'check_appointment_availability', description: 'Check whether a date and time slot is free in the business calendar before offering or confirming an appointment.', parameters: {type: 'object', properties: {date: {type: 'string', description: 'Appointment date YYYY-MM-DD in Africa/Nairobi'}, time: {type: 'string', description: 'Start time HH:MM in 24-hour Africa/Nairobi time'}, durationMinutes: {type: 'number', minimum: 15, maximum: 180}}, required: ['date', 'time'], additionalProperties: false}}},
    {type: 'function', function: {name: 'book_appointment', description: 'Create a confirmed appointment in the business order ledger and calendar after the customer confirms an available time.', parameters: {type: 'object', properties: {customerName: {type: 'string'}, customerPhone: {type: 'string'}, serviceItemId: {type: 'string', description: 'ItemId of an active service from list_products'}, date: {type: 'string', description: 'YYYY-MM-DD in Africa/Nairobi'}, time: {type: 'string', description: 'HH:MM in 24-hour Africa/Nairobi time'}, durationMinutes: {type: 'number', minimum: 15, maximum: 180}, notes: {type: 'string'}}, required: ['customerName', 'customerPhone', 'serviceItemId', 'date', 'time'], additionalProperties: false}}}
  ];
}

function safeIso(value) {
  if (!value) return '';
  const d = new Date(value);
  return Number.isFinite(d.getTime()) ? d.toISOString() : '';
}
function safeToolKey(value) {
  return String(value || 'call').replace(/[^a-zA-Z0-9_-]/g, '').slice(-28) || 'call';
}
async function getAiLineConfig(env, number) {
  if (!number) throw new Error('AI phone line number was not included in the call.');
  const r = await fetch(env.SUPABASE_URL + '/rest/v1/rpc/line_get_ai', {
    method: 'POST',
    headers: {apikey: env.SUPABASE_KEY, 'Content-Type': 'application/json'},
    body: JSON.stringify({p_secret: env.LINE_SECRET, p_number: number})
  });
  const d = r.ok ? await r.json() : null;
  if (!d || !d.business || !d.business_id) throw new Error('This TWIGA AI line is not available right now.');
  return d;
}
async function aiListProducts(env, ctx) {
  const r = await ACTIONS.listItems(env, ctx, {});
  const items = (r.items || []).filter(x => Number(x.Active) === 1 || x.Active === true).map(x => ({
    itemId: String(x.ItemId), name: x.Name, kind: x.Kind,
    price: Number(x.Price || 0), stock: Number(x.Stock || 0),
    trackStock: Number(x.TrackStock) === 1
  }));
  return {items: items};
}
async function aiSaveContact(env, ctx, name, phone) {
  if (!phone) return;
  try {
    await ACTIONS.saveContacts(env, ctx, {contacts: [{name: name, phone: phone, email: ''}]});
  } catch (e) {
    console.error('TWIGA AI contact save failed', e && e.message);
  }
}
async function aiCreateOrder(env, ctx, args, callId, toolId) {
  const customer = txt(args.customerName, 120);
  const phone = txt(args.customerPhone, 30);
  const requested = Array.isArray(args.items) ? args.items : [];
  if (!customer || !phone || !requested.length) throw new Error('Customer name, phone and at least one item are required.');
  const catalog = (await ACTIONS.listItems(env, ctx, {})).items || [];
  const byId = new Map(catalog.filter(x => Number(x.Active) === 1 || x.Active === true).map(x => [String(x.ItemId), x]));
  const totals = new Map();
  for (const line of requested) {
    const id = txt(line.itemId, 40);
    if (!byId.has(id)) throw new Error('One of the selected products or services is no longer active.');
    const qty = num(line.quantity, 'quantity', 0.01);
    totals.set(id, (totals.get(id) || 0) + qty);
  }
  const items = Array.from(totals.entries()).map(([id, qty]) => {
    const item = byId.get(id);
    if (Number(item.TrackStock) === 1 && Number(item.Stock || 0) < qty) throw new Error('There is not enough stock for ' + item.Name + '.');
    return {itemId: item.ItemId, name: item.Name, qty: qty, unitPrice: Number(item.Price || 0)};
  });
  const txn = ('vapi-' + safeToolKey(callId) + '-' + safeToolKey(toolId)).slice(0, 100);
  const order = await ACTIONS.createOrder(env, ctx, {
    type: 'Sale', customer: customer, phone: phone, method: 'Pay later', paid: false,
    notes: txt(args.notes, 250) || 'Order taken by TWIGA AI receptionist.',
    items: items
  }, txn);
  await aiSaveContact(env, ctx, customer, phone);
  return {ok: true, orderId: order.orderId, total: order.total, status: order.status, paymentStatus: 'unpaid', message: 'Order saved. Staff must confirm payment.'};
}
async function aiCheckSlot(env, ctx, args) {
  const date = txt(args.date, 10);
  const time = txt(args.time, 5);
  if (!/^\d{4}-\d{2}-\d{2}$/.test(date) || !/^(?:[01]\d|2[0-3]):[0-5]\d$/.test(time)) throw new Error('Use a valid date and 24-hour time in Kenya.');
  const start = new Date(date + 'T' + time + ':00+03:00');
  if (!Number.isFinite(start.getTime()) || start.getTime() <= Date.now()) return {available: false, reason: 'That time has already passed.'};
  const duration = Math.min(180, Math.max(15, Math.round(Number(args.durationMinutes) || 30)));
  const end = new Date(start.getTime() + duration * 60000);
  const dayStart = new Date(date + 'T00:00:00+03:00').toISOString();
  const dayEnd = new Date(date + 'T23:59:59+03:00').toISOString();
  const events = await env.DB.prepare('SELECT title, start_at, end_at FROM events WHERE business_id = ? AND start_at >= ? AND start_at < ?').bind(ctx.biz.id, dayStart, dayEnd).all();
  for (const event of (events.results || [])) {
    const eventStart = new Date(event.start_at);
    const eventEnd = event.end_at ? new Date(event.end_at) : new Date(eventStart.getTime() + 30 * 60000);
    if (eventStart < end && eventEnd > start) return {available: false, reason: 'That time overlaps an existing calendar event. Please offer another time.'};
  }
  const existing = await env.DB.prepare("SELECT order_id, notes FROM orders WHERE business_id = ? AND type = 'Appointment' AND date = ? AND status <> 'Cancelled'").bind(ctx.biz.id, date).all();
  for (const appointment of (existing.results || [])) {
    const match = String(appointment.notes || '').match(/\b([01]\d|2[0-3]):[0-5]\d\b/);
    if (!match) return {available: false, reason: 'There is already an appointment on that date without a recorded time. Staff must confirm availability.'};
    const oldStart = new Date(date + 'T' + match[0] + ':00+03:00');
    const oldEnd = new Date(oldStart.getTime() + 30 * 60000);
    if (oldStart < end && oldEnd > start) return {available: false, reason: 'That time overlaps an existing appointment. Please offer another time.'};
  }
  return {available: true, date: date, time: time, durationMinutes: duration};
}
async function aiBookAppointment(env, ctx, args, callId, toolId) {
  const customer = txt(args.customerName, 120);
  const phone = txt(args.customerPhone, 30);
  const serviceId = txt(args.serviceItemId, 40);
  if (!customer || !phone || !serviceId) throw new Error('Customer name, phone and an active service are required.');
  const callKey = safeToolKey(callId), toolKey = safeToolKey(toolId);
  const txn = ('vapi-appt-' + callKey + '-' + toolKey).slice(0, 100);
  const eventId = ('AI-' + callKey + '-' + toolKey).slice(0, 80);
  const existingEvent = await env.DB.prepare('SELECT title, start_at, end_at, notes FROM events WHERE business_id = ? AND event_id = ?').bind(ctx.biz.id, eventId).first();
  if (existingEvent) {
    const match = String(existingEvent.notes || '').match(/order:\s*([A-Za-z0-9_-]+)/i);
    const oldOrder = match ? await env.DB.prepare('SELECT order_id AS orderId, total, status FROM orders WHERE business_id = ? AND order_id = ?').bind(ctx.biz.id, match[1]).first() : null;
    return {ok: true, appointmentId: eventId, orderId: oldOrder ? oldOrder.orderId : '', service: existingEvent.title, date: txt(args.date, 10), time: txt(args.time, 5), status: oldOrder ? oldOrder.status : 'Confirmed', total: oldOrder ? Number(oldOrder.total || 0) : 0}; --- TRUNCATED --- 33,767 chars