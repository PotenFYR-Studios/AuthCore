#!/usr/bin/env node
// ============================================================================
// AuthCore host-test player simulator.
//
// Connects to the offline-mode server started by the harness as a REAL protocol
// client (node-minecraft-protocol) and drives the actual authentication flows a
// player would: join -> limbo prompt -> /register -> reconnect -> wrong password
// -> chat violations -> kick -> /login -> free chat. Every step is verified
// against the DEFAULT AuthCore message texts, which the harness lets the server
// generate untouched (only captcha is disabled and the violation limit lowered).
//
// Usage: SIM_PORT=25565 SIM_MC=1.21.1 node sim.js  (see env block below)
// Output: a JSON object on stdout:
//   { status: "PASS"|"FAIL"|"SKIP", note, checks: {simLimbo,simRegister,
//     simWrongPw,simViolKick,simLogin,simChatAfter}, failures }
// ============================================================================
'use strict';

const fs = require('fs');
const mc = require('minecraft-protocol');
const { parse } = require('prismarine-nbt');

const HOST = process.env.SIM_HOST || '127.0.0.1';
const PORT = parseInt(process.env.SIM_PORT || '25565', 10);
const MC_VER = process.env.SIM_MC || '';
const TIMEOUT_MS = parseInt(process.env.SIM_TIMEOUT_MS || '180000', 10);
const USER = process.env.SIM_USER || 'AuthCoreSim';
const PW = process.env.SIM_PASSWORD || 'TestPass1234';
const RESULT_FILE = process.env.SIM_RESULT_FILE || '/tmp/sim-result.json';
const CHECKS_FILE = process.env.SIM_CHECKS_FILE || '/tmp/sim-checks.sh';

// ------------------------------------------------------------------ markers
// Substrings of the DEFAULT AuthCore feedback (Messages.java). The bot matches
// these against the serialized bytes of every inbound packet, so it works
// regardless of whether the feedback is a chat message, title, action bar,
// JSON component or NBT component.
const M_LIMBO = ['You are Not Registered', 'You need to authenticate to play',
  'Authenticate with /login or /register', 'Welcome to the Server'];
const M_REGISTER = ['Registered!', 'Your account has been created'];
const M_WRONG_PW = ['Incorrect Password!', 'Try again with /login'];
const M_LOGIN_OK = ['Logged In!', 'Welcome to the Server'];
const M_VIOL_WARN = ['more violation(s) until you are kicked'];
const M_VIOL_KICK = ['You have been kicked for repeated violations'];

const ALL_MARKERS = [].concat(M_LIMBO, M_REGISTER, M_WRONG_PW, M_LOGIN_OK, M_VIOL_WARN, M_VIOL_KICK);

const results = { status: 'FAIL', note: '', checks: {}, failures: '' };
const found = new Map();
const pendingNbt = [];
let running = true;

function match(text) {
  if (!text) return;
  for (const m of ALL_MARKERS) {
    if (!found.has(m) && text.includes(m)) found.set(m, true);
  }
}

function clearMarkers(markers) {
  for (const m of markers) found.delete(m);
}

function sleep(ms) { return new Promise((r) => setTimeout(r, ms)); }

async function nbtPump() {
  const replacer = (k, v) => (typeof v === 'bigint' ? v.toString() : v);
  while (running) {
    const buf = pendingNbt.shift();
    if (!buf) { await sleep(40); continue; }
    try {
      const parsed = await parse(buf);
      match(JSON.stringify(parsed.data, replacer));
    } catch { /* not NBT, ignore */ }
  }
}

// Scans every inbound packet for the marker texts. Buffers (NBT chat/title
// components on 1.21.2+/26.x) are decoded in the background pump - huge
// buffers (registry sync) are skipped for speed.
function scanPacket(meta, data) {
  if (!data || typeof data !== 'object') return;
  try {
    match(JSON.stringify(data, (k, v) => (Buffer.isBuffer(v) ? '<nbt>' : v)));
  } catch { /* ignore */ }
  for (const v of Object.values(data)) {
    if (Buffer.isBuffer(v) && v.length >= 3 && v.length <= 65536 && v[0] === 0x0a) {
      pendingNbt.push(v);
    }
  }
}

