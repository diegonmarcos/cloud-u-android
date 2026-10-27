// #1222 Web Bluetooth polyfill contract test: runs assets/browser/web_bluetooth.js against a fake page environment and a fake NATIVE
// side that implements web-bluetooth-contract.json over a fake adapter (one heart-rate strap), and loads test/web_bluetooth_page.html
// (a local page whose click calls navigator.bluetooth.requestDevice). Every message the polyfill sends and every reply the fake
// native builds is checked against the contract's `ops`.
// usage: node ble_polyfill_harness.js <polyfill.js> <contract.json> <page.html>   -> one `ok|bad <label>` line per assertion.
const fs = require('fs'), vm = require('vm');
const [polyfillPath, contractPath, pagePath] = process.argv.slice(2);
const SRC = fs.readFileSync(polyfillPath, 'utf8');
const CONTRACT = JSON.parse(fs.readFileSync(contractPath, 'utf8'));
const PAGE = fs.readFileSync(pagePath, 'utf8');
const out = [];
const check = (c, l) => out.push((c ? 'ok ' : 'bad ') + l);

const BASE = '-0000-1000-8000-00805f9b34fb';
const HR = '0000180d' + BASE, HRM = '00002a37' + BASE, BATT = '0000180f' + BASE, BATT_L = '00002a19' + BASE, DEVINFO = '0000180a' + BASE;
const ALIASES = { services: { heart_rate: HR, battery_service: BATT, device_information: DEVINFO }, characteristics: { heart_rate_measurement: HRM, battery_level: BATT_L } };

// ---- contract type checker ----
function typeOk(t, v) {
  if (t.endsWith('[]')) return Array.isArray(v) && v.every(x => typeOk(t.slice(0, -2), x));
  if (t.includes('|')) return t.split('|').includes(v);
  switch (t) {
    case 'uuid': return typeof v === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(v);
    case 'string': return typeof v === 'string';
    case 'hex': return typeof v === 'string' && /^([0-9a-f]{2})*$/.test(v);
    case 'bool': return typeof v === 'boolean';
    case 'device': return typeof v === 'string' && v.length > 0;
    case 'filters': return Array.isArray(v) && v.every(f => typeOk('uuid[]', f.services === undefined ? [] : f.services) && Object.keys(f).every(k => ['services', 'name', 'namePrefix'].includes(k)));
    case 'props': return v && ['broadcast', 'read', 'writeWithoutResponse', 'write', 'notify', 'indicate', 'authenticatedSignedWrites', 'reliableWrite', 'writableAuxiliaries'].every(k => typeof v[k] === 'boolean');
    case 'device-info': return shapeOk(CONTRACT.shapes['device-info'], v);
    case 'characteristic-info': return shapeOk(CONTRACT.shapes['characteristic-info'], v);
  }
  return false;
}
function shapeOk(shape, obj) {
  if (!obj || typeof obj !== 'object') return false;
  const keys = Object.keys(shape);
  for (const k of keys) { const opt = k.endsWith('?'), n = opt ? k.slice(0, -1) : k; if (obj[n] === undefined ? !opt : !typeOk(shape[k], obj[n])) return false; }
  const known = new Set(keys.map(k => k.replace(/\?$/, '')));
  return Object.keys(obj).every(k => known.has(k));
}

// ---- the fake native side: contract-driven, over a fake adapter ----
const problems = [];
const native = {
  sent: [], gestureSeen: null, connected: false, subscribed: false, win: null,
  allowed: new Set(),
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
    setImmediate(() => this.win.__cloudBle.recv(JSON.stringify(body)));
  },
  event(e) { setImmediate(() => this.win.__cloudBle.recv(JSON.stringify(Object.assign({ v: 1 }, e)))); },
  run(m) {
    const a = m.args, props = (p) => Object.assign({ broadcast: false, read: false, writeWithoutResponse: false, write: false, notify: false, indicate: false, authenticatedSignedWrites: false, reliableWrite: false, writableAuxiliaries: false }, p);
    switch (m.op) {
      case 'getAvailability': return this.reply(m, { available: true });
      case 'getDevices': return this.reply(m, { devices: [] });
      case 'requestDevice':
        this.allowed = new Set([...(a.filters || []).flatMap(f => f.services || []), ...a.optionalServices]);
        return this.reply(m, { device: { id: 'dev-strap', name: 'Fake Strap' } });
      case 'gattConnect': this.connected = true; return this.reply(m, {});
      case 'gattDisconnect': this.connected = false; return this.reply(m, {});
      case 'getPrimaryServices': {
        if (a.service && !this.allowed.has(a.service)) return this.reply(m, null, { name: 'SecurityError', message: 'Origin is not allowed to access the service.' });
        const all = [HR, BATT].filter(s => this.allowed.has(s) && (!a.service || a.service === s));
        return this.reply(m, { services: all });
      }
      case 'getCharacteristics': {
        const list = a.service === HR ? [{ uuid: HRM, properties: props({ notify: true }) }] : [{ uuid: BATT_L, properties: props({ read: true, notify: true }) }];
        return this.reply(m, { characteristics: list.filter(c => !a.characteristic || c.uuid === a.characteristic) });
      }
      case 'readValue': return this.reply(m, { value: '57' });
      case 'startNotifications':
        this.subscribed = true; this.reply(m, {});
        setTimeout(() => this.event({ event: 'characteristicvaluechanged', device: a.device, service: a.service, characteristic: a.characteristic, value: '0048' }), 5);
        return;
      case 'stopNotifications': this.subscribed = false; return this.reply(m, {});
      case 'writeValue': this.written = a; return this.reply(m, {});
      case 'forget': return this.reply(m, {});
    }
  },
};

