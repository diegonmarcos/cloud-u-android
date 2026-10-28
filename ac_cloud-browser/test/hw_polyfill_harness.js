// #1225 Web Serial / WebUSB / Web NFC polyfill contract test: runs assets/browser/web_hardware.js against a fake page environment and a
// fake NATIVE side that implements web-hardware-contract.json over a fake port, a fake USB device and a fake tag, and loads
// test/web_hardware_page.html (a local page whose clicks call navigator.serial.requestPort, navigator.usb.requestDevice and
// NDEFReader.scan / write). Every message the polyfill sends and every reply the fake native builds is checked against the contract's `ops`.
// usage: node hw_polyfill_harness.js <polyfill.js> <contract.json> <page.html>   -> one `ok|bad <label>` line per assertion.
const fs = require('fs'), vm = require('vm');
const [polyfillPath, contractPath, pagePath] = process.argv.slice(2);
const SRC = fs.readFileSync(polyfillPath, 'utf8');
const CONTRACT = JSON.parse(fs.readFileSync(contractPath, 'utf8'));
const PAGE = fs.readFileSync(pagePath, 'utf8');
const out = [];
const check = (c, l) => out.push((c ? 'ok ' : 'bad ') + l);

// ---- contract type checker ----
function typeOk(t, v) {
  if (t.endsWith('[]')) return Array.isArray(v) && v.every(x => typeOk(t.slice(0, -2), x));
  if (t.includes('|')) return t.split('|').includes(v);
  switch (t) {
    case 'int': return Number.isInteger(v);
    case 'string': return typeof v === 'string';
    case 'bool': return typeof v === 'boolean';
    case 'hex': return typeof v === 'string' && /^([0-9a-f]{2})*$/.test(v);
    case 'id': return typeof v === 'string' && v.length > 0;
  }
  if (CONTRACT.shapes[t]) return shapeOk(CONTRACT.shapes[t], v);
  return false;
}
function shapeOk(shape, obj) {
  if (!obj || typeof obj !== 'object') return false;
  const keys = Object.keys(shape);
  for (const k of keys) { const opt = k.endsWith('?'), n = opt ? k.slice(0, -1) : k; if (obj[n] === undefined ? !opt : !typeOk(shape[k], obj[n])) return false; }
  const known = new Set(keys.map(k => k.replace(/\?$/, '')));
  return Object.keys(obj).every(k => known.has(k));
}

