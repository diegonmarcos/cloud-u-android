// #802 the form fields of the page, described (never their values): index, type,
// autocomplete, name, id, label text, placeholder. BrowserAutofillMatch decides what
// each may receive; passwords and card fields are refused there.
(function () {
  var els = Array.prototype.slice.call(document.querySelectorAll('input, select, textarea'));
  return JSON.stringify({ fields: els.map(function (e, i) {
    var lab = (e.labels && e.labels[0] && e.labels[0].innerText) || e.getAttribute('aria-label') || '';
    return { i: i, type: (e.type || e.tagName).toLowerCase(), autocomplete: e.getAttribute('autocomplete') || '',
             name: e.name || '', id: e.id || '', label: lab.slice(0, 80), placeholder: e.placeholder || '' };
  }) });
})()
