// Cloud Calc plot viewer: draws one formula's plot spec (see model.js) as a 2D plot and a 3D scene on two canvases, with
// tap / long-press probing, animation, orbit and zoom. Kotlin (ui/LatexScreens.kt) drives it through evaluateJavascript and hears back
// through the JavascriptInterface named in build.json::latex.graph.bridge. Plots are computed only when plotLoad runs (a formula
// opened), never for a list. Own code, no library, no network.
(function (root) {
  "use strict";
  var doc = root.document;
  var bridge = root.CalcPlot || null;
  var PM = root.PlotModel;
  var PALETTE = ["#8ab4f8", "#f28b82", "#81c995", "#fdd663", "#c58af9", "#78d9ec", "#ffa94d", "#e599f7"];
  var RAMP = [[68, 1, 84], [59, 82, 139], [33, 145, 140], [94, 201, 98], [253, 231, 37]];
  var theme = { bg: "#121212", fg: "#e8e8e8", muted: "#9aa0a6", accent: "#8ab4f8", line: "#2a2a2a", grid: "#2f2f2f" };
  var S = {
    spec: null, prepared: null, values: {}, labels: {}, mode: "both",
    yaw: -0.7, pitch: 0.55, zoom: 1,
    anim: { on: false, speed: 1, pos: 0, last: 0, sent: 0 },
    probe2: null, probe3: null, s2: null, s3: null, dirty2: true, dirty3: true, frame: 0, view3: null, view2: null
  };
  function el(id) { return doc.getElementById(id); }
  var c2 = el("c2"), c3 = el("c3");

  // ── small helpers ─────────────────────────────────────────────────────────────────────────────────────────────
  function fin(x) { return typeof x === "number" && isFinite(x); }
  function quant(sorted, q) { return sorted[Math.min(sorted.length - 1, Math.max(0, Math.floor(q * (sorted.length - 1))))]; }
  // A robust [lo, hi] of finite values: the 2nd to 98th percentile once there are many, so a singularity is clipped, not charted.
  function robust(vals) {
    var a = vals.filter(fin).sort(function (p, q) { return p - q; });
    if (!a.length) return [-1, 1];
    var lo = a[0], hi = a[a.length - 1];
    if (a.length > 40) { lo = quant(a, 0.02); hi = quant(a, 0.98); }
    if (hi - lo < 1e-12) { var m = Math.abs(lo) || 1; lo -= m * 0.5; hi += m * 0.5; }
    return [lo, hi];
  }
  function niceStep(span, target) {
    var raw = span / Math.max(1, target), p = Math.pow(10, Math.floor(Math.log10(raw))), f = raw / p;
    return (f < 1.5 ? 1 : f < 3.5 ? 2 : f < 7.5 ? 5 : 10) * p;
  }
  function fmt(x) {
    if (!fin(x)) return String(x);
    var a = Math.abs(x);
    if (a !== 0 && (a >= 1e5 || a < 1e-3)) return x.toExponential(3).replace(/\.?0+e/, "e");
    return String(parseFloat(x.toPrecision(5)));
  }
  function mix(c0, c1, t) { return [c0[0] + (c1[0] - c0[0]) * t, c0[1] + (c1[1] - c0[1]) * t, c0[2] + (c1[2] - c0[2]) * t]; }
  function ramp(t) {
    t = Math.max(0, Math.min(1, t)) * (RAMP.length - 1);
    var i = Math.min(RAMP.length - 2, Math.floor(t));
    return mix(RAMP[i], RAMP[i + 1], t - i);
  }
  function rgb(c, k) { k = k === undefined ? 1 : k; return "rgb(" + Math.round(c[0] * k) + "," + Math.round(c[1] * k) + "," + Math.round(c[2] * k) + ")"; }
  function colorOf(i) { return PALETTE[i % PALETTE.length]; }
  function say(id, text) { var e = el(id); if (e) e.textContent = text; }
  function report(name) { if (bridge && bridge[name]) { var a = Array.prototype.slice.call(arguments, 1); bridge[name].apply(bridge, a); } }

  function setup(canvas, cssH) {
    var dpr = root.devicePixelRatio || 1, w = Math.max(160, Math.round(canvas.clientWidth || (root.innerWidth || 360) - 14));
    canvas.style.height = cssH + "px";
    if (canvas.width !== Math.round(w * dpr) || canvas.height !== Math.round(cssH * dpr)) { canvas.width = Math.round(w * dpr); canvas.height = Math.round(cssH * dpr); }
    var g = canvas.getContext("2d");
    g.setTransform(dpr, 0, 0, dpr, 0, 0);
    return { g: g, w: w, h: cssH };
  }
  function heights() { return S.mode === "both" ? { h2: 250, h3: 300 } : { h2: 360, h3: 400 }; }

  // ── spec and values ───────────────────────────────────────────────────────────────────────────────────────────
  function defaults(plot) { var v = {}; plot.vars.forEach(function (x) { v[x.symbol] = x.default; }); return v; }

  root.plotLoad = function (json) {
    var o;
    try { o = JSON.parse(json); } catch (e) { fail("bad plot message"); return; }
    S.labels = o.labels || S.labels;
    try { S.prepared = PM.prepare(o.plot); }
    catch (e) { S.prepared = null; fail(String((e && e.message) || e)); return; }
    S.spec = o.plot;
    S.values = Object.assign(defaults(o.plot), o.values || {});
    S.mode = o.mode || S.mode;
    S.probe2 = S.probe3 = null;
    S.anim.on = false; S.anim.pos = 0;
    say("msg", "");
    var cap = el("cap");
    if (cap) cap.textContent = o.plot.caption + (o.plot.kind === "illustration" && S.labels.illustration ? " (" + S.labels.illustration + ")" : "");
    say("l2", S.labels.d2 || "2D"); say("l3", S.labels.d3 || "3D");
    S.dirty2 = S.dirty3 = true;
    layout();
    S.view3 = null; S.view2 = null;
    schedule();
  };
  function fail(msg) { say("msg", msg); report("error", msg); layout(); }

  root.plotValues = function (json) {
    var v; try { v = JSON.parse(json); } catch (e) { return; }
    Object.keys(v).forEach(function (k) {
      if (S.anim.on && S.spec && S.spec.anim && S.spec.anim.var === k) return; // the animation owns its variable while it plays
      S.values[k] = v[k];
    });
    S.dirty2 = S.dirty3 = true; schedule();
  };
  root.plotMode = function (m) { S.mode = m; layout(); S.dirty2 = S.dirty3 = true; schedule(); };
  root.plotTheme = function (json) {
    var t; try { t = JSON.parse(json); } catch (e) { return; }
    Object.keys(t).forEach(function (k) { theme[k] = t[k]; });
    var st = doc.documentElement.style;
    ["bg", "fg", "muted", "accent", "err", "line"].forEach(function (k) { if (t[k]) st.setProperty("--" + k, t[k]); });
    theme.grid = t.line || theme.grid;
    S.dirty2 = S.dirty3 = true; schedule();
  };
  root.plotAnim = function (on, speed) {
    var a = S.anim;
    a.speed = speed > 0 ? speed : 1;
    if (on && S.spec && S.spec.anim) {
      if (!a.on) { a.on = true; a.last = 0; var an = S.spec.anim, span = an.max - an.min; a.pos = span > 0 ? (S.values[an.var] - an.min) / span : 0; }
    } else a.on = false;
    schedule();
  };
  root.plotView = function (what, factor) {
    if (what === "reset") { S.yaw = -0.7; S.pitch = 0.55; S.zoom = 1; }
    else if (what === "zoom") S.zoom = Math.max(0.4, Math.min(5, S.zoom * (factor || 1)));
    S.dirty3 = true; schedule();
  };
  root.plotClearProbe = function () { S.probe2 = S.probe3 = null; say("r2", ""); say("r3", ""); S.dirty2 = S.dirty3 = true; schedule(); };

  function layout() {
    var show2 = S.mode !== "3d" && !!S.spec, show3 = S.mode !== "2d" && !!S.spec;
    el("s2").style.display = show2 ? "block" : "none";
    el("s3").style.display = show3 ? "block" : "none";
    el("cap").style.display = S.spec ? "block" : "none";
    S.dirty2 = S.dirty3 = true;
    root.setTimeout(function () { if (bridge && bridge.size) bridge.size(Math.ceil(doc.body.offsetHeight)); }, 0);
  }

  // ── the loop ──────────────────────────────────────────────────────────────────────────────────────────────────
  function schedule() { if (!S.frame) S.frame = (root.requestAnimationFrame || function (f) { return root.setTimeout(function () { f(Date.now()); }, 16); })(tick); }
  function tick(now) {
    S.frame = 0;
    var a = S.anim;
    if (a.on && S.spec && S.spec.anim) {
      var an = S.spec.anim;
      if (a.last) a.pos = (a.pos + (now - a.last) / 1000 * a.speed / 6) % 1;
      a.last = now;
      var val = an.min + (an.max - an.min) * a.pos;
      S.values[an.var] = val;
      S.dirty2 = S.dirty3 = true;
      if (now - a.sent > 120) { a.sent = now; report("anim", an.var, val); }
    }
    if (S.prepared && (S.dirty2 || S.dirty3)) {
      try {
        if (S.mode !== "3d" && S.dirty2) { S.s2 = S.prepared.sample2d(S.values); draw2d(); S.dirty2 = false; }
        if (S.mode !== "2d" && S.dirty3) { S.s3 = S.prepared.sample3d(S.values); draw3d(); S.dirty3 = false; }
      } catch (e) { fail(String((e && e.message) || e)); S.dirty2 = S.dirty3 = false; }
    }
    if (a.on || S.dirty2 || S.dirty3) schedule();
  }
  function redraw3() { S.dirty3 = true; schedule(); }

  // ── 2D ────────────────────────────────────────────────────────────────────────────────────────────────────────
  function frame2(s) {
    var d = S.spec.d2, vars = {}; S.spec.vars.forEach(function (v) { vars[v.symbol] = v; });
    var xs = [], ys = [], equal = false, i;
    s.layers.forEach(function (l) {
      if (l.t === "line" || l.t === "vline" || l.t === "hline") { xs.push(s.x[0], s.x[1]); }
      if (l.t === "line") l.pts.forEach(function (p) { ys.push(p[1]); });
      else if (l.t === "param") { equal = true; l.pts.forEach(function (p) { xs.push(p[0]); ys.push(p[1]); }); }
      else if (l.t === "points" || l.t === "scatter" || l.t === "dot") l.pts.forEach(function (p) { xs.push(p[0]); ys.push(p[1]); });
      else if (l.t === "hline" && fin(l.v)) ys.push(l.v);
      else if (l.t === "vline" && fin(l.v)) xs.push(l.v);
      else if (l.t === "field") { equal = true; xs.push(s.x[0], s.x[1]); ys.push(s.y[0], s.y[1]); }
    });
    var xr = xs.some(fin) ? [Math.min.apply(null, xs.filter(fin)), Math.max.apply(null, xs.filter(fin))] : s.x.slice();
    if (!(xr[1] > xr[0])) { xr = [xr[0] - 1, xr[0] + 1]; }
    var yr = robust(ys);
    if (d.ymin !== undefined) yr[0] = d.ymin;
    if (d.ymax !== undefined) yr[1] = d.ymax;
    var xp = (xr[1] - xr[0]) * 0.04, yp = (yr[1] - yr[0]) * 0.1;
    xr = [xr[0] - xp, xr[1] + xp]; yr = [yr[0] - yp, yr[1] + yp];
    return { x: xr, y: yr, equal: equal };
  }

  function draw2d() {
    var s = S.s2, hh = heights().h2, c = setup(c2, hh), g = c.g, W = c.w, H = c.h;
    var m = { l: 46, r: 10, t: 22, b: 24 }, pw = W - m.l - m.r, ph = H - m.t - m.b;
    var f = frame2(s), xr = f.x, yr = f.y;
    if (f.equal) { // same scale on both axes so a circle is round
      var kx = pw / (xr[1] - xr[0]), ky = ph / (yr[1] - yr[0]), k = Math.min(kx, ky);
      var cx = (xr[0] + xr[1]) / 2, cy = (yr[0] + yr[1]) / 2;
      xr = [cx - pw / k / 2, cx + pw / k / 2]; yr = [cy - ph / k / 2, cy + ph / k / 2];
    }
    function X(x) { return m.l + (x - xr[0]) / (xr[1] - xr[0]) * pw; }
    function Y(y) { return m.t + (1 - (y - yr[0]) / (yr[1] - yr[0])) * ph; }
    S.view2 = { xr: xr, yr: yr, m: m, pw: pw, ph: ph, X: X, Y: Y, W: W, H: H };
    g.clearRect(0, 0, W, H);
    g.fillStyle = theme.bg; g.fillRect(0, 0, W, H);
    g.font = "10px sans-serif"; g.lineWidth = 1;
    var gx = niceStep(xr[1] - xr[0], pw / 60), gy = niceStep(yr[1] - yr[0], ph / 34), v;
    g.textAlign = "center"; g.textBaseline = "top";
    for (v = Math.ceil(xr[0] / gx) * gx; v <= xr[1]; v += gx) {
      g.strokeStyle = Math.abs(v) < gx / 1e3 ? theme.muted : theme.grid; g.beginPath(); g.moveTo(X(v), m.t); g.lineTo(X(v), H - m.b); g.stroke();
      g.fillStyle = theme.muted; g.fillText(fmt(v), X(v), H - m.b + 4);
    }
    g.textAlign = "right"; g.textBaseline = "middle";
    for (v = Math.ceil(yr[0] / gy) * gy; v <= yr[1]; v += gy) {
      g.strokeStyle = Math.abs(v) < gy / 1e3 ? theme.muted : theme.grid; g.beginPath(); g.moveTo(m.l, Y(v)); g.lineTo(W - m.r, Y(v)); g.stroke();
      g.fillStyle = theme.muted; g.fillText(fmt(v), m.l - 4, Y(v));
    }
    g.save(); g.beginPath(); g.rect(m.l, m.t, pw, ph); g.clip();
    var jump = (yr[1] - yr[0]) * 3;
    s.layers.forEach(function (l, li) {
      var col = colorOf(li), pts = l.pts, i, p, q;
      g.strokeStyle = col; g.fillStyle = col; g.lineWidth = 2; g.setLineDash(l.dash ? [6, 4] : []);
      if ((l.t === "line" || l.t === "param") && l.style === "dots") {
        l.pts.forEach(function (p) { if (fin(p[0]) && fin(p[1])) g.fillRect(X(p[0]) - 0.7, Y(p[1]) - 0.7, 1.6, 1.6); });
      } else if (l.t === "line" || l.t === "param") {
        g.beginPath();
        var pen = false, prev = null;
        for (i = 0; i < pts.length; i++) {
          p = pts[i];
          if (!fin(p[0]) || !fin(p[1])) { pen = false; prev = null; continue; }
          if (pen && prev && Math.abs(p[1] - prev[1]) > jump) pen = false;
          if (pen) g.lineTo(X(p[0]), Y(p[1])); else g.moveTo(X(p[0]), Y(p[1]));
          pen = true; prev = p;
        }
        g.stroke();
      } else if (l.t === "points") {
        for (i = 0; i < pts.length; i++) {
          p = pts[i];
          if (!fin(p[0]) || !fin(p[1])) continue;
          if (l.stem) { g.lineWidth = 1; g.beginPath(); g.moveTo(X(p[0]), Y(0)); g.lineTo(X(p[0]), Y(p[1])); g.stroke(); }
          g.beginPath(); g.arc(X(p[0]), Y(p[1]), pts.length > 150 ? 1.6 : 3, 0, 6.2832); g.fill();
        }
      } else if (l.t === "scatter") {
        g.font = "10px sans-serif"; g.textAlign = "left"; g.textBaseline = "bottom";
        for (i = 0; i < pts.length; i++) {
          g.beginPath(); g.arc(X(pts[i][0]), Y(pts[i][1]), 4, 0, 6.2832); g.fill();
          if (l.names[i]) g.fillText(l.names[i], X(pts[i][0]) + 5, Y(pts[i][1]) - 3);
        }
      } else if (l.t === "hline" && fin(l.v)) {
        g.lineWidth = 1; g.beginPath(); g.moveTo(m.l, Y(l.v)); g.lineTo(W - m.r, Y(l.v)); g.stroke();
      } else if (l.t === "vline" && fin(l.v)) {
        g.lineWidth = 1; g.beginPath(); g.moveTo(X(l.v), m.t); g.lineTo(X(l.v), H - m.b); g.stroke();
      } else if (l.t === "dot" && pts.length && fin(pts[0][0]) && fin(pts[0][1])) {
        g.beginPath(); g.arc(X(pts[0][0]), Y(pts[0][1]), 5, 0, 6.2832); g.fill();
      } else if (l.t === "field") {
        var mag = 0;
        pts.forEach(function (a) { var r = Math.hypot(a[2], a[3]); if (fin(r) && r > mag) mag = r; });
        var cell = Math.min(pw, ph) / 13 * 0.85;
        pts.forEach(function (a) {
          var r = Math.hypot(a[2], a[3]);
          if (!fin(r) || r === 0 || mag === 0) return;
          var len = cell * (0.35 + 0.65 * r / mag), ux = a[2] / r, uy = -a[3] / r; // screen y points down
          var x0 = X(a[0]), y0 = Y(a[1]), x1 = x0 + ux * len, y1 = y0 + uy * len;
          g.globalAlpha = 0.35 + 0.65 * r / mag; g.lineWidth = 1.5;
          g.beginPath(); g.moveTo(x0, y0); g.lineTo(x1, y1);
          g.lineTo(x1 - ux * 5 - uy * 3, y1 - uy * 5 + ux * 3); g.moveTo(x1, y1); g.lineTo(x1 - ux * 5 + uy * 3, y1 - uy * 5 - ux * 3);
          g.stroke(); g.globalAlpha = 1;
        });
      }
      g.setLineDash([]);
    });
    // probe markers: the layer value at the probe variable
    s.layers.forEach(function (l, li) {
      if ((l.t === "line" || l.t === "param") && l.style !== "dots" && fin(l.at) && fin(l.atX)) {
        g.fillStyle = colorOf(li); g.strokeStyle = theme.bg; g.lineWidth = 1.5;
        g.beginPath(); g.arc(X(l.atX), Y(l.at), 4.5, 0, 6.2832); g.fill(); g.stroke();
      }
    });
    g.restore();
    // legend
    g.font = "11px sans-serif"; g.textAlign = "left"; g.textBaseline = "middle";
    var lx = m.l + 6, seen = {};
    s.layers.forEach(function (l, li) {
      if (!l.label || seen[l.label]) return;
      seen[l.label] = 1;
      g.fillStyle = colorOf(li); g.fillRect(lx, 8, 10, 3);
      g.fillStyle = theme.fg; g.fillText(l.label, lx + 14, 10);
      lx += 22 + g.measureText(l.label).width;
    });
    // axis name
    var xv = S.spec.d2.x, xl = S.spec.vars.filter(function (v) { return v.symbol === xv; })[0];
    g.fillStyle = theme.muted; g.textAlign = "right"; g.textBaseline = "bottom";
    g.fillText(xl ? xl.symbol : xv, W - m.r, H - 1);
    // probe crosshair
    var pr = S.probe2;
    if (pr) {
      g.strokeStyle = theme.fg; g.fillStyle = theme.fg; g.lineWidth = 1; g.setLineDash([3, 3]);
      g.beginPath(); g.moveTo(X(pr.x), m.t); g.lineTo(X(pr.x), H - m.b); g.moveTo(m.l, Y(pr.y)); g.lineTo(W - m.r, Y(pr.y)); g.stroke();
      g.setLineDash([]); g.beginPath(); g.arc(X(pr.x), Y(pr.y), 5, 0, 6.2832); g.stroke();
    }
  }

  function probe2d(px, py, free) {
    var v = S.view2, s = S.s2;
    if (!v || !s) return;
    function toX(sx) { return v.xr[0] + (sx - v.m.l) / v.pw * (v.xr[1] - v.xr[0]); }
    function toY(sy) { return v.yr[0] + (1 - (sy - v.m.t) / v.ph) * (v.yr[1] - v.yr[0]); }
    var best = null, bd = 28 * 28;
    if (!free) {
      s.layers.forEach(function (l) {
        if (l.t === "hline" || l.t === "vline" || l.t === "field") return;
        l.pts.forEach(function (p) {
          if (!fin(p[0]) || !fin(p[1])) return;
          var dx = v.X(p[0]) - px, dy = v.Y(p[1]) - py, d = dx * dx + dy * dy;
          if (d < bd) { bd = d; best = { x: p[0], y: p[1], label: l.label }; }
        });
      });
    }
    var text;
    if (best) { S.probe2 = best; text = (best.label ? best.label + ": " : "") + "x = " + fmt(best.x) + ", y = " + fmt(best.y); }
    else {
      var x = toX(px); S.probe2 = { x: x, y: toY(py) };
      var parts = [];
      // the value of every curve at that x (long-press reads the curves, a tap on empty space reads the point)
      s.layers.forEach(function (l) {
        if (l.t !== "line" || !l.pts.length) return;
        var lo = l.pts[0][0], hi = l.pts[l.pts.length - 1][0];
        if (x < lo || x > hi) return;
        var idx = Math.round((x - lo) / (hi - lo) * (l.pts.length - 1)), y = l.pts[idx] && l.pts[idx][1];
        if (fin(y)) parts.push((l.label || "y") + " = " + fmt(y));
      });
      text = "x = " + fmt(x) + (parts.length ? ", " + parts.join(", ") : ", y = " + fmt(S.probe2.y));
      if (parts.length && free) S.probe2.y = toY(py);
    }
    say("r2", text); report("probe", "2d", text);
    S.dirty2 = true; schedule();
  }

  // ── 3D ────────────────────────────────────────────────────────────────────────────────────────────────────────
  function bounds3(s) {
    var ax = [[], [], []], uniform = false;
    s.layers.forEach(function (l) {
      if (l.t === "platonic") { uniform = true; l.verts.forEach(function (p) { for (var k = 0; k < 3; k++) ax[k].push(p[k]); }); return; }
      if (l.t === "psurface") uniform = true;
      (l.pts || []).forEach(function (p) { for (var k = 0; k < 3; k++) ax[k].push(p[k]); });
    });
    var r = ax.map(function (a) { return robust(a); });
    // surface heights are clipped to the robust range; the x/y/z axes of other layers keep their full extent
    s.layers.forEach(function (l) {
      if (l.t === "surface") return;
      (l.pts || []).forEach(function (p) { for (var k = 0; k < 3; k++) if (fin(p[k])) { if (p[k] < r[k][0]) r[k][0] = p[k]; if (p[k] > r[k][1]) r[k][1] = p[k]; } });
    });
    if (uniform) {
      var span = Math.max(r[0][1] - r[0][0], r[1][1] - r[1][0], r[2][1] - r[2][0]);
      r = r.map(function (q) { var c = (q[0] + q[1]) / 2; return [c - span / 2, c + span / 2]; });
    }
    return r;
  }

  function project(v, p, W, H) {
    // p is in [-1,1]^3 (z up); rotate by yaw about z, then pitch about x
    var cy = Math.cos(v.yaw), sy = Math.sin(v.yaw), cp = Math.cos(v.pitch), sp = Math.sin(v.pitch);
    var x1 = p[0] * cy - p[1] * sy, y1 = p[0] * sy + p[1] * cy, z1 = p[2];
    var y2 = y1 * cp - z1 * sp, z2 = y1 * sp + z1 * cp; // y2 is depth (away from the viewer), z2 is up
    var d = 4.2, k = (Math.min(W, H) * 0.34 * v.zoom) * d / (d + y2);
    return [W / 2 + x1 * k, H / 2 + 6 - z2 * k, y2];
  }

  function draw3d() {
    var s = S.s3, hh = heights().h3, c = setup(c3, hh), g = c.g, W = c.w, H = c.h;
    g.clearRect(0, 0, W, H); g.fillStyle = theme.bg; g.fillRect(0, 0, W, H);
    var r = bounds3(s), vars = S.spec.d3;
    function norm(p) {
      var o = [0, 0, 0];
      for (var k = 0; k < 3; k++) {
        var lo = r[k][0], hi = r[k][1], h = hi - lo || 1, x = p[k];
        if (!fin(x)) return null;
        if (k === 2) x = Math.max(lo - 0.3 * h, Math.min(hi + 0.3 * h, x)); // spikes are clipped, not charted
        o[k] = (x - lo) / h * 2 - 1;
      }
      return o;
    }
    var v = { yaw: S.yaw, pitch: S.pitch, zoom: S.zoom };
    S.view3 = { r: r, v: v, W: W, H: H, norm: norm, items: [] };
    var prim = [], items = S.view3.items, li;
    function P(p) { return project(v, p, W, H); }
    // the box
    var corners = [], a, b, d;
    for (a = 0; a < 2; a++) for (b = 0; b < 2; b++) for (d = 0; d < 2; d++) corners.push([a * 2 - 1, b * 2 - 1, d * 2 - 1]);
    var boxEdges = [[0, 1], [2, 3], [4, 5], [6, 7], [0, 2], [1, 3], [4, 6], [5, 7], [0, 4], [1, 5], [2, 6], [3, 7]];
    g.strokeStyle = theme.grid; g.lineWidth = 1;
    boxEdges.forEach(function (e) { var p = P(corners[e[0]]), q = P(corners[e[1]]); g.beginPath(); g.moveTo(p[0], p[1]); g.lineTo(q[0], q[1]); g.stroke(); });
    // axis labels with ranges
    var tips = [[1, -1, -1], [-1, 1, -1], [-1, -1, 1]];
    g.font = "10px sans-serif"; g.textAlign = "center"; g.textBaseline = "middle";
    var plain = s.layers.every(function (l) { return l.t === "surface"; });
    var lab3 = plain ? [vars.x, vars.y, "z"] : ["x", "y", "z"];
    for (var k = 0; k < 3; k++) {
      var from = [-1, -1, -1], to = tips[k], pa = P(from), pb = P(to);
      g.strokeStyle = theme.muted; g.beginPath(); g.moveTo(pa[0], pa[1]); g.lineTo(pb[0], pb[1]); g.stroke();
      g.fillStyle = theme.muted; g.fillText(lab3[k] + " " + fmt(r[k][0]) + ".." + fmt(r[k][1]), pb[0], pb[1] + (k === 2 ? -8 : 9));
    }
    // primitives
    for (li = 0; li < s.layers.length; li++) {
      var l = s.layers[li], col = colorOf(li);
      if (l.t === "surface" || l.t === "psurface") {
        var n = l.n, grid = [], i, j;
        for (i = 0; i <= n; i++) { grid.push([]); for (j = 0; j <= n; j++) { var np = norm(l.pts[i * (n + 1) + j]); grid[i].push(np); } }
        var zlo = r[2][0], zhi = r[2][1];
        for (i = 0; i < n; i++) for (j = 0; j < n; j++) {
          var q = [grid[i][j], grid[i + 1][j], grid[i + 1][j + 1], grid[i][j + 1]];
          if (q.some(function (z) { return !z; })) continue;
          var pr = q.map(P), zavg = (q[0][2] + q[1][2] + q[2][2] + q[3][2]) / 4;
          // screen-space normal for a little shading
          var ux = pr[1][0] - pr[0][0], uy = pr[1][1] - pr[0][1], wx = pr[3][0] - pr[0][0], wy = pr[3][1] - pr[0][1];
          var shade = 0.62 + 0.38 * Math.min(1, Math.abs(ux * wy - uy * wx) / (Math.hypot(ux, uy) * Math.hypot(wx, wy) + 1e-6));
          prim.push({ d: (pr[0][2] + pr[1][2] + pr[2][2] + pr[3][2]) / 4, quad: pr, color: rgb(ramp((zavg + 1) / 2), shade) });
        }
        for (i = 0; i <= n; i++) for (j = 0; j <= n; j++) { if (grid[i][j]) items.push({ p: grid[i][j], raw: l.pts[i * (n + 1) + j], label: l.label }); }
      } else if (l.t === "curve") {
        var prev = null, step = Math.max(1, Math.floor(l.pts.length / 400));
        for (var ci = 0; ci < l.pts.length; ci += step) {
          var cp = norm(l.pts[ci]);
          if (cp && prev) { var a1 = P(prev), b1 = P(cp); prim.push({ d: (a1[2] + b1[2]) / 2, seg: [a1, b1], color: col, w: 2.5 }); }
          prev = cp;
          if (cp) items.push({ p: cp, raw: l.pts[ci], label: l.label });
        }
        if (fin(l.at && l.at[0])) { var ap = norm(l.at); if (ap) { var aq = P(ap); prim.push({ d: aq[2] - 0.01, dot: aq, color: col, r: 5.5, ring: true }); } }
      } else if (l.t === "points" || l.t === "dot") {
        l.pts.forEach(function (p) {
          var np2 = norm(p); if (!np2) return;
          var pq = P(np2);
          prim.push({ d: pq[2], dot: pq, color: col, r: l.t === "dot" ? 6 : 2.6, ring: l.t === "dot", label: l.t === "dot" ? l.label : "" });
          items.push({ p: np2, raw: p, label: l.label });
        });
      } else if (l.t === "quiver") {
        var mag = 0, mx = 0;
        l.pts.forEach(function (p) { var m3 = Math.hypot(p[3], p[4], p[5]); if (fin(m3) && m3 > mag) mag = m3; });
        var g5 = (S.spec.d3.g || 6), cell = 2 / Math.max(1, g5 - 1) * 0.55;
        l.pts.forEach(function (p) {
          var m3 = Math.hypot(p[3], p[4], p[5]);
          if (!fin(m3) || m3 === 0 || mag === 0) return;
          var base = norm([p[0], p[1], p[2]]); if (!base) return;
          // direction in normalised space: scale each component by the axis scale so the arrow points the way the data does
          var sx = 2 / ((r[0][1] - r[0][0]) || 1), sy = 2 / ((r[1][1] - r[1][0]) || 1), sz = 2 / ((r[2][1] - r[2][0]) || 1);
          var dx = p[3] * sx, dy = p[4] * sy, dz = p[5] * sz, dl = Math.hypot(dx, dy, dz) || 1, len = cell * (0.3 + 0.7 * m3 / mag);
          var tip = [base[0] + dx / dl * len, base[1] + dy / dl * len, base[2] + dz / dl * len];
          var pa2 = P(base), pb2 = P(tip);
          prim.push({ d: (pa2[2] + pb2[2]) / 2, seg: [pa2, pb2], color: rgb(ramp(m3 / mag)), w: 1.8 });
          prim.push({ d: pb2[2], dot: pb2, color: rgb(ramp(m3 / mag)), r: 2.4 });
          items.push({ p: base, raw: [p[0], p[1], p[2]], label: l.label });
        });
      } else if (l.t === "platonic") {
        var vs = l.verts.map(function (p) { return norm(p); });
        l.edges.forEach(function (e) { var a3 = P(vs[e[0]]), b3 = P(vs[e[1]]); prim.push({ d: (a3[2] + b3[2]) / 2, seg: [a3, b3], color: col, w: 2.5 }); });
        l.verts.forEach(function (p, vi) { var q3 = P(vs[vi]); prim.push({ d: q3[2] - 0.01, dot: q3, color: colorOf(li + 1), r: 4 }); items.push({ p: vs[vi], raw: p, label: l.name }); });
        say("r3", l.name + ": V = " + l.V + ", E = " + l.E + ", F = " + l.F + ", V − E + F = " + (l.V - l.E + l.F));
      }
    }
    prim.sort(function (p, q) { return q.d - p.d; });
    prim.forEach(function (o) {
      if (o.quad) {
        g.beginPath(); g.moveTo(o.quad[0][0], o.quad[0][1]);
        for (var t = 1; t < 4; t++) g.lineTo(o.quad[t][0], o.quad[t][1]);
        g.closePath(); g.fillStyle = o.color; g.fill(); g.strokeStyle = "rgba(0,0,0,0.25)"; g.lineWidth = 0.5; g.stroke();
      } else if (o.seg) {
        g.strokeStyle = o.color; g.lineWidth = o.w; g.beginPath(); g.moveTo(o.seg[0][0], o.seg[0][1]); g.lineTo(o.seg[1][0], o.seg[1][1]); g.stroke();
      } else if (o.dot) {
        g.fillStyle = o.color; g.beginPath(); g.arc(o.dot[0], o.dot[1], o.r, 0, 6.2832); g.fill();
        if (o.ring) { g.strokeStyle = theme.bg; g.lineWidth = 1.5; g.stroke(); }
        if (o.label) { g.fillStyle = theme.fg; g.font = "10px sans-serif"; g.textAlign = "left"; g.fillText(o.label, o.dot[0] + 8, o.dot[1] - 6); }
      }
    });
    // legend
    g.font = "11px sans-serif"; g.textAlign = "left"; g.textBaseline = "middle";
    var lx = 8, seen = {};
    s.layers.forEach(function (l, i) {
      var name = l.label || ""; if (!name || seen[name] || l.t === "platonic") return; seen[name] = 1;
      g.fillStyle = colorOf(i); g.fillRect(lx, 8, 10, 3); g.fillStyle = theme.fg; g.fillText(name, lx + 14, 10); lx += 22 + g.measureText(name).width;
    });
    // probe crosshair: the point and drop lines to the three box walls
    var pb3 = S.probe3;
    if (pb3 && pb3.p) {
      var p0 = P(pb3.p), wx = P([-1, pb3.p[1], pb3.p[2]]), wy = P([pb3.p[0], 1, pb3.p[2]]), wz = P([pb3.p[0], pb3.p[1], -1]);
      g.strokeStyle = theme.fg; g.lineWidth = 1; g.setLineDash([3, 3]);
      [wx, wy, wz].forEach(function (w) { g.beginPath(); g.moveTo(p0[0], p0[1]); g.lineTo(w[0], w[1]); g.stroke(); });
      g.setLineDash([]); g.beginPath(); g.arc(p0[0], p0[1], 6, 0, 6.2832); g.stroke();
    }
  }

  function probe3d(px, py, radius) {
    var vw = S.view3;
    if (!vw) return;
    var best = null, bd = radius * radius;
    vw.items.forEach(function (it) {
      var q = project(vw.v, it.p, vw.W, vw.H), dx = q[0] - px, dy = q[1] - py, d = dx * dx + dy * dy;
      if (d < bd || (best && d === bd && q[2] < best.depth)) { bd = d; best = { it: it, depth: q[2] }; }
    });
    var text;
    if (best) {
      S.probe3 = { p: best.it.p, raw: best.it.raw };
      text = (best.it.label ? best.it.label + ": " : "") + "x = " + fmt(best.it.raw[0]) + ", y = " + fmt(best.it.raw[1]) + ", z = " + fmt(best.it.raw[2]);
    } else { S.probe3 = null; text = ""; }
    say("r3", text); report("probe", "3d", text);
    redraw3();
  }

  // ── gestures ──────────────────────────────────────────────────────────────────────────────────────────────────
  function local(canvas, e) { var r = canvas.getBoundingClientRect(); return [e.clientX - r.left, e.clientY - r.top]; }

  function wire() {
    if (!c2 || !c3 || !c2.addEventListener) return;
    var timer = 0, down = null, ptrs = {}, pinch = 0;
    function clear() { if (timer) { root.clearTimeout(timer); timer = 0; } }
    function count() { return Object.keys(ptrs).length; }

    c2.addEventListener("pointerdown", function (e) {
      var p = local(c2, e); down = { x: p[0], y: p[1], t: Date.now(), moved: false, id: e.pointerId };
      clear();
      timer = root.setTimeout(function () { timer = 0; if (down && !down.moved) { down.long = true; probe2d(down.x, down.y, true); } }, 450);
    });
    c2.addEventListener("pointermove", function (e) {
      if (!down) return;
      var p = local(c2, e);
      if (!down.moved && Math.hypot(p[0] - down.x, p[1] - down.y) > 8) { down.moved = true; clear(); }
      if (down.moved && Math.abs(p[0] - down.x) > Math.abs(p[1] - down.y) * 1.2) probe2d(p[0], p[1], true); // scrub along x
    });
    function up2() { clear(); down = null; }
    c2.addEventListener("pointerup", function (e) {
      clear();
      if (down && !down.moved && !down.long && Date.now() - down.t < 450) { var p = local(c2, e); probe2d(p[0], p[1], false); }
      down = null;
    });
    c2.addEventListener("pointercancel", up2);

    var drag = null;
    c3.addEventListener("pointerdown", function (e) {
      if (c3.setPointerCapture) { try { c3.setPointerCapture(e.pointerId); } catch (x) { /* a synthetic event */ } }
      var p = local(c3, e); ptrs[e.pointerId] = p;
      report("grab", true);
      clear();
      if (count() === 1) {
        drag = { x: p[0], y: p[1], t: Date.now(), moved: false };
        timer = root.setTimeout(function () { timer = 0; if (drag && !drag.moved) { drag.long = true; probe3d(drag.x, drag.y, 70); } }, 450);
      } else { drag = null; pinch = 0; }
    });
    c3.addEventListener("pointermove", function (e) {
      if (!ptrs[e.pointerId]) return;
      var p = local(c3, e), old = ptrs[e.pointerId];
      ptrs[e.pointerId] = p;
      if (count() >= 2) {
        var ids = Object.keys(ptrs), a = ptrs[ids[0]], b = ptrs[ids[1]], dist = Math.hypot(a[0] - b[0], a[1] - b[1]);
        if (pinch > 0 && dist > 0) { S.zoom = Math.max(0.4, Math.min(5, S.zoom * dist / pinch)); redraw3(); }
        pinch = dist; return;
      }
      if (!drag) return;
      if (!drag.moved && Math.hypot(p[0] - drag.x, p[1] - drag.y) > 8) { drag.moved = true; clear(); }
      if (drag.moved) {
        S.yaw += (p[0] - old[0]) * 0.012;
        S.pitch = Math.max(-1.45, Math.min(1.45, S.pitch + (p[1] - old[1]) * 0.012));
        redraw3();
      }
    });
    function end3(e) {
      var was = ptrs[e.pointerId];
      delete ptrs[e.pointerId]; clear();
      if (drag && !drag.moved && !drag.long && was && e.type === "pointerup" && Date.now() - drag.t < 450) probe3d(was[0], was[1], 40);
      if (!count()) { drag = null; pinch = 0; report("grab", false); }
    }
    c3.addEventListener("pointerup", end3);
    c3.addEventListener("pointercancel", end3);
    c3.addEventListener("wheel", function (e) { if (e.preventDefault) e.preventDefault(); S.zoom = Math.max(0.4, Math.min(5, S.zoom * (e.deltaY < 0 ? 1.1 : 0.9))); redraw3(); });
    root.addEventListener("resize", function () { S.dirty2 = S.dirty3 = true; layout(); schedule(); });
  }
  wire();

  root.__plotState = S; // read by the headless smoke test (test/test-plots.mjs); the app never touches it
  if (bridge && bridge.ready) bridge.ready();
})(typeof window !== "undefined" ? window : this);
