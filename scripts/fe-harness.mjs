// End-to-end check of the web page's upload engine against a running server, without a browser.
// Loads the page's <script> into a Node vm with a tiny DOM stub and an XMLHttpRequest built on
// node:http, then uploads real files — including one whose connection is cut mid-chunk — and
// verifies the bytes the server stored.
//
//   ./gradlew testDebugUnitTest --tests '*ServeForManualTest*' -Dlanbeam.serve=120 &   (serves :18765)
//   node scripts/fe-harness.mjs
import fs from 'node:fs';
import http from 'node:http';
import vm from 'node:vm';
import crypto from 'node:crypto';
import path from 'node:path';

const BASE = process.env.LB_BASE || 'http://127.0.0.1:18765';
const ROOT = process.env.LB_ROOT || 'app/build/serve';
const html = fs.readFileSync('app/src/main/assets/frontend.html', 'utf8');
const script = html.match(/<script>([\s\S]*)<\/script>/)[1];

// ── DOM stub: every element accepts any property; enough for the page to boot ──
function el(id) {
  const cls = new Set();
  return new Proxy({
    id, style: {}, dataset: {}, innerHTML: '', textContent: '', value: '', files: [],
    classList: { add: c => cls.add(c), remove: c => cls.delete(c), contains: c => cls.has(c), toggle: (c, on) => (on ?? !cls.has(c)) ? cls.add(c) : cls.delete(c) },
    setAttribute() {}, addEventListener() {}, appendChild() {}, remove() {}, click() {}, submit() {},
    contains: () => false, closest: () => null,
  }, { get: (t, k) => (k in t ? t[k] : undefined), set: (t, k, v) => { t[k] = v; return true; } });
}
const els = new Map();
const document = {
  getElementById: id => els.get(id) || (els.set(id, el(id)), els.get(id)),
  querySelectorAll: () => [], addEventListener() {}, createElement: () => el('x'), body: el('body'), hidden: false, title: '',
};
const store = new Map();
const localStorage = { getItem: k => store.get(k) ?? null, setItem: (k, v) => store.set(k, String(v)), removeItem: k => store.delete(k) };

// ── XMLHttpRequest over node:http, with an optional fault hook ──
let faultOnce = null; // (url) => bytesToSendBeforeCut | null
class XMLHttpRequest {
  constructor() { this.upload = {}; this.status = 0; this.responseText = ''; this.headers = {}; this.timeout = 0; }
  open(method, url) { this.method = method; this.url = new URL(url, BASE); }
  setRequestHeader(k, v) { this.headers[k] = v; }
  abort() { this.aborted = true; this.req?.destroy(); this.onabort?.(); }
  async send(body) {
    const buf = body ? Buffer.from(await body.arrayBuffer()) : Buffer.alloc(0);
    const cut = faultOnce?.(this.url.href);
    if (cut != null) faultOnce = null;
    const req = this.req = http.request(this.url, { method: this.method, headers: { ...this.headers, 'Content-Length': buf.length } }, res => {
      let data = '';
      res.on('data', d => data += d);
      res.on('end', () => { this.status = res.statusCode; this.statusText = res.statusMessage; this.responseText = data; this.onload?.(); });
    });
    req.on('error', () => { if (!this.aborted) this.onerror?.(); });
    const step = 256 * 1024;
    let sent = 0;
    const limit = cut ?? buf.length;
    while (sent < limit) {
      const n = Math.min(step, limit - sent);
      if (!req.write(buf.subarray(sent, sent + n))) await new Promise(r => req.once('drain', r));
      sent += n;
      this.upload.onprogress?.({ loaded: sent, total: buf.length, lengthComputable: true });
    }
    if (cut != null) { req.destroy(new Error('simulated Wi-Fi drop')); return; }
    req.end();
  }
}

const ctx = vm.createContext({
  document, localStorage, XMLHttpRequest, console, URL, Blob, File, JSON, Math, Date, Promise, Object, Error,
  setTimeout, clearTimeout, setInterval: () => 0, performance,
  requestAnimationFrame: f => setImmediate(f), confirm: () => true,
  fetch: (u, o) => fetch(new URL(u, BASE), o),
  location: new URL(BASE), WebSocket: class { constructor() { this.readyState = 3; } close() {} },
  window: { addEventListener() {}, matchMedia: () => ({ matches: false, addEventListener() {} }) }, navigator: {},
});
vm.runInContext(script, ctx);

function sha(b) { return crypto.createHash('sha256').update(b).digest('hex').slice(0, 16); }
async function waitIdle(ms = 120000) {
  const t0 = Date.now();
  while (Date.now() - t0 < ms) {
    const busy = vm.runInContext("queue.some(q => q.status === 'uploading' || q.status === 'queued')", ctx);
    if (!busy) return;
    await new Promise(r => setTimeout(r, 100));
  }
  throw new Error('timeout');
}

const cases = [
  { name: 'small.txt', size: 1234 },
  { name: 'empty.bin', size: 0 },
  { name: 'exact-chunk.bin', size: 8 * 1024 * 1024 },
  { name: 'big video.mov', size: 50 * 1024 * 1024 + 17, cutAfter: 5 * 1024 * 1024 },
];
let failed = 0;
for (const c of cases) {
  const bytes = crypto.randomBytes(c.size);
  if (c.cutAfter) faultOnce = url => (url.includes('/api/upload/chunk') && url.includes('offset=8388608') ? c.cutAfter : null);
  const t0 = performance.now();
  vm.runInContext('enqueue', ctx)([new File([bytes], c.name, { lastModified: 1700000000000 })]);
  await waitIdle();
  const secs = (performance.now() - t0) / 1000;
  const item = vm.runInContext('queue[queue.length - 1]', ctx);
  const saved = path.join(ROOT, 'Uploads', item.finalName || c.name);
  const ok = item.status === 'done' && fs.existsSync(saved) && sha(fs.readFileSync(saved)) === sha(bytes);
  if (!ok) failed++;
  console.log(`${ok ? 'PASS' : 'FAIL'} ${c.name} ${c.size} B status=${item.status}${item.note ? ' note=' + item.note : ''}` +
    `${c.cutAfter ? ' (connection cut mid-chunk, resumed)' : ''} ${(c.size / 1048576 / secs).toFixed(0)} MB/s`);
}
const leftovers = fs.readdirSync(path.join(ROOT, 'Uploads')).filter(f => f.endsWith('.part'));
if (leftovers.length) { failed++; console.log('FAIL leftover partial files', leftovers); } else console.log('PASS no partial files left');
process.exit(failed ? 1 : 0);
