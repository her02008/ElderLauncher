/**
 * 长辈助手 · 远程配置服务端（Cloudflare Workers 版）
 *
 * 协议见 docs/remote-config-protocol.md。这里是**其中一种实现**，
 * 另一种是同目录外的 server/node/server.js，两套实现共用同一份 admin.html。
 *
 * 部署：
 *   cd server
 *   npm i -g wrangler
 *   wrangler kv namespace create ELDER     # 把返回的 id 填进 wrangler.toml
 *   wrangler deploy
 *
 * 存储用 KV。注意 KV 是**最终一致**的：写完立刻读可能读到旧值（最多约 60 秒）。
 *
 * ## 为什么指令要一条一个键（v1.1 改的）
 * v1 把待执行指令放在 `cfg:{code}.cmds` 数组里，下指令是「读数组 → push → 写回」。
 * 多个子女同时点按钮时，两边都读到 `[A]`，都写回 `[A,B]` / `[A,C]`，**必丢一条**。
 * 现在每条指令写自己的键 `cmd:{code}:{id}`，彼此不覆盖，写操作天然并发安全。
 *
 * 代价是「拿全部待执行指令」从一次 get 变成一次 list + N 次 get。
 * 对本场景（队列长度基本是 0~3）完全无所谓，换来的是多人同时用不会丢指令。
 */

import ADMIN_HTML from '../admin.html';

/** 只允许这些指令下落。长辈端也有同一份白名单，两边都拦。 */
const SAFE_OPS = new Set(['set_font', 'set_voice', 'set_alive', 'set_home_order', 'push_msg']);
const DANGER_OPS = new Set(['set_sos', 'set_blocks', 'set_quick']);

/** 待执行指令最多留这么多条，避免长时间不执行时无限堆积 */
const MAX_CMDS = 50;

/** 签名允许的时间偏差（秒） */
const TS_SKEW = 300;

/**
 * 修订号基准。rev 用「秒级时间戳 - 基准」算，落在 1.9e8 量级；
 * 到 2038 年也才 5.5e8，不会溢出长辈端的 `JSONObject.optInt`（32 位有符号）。
 * **不要直接把毫秒时间戳当 rev**：1.7e12 在 optInt 里会截断成负数，
 * 长辈端就永远认为「版本变了」，每次同步都重跑一遍历史指令。
 */
const REV_BASE = 1600000000; // 2020-09-13

const CORS = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Headers': 'Content-Type',
  'Access-Control-Allow-Methods': 'GET,POST,OPTIONS',
};

export default {
  async fetch(request, env) {
    try {
      return await route(request, env);
    } catch (e) {
      return json({ ok: false, error: String((e && e.message) || e) }, 500);
    }
  },
};

async function route(request, env) {
  const url = new URL(request.url);
  const parts = url.pathname.split('/').filter(Boolean);

  if (request.method === 'OPTIONS') return new Response(null, { status: 204, headers: CORS });

  // ---- 设备端 ----
  if (parts[0] === 'b' && parts.length === 3) {
    const code = parts[1].toUpperCase();
    if (parts[2] === 'bind' && request.method === 'POST') return doBind(request, env, code);
    if (parts[2] === 'cfg' && request.method === 'GET') return doCfg(request, env, code, url);
    if (parts[2] === 'ack' && request.method === 'POST') return doAck(request, env, code);
  }

  // ---- 管理端（子女） ----
  if (parts[0] === 'a' && parts.length >= 2) {
    const code = parts[1].toUpperCase();
    if (parts.length === 2) {
      return new Response(ADMIN_HTML, {
        headers: { 'Content-Type': 'text/html; charset=utf-8', ...CORS },
      });
    }
    if (parts[2] === 'api' && parts[3] === 'state' && request.method === 'GET') {
      return doAdminState(env, code);
    }
    if (parts[2] === 'api' && parts[3] === 'cmd' && request.method === 'POST') {
      return doAdminCmd(request, env, code);
    }
    if (parts[2] === 'api' && parts[3] === 'clear' && request.method === 'POST') {
      return doAdminClear(env, code);
    }
  }

  return json({ ok: false, error: 'not found' }, 404);
}

// ---------------- 设备端 ----------------

