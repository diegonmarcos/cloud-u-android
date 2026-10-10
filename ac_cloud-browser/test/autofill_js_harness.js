// Tier 2 DOM autofill (a0_docs/eng-specs/autofill-3-tier.md): runs assets/browser/autofill_engine.js
// against a small fake DOM that models what matters here — labels, forms, the native value setter vs
// a React-style controlled input (value tracker), event dispatch with capture/bubble, :-webkit-autofill,
// MutationObserver and history. Fixtures are obvious fakes.
// usage: node autofill_js_harness.js <assets dir>   -> one `ok|bad <label>` line per assertion.
const fs = require('fs'), vm = require('vm'), path = require('path');
const dir = process.argv[2];
const out = [];
const check = (c, l) => out.push((c ? 'ok ' : 'bad ') + l);
const ENGINE = fs.readFileSync(path.join(dir, 'autofill_engine.js'), 'utf8');

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
    CloudAutofill: {
      focus: j => bridge.calls.push(['focus', JSON.parse(j)]),
      none: j => bridge.calls.push(['none', JSON.parse(j)]),
      submitted: j => bridge.calls.push(['submitted', JSON.parse(j)]),
    },
  };
  win.window = win;
  world.win = win; world.historyCalls = historyCalls;
  world.ctx = vm.createContext(win);
  world.install = cfg => vm.runInContext(ENGINE.split('__CFG__').join(JSON.stringify(cfg || { incognito: false, rules: [] })), world.ctx);
  world.focus = el => { el.dispatchEvent(new Ev('focusin', { bubbles: true })); return bridge.calls[bridge.calls.length - 1]; };
  world.api = () => win.__cloudAutofill;
  return world;
}
const I = (w, attrs) => w.make('input', attrs);
const L = (w, forId, text) => w.make('label', { for: forId, _text: text });

// ── 1. autocomplete tokens win, keys are the WHATWG tokens ──────────────────────
{
  const w = makeWorld();
  const f = w.make('form', { id: 'ship' }, [
    I(w, { id: 'a', autocomplete: 'shipping given-name' }), I(w, { id: 'b', autocomplete: 'family-name' }),
    I(w, { id: 'c', autocomplete: 'email' }), I(w, { id: 'd', autocomplete: 'section-x shipping tel' }),
    I(w, { id: 'e', autocomplete: 'address-line1', name: 'phone' }), I(w, { id: 'f', autocomplete: 'postal-code' }),
    I(w, { id: 'g', autocomplete: 'address-level2' }), w.make('select', { id: 'h', autocomplete: 'country' }, [w.make('option', { value: 'ZZ', _text: 'Zedland' })]),
  ]);
  w.body.append(f); w.install();
  const api = w.api(); const k = id => api.classify(w.document.getElementById(id)).key;
  check(k('a') === 'given-name' && k('b') === 'family-name' && k('c') === 'email' && k('d') === 'tel', 'autocomplete tokens map 1:1 (section/shipping prefixes skipped)');
  check(k('e') === 'address-line1', 'the autocomplete token beats a misleading name ("phone")');
  check(k('f') === 'postal-code' && k('g') === 'address-level2' && k('h') === 'country', 'postal, city and a country <select> classified');
  const r = w.focus(w.document.getElementById('a'));
  check(r[0] === 'focus' && r[1].kind === 'address' && r[1].fields.length === 8, 'focus on a classified field reports the whole address block');
  check(!JSON.stringify(r[1]).match(/Zedland|value/), 'the focus report carries keys and ids, never a value');
}

