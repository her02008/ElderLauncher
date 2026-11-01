/**
 * 长辈助手 · 远程配置服务端（NAS / 自建版）
 *
 * 协议与 server/workers/worker.js 完全一致，见 docs/remote-config-protocol.md。
 * 零依赖，只用 Node 内置模块，方便扔在 NAS、树莓派、旧笔记本上常驻。
 *
 * 跑起来：
 *   node server.js                  # 默认 127.0.0.1:8787
 *   PORT=9000 node server.js
 *
 * 让外网连得到（家里没有公网 IP 也能用）：
 *   cloudflared tunnel --url http://127.0.0.1:8787
 *   # 得到的 https://xxx.trycloudflare.com 就是填进手机的服务地址
 *
 * 想固定域名就跑命名隧道（free 账号即可）：
 *   cloudflared tunnel login
 *   cloudflared tunnel create elder
 *   cloudflared tunnel route dns elder elder.example.com
 *   cloudflared tunnel run --url http://127.0.0.1:8787 elder
 *
 * 注意：
 * - 默认只监听 127.0.0.1。**不要改成 0.0.0.0 直接暴露到公网**，
 *   要么走 cloudflared，要么至少放在带 HTTPS 的反向代理后面。
 * - 管理端接口（/a/…）只靠家庭码鉴权，所以 HTTPS 是硬要求，别用明文暴露。
 *
 * 存储形态与 Workers 版一致：一条待执行指令一个键（`cmd:CODE:ID`），
 * 下指令不经过「读数组-改-写回」，多个人同时点按钮不会互相覆盖。
 * 本文件是单进程同步执行，本来就不会有并发问题，但为了两套实现行为一致，
 * 存储形态保持一致——这样同一个 store.json 搬去 Workers 也能用。
 */

'use strict';

const http = require('http');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

const PORT = Number(process.env.PORT || 8787);
const HOST = process.env.HOST || '127.0.0.1';
const DATA_DIR = process.env.DATA_DIR || path.join(__dirname, 'data');
const STORE_FILE = path.join(DATA_DIR, 'store.json');
const ADMIN_FILE = path.join(__dirname, '..', 'admin.html');

/** 与 Workers 版保持一致的白名单 */
const SAFE_OPS = new Set(['set_font', 'set_voice', 'set_alive', 'set_home_order', 'push_msg']);
const DANGER_OPS = new Set(['set_sos', 'set_blocks', 'set_quick']);
const MAX_CMDS = 50;
const TS_SKEW = 300;

/**
 * 修订号基准，与 Workers 版必须完全相同。
 * rev = 秒级时间戳 - 基准，落在 1.9e8 量级，2038 年也才 5.5e8，
 * 不会溢出长辈端的 `JSONObject.optInt`。
 */
const REV_BASE = 1600000000; // 2020-09-13

// ---------------- 存储 ----------------

/**
 * 整个库就是一个 JSON：
 * {
 *   "bind:CODE":     { token, deviceId, name, model, version, boundAt },
 *   "meta:CODE":     { rev, clearedAt, updatedAt },
 *   "cmd:CODE:ID":   { id, op, args, danger, at },
 *   "state:CODE":    { lastActive, battery, ackAt, … }
 * }
 */
let store = {};

function loadStore() {
  try {
    store = JSON.parse(fs.readFileSync(STORE_FILE, 'utf8'));
  } catch {
    store = {};
  }
}

/**
 * 先写 .tmp 再 rename：断电时要么是旧文件、要么是新文件，
 * 不会出现写了一半的半个 JSON（那样整个库都读不出来，全家失联）。
 */
function saveStore() {
  fs.mkdirSync(DATA_DIR, { recursive: true });
  const tmp = STORE_FILE + '.tmp';
  fs.writeFileSync(tmp, JSON.stringify(store, null, 2));
  fs.renameSync(tmp, STORE_FILE);
}

const getKey = (k) => store[k] || null;
const putKey = (k, v) => {
  store[k] = v;
  saveStore();
};

// ---------------- 指令队列 ----------------

function getMeta(code) {
  const m = store['meta:' + code] || {};
  return {
    rev: Number(m.rev) || 0,
    clearedAt: Number(m.clearedAt) || 0,
    updatedAt: Number(m.updatedAt) || 0,
  };
}

/** 修订号只增不减，理由同 Workers 版 */
function nextRev(current, at) {
  const now = Math.floor((at || Date.now()) / 1000) - REV_BASE;
  return Math.max(now, (Number(current) || 0) + 1);
}

function listCmds(code, clearedAt) {
  const prefix = 'cmd:' + code + ':';
  const out = [];
  for (const k of Object.keys(store)) {
    if (!k.startsWith(prefix)) continue;
    const c = store[k];
    if (!c || !c.id) continue;
    if (clearedAt && c.at <= clearedAt) continue;
    out.push(c);
  }
  out.sort((a, b) => (a.at || 0) - (b.at || 0));
  return out;
}

