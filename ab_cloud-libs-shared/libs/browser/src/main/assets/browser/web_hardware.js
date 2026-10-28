// #1225 Web Serial (navigator.serial), WebUSB (navigator.usb) and Web NFC (NDEFReader) for Cloud Browser, over the native JS bridge
// (AndroidWebHardware), because Android WebView ships none of them. Injected into every page by WebHardware.kt (document start where the
// WebView supports it, else page start). The sibling of web_bluetooth.js, same shape: the wire is the CONTRACT in
// web-hardware-contract.json, and the native side (WebHardwareCore) and this file are both tested against it
// (test-browser-web-hardware.sh, WebHardwareCoreTest).
//   JS -> native  AndroidWebHardware.post('{"v":1,"id":N,"op":"usb.transferIn","args":{...},"gesture":true}')
//   native -> JS  window.__cloudHw.recv('{"v":1,"id":N,"ok":true,"result":{...}}')                a reply
//                 window.__cloudHw.recv('{"v":1,"event":"serial.data","port":"..","value":"hex"}')  an event
// Chrome semantics kept: secure contexts only, requestPort / requestDevice need a user gesture, devices are origin-scoped and opaque, and
// the rules that need the phone's state (grants, protected USB classes, the chooser, Android's permission) are enforced natively.
(function (root) {
  'use strict';
  if (root.__cloudHw) return;
  var nav = root.navigator;
  if (!root.isSecureContext) return;                                          // Chrome: none of the three on an insecure origin
  try { if (root.top !== root) { void root.top.location.href; } } catch (e) { return; }   // a cross-origin frame gets none
  var bridge = root.AndroidWebHardware;
  var seq = 0, pending = {};
  var installed = {};

  function err(name, message) { return new DOMException(message, name); }

  // ---- user activation (Chrome: requestPort / requestDevice only while handling a gesture) ----
  var lastGesture = -1e9;
  ['click', 'touchend', 'pointerup', 'keydown', 'mouseup'].forEach(function (t) {
    root.addEventListener(t, function (e) { if (e.isTrusted) lastGesture = Date.now(); }, true);
  });
  function hasGesture() {
    if (nav.userActivation) return !!nav.userActivation.isActive;
    return Date.now() - lastGesture < 5000;
  }

  // ---- bytes <-> hex ----
  function bytesOf(v) {
    if (v instanceof ArrayBuffer) return new Uint8Array(v);
    if (ArrayBuffer.isView(v)) return new Uint8Array(v.buffer, v.byteOffset, v.byteLength);
    return null;
  }
  function toHex(u8) { var s = ''; for (var i = 0; i < u8.length; i++) s += (u8[i] < 16 ? '0' : '') + u8[i].toString(16); return s; }
  function fromHex(h) {
    var b = new Uint8Array((h || '').length / 2);
    for (var i = 0; i < b.length; i++) b[i] = parseInt(h.substr(i * 2, 2), 16);
    return b;
  }
  function viewOf(h) { return new DataView(fromHex(h).buffer); }
  function utf8(s) { return new TextEncoder().encode(s); }

  // ---- transport ----
  function call(op, args, gesture) {
    return new Promise(function (resolveP, rejectP) {
      if (!bridge || typeof bridge.post !== 'function') { rejectP(err('NotSupportedError', 'Hardware access is not available in this browser.')); return; }
      var id = ++seq;
      pending[id] = { resolve: resolveP, reject: rejectP };
      var msg = { v: 1, id: id, op: op, args: args || {} };
      if (gesture !== undefined) msg.gesture = gesture;
      try { bridge.post(JSON.stringify(msg)); }
      catch (e) { delete pending[id]; rejectP(err('NetworkError', 'Hardware bridge failed: ' + e)); }
    });
  }
  function emit(target, type, props) {
    var e = new Event(type, { bubbles: false });
    if (props) Object.keys(props).forEach(function (k) { Object.defineProperty(e, k, { value: props[k], enumerable: true }); });
    target.dispatchEvent(e);
  }
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
  function expose(table) { Object.keys(table).forEach(function (k) { Object.defineProperty(root, k, { value: table[k], configurable: true, writable: true }); }); }
  function define(name, value) {
    if (nav && nav[name]) return false;      // a WebView that has its own keeps it
    Object.defineProperty(nav, name, { value: value, configurable: true, enumerable: true });
    return true;
  }

  var ports = {}, devices = {}, serialApi, usbApi, readers = [];

  function recv(text) {
    var m; try { m = JSON.parse(text); } catch (e) { return; }
    if (m.event) {
      if (m.event === 'serial.data') { var p = ports[m.port]; if (p && p.__ctl) { try { p.__ctl.enqueue(fromHex(m.value)); } catch (e) { } } }
      else if (m.event === 'serial.disconnect') { var q = ports[m.port]; if (q) q.__lost(); }
      else if (m.event === 'usb.connect') { var d = makeDevice(m.device); if (usbApi) emit(usbApi, 'connect', { device: d }); }
      else if (m.event === 'usb.disconnect') { var u = devices[m.device]; if (u) { u.__drop(); if (usbApi) emit(usbApi, 'disconnect', { device: u }); } }
      else if (m.event === 'nfc.reading') { readers.forEach(function (r) { if (r.__scanning) r.__reading(m.message); }); }
      else if (m.event === 'nfc.readingerror') { readers.forEach(function (r) { if (r.__scanning) emit(r, 'readingerror'); }); }
      return;
    }
    var pr = pending[m.id]; if (!pr) return;
    delete pending[m.id];
    if (m.ok) pr.resolve(m.result || {}); else pr.reject(err((m.error && m.error.name) || 'NetworkError', (m.error && m.error.message) || 'Hardware error'));
  }

  // =================================== Web Serial ===================================
  class SerialPort extends EventTarget {
    constructor(info) {
      super();
      this.__id = info.id; this.__vid = info.usbVendorId; this.__pid = info.usbProductId;
      this.readable = null; this.writable = null; this.connected = true; this.__open = false; this.__ctl = null;
    }
  }
  wire(SerialPort.prototype, ['connect', 'disconnect']);
  SerialPort.prototype.getInfo = function () { return { usbVendorId: this.__vid, usbProductId: this.__pid }; };
  SerialPort.prototype.open = function (options) {
    var self = this;
    if (!options || typeof options.baudRate !== 'number' || !(options.baudRate > 0)) return Promise.reject(new TypeError('Failed to execute \'open\' on \'SerialPort\': required member baudRate is undefined.'));
    var dataBits = options.dataBits === undefined ? 8 : options.dataBits, stopBits = options.stopBits === undefined ? 1 : options.stopBits;
    var parity = options.parity || 'none', flow = options.flowControl || 'none';
    if (dataBits !== 7 && dataBits !== 8) return Promise.reject(new TypeError('Failed to execute \'open\' on \'SerialPort\': Invalid dataBits.'));
    if (stopBits !== 1 && stopBits !== 2) return Promise.reject(new TypeError('Failed to execute \'open\' on \'SerialPort\': Invalid stopBits.'));
    if (['none', 'even', 'odd'].indexOf(parity) < 0) return Promise.reject(new TypeError('Failed to execute \'open\' on \'SerialPort\': Invalid parity.'));
    if (['none', 'hardware'].indexOf(flow) < 0) return Promise.reject(new TypeError('Failed to execute \'open\' on \'SerialPort\': Invalid flowControl.'));
    if (this.__open) return Promise.reject(err('InvalidStateError', 'Failed to execute \'open\' on \'SerialPort\': The port is already open.'));
    return call('serial.open', { port: this.__id, baudRate: options.baudRate, dataBits: dataBits, stopBits: stopBits, parity: parity, flowControl: flow }).then(function () {
      self.__open = true;
      self.readable = new ReadableStream({
        start: function (c) { self.__ctl = c; },
        cancel: function () { self.__ctl = null; }
      });
      self.writable = new WritableStream({
        write: function (chunk) {
          var b = bytesOf(chunk);
          if (!b) return Promise.reject(new TypeError('Failed to execute \'write\' on \'WritableStreamDefaultWriter\': The provided value is not an ArrayBuffer or view.'));
          return call('serial.write', { port: self.__id, value: toHex(b) }).then(function () { });
        }
      });
    });
  };
  SerialPort.prototype.close = function () {
    var self = this;
    if (!this.__open) return Promise.resolve();
    if ((this.readable && this.readable.locked) || (this.writable && this.writable.locked))
      return Promise.reject(err('InvalidStateError', 'Failed to execute \'close\' on \'SerialPort\': Cannot cancel a locked stream.'));
    var ctl = this.__ctl;
    this.__open = false; this.__ctl = null; this.readable = null; this.writable = null;
    try { if (ctl) ctl.close(); } catch (e) { }
    return call('serial.close', { port: self.__id }).then(function () { });
  };
  SerialPort.prototype.setSignals = function (s) {
    if (!this.__open) return Promise.reject(err('InvalidStateError', 'Failed to execute \'setSignals\' on \'SerialPort\': The port is not open.'));
    s = s || {};
    var a = { port: this.__id };
    if (s.dataTerminalReady !== undefined) a.dataTerminalReady = !!s.dataTerminalReady;
    if (s.requestToSend !== undefined) a.requestToSend = !!s.requestToSend;
    if (s.break !== undefined) a['break'] = !!s.break;
    if (Object.keys(a).length === 1) return Promise.reject(new TypeError('Failed to execute \'setSignals\' on \'SerialPort\': Signals must have at least one member.'));
    return call('serial.setSignals', a).then(function () { });
  };
  SerialPort.prototype.getSignals = function () {
    return Promise.reject(err('NotSupportedError', 'Reading the modem status lines is not available on USB CDC serial in this browser.'));
  };
  SerialPort.prototype.forget = function () {
    var self = this;
    return call('serial.forget', { port: this.__id }).then(function () { delete ports[self.__id]; self.__lost(true); });
  };
  SerialPort.prototype.__lost = function (quiet) {
    var was = this.__open || this.connected;
    var ctl = this.__ctl;
    this.__open = false; this.__ctl = null; this.connected = false; this.readable = null; this.writable = null;
    try { if (ctl) ctl.error(err('NetworkError', 'The device has been lost.')); } catch (e) { }
    if (was && !quiet) { emit(this, 'disconnect'); if (serialApi) emit(serialApi, 'disconnect', { port: this }); }
  };
  function makePort(info) { var p = ports[info.id]; if (!p) p = ports[info.id] = new SerialPort(info); p.connected = true; return p; }

  class Serial extends EventTarget { }
  wire(Serial.prototype, ['connect', 'disconnect']);
  Serial.prototype.getPorts = function () { return call('serial.getPorts', {}).then(function (r) { return r.ports.map(makePort); }); };
  Serial.prototype.requestPort = function (options) {
    options = options || {};
    var filters;
    if (options.filters !== undefined) {
      if (!Array.isArray(options.filters)) return Promise.reject(new TypeError('Failed to execute \'requestPort\' on \'Serial\': filters must be an array.'));
      filters = [];
      for (var i = 0; i < options.filters.length; i++) {
        var f = options.filters[i];
        if (typeof f.usbVendorId !== 'number') return Promise.reject(new TypeError('Failed to execute \'requestPort\' on \'Serial\': A filter containing a usbProductId must also specify a usbVendorId.'));
        var o = { usbVendorId: f.usbVendorId }; if (typeof f.usbProductId === 'number') o.usbProductId = f.usbProductId; filters.push(o);
      }
    }
    if (!hasGesture()) return Promise.reject(err('SecurityError', 'Failed to execute \'requestPort\' on \'Serial\': Must be handling a user gesture to show a permission request.'));
    var args = {}; if (filters) args.filters = filters;
    return call('serial.requestPort', args, true).then(function (r) { return makePort(r.port); });
  };

  // =================================== WebUSB ===================================
  var REQ_TYPES = ['standard', 'class', 'vendor'], RECIPIENTS = ['device', 'interface', 'endpoint', 'other'];

  class USBDevice {
    constructor(info) {
      this.__id = info.id; this.__drop(); this.__info = info; this.opened = false; this.configuration = null; this.__claimed = {};
      this.usbVersionMajor = 2; this.usbVersionMinor = 0; this.usbVersionSubminor = 0;
      this.deviceClass = info.deviceClass; this.deviceSubclass = info.deviceSubclass; this.deviceProtocol = info.deviceProtocol;
      this.vendorId = info.vendorId; this.productId = info.productId;
      this.deviceVersionMajor = 0; this.deviceVersionMinor = 0; this.deviceVersionSubminor = 0;
      this.manufacturerName = info.manufacturerName || null; this.productName = info.productName || null; this.serialNumber = info.serialNumber || null;
      var self = this;
      this.configurations = (info.configurations || []).map(function (c) { return makeConfig(self, c); });
    }
  }
  function makeConfig(dev, c) {
    var cfg = { configurationValue: c.configurationValue, configurationName: null, interfaces: [] };
    cfg.interfaces = c.interfaces.map(function (i) {
      var alts = i.alternates.map(function (a) {
        return { alternateSetting: a.alternateSetting, interfaceClass: a.interfaceClass, interfaceSubclass: a.interfaceSubclass, interfaceProtocol: a.interfaceProtocol, interfaceName: null,
          endpoints: a.endpoints.map(function (e) { return { endpointNumber: e.endpointNumber, direction: e.direction, type: e.type, packetSize: e.packetSize }; }) };
      });
      var iface = { interfaceNumber: i.interfaceNumber, alternates: alts, alternate: alts[0] || null };
      Object.defineProperty(iface, 'claimed', { enumerable: true, get: function () { return !!dev.__claimed[i.interfaceNumber]; } });
      return iface;
    });
    return cfg;
  }
  USBDevice.prototype.__drop = function () { this.opened = false; this.__claimed = {}; };
  function needOpen(d) {
    return d.opened ? null : Promise.reject(err('InvalidStateError', 'The device must be opened first.'));
  }
  function needConfig(d) {
    return d.configuration ? null : Promise.reject(err('InvalidStateError', 'The device must have a configuration selected.'));
  }
  USBDevice.prototype.open = function () {
    var self = this;
    return call('usb.open', { device: this.__id }).then(function () {
      self.opened = true;
      if (!self.configuration && self.configurations.length === 1) self.configuration = self.configurations[0];
    });
  };
  USBDevice.prototype.close = function () {
    var self = this;
    return call('usb.close', { device: this.__id }).then(function () { self.__drop(); });
  };
  USBDevice.prototype.forget = function () {
    var self = this;
    return call('usb.forget', { device: this.__id }).then(function () { self.__drop(); delete devices[self.__id]; });
  };
  USBDevice.prototype.selectConfiguration = function (value) {
    var self = this, bad = needOpen(this); if (bad) return bad;
    if (!(value >= 0 && value <= 255)) return Promise.reject(new TypeError('Failed to execute \'selectConfiguration\' on \'USBDevice\': invalid configuration value.'));
    return call('usb.selectConfiguration', { device: this.__id, configurationValue: value }).then(function () {
      self.configuration = self.configurations.filter(function (c) { return c.configurationValue === value; })[0] || null;
    });
  };
  function iface(d, n) { return d.configuration && d.configuration.interfaces.filter(function (i) { return i.interfaceNumber === n; })[0]; }
  USBDevice.prototype.claimInterface = function (n) {
    var self = this, bad = needOpen(this) || needConfig(this); if (bad) return bad;
    return call('usb.claimInterface', { device: this.__id, interfaceNumber: n }).then(function () { self.__claimed[n] = true; });
  };
  USBDevice.prototype.releaseInterface = function (n) {
    var self = this, bad = needOpen(this) || needConfig(this); if (bad) return bad;
    return call('usb.releaseInterface', { device: this.__id, interfaceNumber: n }).then(function () {
      delete self.__claimed[n]; var i = iface(self, n); if (i) i.alternate = i.alternates[0] || null;
    });
  };
  USBDevice.prototype.selectAlternateInterface = function (n, alt) {
    var self = this, bad = needOpen(this) || needConfig(this); if (bad) return bad;
    return call('usb.selectAlternateInterface', { device: this.__id, interfaceNumber: n, alternateSetting: alt }).then(function () {
      var i = iface(self, n); if (i) i.alternate = i.alternates.filter(function (a) { return a.alternateSetting === alt; })[0] || i.alternate;
    });
  };
  function setupOf(s) {
    if (!s || REQ_TYPES.indexOf(s.requestType) < 0 || RECIPIENTS.indexOf(s.recipient) < 0) throw new TypeError('Invalid control transfer setup.');
    return { requestType: s.requestType, recipient: s.recipient, request: s.request | 0, value: s.value | 0, index: s.index | 0 };
  }
  USBDevice.prototype.controlTransferIn = function (setup, length) {
    var bad = needOpen(this) || needConfig(this); if (bad) return bad;
    var s; try { s = setupOf(setup); } catch (e) { return Promise.reject(e); }
    return call('usb.controlTransferIn', { device: this.__id, setup: s, length: length | 0 }).then(function (r) { return { data: viewOf(r.data), status: r.status }; });
  };
  USBDevice.prototype.controlTransferOut = function (setup, data) {
    var bad = needOpen(this) || needConfig(this); if (bad) return bad;
    var s; try { s = setupOf(setup); } catch (e) { return Promise.reject(e); }
    var b = data === undefined ? new Uint8Array(0) : bytesOf(data);
    if (!b) return Promise.reject(new TypeError('Failed to execute \'controlTransferOut\' on \'USBDevice\': The provided value is not an ArrayBuffer or view.'));
    return call('usb.controlTransferOut', { device: this.__id, setup: s, data: toHex(b) }).then(function (r) { return { bytesWritten: r.bytesWritten, status: r.status }; });
  };
  USBDevice.prototype.transferIn = function (ep, length) {
    var bad = needOpen(this) || needConfig(this); if (bad) return bad;
    return call('usb.transferIn', { device: this.__id, endpointNumber: ep | 0, length: length | 0 }).then(function (r) { return { data: viewOf(r.data), status: r.status }; });
  };
  USBDevice.prototype.transferOut = function (ep, data) {
    var bad = needOpen(this) || needConfig(this); if (bad) return bad;
    var b = bytesOf(data);
    if (!b) return Promise.reject(new TypeError('Failed to execute \'transferOut\' on \'USBDevice\': The provided value is not an ArrayBuffer or view.'));
    return call('usb.transferOut', { device: this.__id, endpointNumber: ep | 0, data: toHex(b) }).then(function (r) { return { bytesWritten: r.bytesWritten, status: r.status }; });
  };
  USBDevice.prototype.clearHalt = function (direction, ep) {
    var bad = needOpen(this) || needConfig(this); if (bad) return bad;
    if (direction !== 'in' && direction !== 'out') return Promise.reject(new TypeError('Failed to execute \'clearHalt\' on \'USBDevice\': invalid direction.'));
    return call('usb.clearHalt', { device: this.__id, direction: direction, endpointNumber: ep | 0 }).then(function () { });
  };
  USBDevice.prototype.reset = function () {
    var bad = needOpen(this); if (bad) return bad;
    return call('usb.reset', { device: this.__id }).then(function () { });
  };
  ['isochronousTransferIn', 'isochronousTransferOut'].forEach(function (n) {
    USBDevice.prototype[n] = function () { return Promise.reject(err('NotSupportedError', 'Isochronous transfers are not available through Android\'s USB host API.')); };
  });
  function makeDevice(info) { var d = devices[info.id]; if (!d) d = devices[info.id] = new USBDevice(info); return d; }

  class USB extends EventTarget { }
  wire(USB.prototype, ['connect', 'disconnect']);
  USB.prototype.getDevices = function () { return call('usb.getDevices', {}).then(function (r) { return r.devices.map(makeDevice); }); };
  USB.prototype.requestDevice = function (options) {
    if (!options || !Array.isArray(options.filters)) return Promise.reject(new TypeError('Failed to execute \'requestDevice\' on \'USB\': required member filters is undefined.'));
    var filters = [], keys = ['vendorId', 'productId', 'classCode', 'subclassCode', 'protocolCode', 'serialNumber'];
    for (var i = 0; i < options.filters.length; i++) {
      var f = options.filters[i], o = {};
      keys.forEach(function (k) { if (f[k] !== undefined) o[k] = f[k]; });
      if (o.productId !== undefined && o.vendorId === undefined) return Promise.reject(new TypeError('Failed to execute \'requestDevice\' on \'USB\': A filter containing a productId must also specify a vendorId.'));
      if (o.subclassCode !== undefined && o.classCode === undefined) return Promise.reject(new TypeError('Failed to execute \'requestDevice\' on \'USB\': A filter containing a subclassCode must also specify a classCode.'));
      if (o.protocolCode !== undefined && o.subclassCode === undefined) return Promise.reject(new TypeError('Failed to execute \'requestDevice\' on \'USB\': A filter containing a protocolCode must also specify a subclassCode.'));
      filters.push(o);
    }
    if (!hasGesture()) return Promise.reject(err('SecurityError', 'Failed to execute \'requestDevice\' on \'USB\': Must be handling a user gesture to show a permission request.'));
    return call('usb.requestDevice', { filters: filters }, true).then(function (r) { return makeDevice(r.device); });
  };

  // =================================== Web NFC ===================================
  class NDEFRecord {
    constructor(init) {
      init = init || {};
      this.recordType = init.recordType; this.mediaType = init.mediaType === undefined ? null : init.mediaType;
      this.id = init.id === undefined ? null : init.id; this.encoding = init.encoding === undefined ? null : init.encoding; this.lang = init.lang === undefined ? null : init.lang;
      this.data = init.__hex !== undefined ? viewOf(init.__hex) : (init.data === undefined ? null : init.data);
    }
  }
  NDEFRecord.prototype.toRecords = function () { return null; };
  class NDEFMessage { constructor(init) { this.records = ((init && init.records) || []).map(function (r) { return r instanceof NDEFRecord ? r : new NDEFRecord(r); }); } }
  class NDEFReadingEvent extends Event {
    constructor(type, init) { super(type); this.serialNumber = init.serialNumber; this.message = init.message; }
  }

  // A page's write(): a string, a BufferSource, or {records:[{recordType, mediaType, id, encoding, lang, data}]} -> wire records.
  function recordsOf(message) {
    var list;
    if (typeof message === 'string') list = [{ recordType: 'text', data: message }];
    else if (bytesOf(message)) list = [{ recordType: 'mime', mediaType: 'application/octet-stream', data: message }];
    else if (message && Array.isArray(message.records)) list = message.records;
    else throw new TypeError('Failed to execute \'write\' on \'NDEFReader\': Argument 1 could not be converted to a valid NDEF message.');
    return list.map(function (r) {
      var type = r.recordType;
      if (typeof type !== 'string' || !type) throw new TypeError('Failed to execute \'write\' on \'NDEFReader\': The record has no recordType.');
      var out = { recordType: type, data: '' };
      if (r.mediaType !== undefined && r.mediaType !== null) out.mediaType = String(r.mediaType);
      if (r.id !== undefined && r.id !== null) out.id = String(r.id);
      var d = r.data;
      if (type === 'text') {
        if (r.encoding !== undefined && r.encoding !== null && r.encoding !== 'utf-8') throw err('NotSupportedError', 'Only utf-8 text records can be written.');
        out.encoding = 'utf-8'; out.lang = r.lang || 'en';
      }
      if (typeof d === 'string') out.data = toHex(utf8(d));
      else if (d !== undefined && d !== null) {
        var b = bytesOf(d);
        if (!b) throw new TypeError('Failed to execute \'write\' on \'NDEFReader\': The record data must be a string or a BufferSource.');
        out.data = toHex(b);
      }
      if (type === 'mime' && out.mediaType === undefined) out.mediaType = 'application/octet-stream';
      return out;
    });
  }

  class NDEFReader extends EventTarget {
    constructor() { super(); this.__scanning = false; readers.push(this); }
  }
  wire(NDEFReader.prototype, ['reading', 'readingerror']);
  NDEFReader.prototype.__reading = function (m) {
    var rs = m.records.map(function (r) {
      return new NDEFRecord({ recordType: r.recordType, mediaType: r.mediaType, id: r.id, encoding: r.encoding, lang: r.lang, __hex: r.data });
    });
    var ev = new NDEFReadingEvent('reading', { serialNumber: m.serialNumber, message: new NDEFMessage({ records: rs }) });
    this.dispatchEvent(ev);
  };
  function aborted(signal) { return signal && signal.aborted ? err('AbortError', 'The operation was aborted.') : null; }
  NDEFReader.prototype.scan = function (options) {
    var self = this, signal = options && options.signal, a = aborted(signal);
    if (a) return Promise.reject(a);
    return call('nfc.scan', {}, hasGesture() ? true : undefined).then(function () {
      self.__scanning = true;
      if (signal) signal.addEventListener('abort', function () {
        self.__scanning = false;
        if (!readers.some(function (r) { return r.__scanning; })) call('nfc.stop', {}).catch(function () { });
      });
    });
  };
  NDEFReader.prototype.write = function (message, options) {
    var signal = options && options.signal, a = aborted(signal);
    if (a) return Promise.reject(a);
    var recs;
    try { recs = recordsOf(message); } catch (e) { return Promise.reject(e); }
    var overwrite = !(options && options.overwrite === false);
    return call('nfc.write', { records: recs, overwrite: overwrite }, hasGesture() ? true : undefined).then(function () { });
  };
  NDEFReader.prototype.makeReadOnly = function () {
    return Promise.reject(err('NotSupportedError', 'Making a tag read-only is not available in this browser.'));
  };

  // ---- install ----
  var did = {};
  if (define('serial', (serialApi = new Serial()))) did.serial = true;
  if (define('usb', (usbApi = new USB()))) did.usb = true;
  if (did.serial) expose({ Serial: Serial, SerialPort: SerialPort });
  if (did.usb) expose({ USB: USB, USBDevice: USBDevice });
  if (!root.NDEFReader) { expose({ NDEFReader: NDEFReader, NDEFMessage: NDEFMessage, NDEFRecord: NDEFRecord, NDEFReadingEvent: NDEFReadingEvent }); did.nfc = true; }
  root.__cloudHw = { recv: recv, version: 1, installed: did };
})(typeof window !== 'undefined' ? window : globalThis);
