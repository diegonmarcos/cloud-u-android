// #802 reader mode: the article (or the block with the most paragraph text), stripped
// of chrome, as {title, html, length}. Runs in the page through evaluateJavascript.
(function () {
  var best = document.querySelector('article') || document.querySelector('main');
  if (!best) {
    var max = 0;
    document.querySelectorAll('div,section').forEach(function (e) {
      var n = 0;
      for (var i = 0; i < e.children.length; i++) if (e.children[i].tagName === 'P') n += e.children[i].innerText.length;
      if (n > max) { max = n; best = e; }
    });
  }
  best = best || document.body;
  var c = best.cloneNode(true);
  c.querySelectorAll('script,style,nav,aside,form,iframe,noscript,button,footer,header,svg').forEach(function (e) { e.remove(); });
  return JSON.stringify({ title: document.title, html: c.innerHTML, length: (c.innerText || c.textContent || '').length });
})()
