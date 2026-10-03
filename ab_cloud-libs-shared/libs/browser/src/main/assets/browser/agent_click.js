// #802 I9 the assistant's click, confirmed by him first: the first element matching the selector,
// clicked the way a tap would (the page's own handlers run).
(function (css) {
  var e = null;
  try { e = document.querySelector(css); } catch (x) { return JSON.stringify({ ok: false, error: 'bad selector' }); }
  if (!e) return JSON.stringify({ ok: false, error: 'no element matches ' + css });
  e.scrollIntoView({ block: 'center' });
  e.click();
  return JSON.stringify({ ok: true, tag: e.tagName.toLowerCase(), text: (e.innerText || '').slice(0, 80) });
})(__CSS__)
