// #886 Summarise by topics, step 1: the page as sections. Answers {title, lang, sections: [{h, level, text}]},
// the text capped at __N__ characters in all. A section starts at each heading (h1-h4); text before the
// first heading is a section with no heading. Navigation, footers, asides, forms and hidden blocks are left out.
(function () {
  var CAP = __N__, used = 0;
  var SKIP = 'script,style,noscript,nav,footer,aside,form,header nav,svg,iframe,button,select,[aria-hidden="true"],[hidden]';
  var secs = [{ h: '', level: 0, text: '' }];
  var root = document.querySelector('article') || document.querySelector('main') || document.body;
  var w = document.createTreeWalker(root, NodeFilter.SHOW_ELEMENT, null);
  var e;
  while ((e = w.nextNode()) && used < CAP) {
    if (e.closest(SKIP)) continue;
    var tag = e.tagName;
    if (/^H[1-4]$/.test(tag)) {
      var h = (e.innerText || '').trim();
      if (h) secs.push({ h: h.slice(0, 160), level: +tag[1], text: '' });
    } else if (tag === 'P' || tag === 'LI' || tag === 'BLOCKQUOTE' || tag === 'DD' || tag === 'TD') {
      if (e.querySelector('p,li,blockquote')) continue;
      var t = (e.innerText || '').replace(/\s+/g, ' ').trim();
      if (t.length < 25) continue;
      var s = secs[secs.length - 1];
      if (s.text.length > 2500) continue;
      s.text += (s.text ? ' ' : '') + t;
      used += t.length;
    }
  }
  return JSON.stringify({
    title: document.title, lang: document.documentElement.lang || '',
    sections: secs.filter(function (s) { return s.text || s.h; })
  });
})()