// ── 2. multilingual heuristics (no autocomplete) ─────────────────────────────
{
  const langs = {
    en: [['First name', 'given-name'], ['Last name', 'family-name'], ['Email address', 'email'], ['Phone number', 'tel'], ['Street address', 'address-line1'], ['Apartment, suite', 'address-line2'], ['ZIP code', 'postal-code'], ['City', 'address-level2'], ['State', 'address-level1'], ['Country', 'country'], ['Company', 'organization']],
    es: [['Nombre', 'given-name'], ['Apellidos', 'family-name'], ['Correo electrónico', 'email'], ['Teléfono', 'tel'], ['Dirección', 'address-line1'], ['Código postal', 'postal-code'], ['Ciudad', 'address-level2'], ['Provincia', 'address-level1'], ['País', 'country'], ['Empresa', 'organization']],
    pt: [['Primeiro nome', 'given-name'], ['Sobrenome', 'family-name'], ['E-mail', 'email'], ['Telefone', 'tel'], ['Endereço', 'address-line1'], ['Complemento', 'address-line2'], ['CEP', 'postal-code'], ['Cidade', 'address-level2'], ['Estado', 'address-level1'], ['Nome completo', 'name']],
    fr: [['Prénom', 'given-name'], ['Nom', 'family-name'], ['Adresse e-mail', 'email'], ['Téléphone', 'tel'], ['Adresse', 'address-line1'], ['Code postal', 'postal-code'], ['Ville', 'address-level2'], ['Pays', 'country'], ['Entreprise', 'organization']],
    de: [['Vorname', 'given-name'], ['Nachname', 'family-name'], ['E-Mail', 'email'], ['Telefon', 'tel'], ['Straße und Hausnummer', 'address-line1'], ['Adresszusatz', 'address-line2'], ['PLZ', 'postal-code'], ['Ort', 'address-level2'], ['Bundesland', 'address-level1'], ['Land', 'country'], ['Firma', 'organization']],
  };
  for (const [lang, rows] of Object.entries(langs)) {
    const w = makeWorld();
    const f = w.make('form', { id: 'f' });
    rows.forEach(([lab], i) => { f.append(L(w, 'x' + i, lab)); f.append(I(w, { id: 'x' + i, name: 'field' + i })); });
    w.body.append(f); w.install();
    const bad = rows.map(([lab, key], i) => [lab, key, w.api().classify(w.document.getElementById('x' + i)).key]).filter(r => r[1] !== r[2]);
    check(bad.length === 0, `${lang}: every label classified (${bad.map(b => b[0] + '->' + b[2]).join(', ') || 'all ok'})`);
  }
  // placeholder / aria-label / name / id carry it as well as a <label>
  const w = makeWorld();
  w.body.append(w.make('form', {}, [I(w, { id: 'p', placeholder: 'Your e-mail' }), I(w, { id: 'q', 'aria-label': 'Código postal' }), I(w, { id: 'r', name: 'billing_zip' }), I(w, { id: 'customer_city' })]));
  w.install(); const c = id => w.api().classify(w.document.getElementById(id)).key;
  check(c('p') === 'email' && c('q') === 'postal-code' && c('r') === 'postal-code' && c('customer_city') === 'address-level2', 'placeholder, aria-label, name and id are read');
}

