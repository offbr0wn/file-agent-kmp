// File Agent AI relay: sign-up, credits, the AI call and Stripe payments.
// Secrets (set with `wrangler secret put`): DEEPSEEK_API_KEY, STRIPE_WEBHOOK_SECRET.

const DEEPSEEK = "https://api.deepseek.com/anthropic/v1/messages";
// DeepSeek peak prices per token (USD). Used for the safety-net cost counter.
const PRICE_IN = 0.30 / 1e6, PRICE_CACHED = 0.006 / 1e6, PRICE_OUT = 1.20 / 1e6;
const MAX_BODY = 6 * 1024 * 1024;

const now = () => Math.floor(Date.now() / 1000);
const monthKey = () => new Date().toISOString().slice(0, 7);
const json = (body, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } });
const fail = (status, code, message) => json({ type: "error", error: { type: code, message } }, status);

async function sha256(text) {
  const d = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(text));
  return [...new Uint8Array(d)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

function randomHex(bytes) {
  const a = crypto.getRandomValues(new Uint8Array(bytes));
  return [...a].map((b) => b.toString(16).padStart(2, "0")).join("");
}

async function accountFor(req, env) {
  const auth = req.headers.get("authorization") || "";
  const token = auth.startsWith("Bearer ") ? auth.slice(7).trim() : "";
  if (token.length < 40) return null;
  return env.DB.prepare("SELECT * FROM accounts WHERE token_hash = ?").bind(await sha256(token)).first();
}

// ---- sign-up: a private account per install, no email needed -------------------------------
async function register(req, env) {
  const ip = req.headers.get("cf-connecting-ip") || "";
  const ipHash = await sha256("ip:" + ip);
  const recent = await env.DB.prepare("SELECT count(*) AS n FROM accounts WHERE ip_hash = ? AND created_at > ?")
    .bind(ipHash, now() - 3600).first();
  if ((recent?.n || 0) >= 10) return fail(429, "rate_limit_error", "Too many sign-ups. Try again later.");
  const id = crypto.randomUUID();
  const token = "fa_" + randomHex(32);
  await env.DB.prepare("INSERT INTO accounts (id, token_hash, ip_hash, created_at, cost_month_key) VALUES (?, ?, ?, ?, ?)")
    .bind(id, await sha256(token), ipHash, now(), monthKey()).run();
  return json({ account: id, token });
}

// ---- what the user has left, and where to pay ------------------------------------------------
async function account(acc, env) {
  const link = (base) => (base ? `${base}?client_reference_id=${acc.id}` : "");
  const active = acc.plan === "plus" && acc.plan_until > now();
  return json({
    account: acc.id,
    plan: active ? "plus" : "free",
    plan_until: acc.plan_until,
    monthly_left: active ? acc.monthly_left : 0,
    pack_left: acc.pack_left,
    plan_messages: Number(env.PLAN_MESSAGES),
    pack_messages: Number(env.PACK_MESSAGES),
    plan_link: link(env.PLAN_LINK),
    pack_link: link(env.PACK_LINK),
  });
}

// ---- credits --------------------------------------------------------------------------------
// Returns "ok" or why not: no_credit, too_many_steps, slow_down, cost_cap.
async function useStep(acc, requestId, env) {
  const db = env.DB;
  const key = monthKey();
  let cost = acc.cost_month;
  if (acc.cost_month_key !== key) {
    await db.prepare("UPDATE accounts SET cost_month = 0, cost_month_key = ? WHERE id = ?").bind(key, acc.id).run();
    cost = 0;
  }
  if (cost >= Number(env.COST_CAP_USD)) return "cost_cap";

  // More steps of a message already paid for.
  const more = await db.prepare(
    "UPDATE requests SET calls = calls + 1 WHERE account_id = ? AND request_id = ? AND calls < ? RETURNING calls",
  ).bind(acc.id, requestId, Number(env.MAX_STEPS)).first();
  if (more) return "ok";
  const seen = await db.prepare("SELECT 1 AS x FROM requests WHERE account_id = ? AND request_id = ?").bind(acc.id, requestId).first();
  if (seen) return "too_many_steps";

  // A new message: rate limit, then take one credit (plan first, then packs).
  const recent = await db.prepare("SELECT count(*) AS n FROM requests WHERE account_id = ? AND created_at > ?")
    .bind(acc.id, now() - 60).first();
  if ((recent?.n || 0) >= Number(env.PER_MINUTE)) return "slow_down";

  let source = null;
  const plan = await db.prepare(
    "UPDATE accounts SET monthly_left = monthly_left - 1 WHERE id = ? AND plan = 'plus' AND plan_until > ? AND monthly_left > 0 RETURNING id",
  ).bind(acc.id, now()).first();
  if (plan) source = "plan";
  else {
    const pack = await db.prepare("UPDATE accounts SET pack_left = pack_left - 1 WHERE id = ? AND pack_left > 0 RETURNING id")
      .bind(acc.id).first();
    if (pack) source = "pack";
  }
  if (!source) return "no_credit";
  await db.prepare("INSERT OR IGNORE INTO requests (account_id, request_id, source, created_at) VALUES (?, ?, ?, ?)")
    .bind(acc.id, requestId, source, now()).run();
  return "ok";
}

const WHY = {
  no_credit: [402, "You have no AI messages left. Get the monthly plan or a top-up in Settings."],
  too_many_steps: [429, "This job took too many steps. Try a smaller request."],
  slow_down: [429, "Too many messages at once. Wait a minute and try again."],
  cost_cap: [429, "You have reached this month's fair-use limit. It resets on the 1st."],
};

// ---- the AI call ----------------------------------------------------------------------------
async function messages(req, acc, env, ctx) {
  const size = Number(req.headers.get("content-length") || 0);
  if (size > MAX_BODY) return fail(413, "request_too_large", "That message is too big.");
  const raw = await req.text();
  if (raw.length > MAX_BODY) return fail(413, "request_too_large", "That message is too big.");
  let body;
  try { body = JSON.parse(raw); } catch { return fail(400, "invalid_request_error", "Bad request"); }

  const requestId = (req.headers.get("x-request-id") || "").slice(0, 80);
  if (!requestId) return fail(400, "invalid_request_error", "Missing request id");
  const ok = await useStep(acc, requestId, env);
  if (ok !== "ok") return fail(WHY[ok][0], ok, WHY[ok][1]);

  // The server picks the model and limits, whatever the app sends.
  const upstreamBody = {
    model: env.MODEL,
    max_tokens: Math.min(Number(body.max_tokens) || 4096, 4096),
    system: body.system,
    messages: body.messages,
    tools: body.tools,
    thinking: { type: "disabled" },
  };
  const res = await fetch(env.UPSTREAM || DEEPSEEK, {
    method: "POST",
    headers: {
      "content-type": "application/json",
      "x-api-key": env.DEEPSEEK_API_KEY,
      "anthropic-version": "2023-06-01",
    },
    body: JSON.stringify(upstreamBody),
  });
  const text = await res.text();
  if (res.ok) {
    try {
      const u = JSON.parse(text).usage || {};
      const cached = u.cache_read_input_tokens || 0;
      const fresh = u.input_tokens || 0; // Anthropic format counts cached tokens separately
      const usd = fresh * PRICE_IN + cached * PRICE_CACHED + (u.output_tokens || 0) * PRICE_OUT;
      ctx.waitUntil(env.DB.prepare("UPDATE accounts SET cost_month = cost_month + ? WHERE id = ?").bind(usd, acc.id).run());
    } catch {}
  }
  return new Response(text, { status: res.status, headers: { "content-type": "application/json" } });
}

// ---- Stripe ---------------------------------------------------------------------------------
async function verifyStripe(raw, header, secret) {
  const parts = Object.fromEntries((header || "").split(",").map((p) => p.split("=")));
  const t = Number(parts.t);
  const sigs = (header || "").split(",").filter((p) => p.startsWith("v1=")).map((p) => p.slice(3));
  if (!t || !sigs.length || Math.abs(now() - t) > 300) return false;
  const key = await crypto.subtle.importKey("raw", new TextEncoder().encode(secret), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  const mac = await crypto.subtle.sign("HMAC", key, new TextEncoder().encode(`${t}.${raw}`));
  const hex = [...new Uint8Array(mac)].map((b) => b.toString(16).padStart(2, "0")).join("");
  return sigs.some((s) => s.length === hex.length && [...s].every((ch, i) => ch === hex[i]));
}

async function stripeWebhook(req, env) {
  const raw = await req.text();
  if (!env.STRIPE_WEBHOOK_SECRET || !(await verifyStripe(raw, req.headers.get("stripe-signature"), env.STRIPE_WEBHOOK_SECRET))) {
    return new Response("bad signature", { status: 400 });
  }
  const event = JSON.parse(raw);
  const o = event.data?.object || {};
  const db = env.DB;
  const seen = await db.prepare("INSERT OR IGNORE INTO payment_events (event_id, kind, created_at) VALUES (?, ?, ?) RETURNING event_id")
    .bind(event.id, event.type, now()).first();
  if (!seen) return json({ ok: true, repeat: true });

  const byCustomer = async (c) => (c ? db.prepare("SELECT id FROM accounts WHERE stripe_customer = ?").bind(c).first() : null);
  const planMsgs = Number(env.PLAN_MESSAGES), packMsgs = Number(env.PACK_MESSAGES);

  if (event.type === "checkout.session.completed" && o.payment_status !== "unpaid") {
    const id = o.client_reference_id;
    if (!id) return json({ ok: true, note: "no account" });
    if (o.mode === "subscription") {
      await db.prepare(
        "UPDATE accounts SET plan = 'plus', plan_until = max(plan_until, ?), monthly_left = ?, stripe_customer = coalesce(?, stripe_customer), stripe_subscription = ? WHERE id = ?",
      ).bind(now() + 32 * 86400, planMsgs, o.customer || null, o.subscription || null, id).run();
    } else if (o.mode === "payment") {
      await db.prepare("UPDATE accounts SET pack_left = pack_left + ?, stripe_customer = coalesce(stripe_customer, ?) WHERE id = ?")
        .bind(packMsgs, o.customer || null, id).run();
    }
  } else if (event.type === "invoice.paid" && o.billing_reason === "subscription_cycle") {
    const acc = await byCustomer(o.customer);
    const end = o.lines?.data?.[0]?.period?.end || now() + 32 * 86400;
    if (acc) await db.prepare("UPDATE accounts SET plan = 'plus', plan_until = ?, monthly_left = ? WHERE id = ?").bind(end, planMsgs, acc.id).run();
  } else if (event.type === "customer.subscription.deleted") {
    const acc = await byCustomer(o.customer);
    if (acc) await db.prepare("UPDATE accounts SET plan = 'free', monthly_left = 0, plan_until = ? WHERE id = ?").bind(now(), acc.id).run();
  }
  return json({ ok: true });
}

// ---- routes ---------------------------------------------------------------------------------
export default {
  async fetch(req, env, ctx) {
    const { pathname } = new URL(req.url);
    try {
      if (req.method === "GET" && pathname === "/") return json({ name: "File Agent AI", ok: true });
      if (req.method === "POST" && pathname === "/v1/register") return await register(req, env);
      if (req.method === "POST" && pathname === "/stripe/webhook") return await stripeWebhook(req, env);

      const acc = await accountFor(req, env);
      if (!acc) return fail(401, "authentication_error", "Sign in again in Settings.");
      if (req.method === "GET" && pathname === "/v1/account") return await account(acc, env);
      if (req.method === "POST" && pathname === "/v1/messages") return await messages(req, acc, env, ctx);
      return fail(404, "not_found_error", "Not found");
    } catch (e) {
      return fail(500, "api_error", "Server error. Try again.");
    }
  },
};