/** 队列太长就砍最旧的，别让它无限涨 */
function trimCmds(code) {
  const all = listCmds(code, 0);
  if (all.length <= MAX_CMDS) return;
  for (const c of all.slice(0, all.length - MAX_CMDS)) {
    delete store['cmd:' + code + ':' + c.id];
  }
}

// ---------------- 鉴权 ----------------

function hmacHex(secret, message) {
  return crypto.createHmac('sha256', secret).update(message, 'utf8').digest('hex');
}

function timingEqual(a, b) {
  const ba = Buffer.from(a, 'utf8');
  const bb = Buffer.from(b, 'utf8');
  if (ba.length !== bb.length) return false;
  return crypto.timingSafeEqual(ba, bb);
}

/** 返回 { bind } 或 { error }，与 Workers 版同语义 */
function verifyDevice(req, code, body, rawUrl) {
  const deviceId = req.headers['x-device'] || '';
  const ts = Number(req.headers['x-ts'] || 0);
  const sig = String(req.headers['x-sig'] || '').toLowerCase();
  if (!deviceId || !ts || !sig) return { error: 'missing auth' };
  if (Math.abs(Date.now() / 1000 - ts) > TS_SKEW) return { error: 'stale' };

  const bind = getKey('bind:' + code);
  if (!bind) return { error: 'not bound' };
  if (bind.deviceId !== deviceId) return { error: 'device mismatch' };

  const canonical = [req.method, rawUrl, ts, body].join('\n');
  if (!timingEqual(hmacHex(bind.token, canonical), sig)) return { error: 'bad sig' };
  return { bind };
}

// ---------------- 响应工具 ----------------

const CORS = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Headers': 'Content-Type',
  'Access-Control-Allow-Methods': 'GET,POST,OPTIONS',
};

function sendJson(res, obj, status = 200) {
  const body = JSON.stringify(obj);
  res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', ...CORS });
  res.end(body);
}

function readBody(req) {
  return new Promise((resolve) => {
    let data = '';
    req.on('data', (c) => {
      data += c;
      if (data.length > 512 * 1024) req.destroy();
    });
    req.on('end', () => resolve(data));
    req.on('error', () => resolve(''));
  });
}

const randomHex = (n) => crypto.randomBytes(n).toString('hex');

// ---------------- 路由 ----------------

async function handle(req, res) {
  const rawUrl = req.url || '/';
  const u = new URL(rawUrl, 'http://localhost');
  const parts = u.pathname.split('/').filter(Boolean);

  if (req.method === 'OPTIONS') {
    res.writeHead(204, CORS);
    return res.end();
  }

  // ---- 设备端 ----
  if (parts[0] === 'b' && parts.length === 3) {
    const code = parts[1].toUpperCase();
    if (parts[2] === 'bind' && req.method === 'POST') return doBind(req, res, code);
    if (parts[2] === 'cfg' && req.method === 'GET') return doCfg(req, res, code, u, rawUrl);
    if (parts[2] === 'ack' && req.method === 'POST') return doAck(req, res, code, rawUrl);
  }

  // ---- 管理端 ----
  if (parts[0] === 'a' && parts.length >= 2) {
    const code = parts[1].toUpperCase();
    if (parts.length === 2) {
      let html;
      try {
        html = fs.readFileSync(ADMIN_FILE, 'utf8');
      } catch {
        res.writeHead(500, { 'Content-Type': 'text/plain; charset=utf-8', ...CORS });
        return res.end('找不到 admin.html：' + ADMIN_FILE);
      }
      res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8', ...CORS });
      return res.end(html);
    }
    if (parts[2] === 'api' && parts[3] === 'state' && req.method === 'GET') return doAdminState(res, code);
    if (parts[2] === 'api' && parts[3] === 'cmd' && req.method === 'POST') return doAdminCmd(req, res, code);
    if (parts[2] === 'api' && parts[3] === 'clear' && req.method === 'POST') return doAdminClear(res, code);
  }

  // 健康检查，方便 cloudflared / 监控探活
  if (u.pathname === '/healthz') return sendJson(res, { ok: true, up: Date.now() });

  sendJson(res, { ok: false, error: 'not found' }, 404);
}

