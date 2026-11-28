// File Agent AI relay: sign-up, credits, the AI call and Stripe payments.
// Secrets (set with `wrangler secret put`): OPENROUTER_API_KEY, STRIPE_WEBHOOK_SECRET,
// GOOGLE_PLAY_SA (the Play service account's JSON key, for checking Play purchases).

// OpenRouter speaks the same Anthropic Messages format the app uses.
const OPENROUTER = "https://openrouter.ai/api/v1/messages";
// DeepSeek V4.1 Flash prices per token (USD), plus OpenRouter's 5.5% credit fee.
// Used for the safety-net cost counter.
const FEE = 1.055;
const PRICE_IN = (0.30 / 1e6) * FEE, PRICE_CACHED = (0.006 / 1e6) * FEE, PRICE_OUT = (1.20 / 1e6) * FEE;
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
  const token = (auth.startsWith("Bearer ") ? auth.slice(7) : req.headers.get("x-api-key") || "").trim();
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

  // The server picks the model and limits, whatever the app sends. Each person also gets their
  // own tag in OpenRouter (a hash, not the account id), which helps it spot abuse.
  const upstreamBody = {
    model: env.MODEL,
    max_tokens: Math.min(Number(body.max_tokens) || 4096, 4096),
    system: body.system,
    messages: body.messages,
    tools: body.tools,
    thinking: { type: "disabled" },
    user: (await sha256("user:" + acc.id)).slice(0, 32),
  };
  const res = await fetch(env.UPSTREAM || OPENROUTER, {
    method: "POST",
    headers: {
      "content-type": "application/json",
      authorization: `Bearer ${env.OPENROUTER_API_KEY}`,
      "anthropic-version": "2023-06-01",
      // Shown in your OpenRouter dashboard, so you can see File Agent's usage.
      "http-referer": "https://github.com/offbr0wn/file-agent-kmp",
      "x-title": "File Agent",
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

// ---- Google Play ----------------------------------------------------------------------------
// The app sends each Play purchase here. We ask Google if it is real before adding messages.
// Auth is a service account: we sign a short JWT with its key and swap it for an access token.
const GOOGLE_TOKEN = "https://oauth2.googleapis.com/token";
const PUBLISHER = "https://androidpublisher.googleapis.com/androidpublisher/v3/applications";
let googleToken = { value: "", until: 0 };

const b64url = (bytes) => btoa(String.fromCharCode(...new Uint8Array(bytes))).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
const b64urlText = (t) => b64url(new TextEncoder().encode(t));

async function googleAccess(env) {
  if (googleToken.value && googleToken.until > now() + 60) return googleToken.value;
  const sa = JSON.parse(env.GOOGLE_PLAY_SA);
  const pem = sa.private_key.replace(/-----[^-]+-----/g, "").replace(/\s+/g, "");
  const der = Uint8Array.from(atob(pem), (ch) => ch.charCodeAt(0));
  const key = await crypto.subtle.importKey("pkcs8", der, { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["sign"]);
  const aud = env.GOOGLE_TOKEN_URL || GOOGLE_TOKEN;
  const head = b64urlText(JSON.stringify({ alg: "RS256", typ: "JWT" }));
  const claims = b64urlText(JSON.stringify({
    iss: sa.client_email, scope: "https://www.googleapis.com/auth/androidpublisher", aud, iat: now(), exp: now() + 3600,
  }));
  const sig = await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key, new TextEncoder().encode(`${head}.${claims}`));
  const res = await fetch(aud, {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({ grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer", assertion: `${head}.${claims}.${b64url(sig)}` }),
  });
  if (!res.ok) throw new Error("google auth " + res.status);
  const t = await res.json();
  googleToken = { value: t.access_token, until: now() + (t.expires_in || 3600) };
  return googleToken.value;
}

async function googleGet(env, path) {
  const base = env.PLAY_API || PUBLISHER;
  const res = await fetch(`${base}/${env.PACKAGE}/purchases/${path}`, {
    headers: { authorization: `Bearer ${await googleAccess(env)}` },
  });
  if (res.status === 404 || res.status === 400 || res.status === 410) return null; // not a real purchase
  if (!res.ok) throw new Error("google " + res.status);
  return res.json();
}

/** Asks Google about a subscription. Returns { active, expiry } or null when it isn't ours. */
async function playSubscription(env, token) {
  const s = await googleGet(env, `subscriptionsv2/tokens/${encodeURIComponent(token)}`);
  if (!s) return null;
  const items = (s.lineItems || []).filter((l) => l.productId === env.PLAY_PLUS_ID);
  if (!items.length) return null;
  const expiry = Math.max(...items.map((l) => Math.floor(Date.parse(l.expiryTime) / 1000) || 0));
  const active = ["SUBSCRIPTION_STATE_ACTIVE", "SUBSCRIPTION_STATE_IN_GRACE_PERIOD"].includes(s.subscriptionState) && expiry > now();
  return { active, expiry };
}

/** Gives Plus for a Play subscription. A new paid period refills the monthly messages. */
async function grantPlaySub(env, acc, token, sub) {
  const db = env.DB;
  const row = await db.prepare("SELECT account_id, expiry FROM play_purchases WHERE token = ?").bind(token).first();
  const planMsgs = Number(env.PLAN_MESSAGES);
  if (!sub.active) {
    await db.prepare("UPDATE accounts SET plan = 'free', monthly_left = 0, plan_until = ?, play_checked_at = ? WHERE id = ? AND play_token = ?")
      .bind(now(), now(), acc.id, token).run();
    return false;
  }
  let left = planMsgs;
  if (row && row.account_id !== acc.id) {
    // The same Google account on a new install (or a restored key): move Plus across, with what was left.
    const old = await db.prepare("SELECT monthly_left FROM accounts WHERE id = ?").bind(row.account_id).first();
    if (row.expiry >= sub.expiry) left = old?.monthly_left ?? planMsgs;
    await db.prepare("UPDATE accounts SET plan = 'free', monthly_left = 0, play_token = NULL WHERE id = ? AND play_token = ?")
      .bind(row.account_id, token).run();
  } else if (row && row.expiry >= sub.expiry) {
    // Same period as before: nothing new, keep what is left.
    left = null;
  }
  await db.prepare(
    "INSERT INTO play_purchases (token, account_id, product, kind, expiry, created_at) VALUES (?, ?, ?, 'subs', ?, ?) " +
    "ON CONFLICT(token) DO UPDATE SET account_id = excluded.account_id, expiry = excluded.expiry",
  ).bind(token, acc.id, env.PLAY_PLUS_ID, sub.expiry, now()).run();
  if (left === null) {
    await db.prepare("UPDATE accounts SET plan = 'plus', plan_until = ?, play_token = ?, play_checked_at = ? WHERE id = ?")
      .bind(sub.expiry, token, now(), acc.id).run();
  } else {
    await db.prepare("UPDATE accounts SET plan = 'plus', plan_until = ?, monthly_left = ?, play_token = ?, play_checked_at = ? WHERE id = ?")
      .bind(sub.expiry, left, token, now(), acc.id).run();
  }
  return true;
}

async function playVerify(req, acc, env) {
  if (!env.GOOGLE_PLAY_SA) return fail(503, "api_error", "Play payments aren't set up yet.");
  let b;
  try { b = await req.json(); } catch { return fail(400, "invalid_request_error", "Bad request"); }
  const token = String(b.token || "");
  if (token.length < 10 || token.length > 4096) return fail(400, "invalid_request_error", "Bad purchase");

  if (b.kind === "subs") {
    if (b.product !== env.PLAY_PLUS_ID) return fail(400, "invalid_request_error", "Unknown product");
    // Already known for this account and still paid up: no need to ask Google again.
    const known = await env.DB.prepare("SELECT expiry FROM play_purchases WHERE token = ? AND account_id = ?").bind(token, acc.id).first();
    if (known && known.expiry > now() && acc.play_token === token) return json({ ok: true, plan: "plus", known: true });
    const sub = await playSubscription(env, token);
    if (!sub) return fail(402, "invalid_request_error", "Google Play doesn't know this purchase.");
    const on = await grantPlaySub(env, acc, token, sub);
    return on ? json({ ok: true, plan: "plus" }) : fail(402, "invalid_request_error", "This subscription has ended.");
  }

  if (b.kind === "inapp") {
    if (b.product !== env.PLAY_TOPUP_ID) return fail(400, "invalid_request_error", "Unknown product");
    const done = await env.DB.prepare("SELECT account_id FROM play_purchases WHERE token = ?").bind(token).first();
    if (done) return json({ ok: true, repeat: true }); // counted already; the app can use it up now
    const p = await googleGet(env, `products/${encodeURIComponent(b.product)}/tokens/${encodeURIComponent(token)}`);
    if (!p || p.purchaseState !== 0) return fail(402, "invalid_request_error", "Google Play doesn't show this as paid.");
    const qty = Math.max(1, Number(p.quantity) || 1);
    const first = await env.DB.prepare(
      "INSERT OR IGNORE INTO play_purchases (token, account_id, product, kind, created_at) VALUES (?, ?, ?, 'inapp', ?) RETURNING token",
    ).bind(token, acc.id, b.product, now()).first();
    if (first) {
      await env.DB.prepare("UPDATE accounts SET pack_left = pack_left + ? WHERE id = ?").bind(Number(env.PACK_MESSAGES) * qty, acc.id).run();
    }
    return json({ ok: true });
  }
  return fail(400, "invalid_request_error", "Bad request");
}

/**
 * Play doesn't tell us about renewals here, so when a Plus period has run out we ask Google once
 * (at most every 10 minutes). A renewal refills the month; an ended subscription turns Plus off.
 */
async function playRenewal(acc, env) {
  if (!acc.play_token || !env.GOOGLE_PLAY_SA) return acc;
  if (acc.plan_until > now() || acc.play_checked_at > now() - 600) return acc;
  try {
    const sub = await playSubscription(env, acc.play_token);
    if (sub) await grantPlaySub(env, acc, acc.play_token, sub);
    else await env.DB.prepare("UPDATE accounts SET play_checked_at = ? WHERE id = ?").bind(now(), acc.id).run();
    return (await env.DB.prepare("SELECT * FROM accounts WHERE id = ?").bind(acc.id).first()) || acc;
  } catch {
    return acc; // Google unreachable: try again later
  }
}

// ---- routes ---------------------------------------------------------------------------------
export default {
  async fetch(req, env, ctx) {
    const { pathname } = new URL(req.url);
    try {
      if (req.method === "GET" && pathname === "/") return json({ name: "File Agent AI", ok: true });
      if (req.method === "POST" && pathname === "/v1/register") return await register(req, env);
      if (req.method === "POST" && pathname === "/stripe/webhook") return await stripeWebhook(req, env);

      let acc = await accountFor(req, env);
      if (!acc) return fail(401, "authentication_error", "Sign in again in Settings.");
      acc = await playRenewal(acc, env);
      if (req.method === "GET" && pathname === "/v1/account") return await account(acc, env);
      if (req.method === "POST" && pathname === "/v1/messages") return await messages(req, acc, env, ctx);
      if (req.method === "POST" && pathname === "/v1/play/verify") return await playVerify(req, acc, env);
      return fail(404, "not_found_error", "Not found");
    } catch (e) {
      console.error(e?.stack || e); // shows in Cloudflare's logs
      return fail(500, "api_error", "Server error. Try again.");
    }
  },
};