// ---- the fake native side: contract-driven, over a fake port / device / tag ----
const problems = [];
const DEVINFO = {
  id: 'dev-1', vendorId: 0x1209, productId: 0xabcd, deviceClass: 255, deviceSubclass: 0, deviceProtocol: 0, productName: 'Fake Dev Board',
  configurations: [{ configurationValue: 1, interfaces: [{ interfaceNumber: 0, alternates: [
    { alternateSetting: 0, interfaceClass: 255, interfaceSubclass: 0, interfaceProtocol: 0, endpoints: [{ endpointNumber: 1, direction: 'in', type: 'bulk', packetSize: 64 }, { endpointNumber: 1, direction: 'out', type: 'bulk', packetSize: 64 }] }] }] }],
};
const native = {
  sent: [], win: null, portOpen: false, devOpen: false, claimed: false, scanning: false, written: null,
  post(text) {
    const m = JSON.parse(text);
    this.sent.push(m);
    const op = CONTRACT.ops[m.op];
    if (!op) { problems.push('unknown op ' + m.op); return; }
    if (m.v !== CONTRACT.version || !Number.isInteger(m.id) || typeof m.args !== 'object') problems.push('bad envelope ' + text);
    const extra = Object.keys(m).filter(k => !['v', 'id', 'op', 'args', 'gesture'].includes(k));
    if (extra.length) problems.push('envelope has ' + extra.join(','));
    if (!shapeOk(op.args, m.args)) problems.push('args of ' + m.op + ' break the contract: ' + JSON.stringify(m.args));
    if (op.gesture && m.gesture !== true) { this.reply(m, null, { name: 'SecurityError', message: 'Must be handling a user gesture to show a permission request.' }); return; }
    setImmediate(() => this.run(m));
  },
  reply(m, result, error) {
    const body = error ? { v: 1, id: m.id, ok: false, error } : { v: 1, id: m.id, ok: true, result };
    if (!error && !shapeOk(CONTRACT.ops[m.op].result, result)) problems.push('result of ' + m.op + ' breaks the contract: ' + JSON.stringify(result));
    if (error && !CONTRACT.errors.includes(error.name)) problems.push('error name ' + error.name + ' is not in the contract');
    setImmediate(() => this.win.__cloudHw.recv(JSON.stringify(body)));
  },
  event(e) {
    const spec = CONTRACT.events[e.event];
    if (!spec) problems.push('unknown event ' + e.event);
    else if (!shapeOk(spec, Object.fromEntries(Object.entries(e).filter(([k]) => k !== 'event')))) problems.push('event ' + e.event + ' breaks the contract: ' + JSON.stringify(e));
    setImmediate(() => this.win.__cloudHw.recv(JSON.stringify(Object.assign({ v: 1 }, e))));
  },
  run(m) {
    const a = m.args;
    switch (m.op) {
      case 'serial.getPorts': return this.reply(m, { ports: [] });
      case 'serial.requestPort': return this.reply(m, { port: { id: 'port-1', usbVendorId: 0x2341, usbProductId: 0x43 } });
      case 'serial.open': this.portOpen = true; this.openArgs = a; return this.reply(m, {});
      case 'serial.write': this.reply(m, {}); setTimeout(() => this.event({ event: 'serial.data', port: a.port, value: a.value }), 2); return;   // the fake device echoes
      case 'serial.close': this.portOpen = false; return this.reply(m, {});
      case 'serial.setSignals': this.signals = a; return this.reply(m, {});
      case 'serial.forget': return this.reply(m, {});
      case 'usb.getDevices': return this.reply(m, { devices: [] });
      case 'usb.requestDevice': return this.reply(m, { device: DEVINFO });
      case 'usb.open': this.devOpen = true; return this.reply(m, {});
      case 'usb.close': this.devOpen = false; this.claimed = false; return this.reply(m, {});
      case 'usb.selectConfiguration': return this.reply(m, {});
      case 'usb.claimInterface': this.claimed = true; return this.reply(m, {});
      case 'usb.releaseInterface': this.claimed = false; return this.reply(m, {});
      case 'usb.selectAlternateInterface': return this.reply(m, {});
      case 'usb.transferOut': this.usbOut = a; return this.reply(m, { status: 'ok', bytesWritten: a.data.length / 2 });
      case 'usb.transferIn': return this.reply(m, { status: 'ok', data: '0a0b0c' });
      case 'usb.controlTransferIn': return this.reply(m, { status: 'ok', data: '1201' });
      case 'usb.controlTransferOut': return this.reply(m, { status: 'ok', bytesWritten: a.data.length / 2 });
      case 'usb.clearHalt': case 'usb.forget': return this.reply(m, {});
      case 'usb.reset': return this.reply(m, null, { name: 'NotSupportedError', message: 'no reset' });
      case 'nfc.scan':
        this.scanning = true; this.reply(m, {});
        setTimeout(() => this.event({ event: 'nfc.reading', message: { serialNumber: '04:7a:2b:11', records: [{ recordType: 'text', data: '68656c6c6f', encoding: 'utf-8', lang: 'en' }] } }), 5);
        return;
      case 'nfc.stop': this.scanning = false; return this.reply(m, {});
      case 'nfc.write': this.written = a; return this.reply(m, {});
    }
  },
};