// ---- the fake page environment ----
function makeWindow({ secure = true, topIsSelf = true, existing = false, userActivation } = {}) {
  const listeners = {};
  const win = {
    isSecureContext: secure, navigator: existing ? { bluetooth: { native: true } } : {},
    addEventListener(t, fn) { (listeners[t] = listeners[t] || []).push(fn); },
    AndroidWebBluetooth: { post: (t) => native.post(t) },
    Event, EventTarget, DOMException, TypeError, Promise, JSON, ArrayBuffer, Uint8Array, DataView, Object, Array, Date, Math, Number, String, parseInt, setTimeout, console,
    click() { (listeners.click || []).forEach(fn => fn({ isTrusted: true })); },
    untrustedClick() { (listeners.click || []).forEach(fn => fn({ isTrusted: false })); },
  };
  if (userActivation) win.navigator.userActivation = userActivation;
  win.window = win; win.top = topIsSelf ? win : { get location() { throw new DOMException('blocked', 'SecurityError'); } };
  native.win = win;
  return win;
}
function load(win, aliases = ALIASES) {
  const code = SRC.replace('__ALIASES__', JSON.stringify(aliases));
  vm.runInNewContext(code, win, { filename: 'web_bluetooth.js' });
  return win;
}
const tick = (ms = 20) => new Promise(r => setTimeout(r, ms));
const settle = async (n = 8) => { for (let i = 0; i < n; i++) await tick(5); };
const rejects = async (p) => { try { await p; return null; } catch (e) { return e; } };

