// #887 Save site offline: the page's links, absolute, as {links: [...]}. The scope (same origin, pages
// only, depth, limits) is decided in Kotlin (SiteScope / SiteCrawl); this only reads the anchors.
(function () {
  var out = [], seen = {};
  var a = document.querySelectorAll('a[href]');
  for (var i = 0; i < a.length && out.length < 400; i++) {
    var h = a[i].href;
    if (!h || !/^https?:/i.test(h)) continue;
    h = h.split('#')[0];
    if (!seen[h]) { seen[h] = 1; out.push(h); }
  }
  return JSON.stringify({ links: out });
})()
