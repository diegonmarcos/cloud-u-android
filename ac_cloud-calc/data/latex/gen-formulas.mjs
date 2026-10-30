#!/usr/bin/env node
// Generates the Cloud Calc LaTeX library (assets/latex/formulas.json) from the owner's markdown (100-formulas.md), the
// pi list (pi-formulas.md) and the plot specs (plots.json).
//   node gen-formulas.mjs            write the JSON
//   node gen-formulas.mjs --check    fail if the JSON on disk differs from what the sources yield (drift), or a plot spec is malformed
//   node gen-formulas.mjs --plots    also sample every plot over its default grid (no NaN/Infinity flood) and compile every expression
//   node gen-formulas.mjs --render   --plots, and fail if any formula (or description's inline math) does not render in the bundled KaTeX
// Source shape: `## N. Category`, then per formula `### N. Title` (pi-formulas.md may end it in `{#id}`), a one-line `$$...$$`
// display formula and a description paragraph whose inline math stays as `$...$` (the detail view renders it with KaTeX
// auto-render). A category may carry `Includes: #N, ...`: owner formulas that also list under it (referenced, not copied).
import fs from "node:fs";
import path from "node:path";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));
const SRC = path.join(here, "100-formulas.md");
const SRC_PI = path.join(here, "pi-formulas.md");
const SRC_PLOTS = path.join(here, "plots.json");
const OUT = path.join(here, "..", "assets", "latex", "formulas.json");
const KATEX = path.join(here, "..", "assets", "latex", "katex", "katex.min.js");
const PLOT_DIR = path.join(here, "..", "assets", "latex", "plot");
const require = createRequire(import.meta.url);

const slug = (s) => s.toLowerCase().normalize("NFKD").replace(/\$[^$]*\$/g, " ").replace(/[^a-z0-9]+/g, "-").replace(/^-+|-+$/g, "");

