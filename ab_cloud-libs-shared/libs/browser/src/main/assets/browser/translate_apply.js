// #886 Translate page, step 2: write translations into the nodes translate_collect.js claimed.
// The argument is {"<index>": "<translated text>"}, spliced in by the host. Leading and trailing whitespace of each node is kept,
// so the layout does not shift; a node that is gone from the page is skipped.
(function (m) {
  var S = window.__cbTr, c = 0;
  if (!S) return JSON.stringify({ applied: 0 });
  Object.keys(m).forEach(function (k) {
    var e = S.nodes[+k];
    if (!e || !e.n.isConnected) return;
    var lead = e.o.match(/^\s*/)[0], trail = e.o.match(/\s*$/)[0];
    e.n.nodeValue = lead + m[k] + trail;
    e.t = true;
    c++;
  });
  return JSON.stringify({ applied: c });
})(__MAP__)
