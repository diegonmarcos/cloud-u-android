// #802 I9 the assistant's fill, confirmed by him first: {selector: value}, set the way typing would
// (value + input + change). A password field is never written, whatever the selector says.
(function (fields) {
  var n = 0, skipped = [];
  Object.keys(fields).forEach(function (css) {
    var e = null;
    try { e = document.querySelector(css); } catch (x) { skipped.push(css); return; }
    if (!e || e.type === 'password' || /cc-|card|cvc|csc/i.test(e.autocomplete || '')) { skipped.push(css); return; }
    var proto = e.tagName === 'SELECT' ? HTMLSelectElement.prototype : e.tagName === 'TEXTAREA' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
    Object.getOwnPropertyDescriptor(proto, 'value').set.call(e, String(fields[css]));
    e.dispatchEvent(new Event('input', { bubbles: true }));
    e.dispatchEvent(new Event('change', { bubbles: true }));
    n++;
  });
  return JSON.stringify({ ok: true, filled: n, skipped: skipped });
})(__FIELDS__)