// ── 3. secrets are never classified, whatever else the field says ────────────
{
  const w = makeWorld();
  w.body.append(w.make('form', { id: 'pay' }, [
    I(w, { id: 's1', type: 'password', name: 'email' }), I(w, { id: 's2', autocomplete: 'current-password' }), I(w, { id: 's3', autocomplete: 'new-password' }),
    I(w, { id: 's4', autocomplete: 'one-time-code' }), I(w, { id: 's5', autocomplete: 'cc-number' }), I(w, { id: 's6', autocomplete: 'cc-csc' }),
    I(w, { id: 's7', autocomplete: 'billing cc-exp' }), I(w, { id: 's8', name: 'otp' }), I(w, { id: 's9', placeholder: 'Card number' }),
    I(w, { id: 's10', 'aria-label': 'Kennwort' }), I(w, { id: 's11', placeholder: 'Contraseña' }), I(w, { id: 's12', 'aria-label': 'Senha' }),
    I(w, { id: 's13', inputmode: 'numeric', maxlength: '6', placeholder: 'Código' }), I(w, { id: 's14', name: 'iban' }),
    I(w, { id: 's15', 'aria-label': 'Mot de passe' }), I(w, { id: 's16', autocomplete: 'username', type: 'email' }),
    I(w, { id: 's17', placeholder: 'Verification code' }), I(w, { id: 's18', name: 'cvv' }),
  ]));
  w.install({ incognito: false, rules: [{ form: '', field: '#s1', key: 'email' }, { form: '', field: '#s5', key: 'postal-code' }] });
  const leaks = [];
  for (let i = 1; i <= 18; i++) { const r = w.api().classify(w.document.getElementById('s' + i)); if (r.key || !r.secret) leaks.push('s' + i); }
  check(leaks.length === 0, 'password / OTP / card / IBAN / username fields are secret, never a key (' + (leaks.join(',') || 'none leak') + ')');
  check(w.api().classify(w.document.getElementById('s1')).key === null, 'a site rule cannot turn a password field into a fillable one');
  const r = w.focus(w.document.getElementById('s2'));
  check(r[0] === 'none' && r[1].why === 'secret', 'focus on a secret field: the browser offers nothing (Vault/keyboard own it)');
  const fill = w.api().fill({ 0: 'x', 1: 'x', 4: 'x' });
  check(fill.filled === 0 && w.document.getElementById('s1').value === '', 'fill() refuses secret fields even when asked by id');
}

// ── 4. precedence: login form email is Vault's, contact form email is ours ───
{
  const w = makeWorld();
  const login = w.make('form', { id: 'login', action: '/session' }, [I(w, { id: 'le', type: 'email', name: 'email' }), I(w, { id: 'lp', type: 'password', name: 'password' })]);
  const contact = w.make('form', { id: 'contact' }, [I(w, { id: 'cn', name: 'name', placeholder: 'Your name' }), I(w, { id: 'ce', type: 'email', name: 'email' }), w.make('textarea', { id: 'cm', name: 'message' })]);
  const signin2 = w.make('form', { id: 'signin', class: 'sign-in' }, [I(w, { id: 'se', name: 'email', placeholder: 'E-mail' })]);
  w.body.append(login); w.body.append(contact); w.body.append(signin2); w.install();
  let r = w.focus(w.document.getElementById('le'));
  check(r[0] === 'none' && r[1].why === 'login', 'login form: focusing its email gives no browser chip (left to Vault)');
  r = w.focus(w.document.getElementById('lp'));
  check(r[0] === 'none' && r[1].why === 'secret', 'login form: focusing its password gives no browser chip');
  r = w.focus(w.document.getElementById('se'));
  check(r[0] === 'none' && r[1].why === 'login', 'a passwordless "sign in" step (email only) is treated as login too');
  r = w.focus(w.document.getElementById('ce'));
  check(r[0] === 'focus' && r[1].key === 'email' && r[1].kind === 'contact' && r[1].keys.sort().join() === 'email,name', 'contact form: the email is the browser\'s (pre-fill allowed)');
  const checkout = w.make('form', { id: 'checkout' }, [I(w, { id: 'ke', autocomplete: 'email' }), I(w, { id: 'ks', autocomplete: 'address-line1' }), I(w, { id: 'kc', autocomplete: 'cc-number' })]);
  w.body.append(checkout);
  r = w.focus(w.document.getElementById('ke'));
  check(r[0] === 'focus' && r[1].key === 'email' && !r[1].fields.some(f => f.id === w.api().idOf(w.document.getElementById('kc'))) && r[1].fields.length === 2, 'a checkout form (card fields) is not a login: its email and address are the browser\'s, the card is not');
  r = w.focus(w.document.getElementById('cm'));
  check(r[0] === 'focus' && r[1].kind === 'snippet' && r[1].fields.length === 1, 'a message box is a snippet field: its own chip, only that field');
  check(!w.focus(w.document.getElementById('ce'))[1].fields.some(f => f.key === 'x-snippet'), 'a snippet field is never part of a block fill');
  const notes = w.make('textarea', { id: 'nt', name: 'internal_notes_2' }); contact.append(notes);
  r = w.focus(notes);
  check(r[0] === 'none' && r[1].why === 'unclassified', 'an unclassified field: browser none (the keyboard\'s candidates take it)');
}

