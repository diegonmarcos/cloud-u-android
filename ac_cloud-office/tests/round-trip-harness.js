#!/usr/bin/env node
/*
 * Cloud Office — run the browser half of Text Enhance for real.
 *
 * Called by test-text-enhance-round-trip.sh with the path to a POST-PATCH tree
 * (upstream at the pin with patches/ applied by `git am`). It CUTS THE ACTUAL
 * CODE OUT OF THAT TREE and executes it against stubs. Nothing here re-states
 * what the patch does; if the patch changes, this runs the changed code.
 *
 * Three pieces are extracted, each by an anchor that is upstream's or ours and
 * both of which must be present or the harness dies rather than passing:
 *
 *   A. Clipboard.js  setEnhanceRequest / isEnhanceRequest and the field they use
 *   B. CanvasTileLayer.js  the whole `textselectioncontent:` branch, which is
 *      where the fork between "an enhance asked for this" and "the clipboard
 *      asked for this" actually happens
 *   C. Control.Menubar.ts  the `cloudtextenhance` arm of _executeAction
 *
 * What is asserted is behaviour, not text:
 *   1. tapping the row sets the one-shot flag and sends core exactly one message
 *   2. core's answer, with the flag set, reaches the Android bridge verbatim
 *   3. ...and the clipboard is NOT written, and the flag is cleared
 *   4. the same answer, with no flag, takes the ordinary clipboard path — i.e.
 *      Copy still works, which is the regression an early return could cause
 *   5. Action_Copy still wins when both are somehow pending, because its branch
 *      is first and the PostMessage caller is waiting on a reply
 *   6. a second answer does not re-fire: the flag is one-shot
 */
'use strict';

const fs = require('fs');
const path = require('path');

const tree = process.argv[2];
if (!tree) { console.error('usage: .round-trip-harness.js <post-patch-tree>'); process.exit(2); }

let pass = 0, fail = 0;
const G = '[0;32m', R = '[0;31m', O = '[0m';
const ok  = (m) => { pass++; console.log(`  ${G}ok${O}: ${m}`); };
const bad = (m) => { fail++; console.log(`  ${R}FAIL${O}: ${m}`); };
const die = (m) => { console.error(`ERROR: ${m}`); process.exit(2); };

const read = (p) => {
    const f = path.join(tree, p);
    if (!fs.existsSync(f)) die(`${p} is not in the post-patch tree`);
    return fs.readFileSync(f, 'utf8');
};

/* Cut from `startAnchor` to the brace that closes the block it opens.
 *
 * Counting braces, not matching a closing pattern: the blocks nest and a lazy
 * regex stops at the first `}`. And the scan SKIPS STRINGS AND COMMENTS,
 * because the very branch being cut contains `textMsgContent.startsWith('{')`
 * — one brace, inside a string literal, which a naive counter reads as a block
 * that never closes. */
function cutBlock(src, startAnchor, what) {
    const i = src.indexOf(startAnchor);
    if (i < 0) die(`could not find ${what}: the anchor ${JSON.stringify(startAnchor)} is gone from the post-patch tree`);
    let j = src.indexOf('{', i);
    if (j < 0) die(`${what}: no block opens after its anchor`);
    let depth = 0, k = j;
    for (; k < src.length; k++) {
        const c = src[k], n = src[k + 1];
        if (c === '/' && n === '/') { k = src.indexOf('\n', k); if (k < 0) break; continue; }
        if (c === '/' && n === '*') { k = src.indexOf('*/', k + 2); if (k < 0) break; k += 1; continue; }
        if (c === '"' || c === "'" || c === '`') {
            const q = c;
            for (k++; k < src.length; k++) {
                if (src[k] === '\\') { k++; continue; }
                if (src[k] === q) break;
                if (q !== '`' && src[k] === '\n') break;   // unterminated: do not run away
            }
            continue;
        }
        if (c === '{') depth++;
        else if (c === '}') { depth--; if (depth === 0) break; }
    }
    if (depth !== 0) die(`${what}: braces never balance`);
    return src.slice(i, k + 1);
}

