// cdp-capture.js — dev-only ws capture for recording real CDP frames into NDJSON fixtures.
// Uses Node 21+ global WebSocket (no npm deps). Run: node cdp-capture.js <outFile> <pageTargetId>
// Prereq: forward tcp:9222 to the device's webview socket first (use the app's Port Forwarding page,
//   or: adb -s <serial> forward tcp:9222 localabstract:webview_devtools_remote_<pid>)
// Then get the pageTargetId: node -e 'const w=new WebSocket("ws://localhost:9222/devtools/browser"); w.addEventListener("open",()=>w.send(JSON.stringify({id:1,method:"Target.getTargets"}))); w.addEventListener("message",f=>{const m=JSON.parse(f.data); if(m.id===1){const p=(m.result.targetInfos||[]).find(t=>t.type==="page"); console.log(p&&p.targetId); process.exit(0)}})'
// NOT shipped — a dev tool for fixture recording per Task 2.
const fs = require('fs');
const outFile = process.argv[2];
const targetId = process.argv[3];
if (!outFile || !targetId) { console.error('usage: node cdp-capture.js <outFile> <pageTargetId>'); process.exit(1); }
fs.writeFileSync(outFile, ''); // truncate
const ws = new WebSocket('ws://localhost:9222/devtools/page/' + targetId);
ws.addEventListener('open', () => {
  ['Runtime.enable','Page.enable','Network.enable','Log.enable'].forEach((m,i) =>
    ws.send(JSON.stringify({id: i+1, method: m})));
  console.error('capturing to ' + outFile + ' — exercise the webview, Ctrl+C to stop');
});
ws.addEventListener('message', (e) => fs.appendFileSync(outFile, e.data + '\n'));
ws.addEventListener('error', (ev) => { console.error('ws error:', ev.message || ev); process.exit(2); });
ws.addEventListener('close', () => console.error('ws closed'));