async function doBind(request, env, code) {
  const body = await request.text();
  let p;
  try {
    p = JSON.parse(body || '{}');
  } catch {
    return json({ ok: false, error: 'bad json' }, 400);
  }
  if (!p.deviceId) return json({ ok: false, error: 'deviceId required' }, 400);

  // 每次绑定都轮换 token：换手机、或者怀疑泄露，重新绑一次就作废旧钥匙
  const token = randomHex(32);
  const bind = {
    token,
    deviceId: p.deviceId,
    name: String(p.name || '长辈手机').slice(0, 40),
    model: String(p.model || '').slice(0, 60),
    version: String(p.version || '').slice(0, 20),
    boundAt: Date.now(),
  };
  await env.ELDER.put('bind:' + code, JSON.stringify(bind));

  const meta = await getMeta(env, code);
  return json({ ok: true, token, rev: meta.rev, serverTime: Date.now() });
}

async function doCfg(request, env, code, url) {
  const body = '';
  const auth = await verifyDevice(request, env, code, body);
  if (auth.error) return json({ ok: false, error: auth.error }, 401);

  const meta = await getMeta(env, code);
  const sinceRaw = url.searchParams.get('rev');
  const since = sinceRaw === null ? -1 : Number(sinceRaw);

  // 修订号没变就什么都不发：省一次 N 条指令的读取。
  // 就算这里判断失误多发了，长辈端也有「已执行 id」兜底，不会真的重跑。
  if (since === meta.rev) {
    return json({ ok: true, rev: meta.rev, serverTime: Date.now(), cmds: [] });
  }

  const cmds = await listCmds(env, code, meta.clearedAt || 0);
  return json({ ok: true, rev: meta.rev, serverTime: Date.now(), cmds });
}

async function doAck(request, env, code) {
  const body = await request.text();
  const auth = await verifyDevice(request, env, code, body);
  if (auth.error) return json({ ok: false, error: auth.error }, 401);

  let p;
  try {
    p = JSON.parse(body || '{}');
  } catch {
    return json({ ok: false, error: 'bad json' }, 400);
  }

  // 回执里报过的指令立刻删掉，队列不会越滚越长。delete 本身幂等，
  // 重复 ack 或者已经被「清空」删掉的 id 都不会出错。
  const ids = (p.results || []).map((r) => r && r.id).filter(Boolean);
  await Promise.all(ids.map((id) => env.ELDER.delete('cmd:' + code + ':' + id)));

  if (p.state) {
    await env.ELDER.put(
      'state:' + code,
      JSON.stringify({ ...p.state, ackAt: Date.now() })
    );
  }
  return json({ ok: true });
}

// ---------------- 管理端 ----------------

async function doAdminState(env, code) {
  const [bind, meta, state] = await Promise.all([
    getJSON(env, 'bind:' + code),
    getMeta(env, code),
    getJSON(env, 'state:' + code),
  ]);
  if (bind) delete bind.token;
  const cmds = await listCmds(env, code, meta.clearedAt || 0);
  return json({
    ok: true,
    bind: bind || null,
    rev: meta.rev,
    cmds,
    state: state || {},
  });
}

async function doAdminCmd(request, env, code) {
  let p;
  try {
    p = JSON.parse((await request.text()) || '{}');
  } catch {
    return json({ ok: false, error: 'bad json' }, 400);
  }
  const op = String(p.op || '');
  if (!SAFE_OPS.has(op) && !DANGER_OPS.has(op)) {
    return json({ ok: false, error: 'unknown op' }, 400);
  }

  // 一条指令一个键：两个子女同时下发，写的是两个不同的键，谁也盖不掉谁
  const id = 'c_' + randomHex(5);
  const at = Date.now();
  const cmd = { id, op, args: p.args || {}, danger: DANGER_OPS.has(op), at };
  await env.ELDER.put('cmd:' + code + ':' + id, JSON.stringify(cmd), {
    metadata: { at },
  });

  await bumpRev(env, code, at);
  await trimCmds(env, code);
  return json({ ok: true, id });
}

async function doAdminClear(env, code) {
  const meta = await getMeta(env, code);
  const cmds = await listCmds(env, code, meta.clearedAt || 0);
  await Promise.all(cmds.map((c) => env.ELDER.delete('cmd:' + code + ':' + c.id)));

  // clearedAt 是给并发兜底的：刚被清掉的瞬间有人又写进一条（at 更早的那个已删），
  // 靠时间戳判断比靠「删干净了」可靠。
  meta.clearedAt = Date.now();
  meta.rev = nextRev(meta.rev);
  meta.updatedAt = Date.now();
  await env.ELDER.put('meta:' + code, JSON.stringify(meta));
  return json({ ok: true, rev: meta.rev });
}

