// #802 Web Scraper "pick an element": the next tap on the page is captured (not followed)
// and its CSS path stored in window.__cbPick for the scraper sheet to read.
(function () {
  function path(e) {
    var parts = [];
    while (e && e.nodeType === 1 && e !== document.body && parts.length < 5) {
      var p = e.tagName.toLowerCase();
      var cls = (e.className && typeof e.className === 'string') ? e.className.trim().split(/\s+/).filter(Boolean).slice(0, 2) : [];
      if (cls.length) p += '.' + cls.join('.');
      parts.unshift(p);
      e = e.parentElement;
    }
    return parts.join(' ');
  }
  window.__cbPick = null;
  document.addEventListener('click', function h(ev) {
    ev.preventDefault(); ev.stopPropagation();
    window.__cbPick = path(ev.target);
    document.removeEventListener('click', h, true);
  }, true);
  return JSON.stringify({ armed: true });
})()
