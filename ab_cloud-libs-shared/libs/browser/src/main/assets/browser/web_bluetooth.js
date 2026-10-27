// #1222 Web Bluetooth for Cloud Browser: navigator.bluetooth over the native JS bridge (AndroidWebBluetooth), because Android
// WebView ships none. Injected into every page by WebBluetooth.kt (document start where the WebView supports it, else page start).
// The wire is the CONTRACT in web-bluetooth-contract.json (ops, argument names, result shapes, error names): the native side
// (WebBluetoothCore) and this file are both tested against it (test-browser-web-bluetooth.sh, WebBluetoothCoreTest).
//   JS -> native  AndroidWebBluetooth.post('{"v":1,"id":N,"op":"readValue","args":{...},"gesture":true}')
//   native -> JS  window.__cloudBle.recv('{"v":1,"id":N,"ok":true,"result":{...}}')            a reply
//                 window.__cloudBle.recv('{"v":1,"event":"characteristicvaluechanged","device":..,"value":"hex"}')   an event
// Chrome semantics kept: secure contexts only, requestDevice needs a user gesture, devices are origin-scoped and opaque,
// and a page only reaches the services it named in filters / optionalServices (the native side enforces that, not this file).
(function (root) {
  'use strict';
  if (root.__cloudBle) return;
  var nav = root.navigator;
  if (nav && nav.bluetooth) { root.__cloudBle = { native: true }; return; }   // a WebView that has its own keeps it
  if (!root.isSecureContext) return;                                          // Chrome: no Web Bluetooth on an insecure origin
  try { if (root.top !== root) { void root.top.location.href; } } catch (e) { return; }   // a cross-origin frame gets none

  var ALIASES = __ALIASES__;   // {services:{heart_rate:"0000180d-..."}, characteristics:{...}} from the native catalog
  var BASE = '-0000-1000-8000-00805f9b34fb';
  var FULL = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
  var bridge = root.AndroidWebBluetooth;
  var seq = 0, pending = {};

  function err(name, message) { return new DOMException(message, name); }

  // ---- user activation (Chrome: requestDevice only while handling a gesture) ----
  var lastGesture = -1e9;
  ['click', 'touchend', 'pointerup', 'keydown', 'mouseup'].forEach(function (t) {
    root.addEventListener(t, function (e) { if (e.isTrusted) lastGesture = Date.now(); }, true);
  });
  function hasGesture() {
    if (nav.userActivation) return !!nav.userActivation.isActive;
    return Date.now() - lastGesture < 5000;
  }

  // ---- UUIDs ----
  function canonical(u) {
    if (typeof u === 'number') {
      if (u < 0 || u > 0xffffffff || Math.floor(u) !== u) throw new TypeError('Invalid UUID ' + u);
      var h = u.toString(16); while (h.length < 8) h = '0' + h; return h + BASE;
    }
    if (typeof u !== 'string') throw new TypeError('Invalid UUID');
    var s = u.toLowerCase();
    if (FULL.test(s)) return s;
    if (/^[0-9a-f]{4}$/.test(s)) return '0000' + s + BASE;
    if (/^[0-9a-f]{8}$/.test(s)) return s + BASE;
    return null;
  }
  function resolve(kind, name) {
    var c = canonical(name);
    if (c) return c;
    var table = (ALIASES[kind] || {});
    if (typeof name === 'string' && table.hasOwnProperty(name)) return table[name];
    throw new TypeError('Invalid ' + kind.replace(/s$/, '') + ' name: ' + name + '. It must be a valid UUID alias (e.g. 0x1234), UUID (lowercase hex characters e.g. \'00001234-0000-1000-8000-00805f9b34fb\'), or recognized standard name.');
  }
  var BluetoothUUID = {
    canonicalUUID: function (alias) { return canonical(alias >>> 0); },
    getService: function (n) { return resolve('services', n); },
    getCharacteristic: function (n) { return resolve('characteristics', n); },
    getDescriptor: function (n) { return canonical(n) || (function () { throw new TypeError('Invalid descriptor name: ' + n); })(); }
  };

  // ---- hex <-> DataView ----
  function toHex(buf) {
    var b = new Uint8Array(buf.buffer ? buf.buffer.slice(buf.byteOffset, buf.byteOffset + buf.byteLength) : buf), s = '';
    for (var i = 0; i < b.length; i++) s += (b[i] < 16 ? '0' : '') + b[i].toString(16);
    return s;
  }
  function fromHex(h) {
    var b = new Uint8Array(h.length / 2);
    for (var i = 0; i < b.length; i++) b[i] = parseInt(h.substr(i * 2, 2), 16);
    return new DataView(b.buffer);
  }

  // ---- transport ----
  function call(op, args, gesture) {
    return new Promise(function (resolveP, rejectP) {
      if (!bridge || typeof bridge.post !== 'function') { rejectP(err('NotSupportedError', 'Bluetooth is not available in this browser.')); return; }
      var id = ++seq;
      pending[id] = { resolve: resolveP, reject: rejectP };
      var msg = { v: 1, id: id, op: op, args: args || {} };
      if (gesture !== undefined) msg.gesture = gesture;
      try { bridge.post(JSON.stringify(msg)); }
      catch (e) { delete pending[id]; rejectP(err('NetworkError', 'Bluetooth bridge failed: ' + e)); }
    });
  }

  var devices = {}, services = {}, characteristics = {};

  function emit(target, type) { target.dispatchEvent(new Event(type, { bubbles: true })); }

  function recv(text) {
    var m; try { m = JSON.parse(text); } catch (e) { return; }
    if (m.event) {
      if (m.event === 'gattserverdisconnected') {
        var d = devices[m.device];
        if (d && d.gatt.connected) { d.gatt.__connected = false; emit(d, 'gattserverdisconnected'); }
      } else if (m.event === 'characteristicvaluechanged') {
        var c = characteristics[m.device + '|' + m.service + '|' + m.characteristic];
        if (c) { c.value = fromHex(m.value); emit(c, 'characteristicvaluechanged'); }
      }
      return;
    }
    var p = pending[m.id]; if (!p) return;
    delete pending[m.id];
    if (m.ok) p.resolve(m.result || {}); else p.reject(err((m.error && m.error.name) || 'NetworkError', (m.error && m.error.message) || 'Bluetooth error'));
  }

  // ---- the object model ----
  function wire(proto, events) {
    events.forEach(function (type) {
      Object.defineProperty(proto, 'on' + type, {
        configurable: true, enumerable: true,
        get: function () { return this['__on' + type] || null; },
        set: function (fn) {
          if (this['__on' + type]) this.removeEventListener(type, this['__on' + type]);
          this['__on' + type] = typeof fn === 'function' ? fn : null;
          if (this['__on' + type]) this.addEventListener(type, this['__on' + type]);
        }
      });
    });
  }

  function BluetoothCharacteristicProperties(p) {
    this.broadcast = !!p.broadcast; this.read = !!p.read; this.writeWithoutResponse = !!p.writeWithoutResponse; this.write = !!p.write;
    this.notify = !!p.notify; this.indicate = !!p.indicate; this.authenticatedSignedWrites = !!p.authenticatedSignedWrites;
    this.reliableWrite = !!p.reliableWrite; this.writableAuxiliaries = !!p.writableAuxiliaries;
  }

  class BluetoothRemoteGATTCharacteristic extends EventTarget {
    constructor(service, info) {
      super();
      this.service = service; this.uuid = info.uuid; this.properties = new BluetoothCharacteristicProperties(info.properties || {}); this.value = null;
    }
  }
  wire(BluetoothRemoteGATTCharacteristic.prototype, ['characteristicvaluechanged']);
  function charArgs(c) { return { device: c.service.device.id, service: c.service.uuid, characteristic: c.uuid }; }
  function needConnected(c) {
    if (!c.service.device.gatt.connected) return Promise.reject(err('NetworkError', 'GATT Server is disconnected. Cannot perform GATT operations. (Re)connect first with device.gatt.connect.'));
    return null;
  }
  BluetoothRemoteGATTCharacteristic.prototype.readValue = function () {
    var self = this, bad = needConnected(this); if (bad) return bad;
    return call('readValue', charArgs(this)).then(function (r) { self.value = fromHex(r.value); return self.value; });
  };
  function write(c, value, response) {
    var bad = needConnected(c); if (bad) return bad;
    var view = ArrayBuffer.isView(value) ? value : (value instanceof ArrayBuffer ? new Uint8Array(value) : null);
    if (!view) return Promise.reject(new TypeError('Failed to execute \'writeValue\' on \'BluetoothRemoteGATTCharacteristic\': The provided value is not an ArrayBuffer or view.'));
    if (view.byteLength > 512) return Promise.reject(err('InvalidModificationError', 'Value can\'t exceed 512 bytes.'));
    var a = charArgs(c); a.value = toHex(view); a.response = response;
    return call('writeValue', a).then(function () { });
  }
  BluetoothRemoteGATTCharacteristic.prototype.writeValue = function (v) { return write(this, v, 'auto'); };
  BluetoothRemoteGATTCharacteristic.prototype.writeValueWithResponse = function (v) { return write(this, v, 'with'); };
  BluetoothRemoteGATTCharacteristic.prototype.writeValueWithoutResponse = function (v) { return write(this, v, 'without'); };
  BluetoothRemoteGATTCharacteristic.prototype.startNotifications = function () {
    var self = this, bad = needConnected(this); if (bad) return bad;
    return call('startNotifications', charArgs(this)).then(function () { return self; });
  };
  BluetoothRemoteGATTCharacteristic.prototype.stopNotifications = function () {
    var self = this, bad = needConnected(this); if (bad) return bad;
    return call('stopNotifications', charArgs(this)).then(function () { return self; });
  };
  BluetoothRemoteGATTCharacteristic.prototype.getDescriptor = function () { return Promise.reject(err('NotSupportedError', 'Descriptors are not supported by this browser.')); };
  BluetoothRemoteGATTCharacteristic.prototype.getDescriptors = BluetoothRemoteGATTCharacteristic.prototype.getDescriptor;

  class BluetoothRemoteGATTService extends EventTarget {
    constructor(device, uuid) { super(); this.device = device; this.uuid = uuid; this.isPrimary = true; }
  }
  function makeChar(service, info) {
    var key = service.device.id + '|' + service.uuid + '|' + info.uuid;
    return characteristics[key] || (characteristics[key] = new BluetoothRemoteGATTCharacteristic(service, info));
  }
  BluetoothRemoteGATTService.prototype.getCharacteristics = function (characteristic) {
    var self = this, a = { device: this.device.id, service: this.uuid };
    if (characteristic !== undefined) a.characteristic = resolve('characteristics', characteristic);
    if (!this.device.gatt.connected) return Promise.reject(err('NetworkError', 'GATT Server is disconnected. Cannot retrieve characteristics. (Re)connect first with device.gatt.connect.'));
    return call('getCharacteristics', a).then(function (r) {
      if (!r.characteristics.length) throw err('NotFoundError', 'No Characteristics matching UUID ' + (a.characteristic || '') + ' found in Service with UUID ' + self.uuid + '.');
      return r.characteristics.map(function (i) { return makeChar(self, i); });
    });
  };
  BluetoothRemoteGATTService.prototype.getCharacteristic = function (characteristic) {
    if (characteristic === undefined) return Promise.reject(new TypeError('A characteristic name or UUID is required.'));
    return this.getCharacteristics(characteristic).then(function (l) { return l[0]; });
  };

  function BluetoothRemoteGATTServer(device) { this.device = device; this.__connected = false; }
  Object.defineProperty(BluetoothRemoteGATTServer.prototype, 'connected', { get: function () { return this.__connected; } });
  BluetoothRemoteGATTServer.prototype.connect = function () {
    var self = this;
    return call('gattConnect', { device: this.device.id }).then(function () { self.__connected = true; return self; });
  };
  BluetoothRemoteGATTServer.prototype.disconnect = function () {
    if (!this.__connected) return;
    this.__connected = false;
    call('gattDisconnect', { device: this.device.id }).catch(function () { });
    emit(this.device, 'gattserverdisconnected');
  };
  BluetoothRemoteGATTServer.prototype.getPrimaryServices = function (service) {
    var self = this, a = { device: this.device.id };
    if (service !== undefined) a.service = resolve('services', service);
    if (!this.__connected) return Promise.reject(err('NetworkError', 'GATT Server is disconnected. Cannot retrieve services. (Re)connect first with device.gatt.connect.'));
    return call('getPrimaryServices', a).then(function (r) {
      if (!r.services.length) throw err('NotFoundError', 'No Services' + (a.service ? ' matching UUID ' + a.service : '') + ' found in device.');
      return r.services.map(function (u) { var k = self.device.id + '|' + u; return services[k] || (services[k] = new BluetoothRemoteGATTService(self.device, u)); });
    });
  };
  BluetoothRemoteGATTServer.prototype.getPrimaryService = function (service) {
    if (service === undefined) return Promise.reject(new TypeError('A service name or UUID is required.'));
    return this.getPrimaryServices(service).then(function (l) { return l[0]; });
  };

  class BluetoothDevice extends EventTarget {
    constructor(info) {
      super();
      this.id = info.id; this.name = info.name || null; this.gatt = new BluetoothRemoteGATTServer(this); this.watchingAdvertisements = false;
    }
  }
  wire(BluetoothDevice.prototype, ['gattserverdisconnected', 'advertisementreceived']);
  BluetoothDevice.prototype.forget = function () { var id = this.id; this.gatt.__connected = false; return call('forget', { device: id }).then(function () { delete devices[id]; }); };
  BluetoothDevice.prototype.watchAdvertisements = function () { return Promise.reject(err('NotSupportedError', 'Watching advertisements is not supported by this browser.')); };
  BluetoothDevice.prototype.unwatchAdvertisements = function () { };
  function makeDevice(info) { return devices[info.id] || (devices[info.id] = new BluetoothDevice(info)); }

  class Bluetooth extends EventTarget { }
  Bluetooth.prototype.getAvailability = function () { return call('getAvailability', {}).then(function (r) { return !!r.available; }); };
  Bluetooth.prototype.getDevices = function () { return call('getDevices', {}).then(function (r) { return r.devices.map(makeDevice); }); };
  Bluetooth.prototype.requestDevice = function (options) {
    options = options || {};
    var filters = options.filters, all = options.acceptAllDevices === true;
    if (all ? (filters !== undefined) : (!Array.isArray(filters) || filters.length === 0))
      return Promise.reject(new TypeError('Failed to execute \'requestDevice\' on \'Bluetooth\': Either \'filters\' should be present or \'acceptAllDevices\' should be true, but not both.'));
    var wf, optional;
    try {
      wf = (filters || []).map(function (f) {
        var o = {};
        if (f.services !== undefined) o.services = f.services.map(function (s) { return resolve('services', s); });
        if (f.name !== undefined) o.name = String(f.name);
        if (f.namePrefix !== undefined) o.namePrefix = String(f.namePrefix);
        if (!o.services && o.name === undefined && o.namePrefix === undefined) throw new TypeError('A \'filters\' member must have at least one of \'services\', \'name\', \'namePrefix\'.');
        if (o.services && !o.services.length) throw new TypeError('\'services\', if present, must contain at least one service.');
        return o;
      });
      optional = (options.optionalServices || []).map(function (s) { return resolve('services', s); });
    } catch (e) { return Promise.reject(e); }
    if (!hasGesture()) return Promise.reject(err('SecurityError', 'Must be handling a user gesture to show a permission request.'));
    var args = { optionalServices: optional };
    if (all) args.acceptAllDevices = true; else args.filters = wf;
    return call('requestDevice', args, true).then(function (r) { return makeDevice(r.device); });
  };

  var bluetooth = new Bluetooth();
  Object.defineProperty(nav, 'bluetooth', { value: bluetooth, configurable: true, enumerable: true });
  var g = { Bluetooth: Bluetooth, BluetoothDevice: BluetoothDevice, BluetoothRemoteGATTServer: BluetoothRemoteGATTServer, BluetoothRemoteGATTService: BluetoothRemoteGATTService,
    BluetoothRemoteGATTCharacteristic: BluetoothRemoteGATTCharacteristic, BluetoothCharacteristicProperties: BluetoothCharacteristicProperties, BluetoothUUID: BluetoothUUID };
  Object.keys(g).forEach(function (k) { Object.defineProperty(root, k, { value: g[k], configurable: true, writable: true }); });
  root.__cloudBle = { native: false, recv: recv, version: 1 };
})(typeof window !== 'undefined' ? window : globalThis);