// ── 5. fill: native setter, React-controlled inputs, events, select, deference ─
{
  const w = makeWorld();
  const name = I(w, { id: 'n', autocomplete: 'name' }), email = I(w, { id: 'e', autocomplete: 'email' }), zip = I(w, { id: 'z', autocomplete: 'postal-code' });
  const country = w.make('select', { id: 'c', autocomplete: 'country' }, [w.make('option', { value: '', _text: '—' }), w.make('option', { value: 'ZZ', _text: 'Zedland' })]);
  const hidden = I(w, { id: 'h', autocomplete: 'address-level2', hidden: true });
  w.body.append(w.make('form', { id: 'f' }, [name, email, zip, country, hidden]));
  // React-style controlled input: instance setter updates the tracker, so a plain `el.value =` is invisible to onChange.
  const P = Object.getOwnPropertyDescriptor(w.win.HTMLInputElement.prototype, 'value');
  const react = (el) => { let tracked = el.value; const seen = []; Object.defineProperty(el, 'value', { configurable: true, get() { return P.get.call(el); }, set(v) { tracked = String(v); P.set.call(el, v); } });
    el.addEventListener('input', () => { const cur = P.get.call(el); if (cur !== tracked) { tracked = cur; seen.push(cur); } }); return seen; };
  const reactSeen = react(name);
  // control: the naive way does NOT reach React (proves the harness models the tracker)
  name.value = 'naive'; name.dispatchEvent(new w.win.Event('input', { bubbles: true }));
  check(reactSeen.length === 0, 'control: el.value= + input event is invisible to a React-controlled input');
  P.set.call(name, '');
  w.install();
  const plan = w.focus(name)[1].fields;
  const id = k => plan.find(f => f.key === k).id;
  check(!plan.some(f => f.key === 'address-level2'), 'a hidden field is not part of the fill plan');
  w.events.length = 0;
  const res = w.api().fill({ [id('name')]: 'Testy McTestface', [id('email')]: 'testy@example.invalid', [id('postal-code')]: '00000', [id('country')]: 'zedland', 999: 'nope' });
  check(res.filled === 4, 'fill() fills the four fields it classified (unknown ids ignored)');
  check(reactSeen.length === 1 && reactSeen[0] === 'Testy McTestface', 'React-controlled input sees the value (native setter path)');
  check(country.value === 'ZZ', 'a <select> is matched by its option text and set to the option value');
  const seq = w.events.filter(e => e[0] === 'e').map(e => e[1]).join(',');
  check(seq === 'input,change,blur,focusout', 'events dispatched per field: input, change, blur (+focusout) — got ' + seq);
  // deference: Android autofill framework filled the email (Vault dataset picked) -> never DOM-completed again
  email._autofilled = true; email.dispatchEvent(new w.win.Event('input', { bubbles: true }));
  check(w.api().deferredCount() === 1, 'a field the framework filled (:-webkit-autofill) is deferred');
  let r = w.focus(email);
  check(r[0] === 'none' && r[1].why === 'framework', 'focus on a deferred field: no browser chip for the rest of the page session');
  P.set.call(email, 'vault-picked@example.invalid');
  check(w.api().fill({ [id('email')]: 'overwrite@example.invalid' }).filled === 0 && email.value === 'vault-picked@example.invalid', 'fill() never overwrites a framework-filled field');
  r = w.focus(name);
  check(!r[1].fields.some(f => f.key === 'email'), 'the deferred field drops out of the block plan');
  // frameworkFilled(): the host's signal when Android autofill ran
  zip._autofilled = true;
  check(w.api().frameworkFilled().deferred === 1, 'frameworkFilled() defers every field the framework marked');
}

