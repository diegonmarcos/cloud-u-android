// #900 Autocomplete page engine: runs assets/browser/ac_engine.js
// against a small fake DOM that models what matters here — labels, forms, the native value setter vs
// a React-style controlled input (value tracker), event dispatch with capture/bubble, :-webkit-autofill,
// MutationObserver and history. Fixtures are obvious fakes.
// usage: node autofill_js_harness.js <assets dir>   -> one `ok|bad <label>` line per assertion.
const fs = require('fs'), vm = require('vm'), path = require('path');
const dir = process.argv[2];
const out = [];
const check = (c, l) => out.push((c ? 'ok ' : 'bad ') + l);
const ENGINE = fs.readFileSync(path.join(dir, 'ac_engine.js'), 'utf8');

function makeWorld() {
  const docListeners = [];
  const bridge = { calls: [] };
  class Ev { constructor(type, o) { this.type = type; this.bubbles = !!(o && o.bubbles); } }
  class El {
    constructor(tag, attrs, kids) {
      this.tagName = tag.toUpperCase(); this.attrs = Object.assign({}, attrs || {}); this.children = []; this.parentElement = null;
      this.listeners = []; this._value = this.attrs.value || ''; this.textContent = this.attrs._text || '';
      (kids || []).forEach(k => this.append(k));
      if (this.tagName === 'SELECT') this.options = this.children.filter(c => c.tagName === 'OPTION').map(o => ({ value: o.attrs.value, textContent: o.textContent }));
    }
    append(k) { k.parentElement = this; this.children.push(k); return k; }
    getAttribute(n) { return n in this.attrs ? String(this.attrs[n]) : null; }
    get id() { return this.attrs.id || ''; }
    get name() { return this.attrs.name || ''; }
    get placeholder() { return this.attrs.placeholder || ''; }
    get type() { return this.tagName === 'INPUT' ? (this.attrs.type || 'text') : this.tagName === 'SELECT' ? 'select-one' : this.tagName === 'TEXTAREA' ? 'textarea' : undefined; }
    get disabled() { return !!this.attrs.disabled; }
    get readOnly() { return !!this.attrs.readonly; }
    get form() { return this.closest('form'); }
    get labels() { const doc = world.document; const ls = doc.all().filter(l => l.tagName === 'LABEL' && (l.attrs.for && l.attrs.for === this.id || l.contains(this))); return ls; }
    contains(e) { for (let x = e; x; x = x.parentElement) if (x === this) return true; return false; }
    closest(sel) { for (let e = this; e; e = e.parentElement) if (e.matches && e.matches(sel)) return e; return null; }
    matches(sel) {
      return sel.split(',').map(s => s.trim()).some(s => {
        if (s === ':autofill') throw new Error('SyntaxError: unsupported pseudo');
        if (s === ':-webkit-autofill') return !!this._autofilled;
        const m = s.match(/^([a-z]*)(#[\w-]+)?(\.[\w-]+)?(\[([\w-]+)(=["']?([^"'\]]*)["']?)?\])?$/i);
        if (!m) return false;
        if (m[1] && m[1].toUpperCase() !== this.tagName) return false;
        if (m[2] && this.attrs.id !== m[2].slice(1)) return false;
        if (m[3] && !(this.attrs.class || '').split(' ').includes(m[3].slice(1))) return false;
        if (m[4]) { if (!(m[5] in this.attrs)) return false; if (m[6] && String(this.attrs[m[5]]) !== m[7]) return false; }
        return !!(m[1] || m[2] || m[3] || m[4]);
      });
    }
    querySelectorAll(q) { const tags = q.split(',').map(s => s.trim().toUpperCase()); const r = []; const walk = e => e.children.forEach(c => { if (tags.includes(c.tagName)) r.push(c); walk(c); }); walk(this); return r; }
    getClientRects() { for (let e = this; e; e = e.parentElement) if (e.attrs.hidden) return []; return [{}]; }
    addEventListener(t, f) { this.listeners.push({ t, f }); }
    dispatchEvent(ev) {
      ev.target = this;
      docListeners.filter(l => l.t === ev.type && l.capture).forEach(l => l.f(ev));
      this.listeners.filter(l => l.t === ev.type).forEach(l => l.f(ev));
      if (ev.bubbles) {
        for (let e = this.parentElement; e; e = e.parentElement) e.listeners.filter(l => l.t === ev.type).forEach(l => l.f(ev));
        docListeners.filter(l => l.t === ev.type && !l.capture).forEach(l => l.f(ev));
      }
      ev.log && ev.log.push(ev.type);
      world.events.push([this.attrs.id || this.attrs.name, ev.type]);
      return true;
    }
  }
  class HTMLInputElement extends El {}
  class HTMLSelectElement extends El {}
  class HTMLTextAreaElement extends El {}
  for (const C of [HTMLInputElement, HTMLSelectElement, HTMLTextAreaElement]) {
    Object.defineProperty(C.prototype, 'value', { configurable: true, get() { return this._value; }, set(v) { this._value = String(v); world.nativeSets++; } });
  }
  const make = (tag, attrs, kids) => {
    const T = tag.toUpperCase();
    const C = T === 'INPUT' ? HTMLInputElement : T === 'SELECT' ? HTMLSelectElement : T === 'TEXTAREA' ? HTMLTextAreaElement : El;
    return new C(tag, attrs, kids);
  };
  const body = make('body', {});
  const html = make('html', {}, [body]);
  const observers = [];
  const world = {
    events: [], nativeSets: 0, bridge, observers, make, body,
    document: {
      documentElement: html, body,
      all() { const r = []; const walk = e => e.children.forEach(c => { r.push(c); walk(c); }); walk(html); return r; },
      getElementById(id) { return this.all().find(e => e.attrs.id === id) || null; },
      querySelectorAll(q) { return html.querySelectorAll(q); },
      addEventListener(t, f, capture) { docListeners.push({ t, f, capture: !!capture }); },
      createEvent() { return new Ev(''); },
    },
  };
  const historyCalls = [];
  const win = {
    document: world.document, JSON, Object, Array, String, Math, WeakMap, WeakSet, Error,
    Event: Ev, HTMLInputElement, HTMLSelectElement, HTMLTextAreaElement,
    setTimeout: (f) => { world.timers = (world.timers || []).concat(f); return 1; },
    MutationObserver: class { constructor(cb) { this.cb = cb; observers.push(this); } observe(t, o) { this.target = t; this.opts = o; } },
    history: { pushState() { historyCalls.push('push'); }, replaceState() { historyCalls.push('replace'); } },
    addEventListener() {},
    location: { hostname: 'shop.example', href: 'https://shop.example/checkout' },
    CloudAc: {
      focus: j => bridge.calls.push(['focus', JSON.parse(j)]),
      none: j => bridge.calls.push(['none', JSON.parse(j)]),
      submitted: j => bridge.calls.push(['submitted', JSON.parse(j)]),
      framework: j => bridge.calls.push(['framework', JSON.parse(j)]),
    },
  };
  win.window = win;
  world.win = win; world.historyCalls = historyCalls;
  world.ctx = vm.createContext(win);
  world.install = cfg => vm.runInContext(ENGINE.split('__CFG__').join(JSON.stringify(cfg || { incognito: false })), world.ctx);
  world.focus = el => { el.dispatchEvent(new Ev('focusin', { bubbles: true })); return bridge.calls[bridge.calls.length - 1]; };
  world.api = () => win.__ac;
  world.click = el => { el.dispatchEvent(new Ev('click', { bubbles: true })); };
  world.last = n => bridge.calls.filter(c => c[0] === n).pop();
  return world;
}

const I = (w, attrs) => w.make('input', attrs);
const L = (w, forId, text) => w.make('label', { for: forId, _text: text });

// ── 1. focus reports the signals of the whole form, never a value ───────────────
{
  const w = makeWorld();
  const f = w.make('form', { id: 'checkout' }, [
    L(w, 'fn', 'Vorname'), I(w, { id: 'fn', name: 'fname', value: 'SECRET-FIRST' }),
    I(w, { id: 'em', type: 'email', autocomplete: 'email', value: 'secret@example.test' }),
    I(w, { id: 'pw', type: 'password', value: 'SECRET-PW' }), I(w, { id: 'hid', type: 'hidden', value: 'HIDDEN' }),
    I(w, { id: 'gone', name: 'zip', hidden: true }),
  ]);
  w.body.append(f); w.install();
  const r = w.focus(w.document.getElementById('fn'));
  check(r[0] === 'focus' && r[1].formKey === 'checkout' && r[1].host === 'shop.example', 'focus reports the form key and the host');
  check(r[1].fields.length === 3, 'the visible, non-hidden fields of the form are reported (hidden type and hidden element left out)');
  const fn = r[1].fields.find(x => x.eid === 'fn');
  check(fn.label === 'Vorname' && fn.name === 'fname' && fn.type === 'text', 'a field reports label, name, id and type');
  check(!JSON.stringify(r[1]).match(/SECRET|HIDDEN|secret@/), 'the focus report carries signals, never a value (not even a prefilled one or a password)');
  check(r[1].fields.find(x => x.eid === 'pw').type === 'password', 'a password field is reported as a password field so the host can classify it');
  const none = w.focus(w.make('input', { type: 'submit' }));
  check(true, 'a non-field focus does not throw');
}

// ── 2. submit: the typed values, once ───────────────────────────────────────────
{
  const w = makeWorld();
  const f = w.make('form', { id: 'login' }, [
    I(w, { id: 'u', name: 'user', value: 'ana' }), I(w, { id: 'p', type: 'password', value: 'pw-1' }),
    I(w, { id: 'empty', name: 'x' }), I(w, { id: 'h', type: 'hidden', value: 'tok' }), w.make('textarea', { id: 'ta', value: 'long text' }),
    w.make('button', { id: 'go', type: 'submit' }),
  ]);
  w.body.append(f); w.install();
  f.dispatchEvent(new (w.win.Event)('submit', { bubbles: true }));
  const s = w.last('submitted');
  check(s && s[1].fields.length === 2, 'only the non-empty, non-hidden, non-textarea fields are reported');
  check(s[1].fields.find(x => x.eid === 'p').v === 'pw-1' && s[1].fields.find(x => x.eid === 'u').v === 'ana', 'a submit reports the typed values');
  check(s[1].formKey === 'login' && s[1].incognito === false, 'the submit names the form and the mode');
  w.click(w.document.getElementById('go'));
  check(w.bridge.calls.filter(c => c[0] === 'submitted').length === 1, 'a click on the submit button right after the submit event is not reported twice');
  const w2 = makeWorld();
  const g = w2.make('form', { id: 'f' }, [I(w2, { id: 'a', name: 'a', value: 'v' }), w2.make('button', { id: 'b' })]);
  w2.body.append(g); w2.install();
  w2.click(w2.document.getElementById('b'));
  check(w2.bridge.calls.filter(c => c[0] === 'submitted').length === 1, 'a click on a <button> (default type submit) inside a form is a submit too');
  const w3 = makeWorld(); w3.body.append(w3.make('div', {}, [I(w3, { id: 'loose', name: 'q', value: 'x' }), w3.make('button', { id: 'bb' })])); w3.install();
  w3.click(w3.document.getElementById('bb'));
  check(w3.bridge.calls.filter(c => c[0] === 'submitted').length === 0, 'a button outside any form reports nothing');
}

// ── 3. incognito is passed on, configure updates it ──────────────────────────────
{
  const w = makeWorld(); w.body.append(w.make('form', {}, [I(w, { id: 'a', name: 'a', value: 'v' })]));
  check(w.install({ incognito: true }) === 'installed', 'first install');
  check(w.focus(w.document.getElementById('a'))[1].incognito === true, 'the focus report carries incognito');
  check(w.install({ incognito: false }) === 'already', 'a second install is idempotent');
  check(w.focus(w.document.getElementById('a'))[1].incognito === false, 'and reconfigures');
}

// ── 4. the Android Autofill framework (Cloud Vault's dataset) ────────────────────
{
  const w = makeWorld();
  const f = w.make('form', { id: 'login' }, [I(w, { id: 'u', name: 'user' }), I(w, { id: 'p', type: 'password' })]);
  w.body.append(f); w.install();
  const u = w.document.getElementById('u'), p = w.document.getElementById('p');
  u._autofilled = true; p._autofilled = true;
  p.dispatchEvent(new (w.win.Event)('change', { bubbles: true }));
  (w.timers || []).forEach(t => t());
  const fw = w.last('framework');
  check(fw && fw[1].ids.length === 2 && fw[1].formKey === 'login', 'fields the framework filled are reported (ids only) after a change we did not make');
  check(!JSON.stringify(fw).match(/pw|value/), 'the framework report carries ids, never values');
  const focus = w.focus(u);
  check(focus[1].fields.every(x => x.fw === true), 'the focus report flags the framework-filled fields');
  // our own fill is not a framework fill
  const w2 = makeWorld(); w2.body.append(w2.make('form', {}, [I(w2, { id: 'a', name: 'a' })])); w2.install();
  const a = w2.document.getElementById('a'); const id = w2.focus(a)[1].field;
  w2.api().fill({ [id]: 'x' }, false); a._autofilled = true;
  (w2.timers || []).forEach(t => t());
  check(!w2.last('framework'), 'a change made by our own fill is never reported as the framework');
}

// ── 5. fill ─────────────────────────────────────────────────────────────────────
{
  const w = makeWorld();
  const f = w.make('form', { id: 'f' }, [
    I(w, { id: 'a', name: 'a' }), I(w, { id: 'b', name: 'b' }), I(w, { id: 'c', name: 'c', hidden: true }),
    w.make('select', { id: 'sel', name: 'country' }, [w.make('option', { value: 'DE', _text: 'Germany' }), w.make('option', { value: 'ES', _text: 'Spain' })]),
    w.make('select', { id: 'mm', name: 'mm' }, [w.make('option', { value: '1', _text: '01' }), w.make('option', { value: '5', _text: '05' })]),
  ]);
  w.body.append(f); w.install();
  const el = id => w.document.getElementById(id);
  const ids = {}; w.focus(el('a'))[1].fields.forEach(x => { ids[x.eid] = x.id; });
  w.events.length = 0; const sets = w.nativeSets;
  const out = JSON.parse(w.api().fill({ [ids.a]: 'Ana', [ids.sel]: 'Spain', [ids.mm]: '05', [ids.b]: 'x' }, false));
  check(out.filled === 4 && el('a').value === 'Ana' && el('b').value === 'x', 'a fill sets the values');
  check(el('sel').value === 'ES', 'a <select> is matched by its option text');
  check(el('mm').value === '5', 'a numeric select matches "05" to the option "5"');
  check(w.nativeSets - sets === 4, 'values go through the native setter (React/Vue controlled inputs see them)');
  check(w.events.filter(e => e[0] === 'a').map(e => e[1]).join() === 'input,change', 'input then change are dispatched');
  check(JSON.parse(w.api().fill({ [ids.sel]: 'Atlantis' }, false)).filled === 0 && el('sel').value === 'ES', 'a select with no matching option is left alone');
  el('a')._autofilled = true; el('b')._autofilled = true;
  const skipped = JSON.parse(w.api().fill({ [ids.a]: 'NEW', [ids.b]: 'NEW' }, false));
  check(skipped.filled === 0 && el('a').value === 'Ana', 'a field the framework filled is not overwritten by a profile fill');
  const forced = JSON.parse(w.api().fill({ [ids.a]: 'NEW' }, true));
  check(forced.filled === 1 && el('a').value === 'NEW', 'a secret fill the user asked for may overwrite it');
  check(JSON.parse(w.api().fill({ 999: 'x' }, false)).filled === 0, 'an unknown id is ignored');
}

// ── 6. read, focusField, pageForm, firstLogin ────────────────────────────────────
{
  const w = makeWorld();
  const small = w.make('form', { id: 'search' }, [I(w, { id: 's', name: 'q' })]);
  const big = w.make('form', { id: 'signup' }, [I(w, { id: 'n', name: 'n', value: 'Ana' }), I(w, { id: 'e', type: 'email' }), I(w, { id: 'p', type: 'password', value: 'pw' })]);
  w.body.append(small); w.body.append(big); w.install();
  const pf = JSON.parse(w.api().pageForm());
  check(pf.formKey === 'signup' && pf.fields.length === 3, 'pageForm picks the form with the most visible fields');
  check(!JSON.stringify(pf).match(/Ana|pw/), 'and reports no value');
  const ids = {}; pf.fields.forEach(x => { ids[x.eid] = x.id; });
  const rd = JSON.parse(w.api().read([ids.n, ids.p]));
  check(rd[ids.n] === 'Ana' && rd[ids.p] === 'pw', 'read answers the current values of the asked fields only');
  const login = JSON.parse(w.api().firstLogin());
  check(login.password === true && login.field === ids.e, 'firstLogin picks the box before the password box');
  check(w.api().focusField(ids.n) === 'ok', 'focusField answers');
}

// ── 7. the page keeps nothing ────────────────────────────────────────────────────
{
  const w = makeWorld(); w.body.append(w.make('form', {}, [I(w, { id: 'a', name: 'a', value: 'v' })])); w.install();
  const keys = Object.keys(w.win).filter(k => /^__/.test(k));
  check(keys.join() === '__ac', 'the only global the engine adds is window.__ac');
}
console.log(out.join('\n'));
