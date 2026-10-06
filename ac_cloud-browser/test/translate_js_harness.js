// #886 runs assets/browser/translate_{collect,apply,restore}.js and page_topics.js against a tiny fake DOM.
// usage: node translate_js_harness.js <assets dir>   -> prints one `ok|bad <label>` line per assertion.
const fs = require('fs'), vm = require('vm'), path = require('path');
const dir = process.argv[2];
const out = [];
const check = (c, l) => out.push((c ? 'ok ' : 'bad ') + l);
const src = n => fs.readFileSync(path.join(dir, n), 'utf8');

function el(tag, attrs, parent, rect) {
  return { tagName: tag, attrs: attrs || {}, parentElement: parent || null, rect: rect || { top: 0, width: 100, height: 20 },
    getBoundingClientRect() { return this.rect; },
    closest(sel) {
      const parts = sel.split(',').map(s => s.trim());
      for (let e = this; e; e = e.parentElement) {
        for (const p of parts) {
          if (p === e.tagName.toLowerCase()) return e;
          if (p[0] === '.' && (e.attrs.class || '').split(' ').includes(p.slice(1))) return e;
          const m = p.match(/^\[(\w+)="(\w+)"\]$/);
          if (m && e.attrs[m[1]] === m[2]) return e;
        }
      }
      return null;
    } };
}
function text(v, parent) { return { nodeValue: v, parentElement: parent, isConnected: true }; }

function page(scrollY) {
  const body = el('BODY');
  const mk = (tag, t, top, attrs, w) => { const e = el(tag, attrs, body, { top: top - scrollY, width: w === 0 ? 0 : 100, height: w === 0 ? 0 : 20 }); return text(t, e); };
  const nodes = [
    mk('P', '  Hello world  ', 0), mk('P', 'Second paragraph', 40), mk('SCRIPT', 'var x = "not text";', 50),
    mk('STYLE', 'p{color:red}', 55), mk('CODE', 'let a = 1', 60), mk('PRE', 'preformatted', 65),
    mk('P', '12345 67', 70), mk('P', 'Do not translate me', 80, { class: 'notranslate' }),
    mk('DIV', 'Editable text', 90, { contenteditable: 'true' }), mk('P', 'Hidden text', 100, {}, 0),
    mk('A', 'Link text', 110), mk('P', 'Far below the fold', 5000), mk('P', 'Above the viewport', -200),
  ];
  const context = { window: { scrollY }, document: {
      body, documentElement: body,
      createTreeWalker(root) { let i = -1; return { nextNode() { i++; return i < nodes.length ? (this.currentNode = nodes[i]) : null; } }; } },
    NodeFilter: { SHOW_TEXT: 4 }, JSON, Object, WeakSet, Array };
  context.window.scrollY = scrollY;
  return { context, nodes };
}
const run = (code, ctx) => vm.runInNewContext(code, ctx);

// 1. collect: batch 1 (small cap) skips what must not be translated, goes viewport-first
let { context, nodes } = page(0);
const collect = src('translate_collect.js').replace(/__N__/g, '40');
let r = JSON.parse(run(collect, context));
const texts1 = r.items.map(i => i[1]);
check(texts1[0] === 'Hello world', 'collect: first item is the first visible text, trimmed');
check(!texts1.some(t => /not text|color:red|let a|preformatted/.test(t)), 'collect: script, style, code and pre are skipped');
check(!texts1.includes('12345 67') && !texts1.includes('Do not translate me') && !texts1.includes('Editable text') && !texts1.includes('Hidden text'),
  'collect: numbers, translate=no, editable and hidden text are skipped');
check(r.items.length >= 1 && r.items.length < 5, 'collect: a small cap makes a small batch');
check(r.remaining > 0, 'collect: says how much is left');
// 2. progressive: keep collecting until empty; every translatable node is claimed exactly once
const all = texts1.slice();
let guard = 0;
for (;;) { const n = JSON.parse(run(collect, context)); if (!n.items.length || ++guard > 20) break; n.items.forEach(i => all.push(i[1])); }
check(new Set(all).size === all.length, 'collect: no node is claimed twice');
check(['Hello world', 'Second paragraph', 'Link text', 'Far below the fold', 'Above the viewport'].every(t => all.includes(t)), 'collect: every translatable node is reached by repeating');
check(all.length === 5, 'collect: and nothing else');
// 3. apply keeps leading/trailing whitespace; the node is the same node (structure untouched)
const apply = src('translate_apply.js');
const map = {}; all.forEach((t, i) => {}); map['0'] = 'Hola mundo'; map['1'] = 'Segundo párrafo';
const a = JSON.parse(run(apply.replace('__MAP__', JSON.stringify(map)), context));
check(a.applied === 2, 'apply: reports how many nodes it wrote');
check(nodes[0].nodeValue === '  Hola mundo  ', 'apply: leading and trailing whitespace is kept');
check(nodes[1].nodeValue === 'Segundo párrafo', 'apply: writes the node it was given');
check(nodes[2].nodeValue === 'var x = "not text";', 'apply: other nodes are untouched');
nodes[10].isConnected = false;
const b = JSON.parse(run(apply.replace('__MAP__', JSON.stringify({ '2': 'x' })), context));
check(b.applied === 0 || nodes[10].nodeValue === 'Link text', 'apply: a node gone from the page is skipped');
// 4. restore puts the originals back and forgets the claim
const restore = src('translate_restore.js');
const rr = JSON.parse(run(restore, context));
check(rr.restored === 2 && nodes[0].nodeValue === '  Hello world  ' && nodes[1].nodeValue === 'Second paragraph', 'restore: every translated node has its original text again');
check(context.window.__cbTr === undefined, 'restore: the claim is forgotten, so the next translate starts fresh');
const again = JSON.parse(run(collect, context));
check(again.items.length > 0 && again.items[0][1] === 'Hello world', 'restore: translating again starts from the page as it is');
// 5. scrolled: what is on screen first, what is above counts double
({ context, nodes } = page(5000));
const s = JSON.parse(run(src('translate_collect.js').replace(/__N__/g, '15'), context));
check(s.items[0][1] === 'Far below the fold', 'collect: a reader scrolled down gets the text on their screen first');
// 6. topics
const ptx = { window: {}, document: { title: 'T', documentElement: { lang: 'en' }, body: null, querySelector() { return null; },
    createTreeWalker() { const els = []; const body = el('BODY'); const mkp = (tag, t) => { const e = el(tag, {}, body); e.innerText = t; e.querySelector = () => null; return e; };
      els.push(mkp('P', 'An introduction paragraph that is long enough to keep.'), mkp('H2', 'History'), mkp('P', 'Coffee came from Ethiopia and spread across the world.'), mkp('LI', 'short'));
      let i = -1; return { nextNode() { i++; return i < els.length ? els[i] : null; } }; } }, JSON, Object, NodeFilter: { SHOW_ELEMENT: 1 } };
ptx.document.body = el('BODY');
const pt = JSON.parse(run(src('page_topics.js').replace(/__N__/g, '5000'), ptx));
check(pt.sections.length === 2 && pt.sections[1].h === 'History' && /Ethiopia/.test(pt.sections[1].text), 'topics: sections start at headings and carry their text');
check(!pt.sections.some(s => /short/.test(s.text)), 'topics: tiny list items are dropped');
console.log(out.join('\n'));
