// #900 AUTOCOMPLETE page engine. Installed once per document by AcAutocomplete (evaluateJavascript after the
// page loads; CFG = {incognito}). It only COLLECTS signals; every decision (what a field is, what to offer, what
// to save) is Kotlin's (AcFieldClassifier), so it is tested there. It:
//   * on focus of a field, tells the host (CloudAc.focus) the signals of every field of its form
//     (autocomplete attribute, name, id, label, placeholder, aria-label, type, which fields Android autofill
//     already filled) - NEVER a value;
//   * fills on the host's order (fill) with the NATIVE value setter, so React/Vue/Angular see it, then
//     dispatches input and change; a <select> is matched by value or text;
//   * on submit (a form's submit event, or a click on its submit button), reports the typed values once
//     (CloudAc.submitted) so the host can offer to save; empty and file/hidden fields are left out;
//   * notices a field the Android Autofill framework filled (Cloud Vault's dataset: :-webkit-autofill, or a
//     change we did not make) and says so (CloudAc.framework), so the host can read it and apply "vault wins";
//   * keeps nothing in the page: no storage, no cookie, no global except window.__ac; never logs.
(function (cfg) {
  var w = window, d = document;
  if (w.__ac && w.__ac.v === 1) { w.__ac.configure(cfg); return 'already'; }

  var SKIP = { hidden: 1, submit: 1, button: 1, checkbox: 1, radio: 1, file: 1, image: 1, reset: 1, range: 1, color: 1 };
  var meta = new WeakMap();      // element -> {id}
  var byId = [];                 // id -> element
  var ours = new WeakSet();      // filled by us
  var filling = false, lastSubmitAt = 0, fwTimer = null;
  var state = { incognito: !!(cfg && cfg.incognito) };

  function attr(e, n) { return (e.getAttribute && e.getAttribute(n)) || ''; }
  function tagOf(e) { return (e.tagName || '').toUpperCase(); }
  function typeOf(e) {
    var t = tagOf(e);
    return String(e.type || (t === 'SELECT' ? 'select-one' : t === 'TEXTAREA' ? 'textarea' : 'text')).toLowerCase();
  }
  function labelOf(e) {
    var t = '';
    try { if (e.labels && e.labels.length) t = e.labels[0].textContent || ''; } catch (x) {}
    var by = attr(e, 'aria-labelledby');
    if (!t && by && d.getElementById) { var l = d.getElementById(by.split(' ')[0]); if (l) t = l.textContent || ''; }
    return String(t).replace(/\s+/g, ' ').trim().slice(0, 120);
  }
  function formOf(e) { return e.form || (e.closest && e.closest('form')) || null; }
  function visible(e) {
    if (e.disabled || e.readOnly) return false;
    if (e.getClientRects && e.getClientRects().length === 0) return false;
    return true;
  }
  function framework(e) {
    try { if (e.matches(':-webkit-autofill')) return true; } catch (x) {}
    try { if (e.matches(':autofill')) return true; } catch (x) {}
    return false;
  }
  function idOf(e) {
    var m = meta.get(e);
    if (!m) { m = { id: byId.length }; byId.push(e); meta.set(e, m); }
    return m.id;
  }
  function sig(e) {
    return {
      id: idOf(e), autocomplete: attr(e, 'autocomplete'), name: e.name || attr(e, 'name'), eid: e.id || attr(e, 'id'),
      label: labelOf(e), placeholder: e.placeholder || attr(e, 'placeholder'), aria: attr(e, 'aria-label'),
      type: typeOf(e), inputmode: attr(e, 'inputmode'), maxlength: +attr(e, 'maxlength') || 0, fw: framework(e)
    };
  }
  function fieldsOf(root) {
    var list = (root || d).querySelectorAll ? (root || d).querySelectorAll('input, select, textarea') : [];
    return Array.prototype.slice.call(list).filter(function (e) { return !SKIP[typeOf(e)]; });
  }
  function formKey(f) {
    if (!f) return '';
    var k = f.id || attr(f, 'name') || '';
    if (k) return k;
    try { return new URL(f.action || '', w.location.href).pathname; } catch (x) { return ''; }
  }
  // the fields of e's form (or the formless page), visible ones
  function group(e) {
    var f = formOf(e);
    var members = f ? fieldsOf(f) : fieldsOf(d).filter(function (x) { return !formOf(x); });
    return { form: f, key: formKey(f), fields: members.filter(visible).map(sig) };
  }
  function bridge(fn, payload) {
    try { if (w.CloudAc && w.CloudAc[fn]) w.CloudAc[fn](JSON.stringify(payload)); } catch (x) {}
  }

  function onFocus(ev) {
    var e = ev.target;
    if (!e || !e.tagName || !/^(INPUT|SELECT|TEXTAREA)$/i.test(e.tagName)) return;
    if (SKIP[typeOf(e)]) return bridge('none', {});
    var g = group(e);
    bridge('focus', { field: idOf(e), formKey: g.key, host: w.location && w.location.hostname || '', incognito: state.incognito, fields: g.fields });
  }

  // ── submit: the typed values, once ─────────────────────────────────────────────────────────────
  function valueOf(e) {
    var t = typeOf(e);
    if (t === 'select-one') {
      var o = e.options && e.selectedIndex >= 0 ? e.options[e.selectedIndex] : null;
      return o ? String(o.value || o.textContent || '') : String(e.value || '');
    }
    return String(e.value || '');
  }
  function snapshot(f) {
    var out = [];
    fieldsOf(f).forEach(function (e) {
      var t = typeOf(e);
      if (t === 'textarea') return;
      var v = valueOf(e);
      if (!v) return;
      var s = sig(e);
      s.v = v.slice(0, t === 'password' ? 256 : 120);
      out.push(s);
    });
    return out;
  }
  function reportSubmit(f) {
    if (!f) return;
    var now = Date.now ? Date.now() : 0;
    if (now && now - lastSubmitAt < 800) return;
    lastSubmitAt = now;
    var fields = snapshot(f);
    if (fields.length) bridge('submitted', { formKey: formKey(f), host: w.location && w.location.hostname || '', incognito: state.incognito, fields: fields });
  }
  function onSubmit(ev) { var f = ev.target; if (f && tagOf(f) === 'FORM') reportSubmit(f); }
  function onClick(ev) {
    var b = ev.target && ev.target.closest ? ev.target.closest('button, input[type=submit], input[type=image]') : null;
    if (!b) return;
    var t = String(attr(b, 'type') || (tagOf(b) === 'BUTTON' ? 'submit' : '')).toLowerCase();
    if (t !== 'submit' && t !== 'image') return;
    var f = b.form || (b.closest && b.closest('form'));
    if (f) reportSubmit(f);
  }

  // ── the Android Autofill framework (Cloud Vault's dataset) ─────────────────────────────────────
  function onChange(ev) {
    var e = ev.target;
    if (!e || !e.tagName || filling || ours.has(e) || !/^(INPUT|SELECT)$/i.test(e.tagName)) return;
    if (!framework(e)) return;
    if (fwTimer) return;
    fwTimer = setTimeout(function () {
      fwTimer = null;
      var g = group(e);
      var ids = g.fields.filter(function (s) { return s.fw; }).map(function (s) { return s.id; });
      if (ids.length) bridge('framework', { formKey: g.key, host: w.location && w.location.hostname || '', ids: ids });
    }, 250);
  }

  // ── fill ────────────────────────────────────────────────────────────────────────────────────────
  function norm(s) { return String(s || '').toLowerCase().replace(/\s+/g, ' ').trim(); }
  function pickOption(e, v) {
    var want = norm(v), opts = Array.prototype.slice.call(e.options || []);
    var num = /^[0-9]+$/.test(want) ? String(parseInt(want, 10)) : null;
    var hit = opts.filter(function (o) {
      var ov = norm(o.value), ot = norm(o.textContent);
      return ov === want || ot === want || (num !== null && (String(parseInt(ov, 10)) === num || String(parseInt(ot, 10)) === num));
    })[0] || opts.filter(function (o) { var ot = norm(o.textContent); return ot.indexOf(want) === 0 || (want.length > 3 && want.indexOf(ot) === 0 && ot.length > 3); })[0];
    return hit ? hit.value : null;
  }
  function setValue(e, v) {
    var t = tagOf(e);
    if (t === 'SELECT') {
      var pick = pickOption(e, v);
      if (pick === null) return false;
      v = pick;
    }
    var proto = t === 'SELECT' ? w.HTMLSelectElement && w.HTMLSelectElement.prototype
      : t === 'TEXTAREA' ? w.HTMLTextAreaElement && w.HTMLTextAreaElement.prototype : w.HTMLInputElement && w.HTMLInputElement.prototype;
    var desc = proto && Object.getOwnPropertyDescriptor(proto, 'value');
    if (desc && desc.set) desc.set.call(e, v); else e.value = v;
    return true;
  }
  function fire(e, type) {
    var ev;
    try { ev = new w.Event(type, { bubbles: true }); } catch (x) { ev = d.createEvent('Event'); ev.initEvent(type, true, false); }
    e.dispatchEvent(ev);
  }
  // values: {id: string}. A field the framework filled is left alone unless force (a secret the user asked for).
  function fill(values, force) {
    var n = 0;
    filling = true;
    try {
      Object.keys(values || {}).forEach(function (k) {
        var e = byId[+k];
        if (!e || !visible(e)) return;
        if (!force && framework(e)) return;
        if (setValue(e, String(values[k]))) { ours.add(e); fire(e, 'input'); fire(e, 'change'); n++; }
      });
    } finally { filling = false; }
    return JSON.stringify({ filled: n });
  }
  // the CURRENT values of fields the framework filled: how the host learns what Cloud Vault gave
  function read(ids) {
    var out = {};
    (ids || []).forEach(function (i) { var e = byId[i]; if (e) out[i] = valueOf(e).slice(0, 256); });
    return JSON.stringify(out);
  }
  function focusField(id) { var e = byId[id]; if (e && e.focus) e.focus(); return 'ok'; }
  // the first sign-in field of the page: the user name box if there is one before a password box, else the password box
  function firstLogin() {
    var fs = fieldsOf(d).filter(visible), pw = null, prev = null;
    for (var i = 0; i < fs.length; i++) {
      if (typeOf(fs[i]) === 'password') { pw = fs[i]; break; }
      prev = fs[i];
    }
    var target = prev && formOf(prev) === formOf(pw) ? prev : pw;
    if (target) { if (target.focus) target.focus(); return JSON.stringify({ field: idOf(target), password: !!pw }); }
    return JSON.stringify({ field: -1 });
  }

  // the form of the page with the most visible fields (or the formless fields): "Autofill this page" from the menu
  function pageForm() {
    var fs = fieldsOf(d).filter(visible), forms = [], best = null, bestN = -1;
    fs.forEach(function (e) { var f = formOf(e); if (forms.indexOf(f) < 0) forms.push(f); });
    forms.forEach(function (f) {
      var n = fs.filter(function (e) { return formOf(e) === f; }).length;
      if (n > bestN) { bestN = n; best = f; }
    });
    var members = fs.filter(function (e) { return formOf(e) === best; });
    return JSON.stringify({ formKey: formKey(best), host: w.location && w.location.hostname || '', fields: members.map(sig) });
  }

  d.addEventListener('focusin', onFocus, true);
  d.addEventListener('submit', onSubmit, true);
  d.addEventListener('click', onClick, true);
  d.addEventListener('change', onChange, true);
  d.addEventListener('input', onChange, true);

  w.__ac = {
    v: 1,
    configure: function (c) { state.incognito = !!(c && c.incognito); },
    fill: fill, read: read, focusField: focusField, firstLogin: firstLogin, pageForm: pageForm
  };
  return 'installed';
})(__CFG__);