// ------------------------------------------------------------------ client

function connectOnce() {
  return new Promise((resolve, reject) => {
    let settled = false;
    const client = mc.createClient({
      host: HOST,
      port: PORT,
      username: USER,
      auth: 'offline',
      version: MC_VER || false, // explicit version or auto-detect
      skipValidation: true,
    });
    // handle is referenced by the endPromise executor, so it must be constructed
    // before the promise callback can touch it (TDZ-safe: build the object first,
    // attach the endPromise after).
    const handle = {
      client,
      kickReason: '',
      closed: false,
      joinError: null,
      endPromise: null,
      _endRes: null,
    };
    handle.endPromise = new Promise((res) => { handle._endRes = res; });
    client.on('packet', (data, meta) => {
      if (process.env.SIM_DEBUG) {
        try {
          const line = `[${new Date().toISOString()}] ${meta.state}.${meta.name}\n`;
          fs.appendFileSync('/tmp/sim-debug.log', line);
        } catch { /* ignore */ }
      }
      try { scanPacket(meta, data); } catch { /* keep going */ }
    });
    // --- configuration-phase helpers (1.20.5+) -------------------------------
    // The server pings during configuration and waits for the pong; NeoForge also
    // sends its modded-network query and expects a response (an empty mod list is
    // exactly what a vanilla client sends). Without these the config phase hangs and
    // the join never completes - minecraft-protocol does not answer them itself.
    const debugLog = (msg) => {
      if (!process.env.SIM_DEBUG) return;
      try { fs.appendFileSync('/tmp/sim-debug.log', `[sim] ${msg}\n`); } catch { /* ignore */ }
    };
    client.on('ping', (data) => {
      try {
        debugLog(`ping received id=${data.id}, writing pong`);
        client.write('pong', { id: data.id });
      } catch (e) { debugLog('pong write FAILED: ' + e.message); }
    });
    client.on('custom_payload', (data) => {
      try {
        debugLog(`custom_payload channel=${data && data.channel}`);
        if (data && data.channel === 'neoforge:network/query') {
          client.write('custom_payload', {
            channel: 'neoforge:network/query/response',
            data: Buffer.from([0x00]), // empty mod list (VarInt 0)
          });
          debugLog('neoforge query answered');
        }
      } catch (e) { debugLog('custom_payload handling FAILED: ' + e.message); }
    });
    client.on('kick_disconnect', (data) => {
      try {
        handle.kickReason = typeof data === 'string' ? data : JSON.stringify(data);
      } catch { /* ignore */ }
    });
    client.on('error', (err) => {
      if (!settled) {
        settled = true;
        handle.joinError = err;
        reject(err);
      }
    });
    client.on('end', () => {
      handle.closed = true;
      handle._endRes();
      if (!settled) { settled = true; resolve(handle); }
    });
    client.on('login', () => {
      if (!settled) { settled = true; resolve(handle); }
    });
    setTimeout(() => {
      if (!settled) {
        settled = true;
        try { client.end(); } catch { /* ignore */ }
        reject(new Error('join timeout'));
      }
    }, 60000).unref();
  });
}

function waitFor(markerList, handle, timeoutMs) {
  return new Promise((resolve) => {
    const deadline = Date.now() + timeoutMs;
    const tick = () => {
      if (markerList.some((m) => found.has(m))) return resolve(true);
      if (handle.closed) return resolve(false);
      if (Date.now() >= deadline) return resolve(false);
      setTimeout(tick, 200);
    };
    tick();
  });
}

async function sendChat(handle, text) {
  try { handle.client.chat(text); } catch { /* best-effort */ }
}

function waitClosed(handle, timeoutMs) {
  return new Promise((resolve) => {
    if (handle.closed) return resolve(true);
    const t = setTimeout(() => resolve(false), timeoutMs);
    handle.endPromise.then(() => { clearTimeout(t); resolve(true); });
  });
}

