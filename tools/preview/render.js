const { chromium } = require(process.env.PLAYWRIGHT_PATH || 'playwright');
const http = require('http'), fs = require('fs'), path = require('path');
const root = process.argv[2], dir = process.argv[3], out = process.argv[4], hud = process.argv[5] || '1';
const server = http.createServer((req, res) => {
  const p = path.join(root, decodeURIComponent(req.url.split('?')[0]));
  fs.readFile(p, (err, data) => { if (err) { res.writeHead(404); res.end(); } else { res.writeHead(200, {'Content-Type': {'.wasm': 'application/wasm', '.js': 'text/javascript', '.html': 'text/html'}[path.extname(p)] || 'application/octet-stream'}); res.end(data); } });
}).listen(0, async () => {
  const port = server.address().port;
  const browser = await chromium.launch({ args: ['--use-angle=swiftshader', '--enable-unsafe-swiftshader', '--ignore-gpu-blocklist'] });
  const page = await browser.newPage({ viewport: { width: 1280, height: 640 } });
  page.on('console', m => { const t = m.text(); if (!/feature level|workarounds|FEngine|WebKit|^\s*$/.test(t)) console.log('console:', t.slice(0, 300)); });
  page.on('pageerror', e => console.log('pageerror:', e.message));
  await page.goto(`http://127.0.0.1:${port}/index.html?dir=${dir}&hud=${hud}${process.env.Q || ''}`);
  await page.waitForFunction(() => document.title !== '', null, { timeout: 300000 });
  const title = await page.title();
  console.log(title);
  await page.screenshot({ path: out });
  await browser.close(); server.close();
});
