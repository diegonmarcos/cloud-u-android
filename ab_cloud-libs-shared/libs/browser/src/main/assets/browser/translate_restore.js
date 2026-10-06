// #886 Translate page, off: put every translated node's ORIGINAL text back and forget the claim,
// so the next Translate starts from the page as it is then.
(function () {
  var S = window.__cbTr, c = 0;
  if (!S) return JSON.stringify({ restored: 0 });
  S.nodes.forEach(function (e) {
    if (e.t && e.n.isConnected) { e.n.nodeValue = e.o; c++; }
  });
  delete window.__cbTr;
  return JSON.stringify({ restored: c });
})()
