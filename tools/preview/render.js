const { chromium } = require(process.env.PLAYWRIGHT_PATH || 'playwright');
const http = require('http'), fs = require('fs'), path = require('path');
const root = process.argv[2], dir = process.argv[3], out = process.argv[4], hud = process.argv[5] || '1';
const server = http.createServer((req, res) => {
  const p = path.join(root, decodeURIComponent(req.url.split('?')[0]));
  fs.readFile(p, (err, data) => { if (err) { res.writeHead(404); res.end(); } else { res.writeHead(200); res.end(data); } });
}).listen(0, async () => {
  const port = server.address().port;
  const browser = await chromium.launch({ args: ['--use-angle=swiftshader', '--enable-unsafe-swiftshader', '--ignore-gpu-blocklist'] });
  const page = await browser.newPage({ viewport: { width: 1280, height: 640 } });
  page.on('console', m => console.log('console:', m.text()));
  await page.goto(`http://127.0.0.1:${port}/index.html?dir=${dir}&hud=${hud}`);
  await page.waitForFunction(() => document.title !== '', null, { timeout: 120000 });
  const title = await page.title();
  console.log(title);
  await page.screenshot({ path: out });
  await browser.close(); server.close();
});