// The harness toggles maintenance mode mid-run to verify the join block. A join
// landing inside that window can be kicked at login OR right after it (AuthCore's
// onPlayerJoin disconnect), and the kick reason is not reliably parseable on every
// protocol version. Retry ANY join that did not survive the first moments instead
// of failing the whole simulation.
async function connectWithRetry() {
  for (let attempt = 0; attempt < 10; attempt++) {
    const h = await connectOnce();
    if (!h.closed) {
      // Play phase reached - but a post-login kick (maintenance etc.) can arrive
      // right after. Give it a moment and retry if the connection died.
      await sleep(1200);
      if (!h.closed) return h;
    }
    try { h.client.end(); } catch { /* ignore */ }
    await sleep(1500);
  }
  return connectOnce(); // last attempt as-is
}

// ------------------------------------------------------------------- flow

async function run() {
  const failures = [];
  const checks = { simLimbo: 0, simRegister: 0, simWrongPw: 0, simViolKick: 0, simLogin: 0, simChatAfter: 0 };
  const deadline = Date.now() + TIMEOUT_MS;

  try {
    // --- 1. fresh player joins: held in limbo with the auth prompt ----------
    let h = await connectWithRetry();
    let ok = await waitFor(M_LIMBO, h, 25000);
    checks.simLimbo = ok ? 1 : 0;
    if (!ok) failures.push('sim: no limbo auth prompt on join');
    if (Date.now() > deadline) throw new Error('sim deadline exceeded (step 1)');

    // --- 2. /register creates the account ------------------------------------
    if (ok) {
      await sendChat(h, `/register ${PW} ${PW}`);
      ok = await waitFor(M_REGISTER, h, 15000);
      checks.simRegister = ok ? 1 : 0;
      if (!ok) failures.push('sim: no register success feedback');
    }
    if (Date.now() > deadline) throw new Error('sim deadline exceeded (step 2)');
    try { h.client.end(); } catch { /* ignore */ }
    await waitClosed(h, 8000);

    // --- 3. registered player: wrong password feedback ----------------------
    // M_LIMBO was matched in step 1 - clear it so the reconnect limbo is actually
    // verified again instead of returning instantly from the stale marker.
    clearMarkers(M_LIMBO);
    h = await connectWithRetry();
    ok = await waitFor(M_LIMBO, h, 25000); // login prompt for a registered player
    if (ok) {
      await sendChat(h, `/login wrongpass1234`);
      ok = await waitFor(M_WRONG_PW, h, 15000);
      checks.simWrongPw = ok ? 1 : 0;
      if (!ok) failures.push('sim: no incorrect-password feedback');
    } else {
      failures.push('sim: registered player not put into the login limbo');
    }
    if (Date.now() > deadline) throw new Error('sim deadline exceeded (step 3)');

    // --- 4. lobby chat is restricted: violations until the kick --------------
    if (!h.closed) {
      let warned = false;
      const violDeadline = Date.now() + 45000;
      while (!h.closed && Date.now() < violDeadline) {
        if (found.has(M_VIOL_KICK[0])) break;
        await sendChat(h, 'sim-violation-' + Date.now());
        await sleep(2500);
        if (!warned && found.has(M_VIOL_WARN[0])) warned = true;
      }
      const kicked = h.closed && (found.has(M_VIOL_KICK[0]) || (warned && h.closed));
      checks.simViolKick = kicked ? 1 : 0;
      if (!kicked) {
        failures.push(
          h.closed ? 'sim: disconnected but without the violation-kick reason'
                   : 'sim: repeated lobby chat violations did not kick the player');
      }
      if (Date.now() > deadline) throw new Error('sim deadline exceeded (step 4)');
      await waitClosed(h, 8000);
    } else {
      checks.simViolKick = 0;
      failures.push('sim: lost connection before the violation-kick check');
    }

    // --- 5. correct /login unlocks the player -------------------------------
    clearMarkers(M_LIMBO);
    h = await connectWithRetry();
    ok = await waitFor(M_LIMBO, h, 25000);
    if (ok) {
      await sendChat(h, `/login ${PW}`);
      ok = await waitFor(M_LOGIN_OK, h, 15000);
      checks.simLogin = ok ? 1 : 0;
      if (!ok) failures.push('sim: no login success feedback');
    } else {
      failures.push('sim: player could not rejoin into the login limbo');
    }
    if (Date.now() > deadline) throw new Error('sim deadline exceeded (step 5)');

    // --- 6. after login chat is allowed (no violation warning/kick) -----------
    if (ok && !h.closed) {
      // Step 4 already flooded violation warnings - clear them so a NEW post-login
      // violation is actually detectable. The NBT pump decodes buffers ASYNC and a
      // slow runner can still be draining step-4 buffers long after the kick, so
      // drain + clear repeatedly until no stale buffer can match anymore.
      for (let i = 0; i < 6; i++) {
        pendingNbt.length = 0;
        clearMarkers(M_VIOL_WARN);
        clearMarkers(M_VIOL_KICK);
        await sleep(500);
      }
      pendingNbt.length = 0;
      clearMarkers(M_VIOL_WARN);
      clearMarkers(M_VIOL_KICK);
      await sendChat(h, 'sim-chat-after-login-' + Date.now());
      await sleep(4000);
      const reViolated = found.has(M_VIOL_WARN[0]);
      const reKicked = found.has(M_VIOL_KICK[0]);
      checks.simChatAfter = (!h.closed && !reViolated && !reKicked) ? 1 : 0;
      if (checks.simChatAfter === 0) {
        failures.push(
          h.closed ? 'sim: kicked after a post-login chat'
                   : 'sim: chat after login triggered restriction violations');
      }
    } else {
      checks.simChatAfter = 0;
      failures.push('sim: could not verify post-login chat');
    }
    try { h.client.end(); } catch { /* ignore */ }
    await waitClosed(h, 8000);
  } catch (err) {
    const unsupported = /unsupported|not supported|no data|no protocol|unknown protocol|cannot find.*protocol|protocol.*(?:missing|unknown)|no version|unable to find.*version|no.*data for.*version/i.test(String(err && err.message || err));
    if (unsupported) {
      results.status = 'SKIP';
      results.note = `minecraft-protocol does not provide protocol data for Minecraft ${MC_VER} (${err.message})`;
      results.checks = checks;
      return;
    }
    failures.push('sim: harness error: ' + String(err && err.message || err));
  }

  results.checks = checks;
  if (failures.length > 0) {
    results.status = 'FAIL';
    results.failures = failures.join('; ');
    // Debug aid: append the received-packet trace so CI failures are diagnosable.
    if (process.env.SIM_DEBUG) {
      try {
        const trace = fs.readFileSync('/tmp/sim-debug.log', 'utf8');
        results.failures += ' | packets: ' + trace.split('\n').slice(-40).join(' > ').trim();
      } catch { /* no trace */ }
    }
  } else {
    results.status = 'PASS';
  }
}