// ── 6. site rules override heuristics ─────────────────────────────────────────
{
  const w = makeWorld();
  w.body.append(w.make('form', { id: 'chk', class: 'checkout' }, [
    I(w, { id: 'weird1', name: 'f_017' }), I(w, { id: 'em', type: 'email', name: 'email' }), I(w, { id: 'how', name: 'source' }), I(w, { id: 'zip2', name: 'zip' }),
  ]));
  w.body.append(w.make('form', { id: 'other' }, [I(w, { id: 'weird2', name: 'f_017' })]));
  w.install({ incognito: false, rules: [
    { form: 'form.checkout', field: '#weird1', key: 'postal-code', value: '' },
    { form: '', field: 'input[name=email]', key: 'ignore', value: '' },
    { form: '', field: '#how', key: '', value: 'Newsletter' },
  ] });
  const c = id => w.api().classify(w.document.getElementById(id));
  check(c('weird1').key === 'postal-code', 'a rule names a field the heuristics cannot read');
  check(c('weird2').key === null, 'a rule scoped to a form does not leak to another form');
  check(c('em').key === null && c('em').rule, 'a rule can switch a field off (ignore)');
  check(c('how').literal === 'Newsletter', 'a rule can fill a fixed non-secret value');
  check(c('zip2').key === 'postal-code', 'fields without a rule still use the heuristics');
}

// ── 7. save-new-address on submit; never in incognito; never a secret ─────────
for (const incognito of [false, true]) {
  const w = makeWorld();
  const f = w.make('form', { id: 'addr' }, [I(w, { id: 'n', autocomplete: 'name', value: 'Testy McTestface' }), I(w, { id: 's', autocomplete: 'address-line1', value: '1 Example Street' }),
    I(w, { id: 'z', autocomplete: 'postal-code', value: '00000' }), I(w, { id: 'c', autocomplete: 'address-level2', value: 'Sampletown' }), I(w, { id: 'pw', type: 'password', value: 'pw-zz' })]);
  w.body.append(f); w.install({ incognito, rules: [] });
  f.dispatchEvent(new w.win.Event('submit', { bubbles: true }));
  const sub = w.bridge.calls.filter(c => c[0] === 'submitted');
  if (incognito) check(sub.length === 0, 'incognito: a submitted address is never offered for saving');
  else {
    check(sub.length === 1 && sub[0][1].fields['postal-code'] === '00000' && sub[0][1].fields.name === 'Testy McTestface', 'normal tab: the submitted address goes to the host for a user-confirmed save');
    check(!JSON.stringify(sub).includes('pw-zz'), 'the save offer never carries a password');
    f.dispatchEvent(new w.win.Event('submit', { bubbles: true }));
    check(w.bridge.calls.filter(c => c[0] === 'submitted').length === 1, 'one save offer per page');
  }
}
{
  const w = makeWorld();
  const f = w.make('form', { id: 'login' }, [I(w, { id: 'u', type: 'email', value: 'testy@example.invalid' }), I(w, { id: 'p', type: 'password', value: 'x' })]);
  w.body.append(f); w.install(); f.dispatchEvent(new w.win.Event('submit', { bubbles: true }));
  check(w.bridge.calls.filter(c => c[0] === 'submitted').length === 0, 'a login form submit is never offered for saving');
}

// ── 8. dynamic forms and SPA navigation ──────────────────────────────────────
{
  const w = makeWorld(); w.install();
  check(w.observers.length === 1 && w.observers[0].opts.childList && w.observers[0].opts.subtree, 'a MutationObserver watches the document for added forms');
  const late = I(w, { id: 'late', autocomplete: 'email' });
  w.body.append(w.make('form', {}, [late]));
  w.observers[0].cb([]); (w.timers || []).forEach(f => f());
  const r = w.focus(late);
  check(r[0] === 'focus' && r[1].key === 'email', 'a form added after load is classified and offered');
  w.win.history.pushState({}, '', '/next');
  check(w.historyCalls.includes('push'), 'history.pushState still runs (wrapped for SPA rescans)');
  check(w.install() === 'already', 'installing twice keeps one engine (no duplicate listeners)');
}