// ---- the fake page environment ----
function makeWindow({ secure = true, topIsSelf = true, existing = {}, userActivation } = {}) {
  const listeners = {};
  const win = {
    isSecureContext: secure, navigator: Object.assign({}, existing),
    addEventListener(t, fn) { (listeners[t] = listeners[t] || []).push(fn); },
    AndroidWebHardware: { post: (t) => native.post(t) },
    Event, EventTarget, DOMException, TypeError, Promise, JSON, ArrayBuffer, Uint8Array, DataView, Object, Array, Date, Math, Number, String, parseInt, setTimeout, console,
    ReadableStream, WritableStream, TextEncoder, TextDecoder, AbortController,
    click() { (listeners.click || []).forEach(fn => fn({ isTrusted: true })); },
    untrustedClick() { (listeners.click || []).forEach(fn => fn({ isTrusted: false })); },
  };
  if (userActivation) win.navigator.userActivation = userActivation;
  win.window = win; win.top = topIsSelf ? win : { get location() { throw new DOMException('blocked', 'SecurityError'); } };
  native.win = win;
  return win;
}
function load(win) { vm.runInNewContext(SRC, win, { filename: 'web_hardware.js' }); return win; }
const tick = (ms = 20) => new Promise(r => setTimeout(r, ms));
const settle = async (n = 8) => { for (let i = 0; i < n; i++) await tick(5); };
const rejects = async (p) => { try { await p; return null; } catch (e) { return e; } };