async function doBind(req, res, code) {
  const body = await readBody(req);
  let p;
  try {
    p = JSON.parse(body || '{}');
  } catch {
    return sendJson(res, { ok: false, error: 'bad json' }, 400);
  }
  if (!p.deviceId) return sendJson(res, { ok: false, error: 'deviceId required' }, 400);

  const token = randomHex(32);
  putKey('bind:' + code, {
    token,
    deviceId: p.deviceId,
    name: String(p.name || '长辈手机').slice(0, 40),
    model: String(p.model || '').slice(0, 60),
    version: String(p.version || '').slice(0, 20),
    boundAt: Date.now(),
  });

  const meta = getMeta(code);
  sendJson(res, { ok: true, token, rev: meta.rev, serverTime: Date.now() });
}

async function doCfg(req, res, code, u, rawUrl) {
  const auth = verifyDevice(req, code, '', rawUrl);
  if (auth.error) return sendJson(res, { ok: false, error: auth.error }, 401);

  const meta = getMeta(code);
  const sinceRaw = u.searchParams.get('rev');
  const since = sinceRaw === null ? -1 : Number(sinceRaw);

  // 修订号没变就什么都不发。就算判断失误多发了，长辈端也有「已执行 id」兜底
  if (since === meta.rev) {
    return sendJson(res, { ok: true, rev: meta.rev, serverTime: Date.now(), cmds: [] });
  }

  sendJson(res, {
    ok: true,
    rev: meta.rev,
    serverTime: Date.now(),
    cmds: listCmds(code, meta.clearedAt || 0),
  });
}

async function doAck(req, res, code, rawUrl) {
  const body = await readBody(req);
  const auth = verifyDevice(req, code, body, rawUrl);
  if (auth.error) return sendJson(res, { ok: false, error: auth.error }, 401);

  let p;
  try {
    p = JSON.parse(body || '{}');
  } catch {
    return sendJson(res, { ok: false, error: 'bad json' }, 400);
  }

  // 删键本身幂等，重复 ack 或已被「清空」删掉的 id 都不会出错
  const ids = (p.results || []).map((r) => r && r.id).filter(Boolean);
  if (ids.length) {
    for (const id of ids) delete store['cmd:' + code + ':' + id];
    saveStore();
  }
  if (p.state) putKey('state:' + code, Object.assign({}, p.state, { ackAt: Date.now() }));
  sendJson(res, { ok: true });
}

async function doAdminState(res, code) {
  const bind = getKey('bind:' + code);
  const meta = getMeta(code);
  const state = getKey('state:' + code) || {};
  const safeBind = bind ? Object.assign({}, bind) : null;
  if (safeBind) delete safeBind.token;
  sendJson(res, {
    ok: true,
    bind: safeBind,
    rev: meta.rev,
    cmds: listCmds(code, meta.clearedAt || 0),
    state,
  });
}

async function doAdminCmd(req, res, code) {
  const body = await readBody(req);
  let p;
  try {
    p = JSON.parse(body || '{}');
  } catch {
    return sendJson(res, { ok: false, error: 'bad json' }, 400);
  }
  const op = String(p.op || '');
  if (!SAFE_OPS.has(op) && !DANGER_OPS.has(op)) {
    return sendJson(res, { ok: false, error: 'unknown op' }, 400);
  }

  // 一条指令一个键：多个人同时下发写的是不同的键，谁也盖不掉谁
  const id = 'c_' + randomHex(5);
  const at = Date.now();
  store['cmd:' + code + ':' + id] = {
    id,
    op,
    args: p.args || {},
    danger: DANGER_OPS.has(op),
    at,
  };

  const meta = getMeta(code);
  meta.rev = nextRev(meta.rev, at);
  meta.updatedAt = at;
  store['meta:' + code] = meta;

  trimCmds(code);
  saveStore();
  sendJson(res, { ok: true, id, rev: meta.rev });
}

async function doAdminClear(res, code) {
  const meta = getMeta(code);
  const cmds = listCmds(code, meta.clearedAt || 0);
  for (const c of cmds) delete store['cmd:' + code + ':' + c.id];

  // clearedAt 给并发兜底：清空的瞬间有人又写进一条，靠时间戳判断比「删干净了」可靠
  meta.clearedAt = Date.now();
  meta.rev = nextRev(meta.rev);
  meta.updatedAt = Date.now();
  store['meta:' + code] = meta;
  saveStore();
  sendJson(res, { ok: true, rev: meta.rev });
}

// ---------------- 启动 ----------------

loadStore();

http
  .createServer((req, res) => {
    handle(req, res).catch((e) => {
      try {
        sendJson(res, { ok: false, error: String((e && e.message) || e) }, 500);
      } catch {
        /* 头已经发出去了，没法再回 */
      }
    });
  })
  .listen(PORT, HOST, () => {
    console.log('长辈助手远程服务已启动：http://' + HOST + ':' + PORT);
    console.log('数据目录：' + DATA_DIR);
    console.log('外网入口建议：cloudflared tunnel --url http://' + HOST + ':' + PORT);
  });
