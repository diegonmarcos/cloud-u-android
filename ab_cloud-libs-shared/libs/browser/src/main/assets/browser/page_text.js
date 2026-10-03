// #802 the page's visible text, capped at __N__ chars, as {title, url, text, length}.
(function () {
  var t = (document.body && (document.body.innerText || document.body.textContent)) || '';
  return JSON.stringify({ title: document.title, url: location.href, text: t.slice(0, __N__), length: t.length });
})()
