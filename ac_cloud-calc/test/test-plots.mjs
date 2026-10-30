#!/usr/bin/env node
// #1336 Cloud Calc plots, headless: (1) the safe expression language against data/latex/expr-vectors.json (values, and every
// reject), (2) every formula's plot spec drawn by the real graph.js on a recording fake canvas in a fake DOM: load, probe by tap and
// long-press, orbit, pinch, wheel, animation, extreme values, with no exception and with drawing calls on both canvases.
// Prints one FAIL line per problem; exit 1 on any.
import fs from "node:fs";
import path from "node:path";
import vm from "node:vm";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));
const app = path.join(here, "..");
const PLOT = path.join(app, "data/assets/latex/plot");
const require = createRequire(import.meta.url);
const bad = [];
const EX = require(path.join(PLOT, "expr.js"));

// ── 1 expression vectors ──────────────────────────────────────────────────────────────────────────────────────────
const vec = JSON.parse(fs.readFileSync(path.join(app, "data/latex/expr-vectors.json"), "utf8"));
for (const a of vec.accept) {
  try {
    const got = EX.compile(a.expr, Object.keys(a.vars)).eval(a.vars);
    if (!(Math.abs(got - a.expect) <= Math.max(a.tol, 1e-12) * Math.max(1, Math.abs(a.expect)))) bad.push(`expr ${a.expr} = ${got}, expected ${a.expect}`);
  } catch (e) { bad.push(`expr ${a.expr} was rejected: ${e.message}`); }
}
for (const r of vec.reject) {
  let accepted = false;
  try { EX.compile(r.expr, r.vars); accepted = true; } catch (e) { /* rejected, as it must be */ }
  if (accepted) bad.push(`expr ${r.expr} must be rejected but was accepted`);
}
// no code path to eval or Function in the three scripts
for (const f of ["expr.js", "model.js", "graph.js"]) {
  const code = fs.readFileSync(path.join(PLOT, f), "utf8").replace(/\/\/.*$/gm, "");
  if (/\beval\s*\(|new\s+Function|\bFunction\s*\(|setTimeout\s*\(\s*["'`]|\bimport\s*\(|document\.write/.test(code)) bad.push(`${f} reaches for eval/Function/dynamic code`);
}

// ── 2 the viewer in a fake DOM ───────────────────────────────────────────────────────────────────────────────────
function ctxFor(sink) {
  return new Proxy({}, {
    get(_, k) { if (k === "measureText") return () => ({ width: 30 }); return (...a) => { sink.push(k); }; },
    set() { return true; },
  });
}
function makeEnv() {
  const calls = { c2: [], c3: [] }, handlers = { c2: {}, c3: {} }, texts = {}, timers = [], frames = [], probes = [], grabs = [], errors = [];
  const canvas = (id) => ({ clientWidth: 340, width: 0, height: 0, style: {}, getContext: () => ctxFor(calls[id]), addEventListener: (t, f) => { handlers[id][t] = f; }, getBoundingClientRect: () => ({ left: 0, top: 0 }), setPointerCapture() {} });
  const els = { c2: canvas("c2"), c3: canvas("c3") };
  for (const id of ["cap", "l2", "l3", "r2", "r3", "msg", "s2", "s3"]) els[id] = { style: {}, textContent: "" };
  const win = {
    devicePixelRatio: 2, innerWidth: 360,
    document: { getElementById: (id) => els[id], documentElement: { style: { setProperty() {} } }, body: { offsetHeight: 700 } },
    requestAnimationFrame: (f) => { frames.push(f); return frames.length; },
    setTimeout: (f) => { timers.push(f); return timers.length; }, clearTimeout: (n) => { if (n) timers[n - 1] = null; },
    addEventListener() {},
    CalcPlot: { ready() {}, probe: (w, t) => probes.push([w, t]), anim() {}, grab: (b) => grabs.push(b), size() {}, error: (m) => errors.push(m) },
    Math, JSON, Object, Array, Date, Number, String, isFinite, parseFloat, Uint8Array, Error, console,
  };
  win.window = win;
  vm.createContext(win);
  for (const f of ["expr.js", "model.js", "graph.js"]) vm.runInContext(fs.readFileSync(path.join(PLOT, f), "utf8"), win, { filename: f });
  let now = 1000;
  const flush = () => { for (let i = 0; i < 6 && frames.length; i++) { const batch = frames.splice(0); now += 50; batch.forEach((f) => f(now)); } };
  const fire = (id, type, x, y, pid = 1) => handlers[id][type]?.({ type, clientX: x, clientY: y, pointerId: pid, preventDefault() {}, deltaY: -1 });
  const longPress = () => { timers.splice(0).forEach((f) => f && f()); };
  return { win, calls, texts, els, probes, grabs, errors, flush, fire, longPress, handlers };
}

const doc = JSON.parse(fs.readFileSync(path.join(app, "data/assets/latex/formulas.json"), "utf8"));
const env = makeEnv();
let drawn = 0;
for (const f of doc.formulas) {
  const p = f.plot;
  env.calls.c2.length = env.calls.c3.length = 0;
  env.probes.length = 0; env.errors.length = 0;
  try {
    env.win.plotTheme(JSON.stringify({ bg: "#121212", fg: "#eee", muted: "#999", accent: "#8ab4f8", line: "#333" }));
    env.win.plotLoad(JSON.stringify({ plot: p, mode: "both", labels: { d2: "2D", d3: "3D", illustration: "illustration" } }));
    env.flush();
    if (!env.calls.c2.includes("stroke") && !env.calls.c2.includes("fill") && !env.calls.c2.includes("fillRect")) bad.push(`${f.id}: nothing drawn on the 2D canvas`);
    if (env.calls.c3.length < 20) bad.push(`${f.id}: the 3D canvas got ${env.calls.c3.length} drawing calls`);
    if (env.els.msg.textContent) bad.push(`${f.id}: viewer message "${env.els.msg.textContent}"`);
    // tap and long-press on both canvases, orbit, pinch, wheel
    env.fire("c2", "pointerdown", 170, 120); env.fire("c2", "pointerup", 170, 120);
    env.fire("c2", "pointerdown", 100, 100); env.longPress(); env.fire("c2", "pointerup", 100, 100);
    env.fire("c3", "pointerdown", 170, 150); env.fire("c3", "pointerup", 170, 150);
    env.fire("c3", "pointerdown", 170, 150); env.longPress(); env.fire("c3", "pointerup", 170, 150);
    env.fire("c3", "pointerdown", 100, 100, 1); env.fire("c3", "pointermove", 140, 120, 1); env.fire("c3", "pointerup", 140, 120, 1);
    env.fire("c3", "pointerdown", 100, 100, 1); env.fire("c3", "pointerdown", 200, 100, 2); env.fire("c3", "pointermove", 100, 100, 1); env.fire("c3", "pointermove", 260, 100, 2);
    env.fire("c3", "pointerup", 100, 100, 1); env.fire("c3", "pointerup", 260, 100, 2);
    env.fire("c3", "wheel", 100, 100);
    env.win.plotView("zoom", 1.2); env.win.plotView("reset");
    env.flush();
    if (env.probes.filter((x) => x[0] === "2d").length < 1) bad.push(`${f.id}: tapping the 2D plot reported no coordinates`);
    if (!env.grabs.includes(true) || env.grabs[env.grabs.length - 1] !== false) bad.push(`${f.id}: the 3D gesture did not take and release the touch`);
    // animation
    if (p.anim) {
      env.win.plotAnim(true, 2); env.flush(); env.flush(); env.win.plotAnim(false, 1);
      if (!(env.win.__plotState.values[p.anim.var] >= p.anim.min && env.win.__plotState.values[p.anim.var] <= p.anim.max)) bad.push(`${f.id}: the animation left ${p.anim.var} out of range`);
    }
    // every variable at its minimum, then its maximum, then a mode switch
    for (const pick of ["min", "max"]) {
      const v = {}; p.vars.forEach((x) => { v[x.symbol] = x[pick]; });
      env.win.plotValues(JSON.stringify(v)); env.flush();
    }
    env.win.plotMode("2d"); env.flush(); env.win.plotMode("3d"); env.flush(); env.win.plotMode("both"); env.flush();
    if (env.errors.length) bad.push(`${f.id}: viewer error ${env.errors[0]}`);
    drawn++;
  } catch (e) { bad.push(`${f.id}: the viewer threw ${e.stack.split("\n").slice(0, 3).join(" | ")}`); }
}
// a broken spec is reported, never thrown at the user
env.win.plotLoad(JSON.stringify({ plot: { ...doc.formulas[0].plot, d2: { x: "a", layers: [{ t: "line", y: "eval(1)" }] } } }));
if (!env.errors.length || !env.els.msg.textContent) bad.push("a spec with a forbidden expression was not reported to the user");

bad.forEach((b) => console.log("  FAIL  " + b));
if (bad.length) process.exit(1);
console.log(`ok: ${vec.accept.length} expression values, ${vec.reject.length} rejects, ${drawn} formulas drawn and probed`);