// ── 9. ES / DE / BR address forms; ID document fields are Vault's ─────────────
{
  const w = makeWorld();
  const es = w.make('form', { id: 'es' }, [
    L(w, 'e1', 'Nombre'), I(w, { id: 'e1' }), L(w, 'e2', 'Primer apellido'), I(w, { id: 'e2' }), L(w, 'e3', 'Segundo apellido'), I(w, { id: 'e3' }),
    L(w, 'e4', 'Calle'), I(w, { id: 'e4' }), L(w, 'e5', 'Número'), I(w, { id: 'e5' }), L(w, 'e6', 'Piso / puerta'), I(w, { id: 'e6' }),
    L(w, 'e7', 'Código postal'), I(w, { id: 'e7' }), L(w, 'e8', 'Nacionalidad'), I(w, { id: 'e8' }),
    L(w, 'e9', 'DNI / NIE'), I(w, { id: 'e9' }),
  ]);
  const de = w.make('form', { id: 'de' }, [
    L(w, 'd1', 'c/o'), I(w, { id: 'd1' }), L(w, 'd2', 'Straße'), I(w, { id: 'd2' }), L(w, 'd3', 'Hausnummer'), I(w, { id: 'd3' }),
    L(w, 'd4', 'PLZ'), I(w, { id: 'd4' }), L(w, 'd5', 'Ort'), I(w, { id: 'd5' }), L(w, 'd6', 'Ausweisnummer'), I(w, { id: 'd6' }),
    L(w, 'd7', 'Reisepass'), I(w, { id: 'd7' }),
  ]);
  const br = w.make('form', { id: 'br' }, [
    L(w, 'b1', 'Endereço'), I(w, { id: 'b1' }), L(w, 'b2', 'Complemento'), I(w, { id: 'b2' }), L(w, 'b3', 'Bairro'), I(w, { id: 'b3' }),
    L(w, 'b4', 'CEP'), I(w, { id: 'b4' }), L(w, 'b5', 'CPF'), I(w, { id: 'b5' }), L(w, 'b6', 'RG'), I(w, { id: 'b6' }),
  ]);
  w.body.append(es); w.body.append(de); w.body.append(br); w.install();
  const k = id => w.api().classify(w.document.getElementById(id));
  check(k('e2').key === 'x-family-name-1' && k('e3').key === 'x-family-name-2', 'ES: primer / segundo apellido are the two family names');
  check(k('e6').key === 'x-floor-door' && k('e8').key === 'x-nationality', 'ES: piso/puerta and nacionalidad');
  const esPlan = w.focus(w.document.getElementById('e4'))[1].fields.map(f => f.key);
  check(esPlan.includes('x-street') && esPlan.includes('x-house-number') && !esPlan.includes('address-line1'), 'ES: with a separate número box the street box takes the street alone');
  check(k('d1').key === 'x-co' && k('d3').key === 'x-house-number', 'DE: c/o and Hausnummer');
  const dePlan = w.focus(w.document.getElementById('d2'))[1].fields.map(f => f.key);
  check(dePlan.includes('x-street') && dePlan.includes('x-co'), 'DE: Straße + Hausnummer split, c/o its own line');
  check(k('b2').key === 'address-line2' && k('b3').key === 'address-level3' && k('b4').key === 'postal-code', 'BR: complemento, bairro, CEP');
  const leaks = ['e9', 'd6', 'd7', 'b5', 'b6'].filter(id => { const r = k(id); return r.key || !r.secret; });
  check(leaks.length === 0, 'ID document fields (DNI/NIE, Ausweisnummer, Reisepass, CPF, RG) are never ours (' + (leaks.join(',') || 'none leak') + ')');
  check(!w.focus(w.document.getElementById('e1'))[1].fields.some(f => f.id === w.api().idOf(w.document.getElementById('e9'))), 'an ID field never joins a block fill');
  check(w.api().fill({ [w.api().idOf(w.document.getElementById('d6'))]: 'X0000000' }).filled === 0 && w.document.getElementById('d6').value === '', 'fill() refuses an ID field even when asked by id');
}

console.log(out.join('\n'));
