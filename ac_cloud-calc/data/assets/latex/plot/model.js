// Cloud Calc plot model: turns a formula's plot block (data/latex/plots.json, merged into formulas.json) and the current
// variable values into sample points, with no drawing. graph.js draws them; test/test-plots.mjs samples every formula with
// this same file. Expressions go through expr.js only (no eval).
(function (root) {
  "use strict";
  var EX = typeof module !== "undefined" && module.exports ? require("./expr.js") : root.PlotExpr;
  var EXPR_KEYS = { y: 1, z: 1, fx: 1, fy: 1, fz: 1, at: 1, sel: 1 };
  var SOLIDS = [ // [V, E, F] and unit-vertex generators for the five Platonic solids
    { name: "tetrahedron", V: 4, E: 6, F: 4 }, { name: "cube", V: 8, E: 12, F: 6 }, { name: "octahedron", V: 6, E: 12, F: 8 },
    { name: "dodecahedron", V: 20, E: 30, F: 12 }, { name: "icosahedron", V: 12, E: 30, F: 20 }
  ];

  function solid(k) {
    var i = Math.max(1, Math.min(5, Math.round(k))) - 1, v = [], e = [], phi = (1 + Math.sqrt(5)) / 2, a, b, c;
    function sgn(n) { return [-1, 1][n]; }
    if (i === 0) { v = [[1, 1, 1], [1, -1, -1], [-1, 1, -1], [-1, -1, 1]]; }
    else if (i === 1) { for (a = 0; a < 2; a++) for (b = 0; b < 2; b++) for (c = 0; c < 2; c++) v.push([sgn(a), sgn(b), sgn(c)]); }
    else if (i === 2) { v = [[1, 0, 0], [-1, 0, 0], [0, 1, 0], [0, -1, 0], [0, 0, 1], [0, 0, -1]]; }
    else if (i === 3) {
      for (a = 0; a < 2; a++) for (b = 0; b < 2; b++) for (c = 0; c < 2; c++) v.push([sgn(a), sgn(b), sgn(c)]);
      for (a = 0; a < 2; a++) for (b = 0; b < 2; b++) {
        v.push([0, sgn(a) / phi, sgn(b) * phi]); v.push([sgn(a) / phi, sgn(b) * phi, 0]); v.push([sgn(a) * phi, 0, sgn(b) / phi]);
      }
    } else {
      for (a = 0; a < 2; a++) for (b = 0; b < 2; b++) {
        v.push([0, sgn(a), sgn(b) * phi]); v.push([sgn(a), sgn(b) * phi, 0]); v.push([sgn(a) * phi, 0, sgn(b)]);
      }
    }
    var min = Infinity, d, j, k2;
    for (j = 0; j < v.length; j++) for (k2 = j + 1; k2 < v.length; k2++) { d = dist(v[j], v[k2]); if (d < min) min = d; }
    for (j = 0; j < v.length; j++) for (k2 = j + 1; k2 < v.length; k2++) if (Math.abs(dist(v[j], v[k2]) - min) < 1e-6) e.push([j, k2]);
    var r = Math.sqrt(v[0][0] * v[0][0] + v[0][1] * v[0][1] + v[0][2] * v[0][2]);
    return { name: SOLIDS[i].name, verts: v.map(function (p) { return [p[0] / r, p[1] / r, p[2] / r]; }), edges: e, V: SOLIDS[i].V, E: SOLIDS[i].E, F: SOLIDS[i].F };
  }
  function dist(p, q) { return Math.hypot(p[0] - q[0], p[1] - q[1], p[2] - q[2]); }

  // prepare(plot): compile every expression against the declared variables. Throws EX.ExprError naming the layer.
  function prepare(plot) {
    var names = plot.vars.map(function (v) { return v.symbol; }), byName = {};
    plot.vars.forEach(function (v) { byName[v.symbol] = v; });
    function layers(block, label) {
      return (block.layers || []).map(function (l, i) {
        var c = {};
        Object.keys(l).forEach(function (k) {
          if (EXPR_KEYS[k]) {
            try { c[k] = EX.compile(l[k], names).eval; }
            catch (e) { throw new EX.ExprError(label + " layer " + (i + 1) + " " + k + ": " + e.message); }
          }
        });
        return { spec: l, c: c };
      });
    }
    var p = { plot: plot, names: names, byName: byName, l2: layers(plot.d2, "2D"), l3: layers(plot.d3, "3D") };
    p.sample2d = function (values) { return sample2d(p, values); };
    p.sample3d = function (values) { return sample3d(p, values); };
    return p;
  }

  function lin(lo, hi, n) { var a = []; for (var i = 0; i <= n; i++) a.push(lo + (hi - lo) * i / n); return a; }
  function range(p, name) { var v = p.byName[name]; return [v.min, v.max]; }
  function fin(x) { return typeof x === "number" && isFinite(x); }

  function sample2d(p, values) {
    var d = p.plot.d2, env = Object.assign({}, values), xr = range(p, d.x), n = d.n || 400, out = [], xs = lin(xr[0], xr[1], n);
    p.l2.forEach(function (L) {
      var s = L.spec, c = L.c, t = s.t, pts = [], i, x, o = { t: t, label: s.label || "", dash: !!s.dash };
      if (t === "line") {
        for (i = 0; i < xs.length; i++) { env[d.x] = xs[i]; pts.push([xs[i], c.y(env)]); }
        env[d.x] = values[d.x]; o.at = c.y(env); o.atX = values[d.x];
      } else if (t === "param") {
        for (i = 0; i < xs.length; i++) { env[d.x] = xs[i]; pts.push([c.fx(env), c.fy(env)]); }
        env[d.x] = values[d.x]; o.at = c.fy(env); o.atX = c.fx(env);
      } else if (t === "points") {
        var step = s.step || 1, cap = 0;
        for (x = Math.ceil(xr[0]); x <= xr[1] + 1e-9 && cap < 600; x += step, cap++) { env[d.x] = x; pts.push([c.fx ? c.fx(env) : x, c.fy ? c.fy(env) : c.y(env)]); }
        o.stem = !!s.stem;
      } else if (t === "scatter") {
        for (i = 0; i < s.xs.length; i++) pts.push([s.xs[i], s.ys[i]]);
        o.names = s.names || [];
      } else if (t === "hline") { o.v = c.y(env); }
      else if (t === "vline") { o.v = c.at(env); }
      else if (t === "dot") { pts.push([c.fx(env), c.fy(env)]); }
      else if (t === "field") {
        var yr = range(p, d.y), g = d.g || 13, gx = lin(xr[0], xr[1], g - 1), gy = lin(yr[0], yr[1], g - 1), a, b;
        for (a = 0; a < gx.length; a++) for (b = 0; b < gy.length; b++) {
          env[d.x] = gx[a]; env[d.y] = gy[b];
          pts.push([gx[a], gy[b], c.fx(env), c.fy(env)]);
        }
      }
      o.pts = pts;
      out.push(o);
    });
    return { x: xr, y: d.y ? range(p, d.y) : null, layers: out };
  }

  function sample3d(p, values) {
    var d = p.plot.d3, env = Object.assign({}, values), xr = range(p, d.x), out = [], n = d.n || 36;
    p.l3.forEach(function (L) {
      var s = L.spec, c = L.c, t = s.t, o = { t: t, label: s.label || "" }, i, j, k, pts = [];
      if (t === "surface" || t === "psurface") {
        var yr = range(p, d.y), xs = lin(xr[0], xr[1], n), ys = lin(yr[0], yr[1], n);
        for (i = 0; i <= n; i++) for (j = 0; j <= n; j++) {
          env[d.x] = xs[i]; env[d.y] = ys[j];
          pts.push(t === "surface" ? [xs[i], ys[j], c.z(env)] : [c.fx(env), c.fy(env), c.fz(env)]);
        }
        o.n = n;
        env[d.x] = values[d.x]; env[d.y] = values[d.y];
        o.at = t === "surface" ? [values[d.x], values[d.y], c.z(env)] : [c.fx(env), c.fy(env), c.fz(env)];
      } else if (t === "curve") {
        var m = d.m || 500, ts = lin(xr[0], xr[1], m);
        for (i = 0; i < ts.length; i++) { env[d.x] = ts[i]; pts.push([c.fx(env), c.fy(env), c.fz(env)]); }
        env[d.x] = values[d.x]; o.at = [c.fx(env), c.fy(env), c.fz(env)];
      } else if (t === "points") {
        var step = s.step || 1, cap = 0, x;
        for (x = Math.ceil(xr[0]); x <= xr[1] + 1e-9 && cap < 800; x += step, cap++) { env[d.x] = x; pts.push([c.fx(env), c.fy(env), c.fz(env)]); }
      } else if (t === "quiver") {
        var yr2 = range(p, d.y), g = d.g || 6, gx = lin(xr[0], xr[1], g - 1), gy = lin(yr2[0], yr2[1], g - 1), gz = d.z ? lin(range(p, d.z)[0], range(p, d.z)[1], g - 1) : [0];
        for (i = 0; i < gx.length; i++) for (j = 0; j < gy.length; j++) for (k = 0; k < gz.length; k++) {
          env[d.x] = gx[i]; env[d.y] = gy[j]; if (d.z) env[d.z] = gz[k];
          pts.push([gx[i], gy[j], gz[k], c.fx(env), c.fy(env), c.fz(env)]);
        }
      } else if (t === "dot") {
        pts.push([c.fx(env), c.fy(env), c.fz(env)]);
      } else if (t === "platonic") {
        var sd = solid(c.sel(env));
        o.verts = sd.verts; o.edges = sd.edges; o.name = sd.name; o.V = sd.V; o.E = sd.E; o.F = sd.F;
      }
      o.pts = pts;
      out.push(o);
    });
    return { layers: out };
  }

  // Counting aid for tests: how many sample coordinates are not finite.
  function badCount(samples) {
    var bad = 0, total = 0;
    samples.layers.forEach(function (l) {
      (l.pts || []).forEach(function (pt) { pt.forEach(function (v) { total++; if (!fin(v)) bad++; }); });
      if (l.v !== undefined) { total++; if (!fin(l.v)) bad++; }
    });
    return { bad: bad, total: total };
  }

  var api = { prepare: prepare, solid: solid, badCount: badCount, EXPR_KEYS: EXPR_KEYS, LAYERS_2D: ["line", "param", "points", "scatter", "hline", "vline", "dot", "field"], LAYERS_3D: ["surface", "psurface", "curve", "points", "dot", "quiver", "platonic"] };
  if (typeof module !== "undefined" && module.exports) module.exports = api; else root.PlotModel = api;
})(typeof window !== "undefined" ? window : this);
