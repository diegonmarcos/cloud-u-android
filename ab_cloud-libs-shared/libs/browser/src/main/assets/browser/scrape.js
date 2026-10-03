// #802 Web Scraper, one page: for each column {name, css, attr}, the matched elements'
// text (or attribute, resolved to an absolute URL for href/src); rows are zipped by index.
// Also the next page's URL when the plan has a next_css. Runs through evaluateJavascript.
(function (plan) {
  var cols = plan.columns.map(function (c) {
    return Array.prototype.map.call(document.querySelectorAll(c.css), function (e) {
      if (!c.attr) return (e.innerText || e.textContent || '').trim();
      if (c.attr === 'href' || c.attr === 'src') return e[c.attr] || e.getAttribute(c.attr) || '';
      return e.getAttribute(c.attr) || '';
    });
  });
  var n = cols.reduce(function (m, c) { return Math.max(m, c.length); }, 0), rows = [];
  for (var i = 0; i < n; i++) {
    var r = {};
    plan.columns.forEach(function (c, k) { r[c.name] = cols[k][i] || ''; });
    rows.push(r);
  }
  var next = plan.next_css ? document.querySelector(plan.next_css) : null;
  return JSON.stringify({ url: location.href, rows: rows, next: next && next.href ? next.href : null });
})(__PLAN__)
