// #886 Translate page, step 1: claim the next batch of visible text nodes and hand them to Kotlin.
// Runs in the page through evaluateJavascript; __N__ = the most characters one batch may carry.
// Answers {items: [[index, text], ...], remaining: n}. Nothing is changed here: the nodes are only
// REMEMBERED (window.__cbTr.nodes, with their original text) so that translate_apply.js can write
// the translation into the very same node and translate_restore.js can put the original back.
// Structure is never touched: only Text node values change, so links, formatting, scripts and
// event handlers keep working. Re-running it claims the NEXT batch (nodes already claimed are seen).
(function () {
  var S = window.__cbTr = window.__cbTr || { nodes: [], seen: new WeakSet() };
  var MAXC = __N__, MAXI = 60;
  var SKIP = { SCRIPT: 1, STYLE: 1, NOSCRIPT: 1, TEXTAREA: 1, CODE: 1, PRE: 1, KBD: 1, SAMP: 1, SVG: 1, IFRAME: 1, CANVAS: 1, TEMPLATE: 1 };
  var vy = window.scrollY || 0;
  var cand = [];
  var w = document.createTreeWalker(document.body || document.documentElement, NodeFilter.SHOW_TEXT, null);
  var n;
  while ((n = w.nextNode())) {
    if (S.seen.has(n)) continue;
    var el = n.parentElement;
    if (!el || SKIP[el.tagName]) continue;
    var t = n.nodeValue;
    if (!t || !/[A-Za-zÀ-ɏͰ-῿぀-￯]/.test(t)) continue;
    if (el.closest('[translate="no"],.notranslate,[contenteditable="true"],script,style')) continue;
    var r = el.getBoundingClientRect();
    if (r.width === 0 && r.height === 0) continue;
    var y = r.top + vy;
    // What is on screen and below goes first; what is above counts double, so a long page is
    // translated reading-order from where the reader is.
    cand.push({ n: n, d: y >= vy ? y - vy : (vy - y) * 2 });
  }
  cand.sort(function (a, b) { return a.d - b.d; });
  var items = [], total = 0, i;
  for (i = 0; i < cand.length; i++) {
    var text = cand[i].n.nodeValue.trim();
    if (items.length && (total + text.length > MAXC || items.length >= MAXI)) break;
    S.seen.add(cand[i].n);
    S.nodes.push({ n: cand[i].n, o: cand[i].n.nodeValue });
    items.push([S.nodes.length - 1, text]);
    total += text.length;
  }
  return JSON.stringify({ items: items, remaining: cand.length - items.length });
})()
