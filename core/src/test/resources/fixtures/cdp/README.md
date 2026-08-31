# CDP fixtures — provenance

Source: real device recording. Device: VIDAA_TV (hisense), Android 9 (SDK 28), build id PPR2.180905.006.A1 dev-keys.
Serial: 192.168.50.9:5555 (wireless adb). WebView app: hotel IPTV HTML page (`http://182.138.23.99:8080/sc-hotel/page/hotel2024/home.html`, title "标准新模板2024首页").
Recorded: 2026-08-31.

Capture setup:
1. Launch the WebView app on the device so a `webview_devtools_remote_<pid>` socket appears (here: `webview_devtools_remote_15074`).
2. `adb -s 192.168.50.9:5555 forward tcp:9222 localabstract:webview_devtools_remote_15074`
3. Get the page targetId via `ws://localhost:9222/devtools/browser` → `Target.getTargets` (here: `6B2C732D283DFB37F6661F608DAF6EA9`).
4. `node docs/superpowers/tools/cdp-capture.js core/src/test/resources/fixtures/cdp/real_session.ndjson 6B2C732D283DFB37F6661F608DAF6EA9`
5. Exercise the app (navigate / click) for ~40s; Ctrl+C to stop.

Files:
- `real_session.ndjson` — full raw session (every inbound ws frame, one JSON per line). Used as a regression corpus.
- `console_apicalled.ndjson` — 3 representative `Runtime.consoleAPICalled` frames.
- `network_lifecycle.ndjson` — 8 frames: 2× `Network.requestWillBeSent` + 2× `Network.responseReceived` + 2× `Network.loadingFinished` + 2× `Network.loadingFailed`.

NOT captured this session (no such events occurred): `Runtime.exceptionThrown`, `Log.entryAdded`. Those CdpEventParser branches are covered by inline protocol-spec unit tests in `CdpEventParserTest` (not by a real-fixture regression). To add real fixtures for them, re-run the capture while triggering a JS exception / a console.error — out of scope for this recording.

Reproduce: connect the device, launch the WebView app, forward 9222, run the capture script, exercise the app.