// ── A. the Clipboard flag, verbatim ─────────────────────────────────────────
const clipSrc = read('browser/src/map/Clipboard.js');
const setter = cutBlock(clipSrc, 'setEnhanceRequest: function', 'Clipboard.setEnhanceRequest');
const getter = cutBlock(clipSrc, 'isEnhanceRequest: function',  'Clipboard.isEnhanceRequest');
const actionCopyGetter = cutBlock(clipSrc, 'isActionCopy: function', 'Clipboard.isActionCopy');
for (const [frag, needle, what] of [
    [setter, '_isEnhanceRequest', 'setEnhanceRequest'],
    [getter, '_isEnhanceRequest', 'isEnhanceRequest'],
]) if (!frag.includes(needle)) die(`${what} no longer touches ${needle} — extraction is not reading what it thinks`);

// ── B. the textselectioncontent: branch, verbatim ────────────────────────────
const canvasSrc = read('browser/src/layer/tile/CanvasTileLayer.js');
const branch = cutBlock(canvasSrc, "else if (textMsg.startsWith('textselectioncontent:'))",
                        'the textselectioncontent: handler');
for (const needle of ['isEnhanceRequest', 'isActionCopy', 'COOLMessageHandler', 'setTextSelectionHTML']) {
    if (!branch.includes(needle)) die(`the extracted textselectioncontent: branch has no ${needle} — extraction is not reading what it thinks`);
}

// ── C. the menu arm, verbatim ───────────────────────────────────────────────
const menuSrc = read('browser/src/control/Control.Menubar.ts');
const arm = cutBlock(menuSrc, "if (id === 'cloudtextenhance')", "_executeAction's cloudtextenhance arm");
if (!arm.includes('setEnhanceRequest') || !arm.includes('sendMessage'))
    die('the extracted cloudtextenhance arm neither sets the flag nor sends a message — extraction is not reading what it thinks');

/* ── the stubs ────────────────────────────────────────────────────────────────
 * Deliberately dumb. Every one of them RECORDS rather than simulates, so a
 * failure names what the code did instead of what a fake decided. */
function makeWorld() {
    const sent = [], bridged = [], clipboard = [], fired = [], posted = [];
    const clip = {
        _isEnhanceRequest: false,
        _isActionCopy: false,
        setTextSelectionHTML: (html, plain) => clipboard.push({ html, plain }),
    };
    /* Install the real methods on the stub. They are cut as object-literal
     * members (`name: function (…) {…}`), so the member colon becomes an
     * assignment; the BODY is untouched, which is the whole point. */
    const asAssignment = (member) =>
        member.replace(/,\s*$/, '').replace(/^(\w+)\s*:/, 'clip.$1 =');
    for (const m of [setter, getter, actionCopyGetter]) {
        const js = asAssignment(m);
        if (!js.startsWith('clip.')) die(`cannot install ${m.slice(0, 40)}… on the stub — it is not the member this harness expects`);
        // eslint-disable-next-line no-eval
        eval(js);
    }
    clip.setActionCopy = (v) => { clip._isActionCopy = v; };

    const map = {
        _clip: clip,
        fire: (name, data) => { fired.push({ name, data }); if (name === 'postMessage') posted.push(data); },
    };
    const world = {
        map, sent, bridged, clipboard, fired, posted,
        window: { COOLMessageHandler: { postMobileMessage: (m) => bridged.push(m) } },
        app: { socket: { sendMessage: (m) => sent.push(m) } },
    };
    return world;
}

/* Run the extracted menu arm as if the row were tapped. `id`, `this` and the
 * globals it closes over are supplied; the body is the patch's own. */
function tapTextEnhance(w) {
    const fn = new Function('id', 'window', 'app', `${arm}`);
    fn.call({ _map: w.map }, 'cloudtextenhance', w.window, w.app);
}

/* Run the extracted handler as if core answered. The branch begins with
 * `else if (...)`, so it is wrapped in an `if (false) {} ...` to be a statement,
 * and given the `textMsg` core would have sent. */
function coreAnswers(w, textMsg) {
    const body = `
        if (false) { }
        ${branch}
    `;
    const fn = new Function('textMsg', 'window', 'app', 'RenderManager', 'JSON', body);
    return fn.call({ _map: w.map, _selectedTextContent: null }, textMsg, w.window, w.app, { update() {} }, JSON);
}

