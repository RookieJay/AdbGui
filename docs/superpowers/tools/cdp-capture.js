// cdp-capture.js — dev-only ws capture for recording real CDP frames into NDJSON fixtures.
// Run: node cdp-capture.js <outFile> <pageTargetId>
// Prereq: forward tcp:9222 to the device's webview socket first (use the app's Port Forwarding page).
// Requires: `ws` npm package (npm install ws) — or use `websocat` if available.
// NOT shipped — a dev tool for fixture recording per Task 2.
const WebSocket = require('ws');
const fs = require('fs');
const outFile = process.argv[2];
const targetId = process.argv[3];
if (!outFile || !targetId) { console.error('usage: node cdp-capture.js <outFile> <pageTargetId>'); process.exit(1); }
fs.writeFileSync(outFile, ''); // truncate
const ws = new WebSocket('ws://localhost:9222/devtools/page/' + targetId);
ws.on('open', () => {
  ['Runtime.enable','Page.enable','Network.enable','Log.enable'].forEach((m,i) =>
    ws.send(JSON.stringify({id: i+1, method: m})));
  console.error('capturing to ' + outFile + ' — exercise the webview, Ctrl+C to stop');
});
ws.on('message', (frame) => fs.appendFileSync(outFile, frame.toString() + '\n'));
ws.on('error', (e) => { console.error('ws error:', e.message); process.exit(2); });
ws.on('close', () => console.error('ws closed'));