// ---------------- 指令队列 ----------------

async function getMeta(env, code) {
  const meta = (await getJSON(env, 'meta:' + code)) || {};
  return {
    rev: Number(meta.rev) || 0,
    clearedAt: Number(meta.clearedAt) || 0,
    updatedAt: Number(meta.updatedAt) || 0,
  };
}

async function bumpRev(env, code, at) {
  const meta = await getMeta(env, code);
  meta.rev = nextRev(meta.rev, at);
  meta.updatedAt = at;
  await env.ELDER.put('meta:' + code, JSON.stringify(meta));
  return meta.rev;
}

/**
 * 修订号只增不减。用秒级时间戳保证多个人同时下指令也不会撞回同一个值；
 * 万一（时钟回拨 / 读到旧值）也不会小于当前值，长辈端就不会漏拉。
 */
function nextRev(current, at) {
  const now = Math.floor((at || Date.now()) / 1000) - REV_BASE;
  return Math.max(now, (Number(current) || 0) + 1);
}

async function listCmds(env, code, clearedAt) {
  const prefix = 'cmd:' + code + ':';
  const listed = await env.ELDER.list({ prefix });
  const cmds = [];
  for (const k of listed.keys) {
    const c = await getJSON(env, k.name);
    if (!c || !c.id) continue;
    if (clearedAt && c.at <= clearedAt) continue;
    cmds.push(c);
  }
  cmds.sort((a, b) => (a.at || 0) - (b.at || 0));
  return cmds;
}

/** 队列太长就砍最旧的。并发时可能多留几条，无所谓，别无限涨就行。 */
async function trimCmds(env, code) {
  const cmds = await listCmds(env, code, 0);
  if (cmds.length <= MAX_CMDS) return;
  const drop = cmds.slice(0, cmds.length - MAX_CMDS);
  await Promise.all(drop.map((c) => env.ELDER.delete('cmd:' + code + ':' + c.id)));
}

// ---------------- 鉴权 ----------------

async function verifyDevice(request, env, code, body) {
  const deviceId = request.headers.get('X-Device') || '';
  const ts = Number(request.headers.get('X-Ts') || 0);
  const sig = (request.headers.get('X-Sig') || '').toLowerCase();
  if (!deviceId || !ts || !sig) return { error: 'missing auth' };

  // 时间戳偏差超过 5 分钟直接拒：泄露的 token 最多只能重放这么久的请求
  if (Math.abs(Date.now() / 1000 - ts) > TS_SKEW) return { error: 'stale' };

  const bind = await getJSON(env, 'bind:' + code);
  if (!bind) return { error: 'not bound' };
  if (bind.deviceId !== deviceId) return { error: 'device mismatch' };

  const url = new URL(request.url);
  const canonical = [request.method, url.pathname + url.search, ts, body].join('\n');
  const expect = await hmacHex(bind.token, canonical);
  if (!timingEqual(expect, sig)) return { error: 'bad sig' };

  return { bind };
}

async function hmacHex(secret, message) {
  const key = await crypto.subtle.importKey(
    'raw',
    new TextEncoder().encode(secret),
    { name: 'HMAC', hash: 'SHA-256' },
    false,
    ['sign']
  );
  const buf = await crypto.subtle.sign('HMAC', key, new TextEncoder().encode(message));
  return [...new Uint8Array(buf)].map((b) => b.toString(16).padStart(2, '0')).join('');
}

/** 逐字符比较，长度不同也走完全部循环，避免用耗时泄露前缀 */
function timingEqual(a, b) {
  const n = Math.max(a.length, b.length);
  let diff = a.length ^ b.length;
  for (let i = 0; i < n; i++) {
    diff |= (a.charCodeAt(i) || 0) ^ (b.charCodeAt(i) || 0);
  }
  return diff === 0;
}

// ---------------- 工具 ----------------

async function getJSON(env, key) {
  const raw = await env.ELDER.get(key);
  if (!raw) return null;
  try {
    return JSON.parse(raw);
  } catch {
    return null;
  }
}

function randomHex(bytes) {
  const arr = new Uint8Array(bytes);
  crypto.getRandomValues(arr);
  return [...arr].map((b) => b.toString(16).padStart(2, '0')).join('');
}

function json(obj, status = 200) {
  return new Response(JSON.stringify(obj), {
    status,
    headers: { 'Content-Type': 'application/json; charset=utf-8', ...CORS },
  });
}