const SELECTION = 'the quick brown fox';
const ANSWER = 'textselectioncontent: ' + SELECTION;   // 'textselectioncontent:' is 21 chars; substr(22) drops the space

// ── 1. leg 1 ────────────────────────────────────────────────────────────────
{
    const w = makeWorld();
    tapTextEnhance(w);
    if (w.map._clip.isEnhanceRequest() === true) ok('tapping Text Enhance sets the one-shot enhance flag');
    else bad('tapping Text Enhance did not set the enhance flag — core\'s answer would go to the clipboard');
    if (w.sent.length === 1 && /^gettextselection mimetype=/.test(w.sent[0]))
        ok(`tapping Text Enhance sends core exactly one message: ${JSON.stringify(w.sent[0])}`);
    else
        bad(`expected one gettextselection message, got ${JSON.stringify(w.sent)}`);
}

// ── 2 & 3. leg 2: the answer crosses the bridge and misses the clipboard ────
{
    const w = makeWorld();
    tapTextEnhance(w);
    coreAnswers(w, ANSWER);
    const msg = w.bridged[0];
    if (w.bridged.length === 1 && typeof msg === 'string' && msg.startsWith('CLOUD_TEXT_ENHANCE '))
        ok('core\'s answer is handed to the Android bridge as CLOUD_TEXT_ENHANCE');
    else
        bad(`core's answer did not reach the bridge; bridge saw ${JSON.stringify(w.bridged)}`);
    if (msg && msg.slice('CLOUD_TEXT_ENHANCE '.length) === SELECTION)
        ok(`the bridge receives the selection verbatim (${JSON.stringify(SELECTION)})`);
    else
        bad(`the bridge received ${JSON.stringify(msg && msg.slice('CLOUD_TEXT_ENHANCE '.length))}, not the selection`);
    if (w.clipboard.length === 0)
        ok('THE REAL CLIPBOARD IS NOT TOUCHED — an enhance cannot destroy what the owner copied');
    else
        bad(`the enhance also wrote the clipboard (${JSON.stringify(w.clipboard)}) — it would overwrite the owner's copy`);
    if (w.map._clip.isEnhanceRequest() === false)
        ok('the flag is cleared, so it is genuinely one-shot');
    else
        bad('the flag is still set after the answer — every later copy would be stolen by the enhancer');
}

// ── 4. ordinary copy still works ───────────────────────────────────────────
{
    const w = makeWorld();            // no tap: nothing pending
    coreAnswers(w, ANSWER);
    if (w.bridged.length === 0)
        ok('with no enhance pending, nothing is sent to the Android bridge');
    else
        bad(`an unrelated selection answer was sent to the bridge: ${JSON.stringify(w.bridged)}`);
    if (w.clipboard.length === 1)
        ok('with no enhance pending, the answer takes the ordinary clipboard path — Copy is not regressed');
    else
        bad(`the ordinary clipboard path did not run (setTextSelectionHTML calls: ${w.clipboard.length})`);
}

// ── 5. Action_Copy still wins ──────────────────────────────────────────────
{
    const w = makeWorld();
    tapTextEnhance(w);
    w.map._clip.setActionCopy(true);  // a PostMessage caller is waiting on a reply
    coreAnswers(w, ANSWER);
    const copyResp = w.posted.find((p) => p && p.msgId === 'Action_Copy_Resp');
    if (copyResp && w.bridged.length === 0)
        ok('Action_Copy still answers first — a waiting PostMessage caller is never starved by an enhance');
    else
        bad('an enhance took the answer that Action_Copy was waiting for');
}

// ── 6. one-shot means one ──────────────────────────────────────────────────
{
    const w = makeWorld();
    tapTextEnhance(w);
    coreAnswers(w, ANSWER);
    coreAnswers(w, 'textselectioncontent: something the owner merely copied');
    if (w.bridged.length === 1)
        ok('a second selection answer does not re-fire the enhance');
    else
        bad(`the enhance fired ${w.bridged.length} times — the flag is not one-shot`);
}

console.log(`\n  browser half: ${pass} passed, ${fail} failed`);
process.exit(fail === 0 ? 0 : 1);