(async () => {
  // E1 guards: where the polyfill must not install
  { const w = load(makeWindow({ secure: false })); check(w.navigator.bluetooth === undefined, 'E1 an insecure context gets no navigator.bluetooth (Chrome rule)'); }
  { const w = load(makeWindow({ topIsSelf: false })); check(w.navigator.bluetooth === undefined, 'E1 a cross-origin frame gets no navigator.bluetooth'); }
  { const w = load(makeWindow({ existing: true })); check(w.navigator.bluetooth.native === true && w.__cloudBle.native === true, 'E1 a WebView that has its own Web Bluetooth keeps it'); }
  { const w = load(makeWindow()); const first = w.navigator.bluetooth; vm.runInNewContext(SRC.replace('__ALIASES__', '{}'), w); check(w.navigator.bluetooth === first, 'E1 a second injection is a no-op (document start + page start both run)'); }

  // E2 the surface
  const w = load(makeWindow());
  const bt = w.navigator.bluetooth;
  check(typeof bt.requestDevice === 'function' && typeof bt.getAvailability === 'function' && typeof bt.getDevices === 'function', 'E2 requestDevice / getAvailability / getDevices exist');
  check(bt instanceof w.EventTarget && typeof w.Bluetooth === 'function' && typeof w.BluetoothDevice === 'function' && typeof w.BluetoothRemoteGATTServer === 'function' && typeof w.BluetoothRemoteGATTCharacteristic === 'function', 'E2 the interface objects are exposed');
  check(w.BluetoothUUID.getService('heart_rate') === HR && w.BluetoothUUID.canonicalUUID(0x180d) === HR && w.BluetoothUUID.getCharacteristic('battery_level') === BATT_L, 'E2 BluetoothUUID resolves aliases and numbers from the catalog the native side supplies');
  check(await bt.getAvailability() === true, 'E2 getAvailability answers from the native side');

  // E3 requestDevice rules (arguments, gesture)
  check((await rejects(bt.requestDevice({}))) instanceof w.TypeError, 'E3 no filters and no acceptAllDevices is a TypeError');
  check((await rejects(bt.requestDevice({ filters: [{ services: ['heart_rate'] }], acceptAllDevices: true }))) instanceof w.TypeError, 'E3 filters together with acceptAllDevices is a TypeError');
  check((await rejects(bt.requestDevice({ filters: [{}] }))) instanceof w.TypeError, 'E3 an empty filter is a TypeError');
  check((await rejects(bt.requestDevice({ filters: [{ services: ['nope_service'] }] }))) instanceof w.TypeError, 'E3 an unknown service name is a TypeError');
  const before = native.sent.length;
  const noGesture = await rejects(bt.requestDevice({ acceptAllDevices: true }));
  check(noGesture && noGesture.name === 'SecurityError' && native.sent.length === before, 'E3 without a user gesture requestDevice fails with SecurityError and never reaches the native side');
  w.untrustedClick();
  check((await rejects(bt.requestDevice({ acceptAllDevices: true }))) !== null, 'E3 a script-made (untrusted) click is not a gesture');

  // E4 the local page: click -> requestDevice -> connect -> read -> notify (the flow of a real Web Bluetooth site)
  const pageW = load(makeWindow());
  const script = PAGE.match(/<script>([\s\S]*?)<\/script>/)[1];
  const doc = { _l: {}, getElementById: () => ({ addEventListener: (t, fn) => { doc._l[t] = fn; } }) };
  pageW.document = doc; pageW.navigator.bluetooth;
  vm.runInNewContext(script, Object.assign(pageW, { document: doc }));
  pageW.click();                       // the user's tap: a trusted event through the capture listener, then the page's handler
  doc._l.click();
  for (let i = 0; i < 100 && !pageW.__result.done; i++) await tick(10);
  const steps = pageW.__result.steps;
  check(pageW.__result.done === true, 'E4 the page script ran to its end');
  check(steps.includes('availability:true'), 'E4 getAvailability true');
  check(steps.includes('device:Fake Strap'), 'E4 requestDevice resolved the device the chooser picked');
  check(steps.includes('connected:true'), 'E4 gatt.connect resolved with a connected server');
  check(steps.includes('props:notify=true,read=false'), 'E4 getCharacteristic carries the properties');
  check(steps.includes('battery:87'), 'E4 readValue returns a DataView of the bytes (0x57 = 87)');
  check(steps.includes('numeric-uuid-ok'), 'E4 a numeric 16-bit UUID is accepted');
  check(steps.includes('blocked:SecurityError'), 'E4 a service the page did not name is SecurityError (Chrome allow-list)');
  check(steps.includes('bpm:72'), 'E4 startNotifications delivers characteristicvaluechanged with the new DataView (0x48 = 72)');
  const req = native.sent.find(m => m.op === 'requestDevice');
  check(req.gesture === true && req.args.filters[0].services[0] === HR && req.args.optionalServices[0] === BATT, 'E4 requestDevice is sent with gesture:true, full UUIDs for the filter and the optional service');
  check(native.sent.every(m => m.op in CONTRACT.ops), 'E4 every message is an op of the contract');

  // E5 disconnect and the rest of the surface
  const dev = pageW.__result.device;
  let fired = 0; dev.addEventListener('gattserverdisconnected', () => fired++);
  native.event({ event: 'gattserverdisconnected', device: 'dev-strap' });
  await settle();
  check(fired === 1 && dev.gatt.connected === false, 'E5 a native disconnect event fires gattserverdisconnected once and the server reads disconnected');
  const afterDrop = await rejects(pageW.__result.hrm.readValue());
  check(afterDrop && afterDrop.name === 'NetworkError', 'E5 a read on a dropped server is NetworkError');
  await dev.gatt.connect(); native.sent.length = 0;
  const hrm = pageW.__result.hrm;
  await hrm.service.device.gatt.getPrimaryService('heart_rate');
  await hrm.writeValueWithoutResponse(new Uint8Array([0xab, 0x00]));
  const wv = native.sent.find(m => m.op === 'writeValue');
  check(wv && wv.args.value === 'ab00' && wv.args.response === 'without', 'E5 writeValueWithoutResponse sends hex and response:without');
  await hrm.writeValue(new Uint8Array([1]).buffer);
  check(native.sent.filter(m => m.op === 'writeValue').pop().args.response === 'auto', 'E5 writeValue sends response:auto');
  check((await rejects(hrm.writeValue('text'))) instanceof pageW.TypeError, 'E5 writeValue of a string is a TypeError');
  const big = await rejects(hrm.writeValue(new Uint8Array(513)));
  check(big && big.name === 'InvalidModificationError', 'E5 a value over 512 bytes is refused in the page');
  await hrm.stopNotifications();
  check(native.sent.some(m => m.op === 'stopNotifications'), 'E5 stopNotifications reaches the native side');

  check(problems.length === 0, 'E6 every message and reply matched the contract' + (problems.length ? ': ' + problems.slice(0, 3).join(' | ') : ''));
  console.log(out.join('\n'));
})().catch(e => { console.log(out.join('\n')); console.log('bad harness crashed: ' + (e && e.stack || e)); });
