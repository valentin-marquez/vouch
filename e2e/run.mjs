// Local end-to-end gate: boots the real dev server, plays through the auth flow with a bot.
//
//   node e2e/run.mjs [fabric|neoforge] [--only <scenario>]
//
// Exit code 0 only if every step passed, so it chains: node e2e/run.mjs && git push origin main
// Needs eula=true in <loader>/run/eula.txt (accept it yourself: https://aka.ms/MinecraftEULA).
import mineflayer from 'mineflayer';
import { spawn } from 'node:child_process';
import { createHmac } from 'node:crypto';
import { existsSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const PORT = 25599;
const MC_VERSION = '1.21.1';
const args = process.argv.slice(2);
const loader = args.find(a => ['fabric', 'neoforge'].includes(a)) ?? 'fabric';
const only = args.includes('--only') ? args[args.indexOf('--only') + 1] : null;
const RUN = `${ROOT}/${loader}/run`;
const LOG = `${ROOT}/e2e/last-server.log`;
const PW = 'Passw0rd-e2e', PW2 = 'N3w-Passw0rd-e2e';

// ---------- TOTP (RFC 6238, SHA1, 6 digits, 30 s) ----------
const b32 = s => {
  const A = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';
  const bits = [...s.toUpperCase()].map(c => A.indexOf(c).toString(2).padStart(5, '0')).join('');
  return Buffer.from(bits.match(/.{8}/g).map(b => parseInt(b, 2)));
};
const totp = secret => {
  const c = Buffer.alloc(8);
  c.writeBigUInt64BE(BigInt(Math.floor(Date.now() / 30000)));
  const h = createHmac('sha1', b32(secret)).update(c).digest();
  const o = h[19] & 15;
  const n = ((h[o] & 0x7f) << 24) | (h[o + 1] << 16) | (h[o + 2] << 8) | h[o + 3];
  return String(n % 1e6).padStart(6, '0');
};

// ---------- server ----------
function setProps(file, kv) {
  let s = existsSync(file) ? readFileSync(file, 'utf8') : '';
  for (const [k, v] of Object.entries(kv)) {
    const re = new RegExp(`^${k}=.*$`, 'm');
    s = re.test(s) ? s.replace(re, `${k}=${v}`) : s + `\n${k}=${v}`;
  }
  writeFileSync(file, s);
}

function prepare() {
  const eula = `${RUN}/eula.txt`;
  if (!existsSync(eula) || !/eula=true/.test(readFileSync(eula, 'utf8'))) {
    console.error(`EULA not accepted. Read https://aka.ms/MinecraftEULA, then set eula=true in ${eula}`);
    process.exit(2);
  }
  // Fresh state per scenario: no accounts, no sessions, no world.
  for (const d of ['config/vouch', 'vouch', 'world']) rmSync(`${RUN}/${d}`, { recursive: true, force: true });
  setProps(`${RUN}/server.properties`, {
    'online-mode': 'false', 'server-port': PORT, 'view-distance': 2, 'simulation-distance': 2,
    'level-type': 'minecraft\\:flat', 'generate-structures': 'false', 'spawn-protection': 0,
    'enable-command-block': 'false', 'motd': 'vouch-e2e',
  });
}

function startServer(env) {
  prepare();
  writeFileSync(LOG, '');
  const proc = spawn('./gradlew', [`:${loader}:runServer`, '--offline', '--console=plain'], {
    cwd: ROOT, env: { ...process.env, ...env }, detached: true,
  });
  const append = d => writeFileSync(LOG, d, { flag: 'a' });
  proc.stdout.on('data', append);
  proc.stderr.on('data', append);
  const ready = new Promise((ok, fail) => {
    const t = setTimeout(() => fail(new Error(`server not ready in 4 min, see ${LOG}`)), 240000);
    proc.stdout.on('data', d => /Done \(\d/.test(d) && (clearTimeout(t), ok()));
    proc.on('exit', c => (clearTimeout(t), fail(new Error(`server exited early (${c}), see ${LOG}`))));
  });
  return { proc, ready };
}

async function stopServer({ proc }) {
  const gone = new Promise(r => proc.once('exit', r));
  try { process.kill(-proc.pid, 'SIGTERM'); } catch {}
  const killer = setTimeout(() => { try { process.kill(-proc.pid, 'SIGKILL'); } catch {} }, 20000);
  await gone;
  clearTimeout(killer);
}

// ---------- bot ----------
const BAD = /Unknown or incomplete command/i; // what issue #9 looked like to the player

class Player {
  msgs = []; cursor = 0; kicked = null; ended = false;
  constructor(name) { this.name = name; }

  async join() {
    this.bot = mineflayer.createBot({
      host: '127.0.0.1', port: PORT, username: this.name, auth: 'offline', version: MC_VERSION, hideErrors: true,
    });
    this.bot.on('messagestr', m => this.msgs.push(m));
    this.bot.on('kicked', r => (this.kicked = typeof r === 'string' ? r : JSON.stringify(r)));
    this.bot.on('end', () => (this.ended = true));
    await new Promise((ok, fail) => {
      const t = setTimeout(() => fail(new Error('bot did not spawn in 30s')), 30000);
      this.bot.once('spawn', () => (clearTimeout(t), ok()));
      this.bot.once('error', e => (clearTimeout(t), fail(e)));
    });
    return this;
  }

  say(text) { this.bot.chat(text); }

  async expect(re, ms = 10000) {
    const end = Date.now() + ms;
    while (Date.now() < end) {
      for (; this.cursor < this.msgs.length; this.cursor++) {
        const m = this.msgs[this.cursor];
        if (BAD.test(m) && !re.test(m)) throw new Error(`got "${m}" while waiting for ${re}`);
        if (re.test(m)) { this.cursor++; return m; }
      }
      await new Promise(r => setTimeout(r, 100));
    }
    throw new Error(`timed out waiting for ${re}; last messages: ${JSON.stringify(this.msgs.slice(-5))}`);
  }

  async expectKick(re, ms = 10000) {
    const end = Date.now() + ms;
    while (Date.now() < end) {
      if (this.kicked) {
        if (!re.test(this.kicked)) throw new Error(`kicked with ${this.kicked}, wanted ${re}`);
        return;
      }
      await new Promise(r => setTimeout(r, 100));
    }
    throw new Error(`not kicked within ${ms}ms (wanted ${re})`);
  }

  async leave() {
    if (!this.ended) { this.bot.quit(); await new Promise(r => setTimeout(r, 500)); }
  }
}

// ---------- scenarios ----------
const SECRET = /Manual entry: ([A-Z2-7]+)/;

const scenarios = {
  password_only: {
    env: { VOUCH_AUTH_MODE: 'password_only', VOUCH_SESSION_PERSISTENCE: 'false' },
    async run(step, join) {
      let p;
      await step('join is greeted with the register prompt', async () => {
        p = await join('E2eA'); await p.expect(/Please register with/);
      });
      await step('jail blocks chat', async () => { p.say('hello'); await p.expect(/cannot chat until you authenticate/); });
      await step('jail blocks other commands', async () => { p.say('/gamemode creative'); await p.expect(/only use authentication commands/); });
      await step('register rejects mismatched passwords', async () => { p.say(`/register ${PW} nope`); await p.expect(/do not match/); });
      await step('register rejects a short password', async () => { p.say('/register a a'); await p.expect(/too short/); });
      await step('register succeeds', async () => { p.say(`/register ${PW} ${PW}`); await p.expect(/Registration successful/); });
      await step('rejoin asks for login', async () => {
        await p.leave(); p = await join('E2eA'); await p.expect(/Welcome back! Please login/);
      });
      await step('wrong password is refused', async () => { p.say('/login not-the-password'); await p.expect(/Wrong password/); });
      await step('login succeeds', async () => { p.say(`/login ${PW}`); await p.expect(/Login successful/); });
      await step('password change works', async () => { p.say(`/vouch password ${PW} ${PW2}`); await p.expect(/Password changed/); });
      await step('logout kicks', async () => { p.say('/logout'); await p.expectKick(/logged out/); });
      await step('new password logs in', async () => {
        p = await join('E2eA'); p.say(`/login ${PW2}`); await p.expect(/Login successful/);
      });
      await p.leave();
    },
  },

  // Issue #9: a player who owes a 2FA code must be able to send it.
  '2fa_only': {
    env: { VOUCH_AUTH_MODE: '2fa_only', VOUCH_SESSION_PERSISTENCE: 'false' },
    async run(step, join) {
      let p, secret;
      await step('join is greeted with the 2FA register prompt', async () => {
        p = await join('E2eB'); await p.expect(/use \/register to set up 2FA/i);
      });
      await step('register hands out a TOTP secret', async () => {
        p.say('/register'); secret = SECRET.exec(await p.expect(SECRET))[1];
      });
      await step('/2fa verify is reachable while unauthenticated', async () => {
        p.say(`/2fa verify ${totp(secret)}`); await p.expect(/Registration successful/);
      });
      await step('rejoin asks for a code', async () => {
        await p.leave(); p = await join('E2eB'); await p.expect(/Use: \/login <code>/);
      });
      await step('a wrong code is refused', async () => { p.say('/login 000000'); await p.expect(/Invalid 2FA code|Wrong/i); });
      await step('login with a code succeeds', async () => { p.say(`/login ${totp(secret)}`); await p.expect(/Login successful/); });
      await p.leave();
    },
  },

  password_optional_2fa: {
    env: { VOUCH_AUTH_MODE: 'password_optional_2fa', VOUCH_SESSION_PERSISTENCE: 'false' },
    async run(step, join) {
      let p, secret;
      await step('register with a password', async () => {
        p = await join('E2eC'); await p.expect(/Please register with/);
        p.say(`/register ${PW} ${PW}`); await p.expect(/Registration successful/);
      });
      await step('enable 2FA', async () => {
        p.say('/2fa setup'); secret = SECRET.exec(await p.expect(SECRET))[1];
        p.say(`/2fa verify ${totp(secret)}`); await p.expect(/2FA has been enabled/);
      });
      await step('rejoin: password alone is not enough', async () => {
        await p.leave(); p = await join('E2eC'); await p.expect(/Welcome back! Please login/);
        p.say(`/login ${PW}`); await p.expect(/2FA is enabled/);
      });
      await step('second factor via /vouch 2fa verify completes the login', async () => {
        p.say(`/vouch 2fa verify ${totp(secret)}`); await p.expect(/Login successful|verified/i);
      });
      await p.leave();
    },
  },
};

// ---------- main ----------
let failed = 0;
for (const [name, sc] of Object.entries(scenarios)) {
  if (only && only !== name) continue;
  console.log(`\n# ${loader} / ${name}`);
  const server = startServer(sc.env);
  const players = [];
  const join = async n => { const p = await new Player(n).join(); players.push(p); return p; };
  const step = async (label, fn) => {
    try { await fn(); console.log(`  PASS  ${label}`); }
    catch (e) { console.log(`  FAIL  ${label}\n        ${e.message}`); failed++; throw e; }
  };
  try {
    await server.ready;
    await sc.run(step, join);
  } catch (e) {
    if (!/^(got|timed out|not kicked|kicked|bot did)/.test(e.message)) { console.log(`  ERROR ${e.message}`); failed++; }
  } finally {
    await Promise.all(players.map(p => p.leave().catch(() => {})));
    await stopServer(server);
  }
}
console.log(failed ? `\n${failed} failure(s). Server log: ${LOG}` : '\nAll scenarios passed.');
process.exit(failed ? 1 : 0);