(async () => {
  // E1 guards: where the polyfill must not install
  { const w = load(makeWindow({ secure: false })); check(w.navigator.serial === undefined && w.navigator.usb === undefined && w.NDEFReader === undefined, 'E1 an insecure context gets none of the three (Chrome rule)'); }
  { const w = load(makeWindow({ topIsSelf: false })); check(w.navigator.serial === undefined && w.navigator.usb === undefined && w.NDEFReader === undefined, 'E1 a cross-origin frame gets none of the three'); }
  { const mine = { native: true }; const w = load(makeWindow({ existing: { usb: mine } })); check(w.navigator.usb === mine && w.navigator.serial !== undefined, 'E1 a WebView that has its own WebUSB keeps it and still gains the others'); }
  { const w = load(makeWindow()); const first = w.navigator.usb; vm.runInNewContext(SRC, w); check(w.navigator.usb === first, 'E1 a second injection is a no-op (document start + page start both run)'); }

  // E2 the surface
  const w = load(makeWindow());
  const serial = w.navigator.serial, usb = w.navigator.usb;
  check(typeof serial.requestPort === 'function' && typeof serial.getPorts === 'function' && serial instanceof w.EventTarget, 'E2 navigator.serial has requestPort / getPorts');
  check(typeof usb.requestDevice === 'function' && typeof usb.getDevices === 'function' && usb instanceof w.EventTarget, 'E2 navigator.usb has requestDevice / getDevices');
  check(typeof w.NDEFReader === 'function' && typeof w.NDEFMessage === 'function' && typeof w.NDEFRecord === 'function' && typeof w.Serial === 'function' && typeof w.USBDevice === 'function', 'E2 the interface objects are exposed');
  check(JSON.stringify(Object.keys(await serial.getPorts())) === '[]', 'E2 getPorts answers from the native side (none granted)');

  // E3 rules in the page: arguments and gesture
  check((await rejects(usb.requestDevice())) instanceof w.TypeError, 'E3 usb.requestDevice without filters is a TypeError');
  check((await rejects(usb.requestDevice({ filters: [{ productId: 1 }] }))) instanceof w.TypeError, 'E3 a productId filter without a vendorId is a TypeError');
  check((await rejects(usb.requestDevice({ filters: [{ protocolCode: 1 }] }))) instanceof w.TypeError, 'E3 a protocolCode filter without a subclassCode is a TypeError');
  check((await rejects(serial.requestPort({ filters: [{ usbProductId: 1 }] }))) instanceof w.TypeError, 'E3 a serial filter without usbVendorId is a TypeError');
  const before = native.sent.length;
  const s1 = await rejects(serial.requestPort()), s2 = await rejects(usb.requestDevice({ filters: [] }));
  check(s1 && s1.name === 'SecurityError' && s2 && s2.name === 'SecurityError' && native.sent.length === before, 'E3 without a user gesture requestPort / requestDevice fail with SecurityError and never reach the native side');
  w.untrustedClick();
  check((await rejects(serial.requestPort())) !== null, 'E3 a script-made (untrusted) click is not a gesture');

  // E4 the local page: the three flows of a real site
  const pageW = load(makeWindow());
  const script = PAGE.match(/<script>([\s\S]*?)<\/script>/)[1];
  const doc = { _l: {}, getElementById: (id) => ({ addEventListener: (t, fn) => { doc._l[id] = fn; } }) };
  vm.runInNewContext(script, Object.assign(pageW, { document: doc }));
  const press = async (id) => { pageW.click(); doc._l[id](); for (let i = 0; i < 100 && !pageW.__result.done[id]; i++) await tick(10); };
  await press('serial');
  const steps = pageW.__result.steps;
  check(pageW.__result.done.serial === true, 'E4 the serial script ran to its end');
  check(steps.includes('info:{"usbVendorId":9025,"usbProductId":67}'), 'E4 getInfo returns the vendor and product ids');
  check(steps.includes('open:true'), 'E4 open gives a readable and a writable stream');
  check(steps.includes('rx:hi'), 'E4 bytes written reach the device as hex and the device\'s bytes arrive on the readable stream');
  check(steps.includes('closed:true'), 'E4 close ends the streams');
  const open = native.sent.find(m => m.op === 'serial.open');
  check(open.args.baudRate === 115200 && open.args.dataBits === 8 && open.args.stopBits === 1 && open.args.parity === 'none' && open.args.flowControl === 'none', 'E4 open sends the line settings with Chrome\'s defaults filled in');
  check(native.signals && native.signals.dataTerminalReady === true && Object.keys(native.signals).length === 2, 'E4 setSignals sends only the signals the page named');
  const req = native.sent.find(m => m.op === 'serial.requestPort');
  check(req.gesture === true && req.args.filters[0].usbVendorId === 0x2341, 'E4 requestPort is sent with gesture:true and the filter');

  await press('usb');
  check(pageW.__result.done.usb === true, 'E4 the usb script ran to its end');
  check(steps.includes('usb:Fake Dev Board:1209'), 'E4 requestDevice resolves a USBDevice with its descriptors');
  check(steps.includes('opened:true,config:1'), 'E4 open selects the only configuration (Chrome shows the active one)');
  check(steps.includes('claimed:true'), 'E4 claimInterface is reflected on the interface object');
  check(steps.includes('out:3:ok') && native.usbOut.data === 'c0ffee' && native.usbOut.endpointNumber === 1, 'E4 transferOut sends the bytes as hex to the endpoint');
  check(steps.includes('in:10,11,12'), 'E4 transferIn returns a DataView of the bytes');
  check(steps.includes('ctl:18,1'), 'E4 controlTransferIn returns the data of the setup packet');
  check(steps.includes('closed:false'), 'E4 close leaves the device closed');

  await press('nfc');
  check(pageW.__result.done.nfc === true, 'E4 the nfc script ran to its end');
  check(steps.includes('scanning') && steps.includes('written'), 'E4 scan and write resolve');
  check(steps.includes('tag:04:7a:2b:11:text:hello:en'), 'E4 a native reading event becomes a reading event with a text record');
  check(native.written.overwrite === true && native.written.records[0].recordType === 'text' && native.written.records[0].data === '68656c6c6f' && native.written.records[0].lang === 'en', 'E4 write(string) is sent as one utf-8 text record');
  check(native.sent.every(m => m.op in CONTRACT.ops), 'E4 every message is an op of the contract');

  // E5 more of the surface
  const dev = pageW.__result.device;
  check((await rejects(dev.transferIn(1, 8))).name === 'InvalidStateError', 'E5 a transfer on a closed device is InvalidStateError in the page');
  native.sent.length = 0;
  await dev.open();
  check((await rejects(dev.controlTransferIn({ requestType: 'nope', recipient: 'device', request: 0, value: 0, index: 0 }, 1))) instanceof w.TypeError, 'E5 a bad control setup is a TypeError');
  check((await rejects(dev.transferOut(1, 'text'))) instanceof w.TypeError, 'E5 transferOut of a string is a TypeError');
  check((await rejects(dev.isochronousTransferIn(1, [1]))).name === 'NotSupportedError', 'E5 isochronous transfers are reported unavailable');
  check((await rejects(dev.reset())).name === 'NotSupportedError', 'E5 a native refusal arrives as its error');
  const port = pageW.__result.port;
  check((await rejects(port.getSignals())).name === 'NotSupportedError', 'E5 getSignals is reported unavailable, not faked');
  check((await rejects(port.open({ baudRate: 0 }))) instanceof w.TypeError, 'E5 a port needs a positive baudRate');
  check((await rejects(port.open({ baudRate: 9600, dataBits: 5 }))) instanceof w.TypeError, 'E5 dataBits other than 7 / 8 is a TypeError');
  check((await rejects(pageW.__result.ndef.makeReadOnly())).name === 'NotSupportedError', 'E5 makeReadOnly is reported unavailable');
  const ac = new w.AbortController(); ac.abort();
  check((await rejects(pageW.__result.ndef.scan({ signal: ac.signal }))).name === 'AbortError', 'E5 an aborted signal rejects scan with AbortError before the native side is asked');
  check((await rejects(pageW.__result.ndef.write({ records: [{ recordType: 'text', data: 'x', encoding: 'utf-16' }] }))).name === 'NotSupportedError', 'E5 a text record that is not utf-8 is refused in the page');
  await pageW.__result.ndef.write({ records: [{ recordType: 'mime', mediaType: 'application/json', data: new w.Uint8Array([123, 125]) }, { recordType: 'url', data: 'https://example.test' }] }, { overwrite: false });
  check(native.written.overwrite === false && native.written.records[0].mediaType === 'application/json' && native.written.records[0].data === '7b7d' && native.written.records[1].data === Buffer.from('https://example.test').toString('hex'), 'E5 write(NDEFMessageInit) sends mime and url records as hex, overwrite:false');

  // E6 the port's streams (open again): locked close, native disconnect
  await port.open({ baudRate: 9600 });
  const reader = port.readable.getReader();
  check((await rejects(port.close())).name === 'InvalidStateError', 'E6 close while a reader holds the stream is InvalidStateError');
  let gone = 0; port.addEventListener('disconnect', () => gone++); pageW.navigator.serial.addEventListener('disconnect', (e) => { if (e.port === port) gone++; });
  native.event({ event: 'serial.disconnect', port: 'port-1' });
  await settle();
  check(gone === 2 && port.connected === false && port.readable === null, 'E6 a native disconnect fires disconnect on the port and on navigator.serial, and the streams go');
  check((await rejects(reader.read())).name === 'NetworkError', 'E6 a pending read fails with NetworkError when the device is lost');

  // E7 usb events
  let usbGone = 0; pageW.navigator.usb.addEventListener('disconnect', (e) => { if (e.device === dev) usbGone++; });
  let usbBack = null; pageW.navigator.usb.addEventListener('connect', (e) => { usbBack = e.device; });
  native.event({ event: 'usb.disconnect', device: 'dev-1' });
  await settle();
  check(usbGone === 1 && dev.opened === false, 'E7 a native usb.disconnect fires disconnect on navigator.usb and closes the device');
  native.event({ event: 'usb.connect', device: DEVINFO });
  await settle();
  check(usbBack === dev, 'E7 a usb.connect for a known device hands the same USBDevice object back');

  check(problems.length === 0, 'E8 every message, reply and event matched the contract' + (problems.length ? ': ' + problems.slice(0, 3).join(' | ') : ''));
  console.log(out.join('\n'));
})().catch(e => { console.log(out.join('\n')); console.log('bad harness crashed: ' + (e && e.stack || e)); });