function writeResults() {
  // JSON result (consumed by the entrypoint for the report) + a POSIX
  // source-able file with the individual check variables.
  process.stdout.write(JSON.stringify(results) + '\n');
  try {
    fs.writeFileSync(RESULT_FILE, JSON.stringify(results));
    let sh = `SIM_STATUS=${JSON.stringify(results.status)}\n`;
    sh += `SIM_NOTE=${JSON.stringify(results.note || '')}\n`;
    sh += `SIM_FAILURES=${JSON.stringify(results.failures || '')}\n`;
    for (const [k, v] of Object.entries(results.checks)) sh += `chk_${k}=${v ? 1 : 0}\n`;
    fs.writeFileSync(CHECKS_FILE, sh);
  } catch { /* best-effort */ }
}

(async () => {
  running = true;
  nbtPump();
  const timer = setTimeout(() => {
    // Global deadline: NEVER exit silently. Record a FAIL with the check file so
    // the entrypoint sees a failed simulation instead of a silent green PASS.
    results.status = 'FAIL';
    results.failures = 'sim: global timeout - simulation did not finish in time';
    console.error('sim: global timeout');
    writeResults();
    process.exit(2);
  }, TIMEOUT_MS + 30000);
  timer.unref();
  await run();
  running = false;
  await sleep(100); // let the NBT pump finish decoding

  writeResults();
  process.exit(results.status === 'PASS' ? 0 : results.status === 'SKIP' ? 0 : 1);
})();