export function parse(md) {
  const categories = [];
  const formulas = [];
  let cat = null;
  let cur = null;
  for (const raw of md.split(/\r?\n/)) {
    const line = raw.trimEnd();
    let m;
    if ((m = /^## (\d+)\. (.+)$/.exec(line))) {
      cat = { id: slug(m[2]), label: m[2].trim() };
      categories.push(cat);
      cur = null;
    } else if ((m = /^### (\d+)\. (.+?)(?: \{#([a-z0-9-]+)\})?$/.exec(line))) {
      cur = { id: m[3] || slug(m[2]), number: Number(m[1]), title: m[2].trim(), category: cat.id, latex: "", description: "" };
      formulas.push(cur);
    } else if (!cur && cat && (m = /^Includes: (.+)$/.exec(line))) {
      cat.includesNumbers = m[1].split(",").map((x) => Number(x.trim().replace("#", "")));
    } else if (cur && (m = /^\$\$(.+)\$\$$/.exec(line))) {
      cur.latex = m[1].trim();
    } else if (cur && line && !line.startsWith("#") && line !== "---") {
      cur.description = (cur.description ? cur.description + " " : "") + line.trim();
    }
  }
  return { categories, formulas };
}

const RESERVED = new Set(["pi", "e", "tau", "if", "sum", "prod"]);
const NEEDS = {
  2: { line: ["y"], param: ["fx", "fy"], points: ["fy"], scatter: [], hline: ["y"], vline: ["at"], dot: ["fx", "fy"], field: ["fx", "fy"] },
  3: { surface: ["z"], psurface: ["fx", "fy", "fz"], curve: ["fx", "fy", "fz"], points: ["fx", "fy", "fz"], dot: ["fx", "fy", "fz"], quiver: ["fx", "fy", "fz"], platonic: ["sel"] },
};

// Everything checkable without sampling: shape, ranges, names, that every expression compiles against the declared variables.
export function expandPlot(id, src, problems) {
  const bad = (m) => problems.push(`plot ${id}: ${m}`);
  const EX = require(path.join(PLOT_DIR, "expr.js"));
  const M = require(path.join(PLOT_DIR, "model.js"));
  if (!src || typeof src !== "object") { bad("missing"); return null; }
  if (!src.caption || typeof src.caption !== "string" || src.caption.includes("\n") || src.caption.length > 320) bad("needs a one-line caption (at most 320 characters)");
  if (src.kind !== "function" && src.kind !== "illustration") bad("kind must be function or illustration");
  const vars = [];
  for (const [symbol, a] of Object.entries(src.vars || {})) {
    if (!/^[A-Za-z_][A-Za-z0-9_]*$/.test(symbol) || RESERVED.has(symbol) || EX.FUNCTIONS.one.concat(EX.FUNCTIONS.two, EX.FUNCTIONS.three).includes(symbol)) bad(`variable name ${symbol} is not allowed`);
    if (!Array.isArray(a) || a.length !== 5 || typeof a[0] !== "string" || a.slice(1).some((x) => typeof x !== "number" || !Number.isFinite(x))) { bad(`variable ${symbol} must be [label, default, min, max, step]`); continue; }
    const [label, def, min, max, step] = a;
    if (!(min < max)) bad(`variable ${symbol}: min must be below max`);
    if (!(def >= min && def <= max)) bad(`variable ${symbol}: default ${def} outside [${min}, ${max}]`);
    if (!(step > 0)) bad(`variable ${symbol}: step must be positive`);
    vars.push({ symbol, label, default: def, min, max, step });
  }
  if (!vars.length) bad("needs at least one variable");
  const names = vars.map((x) => x.symbol);
  const has = (n) => names.includes(n);
  for (const dim of [2, 3]) {
    const b = src[`d${dim}`];
    if (!b || !Array.isArray(b.layers) || !b.layers.length) { bad(`needs a ${dim}D view (d${dim}.layers)`); continue; }
    for (const k of ["x", "y", "z"]) if (b[k] !== undefined && !has(b[k])) bad(`d${dim}.${k} names an undeclared variable ${b[k]}`);
    if (!has(b.x)) bad(`d${dim}.x must name the swept variable`);
    b.layers.forEach((l, i) => {
      const where = `${dim}D layer ${i + 1} (${l.t})`;
      const need = NEEDS[dim][l.t];
      if (!need) { bad(`${where}: unknown layer type`); return; }
      need.forEach((k) => { if (typeof l[k] !== "string") bad(`${where}: needs ${k}`); });
      if (l.t === "points" && dim === 2 && typeof l.y !== "string" && typeof l.fy !== "string") bad(`${where}: needs fy`);
      if (l.t === "scatter" && !(Array.isArray(l.xs) && Array.isArray(l.ys) && l.xs.length === l.ys.length && l.xs.length > 0)) bad(`${where}: needs xs and ys of equal length`);
      if ((l.t === "field" && !b.y) || (["surface", "psurface", "quiver"].includes(l.t) && !b.y)) bad(`${where}: needs d${dim}.y`);
      if (l.t === "platonic" && !has(l.sel)) bad(`${where}: sel names an undeclared variable`);
    });
  }
  if (src.anim !== undefined && !has(src.anim)) bad(`anim names an undeclared variable ${src.anim}`);
  for (const k of ["freq", "amp", "phase"]) if (src.wave && src.wave[k] !== undefined && !has(src.wave[k])) bad(`wave.${k} names an undeclared variable`);
  if (problems.some((p) => p.startsWith(`plot ${id}:`))) return null;
  const plot = { caption: src.caption, kind: src.kind, vars, d2: src.d2, d3: src.d3 };
  if (src.anim) { const v = vars.find((x) => x.symbol === src.anim); plot.anim = { var: v.symbol, min: v.min, max: v.max, speed: 1 }; }
  if (src.wave) plot.wave = src.wave;
  try { M.prepare(plot); } catch (e) { bad(e.message); return null; }
  return plot;
}

export function build(mdOwner, mdPi, plots) {
  const owner = parse(mdOwner);
  const pi = parse(mdPi);
  const categories = [...owner.categories, ...pi.categories];
  const formulas = [...owner.formulas, ...pi.formulas];
  const byNumber = new Map(formulas.map((f) => [f.number, f]));
  const problems = [];
  const outCats = categories.map((c) => {
    const o = { id: c.id, label: c.label };
    if (c.includesNumbers) {
      o.includes = c.includesNumbers.map((n) => { const f = byNumber.get(n); if (!f) problems.push(`category ${c.label}: Includes #${n} does not exist`); return f?.id; }).filter(Boolean);
    }
    return o;
  });
  const out = formulas.map((f) => ({ ...f, plot: expandPlot(f.id, plots[f.id], problems) }));
  const orphan = Object.keys(plots).filter((k) => !formulas.some((f) => f.id === k));
  orphan.forEach((k) => problems.push(`plots.json has a spec for ${k}, which is no formula`));
  return {
    doc: {
      _doc: "GENERATED by data/latex/gen-formulas.mjs from data/latex/100-formulas.md (the owner's library, verbatim), data/latex/pi-formulas.md (the pi category) and data/latex/plots.json (one plot spec per formula) - edit those, not this file. categories: [{id, label, includes?}] in the owner's order then pi (includes = ids of owner formulas that also list there); formulas: [{id (unique slug), number, title, category (a category id), latex (the display formula), description (one paragraph; inline math stays as $...$ for KaTeX auto-render), plot {caption, kind (function|illustration), vars [{symbol, label, default, min, max, step}], d2, d3 (layers of safe expressions, see plots.json), anim?, wave?}}]. The app adds a 'Mine' category from the user's saved formulas (build.json::latex.mine_category). test/test-calc-latex.sh --check fails if this file drifts from the sources.",
      version: 2,
      categories: outCats,
      formulas: out,
    },
    problems,
    formulas: out,
  };
}

const text = (o) => JSON.stringify(o, null, 1) + "\n";
const arg = process.argv[2];

// Sample every plot at its default values: a spec whose layers are mostly NaN/Infinity (more than a clipped singularity or a
// domain gap) is a broken spec.
function samplePlots(formulas, bad) {
  const M = require(path.join(PLOT_DIR, "model.js"));
  let layers = 0;
  for (const f of formulas) {
    const p = f.plot;
    const values = {};
    p.vars.forEach((v) => { values[v.symbol] = v.default; });
    const prepared = M.prepare(p);
    for (const [dim, s] of [["2D", prepared.sample2d(values)], ["3D", prepared.sample3d(values)]]) {
      for (const l of s.layers) {
        layers++;
        const label = `plot ${f.id} ${dim} ${l.t} ${l.label}`;
        const pts = l.pts || [];
        const vals = l.v !== undefined ? [l.v] : pts.flat();
        if (l.t === "platonic") { if (!l.verts.length) bad.push(`${label}: no vertices`); continue; }
        const finite = vals.filter((x) => Number.isFinite(x)).length;
        if (!vals.length) bad.push(`${label}: no samples`);
        else if (finite / vals.length < 0.2) bad.push(`${label}: only ${finite} of ${vals.length} samples are finite`);
        else if (l.t !== "hline" && l.t !== "vline" && l.t !== "dot" && finite < 4) bad.push(`${label}: fewer than 4 finite samples`);
        if (vals.some((x) => x === Infinity || x === -Infinity) && vals.filter((x) => Math.abs(x) === Infinity).length / vals.length > 0.05) bad.push(`${label}: Infinity flood`);
        // the plot must not be flat-out empty of variation unless it is a constant by nature
      }
    }
  }
  return layers;
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const { doc, problems, formulas } = build(fs.readFileSync(SRC, "utf8"), fs.readFileSync(SRC_PI, "utf8"), JSON.parse(fs.readFileSync(SRC_PLOTS, "utf8")).plots);
  const bad = [...problems];
  const owner = formulas.filter((f) => f.number <= 100);
  const piCat = doc.categories[doc.categories.length - 1];
  if (owner.length !== 100) bad.push(`expected 100 owner formulas, parsed ${owner.length}`);
  if (doc.categories.length !== 10) bad.push(`expected 9 owner categories plus pi, found ${doc.categories.length}`);
  if (piCat.id !== "pi") bad.push("the last category must be pi");
  if (formulas.filter((f) => f.category === "pi").length < 20) bad.push("the pi category needs at least 20 formulas");
  if (new Set(formulas.map((f) => f.id)).size !== formulas.length) bad.push("duplicate ids");
  formulas.forEach((f, i) => {
    if (f.number !== i + 1) bad.push(`formula ${f.title}: number ${f.number} out of order`);
    if (!f.latex) bad.push(`formula ${f.number} has no $$ formula`);
    if (!f.description) bad.push(`formula ${f.number} has no description`);
  });
  let extra = "";
  if (arg === "--check") {
    const have = fs.existsSync(OUT) ? fs.readFileSync(OUT, "utf8") : "";
    if (have !== text(doc)) bad.push("assets/latex/formulas.json is not what the sources generate (run gen-formulas.mjs)");
  } else if (arg === "--plots" || arg === "--render") {
    if (!bad.length) extra = `, ${samplePlots(formulas, bad)} plot layers sampled`;
    if (arg === "--render") {
      const katex = require(KATEX);
      for (const f of formulas) {
        try { katex.renderToString(f.latex, { throwOnError: true, displayMode: true, trust: false, strict: "ignore" }); }
        catch (e) { bad.push(`formula ${f.number} ${f.title} does not render: ${e.message}`); }
        for (const m of f.description.matchAll(/\$([^$]+)\$/g)) {
          try { katex.renderToString(m[1], { throwOnError: true, strict: "ignore" }); }
          catch (e) { bad.push(`formula ${f.number} description math $${m[1]}$ does not render: ${e.message}`); }
        }
      }
    }
  } else if (!bad.length) fs.writeFileSync(OUT, text(doc));
  if (bad.length) { bad.forEach((b) => console.error("FAIL " + b)); process.exit(1); }
  console.log(`ok: ${formulas.length} formulas, ${doc.categories.length} categories${extra} (${arg || "write"})`);
}
