// Tier 2 of the fleet autofill (a0_docs/eng-specs/autofill-3-tier.md): the DOM engine for
// NON-SECRET data (names, emails, phones, postal addresses) from the Cloud Account SOT.
//
// Installed once per document by DomAutofill (evaluateJavascript after load; the host passes
// the CFG placeholder = {incognito, rules:[{form, field, key, value}]} for THIS host only). It:
//   * scans forms on load and again whenever the DOM changes (MutationObserver, SPA routes),
//     classifying every field: site rule first, then the WHATWG autocomplete token, then
//     type=email/tel, then id/name/label/placeholder words in EN/ES/PT/FR/DE;
//   * NEVER classifies a secret: type=password, autocomplete current-/new-password,
//     one-time-code, cc-*, username, or words that say password/OTP/card/IBAN/PIN. A rule
//     cannot override that. Secrets are Cloud Vault's, through the Android Autofill framework;
//   * leaves a LOGIN form's email/username to Vault (the email collision rule) and fills an
//     email only in a contact/address form;
//   * on focus of a classified field tells the host (CloudAutofill.focus) which keys its form
//     needs — never a value — so the host shows the "Fill address: <profile>" chip; values
//     only arrive back through fill(), after the user tapped;
//   * fills with the NATIVE value setter (so React/Vue/Angular see it) and dispatches input,
//     change and blur;
//   * defers to the framework: a field Android autofill filled (:autofill / :-webkit-autofill,
//     or flagged by frameworkFilled()) is never DOM-completed again for this page session;
//   * on submit of an address form, outside incognito, offers the typed address to the host
//     for a user-confirmed save (CloudAutofill.submitted). Nothing is ever logged.
(function (cfg) {
  var w = window, d = document;
  if (w.__cloudAutofill && w.__cloudAutofill.v === 1) { w.__cloudAutofill.configure(cfg); return 'already'; }

  var SKIP_TYPES = { hidden: 1, submit: 1, button: 1, checkbox: 1, radio: 1, file: 1, image: 1, reset: 1, range: 1, color: 1 };
  // Sign-in secrets (they make a form a LOGIN form) and payment secrets (they do not): both are Vault's.
  var AUTH_AC = /(^|\s)(current-password|new-password|one-time-code|username|webauthn)(\s|$)/;
  var PAY_AC = /(^|\s)cc-[a-z-]+(\s|$)/;
  var AUTH_WORDS = /pass(word|wort|e)?\b|passwd|pwd|contrase|senha|mot de passe|kennwort|\botp\b|one.?time|2fa|totp|verification.?code|codigo de verificacao|codigo de verificacion|code de verification|bestatigungscode|\bpin\b|\btan\b/;
  var PAY_WORDS = /sicherheitscode|security.?code|\bcvv|\bcvc|\bcsc\b|card.?number|credit.?card|kartennummer|numero de (la )?tarjeta|numero do cartao|numero de carte|\biban\b|\bssn\b/;
  // Identity documents (national ID, passport, residence permit, tax numbers): Cloud Vault's Identity items, never ours.
  var ID_WORDS = /\bdni\b|\bnie\b|\bnif\b|personalausweis|ausweis|passport|pasaporte|passaporte|reisepass|\bcpf\b|\brg\b|residence.?permit|aufenthaltstitel|tarjeta de residencia|id.?number|national.?id|identity.?card|carte d.?identite|numero de documento|document.?number|\bssn\b|tax.?id|steuer.?id|steuernummer/;
  var LOGIN_WORDS = /log.?in|sign.?in|signin|auth|session|anmeld|iniciar.?sesion|connexion|se connecter|entrar|acceder/;

  // WHATWG autocomplete token -> SOT field key (address-line3 and the street lines fold into ours).
  var AC = {
    'name': 'name', 'honorific-prefix': 'honorific-prefix', 'given-name': 'given-name', 'additional-name': 'additional-name',
    'family-name': 'family-name', 'email': 'email', 'tel': 'tel', 'tel-national': 'tel', 'organization': 'organization',
    'organization-title': 'organization-title', 'street-address': 'street-address', 'address-line1': 'address-line1',
    'address-line2': 'address-line2', 'address-line3': 'address-line2', 'address-level2': 'address-level2',
    'address-level1': 'address-level1', 'postal-code': 'postal-code', 'country': 'country', 'country-name': 'country',
    'bday': 'bday', 'url': 'url'
  };

  // Words -> key, in priority order (most specific first). Text is lowercased, accents stripped.
  var WORDS = [
    ['email', /e.?mail|correo|courriel|e-post/],
    ['postal-code', /zip|postal.?code|postcode|post.?code|\bplz\b|postleitzahl|codigo postal|\bcep\b|code postal|\bcp\b/],
    ['tel', /phone|\btel\b|telephone|mobile|\bcell|telefon|telefono|celular|\bmovil|handy|portable/],
    ['x-nationality', /nationality|nacionalidad|nacionalidade|staatsangeh|nationalite/],
    ['x-family-name-1', /primer apellido|1er apellido|apellido 1|first surname|apellido paterno/],
    ['x-family-name-2', /segundo apellido|2o apellido|apellido 2|second surname|apellido materno/],
    ['given-name', /first.?name|\bfname|given.?name|forename|vorname|prenom|primeiro nome|nombre de pila|^nombre$|\bnombre\b(?!.*(apellido|complet))/],
    ['family-name', /last.?name|\blname|surname|family.?name|nachname|apellido|sobrenome|nom de famille|\bnom\b(?! complet)/],
    ['organization', /company|organi[sz]ation|\bfirma|empresa|societe|entreprise|unternehmen/],
    ['x-co', /c\/o\b|care of|zu handen|\bz ?\.? ?hd\b/],
    ['x-floor-door', /\bpiso\b|planta|puerta|escalera|\betage\b|stockwerk|\bfloor\b|\bdoor\b|\bandar\b/],
    ['address-line2', /address.?(line)?.?2|line.?2|apartment|\bapt\b|\bapto\b|suite|\bunit\b|apartamento|complemento|wohnung|appartement|bloco|adresszusatz/],
    ['x-house-number', /hausnummer|haus.?nr|house.?(number|no)\b|street.?number|\bnr\b|\bnro\b|\bnumero\b|\bnum\b/],
    ['address-level3', /bairro|barrio|neighbou?rhood|colonia|ortsteil|quartier/],
    ['address-line1', /street|address|\baddr|stra(ss|\u00df)e|calle|direccion|endereco|logradouro|\brua\b|adresse|\brue\b/],
    ['address-level2', /city|town|locality|\bort\b|stadt|ciudad|localidad|cidade|ville|municipio|commune/],
    ['address-level1', /state|province|region|county|bundesland|provincia|\bestado\b|departement/],
    ['country', /country|\bland\b|\bpais\b|\bpays\b/],
    ['bday', /birth|\bdob\b|geburt|nacimiento|nascimento|naissance/],
    ['name', /full.?name|your.?name|^name$|\bname\b|nombre completo|nome completo|nom complet|vollstandiger name|\bnome\b|\bnombre\b/]
  ];
  // A free-text box that may take a snippet — on an explicit tap only, never in a block fill.
  var SNIPPET_WORDS = /about|\bbio\b|biograph|message|comment|nachricht|mensaje|mensagem|commentaire|kommentar|description|beschreibung|sobre mi|sobre mim|motivation|cover.?letter|anschreiben|carta/;
  var ADDRESS_KEYS = { 'street-address': 1, 'address-line1': 1, 'address-line2': 1, 'x-street': 1, 'x-house-number': 1, 'x-floor-door': 1,
    'x-complement': 1, 'x-co': 1, 'address-level3': 1, 'address-level2': 1, 'address-level1': 1, 'postal-code': 1, 'country': 1 };

  var meta = new WeakMap();       // element -> {id, key, secret, literal}
  var byId = [];                  // id -> element
  var deferred = new WeakSet();   // framework-filled: never touched again this page session
  var ours = new WeakSet();       // filled by us (so our own events are not read as the framework's)
  var filling = false, lastFocus = null, submittedOnce = false;
  var state = { incognito: !!(cfg && cfg.incognito), rules: (cfg && cfg.rules) || [] };

  function norm(s) {
    s = String(s || '').toLowerCase();
    try { s = s.normalize('NFD').replace(/[̀-ͯ]/g, ''); } catch (e) {}
    return s.replace(/[_\-\[\]]+/g, ' ');
  }
  function attr(e, n) { return (e.getAttribute && e.getAttribute(n)) || ''; }
  function labelText(e) {
    var t = '';
    if (e.labels && e.labels.length) t = e.labels[0].textContent || '';
    var by = attr(e, 'aria-labelledby');
    if (!t && by && d.getElementById) { var l = d.getElementById(by.split(' ')[0]); if (l) t = l.textContent || ''; }
    return t.slice(0, 120);
  }
  function words(e) {
    return norm([e.name, e.id, labelText(e), attr(e, 'aria-label'), e.placeholder || attr(e, 'placeholder'), attr(e, 'title')].join(' '));
  }
  function formOf(e) { return e.form || (e.closest && e.closest('form')) || null; }
  function matches(e, sel) { try { return !!(sel && e.matches && e.matches(sel)); } catch (x) { return false; } }
  function visible(e) {
    if (e.disabled || e.readOnly) return false;
    if (e.getClientRects && e.getClientRects().length === 0) return false;
    return true;
  }

  // The pure classification of one field: {key, secret, literal}. key null = not ours to fill.
  function classify(e) {
    var tag = (e.tagName || '').toUpperCase();
    var type = String(e.type || (tag === 'SELECT' ? 'select-one' : tag === 'TEXTAREA' ? 'textarea' : 'text')).toLowerCase();
    if (SKIP_TYPES[type]) return { key: null };
    var ac = String(attr(e, 'autocomplete')).toLowerCase().trim();
    var ws = words(e);
    if (type === 'password' || AUTH_AC.test(ac) || AUTH_WORDS.test(ws)) return { key: null, secret: true, auth: true };
    if (PAY_AC.test(ac) || PAY_WORDS.test(ws)) return { key: null, secret: true };
    if (ID_WORDS.test(ws)) return { key: null, secret: true, id: true };
    var im = String(attr(e, 'inputmode')).toLowerCase(), ml = +attr(e, 'maxlength') || 0;
    if ((im === 'numeric' || type === 'number') && ml > 0 && ml <= 8 && /\bcode\b|codigo|\bcode/.test(ws)) return { key: null, secret: true, auth: true };
    var form = formOf(e);
    for (var i = 0; i < state.rules.length; i++) {
      var r = state.rules[i];
      if (r.form && !(form && matches(form, r.form))) continue;
      if (!matches(e, r.field)) continue;
      if (r.key === 'ignore') return { key: null, rule: true };
      return { key: r.key || 'literal', literal: r.value || '', rule: true };
    }
    if (ac && ac !== 'on' && ac !== 'off') {
      var toks = ac.split(/\s+/);
      for (var j = toks.length - 1; j >= 0; j--) if (AC[toks[j]]) return { key: AC[toks[j]], src: 'ac' };
    }
    if (type === 'email') return { key: 'email' };
    if (type === 'tel') return { key: 'tel' };
    if (type === 'number' || type === 'date' && !/birth|geburt|nacimiento|nascimento|naissance|dob/.test(ws)) return { key: null };
    if (tag === 'TEXTAREA' || type === 'textarea') return SNIPPET_WORDS.test(ws) ? { key: 'x-snippet', src: 'words' } : { key: null };
    // "Street and house number" in ONE box is the whole first line, not the number.
    if (/stra(ss|\u00df)e|street|calle|\brua\b|\brue\b/.test(ws) && /hausnummer|house.?(number|no)|\bnumero\b|\bnr\b/.test(ws)) return { key: 'address-line1', src: 'words' };
    for (var k = 0; k < WORDS.length; k++) if (WORDS[k][1].test(ws)) return { key: WORDS[k][0], src: 'words' };
    return { key: null };
  }

  function fields(root) {
    var list = (root || d).querySelectorAll ? (root || d).querySelectorAll('input, select, textarea') : [];
    return Array.prototype.slice.call(list);
  }
  function info(e) {
    var m = meta.get(e);
    if (!m) { m = classify(e); m.id = byId.length; byId.push(e); meta.set(e, m); }
    return m;
  }
  // A login form: it holds a password / OTP field, says so, or is a lone identity field next to one.
  function isLogin(form) {
    var fs = form ? fields(form) : [];
    for (var i = 0; i < fs.length; i++) {
      var m = info(fs[i]);
      if (m.auth) return true;   // a password / OTP / username field; a card field does not make a checkout a login
    }
    if (!form) return false;
    var tag = norm([form.id, form.name, attr(form, 'action'), attr(form, 'class')].join(' '));
    var textish = fs.filter(function (f) { var t = String(f.type || 'text').toLowerCase(); return !SKIP_TYPES[t]; }).length;
    return LOGIN_WORDS.test(tag) && textish <= 2;
  }
  // What a group (the field's form, or the formless page) can take: [{id, key, literal}] for fillable fields.
  function group(e) {
    var form = formOf(e), login = isLogin(form);
    var out = [], keys = {};
    var members = (form ? fields(form) : fields(d).filter(function (f) { return !formOf(f); })).filter(function (f) {
      var m = info(f);
      return m.key && !m.secret && m.key !== 'x-snippet' && !deferred.has(f) && visible(f) &&
        !(login && (m.key === 'email' || m.key === 'tel' || m.key === 'name'));   // a login form's identity is Vault's
    });
    // A form that asks the house number on its own wants the street alone in its "address" box.
    var split = members.some(function (f) { return info(f).key === 'x-house-number'; });
    members.forEach(function (f) {
      var m = info(f);
      var key = split && m.key === 'address-line1' && m.src !== 'ac' ? 'x-street' : m.key;
      out.push({ id: m.id, key: key, literal: m.literal || '' });
      keys[key] = 1;
    });
    return { form: form, login: login, fields: out, keys: Object.keys(keys) };
  }
  function kind(keys) {
    var a = keys.filter(function (k) { return ADDRESS_KEYS[k]; }).length;
    return a >= 1 ? 'address' : 'contact';
  }

  function bridge(fn, payload) {
    try { if (w.CloudAutofill && w.CloudAutofill[fn]) w.CloudAutofill[fn](JSON.stringify(payload)); } catch (x) {}
  }

  function onFocus(ev) {
    var e = ev.target;
    if (!e || !e.tagName || !/^(INPUT|SELECT|TEXTAREA)$/i.test(e.tagName)) return;
    var m = info(e);
    lastFocus = e;
    if (!m.key || m.secret || deferred.has(e)) return bridge('none', { why: m.secret ? 'secret' : deferred.has(e) ? 'framework' : 'unclassified' });
    if (m.key === 'x-snippet') return bridge('focus', { field: m.id, key: m.key, kind: 'snippet', keys: [m.key], fields: [{ id: m.id, key: m.key, literal: '' }], incognito: state.incognito });
    var g = group(e);
    var self = g.fields.filter(function (f) { return f.id === m.id; }).length > 0;
    if (!self) return bridge('none', { why: g.login ? 'login' : 'hidden' });
    bridge('focus', { field: m.id, key: m.key, kind: kind(g.keys), keys: g.keys, fields: g.fields, incognito: state.incognito });
  }

  function nativeSet(e, value) {
    var tag = (e.tagName || '').toUpperCase();
    var proto = tag === 'SELECT' ? w.HTMLSelectElement.prototype : tag === 'TEXTAREA' ? w.HTMLTextAreaElement.prototype : w.HTMLInputElement.prototype;
    var desc = Object.getOwnPropertyDescriptor(proto, 'value');
    if (tag === 'SELECT') {
      var want = norm(value), opts = e.options || [], hit = null;
      for (var i = 0; i < opts.length && !hit; i++) if (norm(opts[i].value) === want || norm(opts[i].textContent || opts[i].text) === want) hit = opts[i].value;
      if (hit === null) return false;
      value = hit;
    }
    if (desc && desc.set) desc.set.call(e, value); else e.value = value;
    return true;
  }
  function fire(e, type) {
    var ev;
    try { ev = new w.Event(type, { bubbles: type !== 'blur' }); } catch (x) { ev = d.createEvent('Event'); ev.initEvent(type, type !== 'blur', false); }
    e.dispatchEvent(ev);
  }

  // values: {fieldId: text}. Only fields this engine classified as non-secret and not deferred; answers {filled}.
  function fill(values) {
    var n = 0;
    filling = true;
    try {
      Object.keys(values || {}).forEach(function (k) {
        var e = byId[+k], m = e && meta.get(e);
        if (!e || !m || !m.key || m.secret || deferred.has(e) || String(e.type).toLowerCase() === 'password') return;
        if (classify(e).secret) return;          // re-checked now: the page may have changed the field since the scan
        if (!visible(e)) return;
        if (!nativeSet(e, String(values[k]))) return;
        ours.add(e);
        fire(e, 'input'); fire(e, 'change'); fire(e, 'blur'); fire(e, 'focusout');
        n++;
      });
    } finally { filling = false; }
    return { filled: n };
  }

  function isFrameworkFilled(e) {
    var hit = false;
    try { hit = e.matches(':autofill'); } catch (x) {}
    if (!hit) try { hit = e.matches(':-webkit-autofill'); } catch (x) {}
    return hit;
  }
  function onValue(ev) {
    var e = ev.target;
    if (filling || !e || !meta.has(e)) return;
    if (isFrameworkFilled(e)) deferred.add(e);
  }
  // The host calls this when Android's autofill framework filled the page (a Vault dataset was picked).
  function frameworkFilled() {
    var n = 0;
    byId.forEach(function (e) { if (e && isFrameworkFilled(e) && !deferred.has(e)) { deferred.add(e); n++; } });
    if (lastFocus && !ours.has(lastFocus) && meta.has(lastFocus) && !deferred.has(lastFocus) && arguments[0] === 'focused') { deferred.add(lastFocus); n++; }
    return { deferred: n };
  }

  function onSubmit(ev) {
    if (state.incognito || submittedOnce) return;
    var form = ev.target;
    if (!form || !form.tagName || form.tagName.toUpperCase() !== 'FORM') return;   // a login form has no address block: it never reaches the offer
    var vals = {}, addr = 0, all = fields(form);
    var split = all.some(function (f) { return info(f).key === 'x-house-number'; });
    all.forEach(function (f) {
      var m = info(f);
      if (!m.key || m.secret || m.literal || m.key === 'literal' || m.key === 'x-snippet') return;
      var v = String(f.value || '').trim();
      if (!v || v.length > 200) return;
      var key = split && m.key === 'address-line1' && m.src !== 'ac' ? 'x-street' : m.key;
      vals[key] = v;
      if (ADDRESS_KEYS[key]) addr++;
    });
    if (addr < 2 || !(vals['address-line1'] || vals['x-street'] || vals['street-address'] || vals['postal-code'])) return;
    submittedOnce = true;
    bridge('submitted', { fields: vals });
  }

  function scan() {
    var n = 0, secret = 0, groups = 0;
    fields(d).forEach(function (f) { var m = info(f); if (m.key) n++; if (m.secret) secret++; });
    return { classified: n, secret: secret };
  }

  var pending = null;
  function rescanSoon() { if (pending) return; pending = setTimeout(function () { pending = null; scan(); }, 250); }

  d.addEventListener('focusin', onFocus, true);
  d.addEventListener('input', onValue, true);
  d.addEventListener('change', onValue, true);
  d.addEventListener('submit', onSubmit, true);
  if (w.MutationObserver) { try { new w.MutationObserver(rescanSoon).observe(d.documentElement || d.body, { childList: true, subtree: true }); } catch (x) {} }
  ['pushState', 'replaceState'].forEach(function (fn) {
    var h = w.history, orig = h && h[fn];
    if (typeof orig !== 'function') return;
    h[fn] = function () { var r = orig.apply(this, arguments); submittedOnce = false; rescanSoon(); return r; };
  });
  w.addEventListener && w.addEventListener('popstate', rescanSoon);

  w.__cloudAutofill = {
    v: 1, scan: scan, fill: fill, frameworkFilled: frameworkFilled, classify: classify, idOf: function (e) { return info(e).id; },
    configure: function (c) { state.incognito = !!(c && c.incognito); state.rules = (c && c.rules) || []; meta = new WeakMap(); byId = []; scan(); },
    deferredCount: function () { return byId.filter(function (e) { return deferred.has(e); }).length; }
  };
  return JSON.stringify(scan());
})(__CFG__)
