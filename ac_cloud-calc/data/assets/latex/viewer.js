// Cloud Calc LaTeX viewer. Kotlin (ui/LatexScreens.kt) drives it through evaluateJavascript and hears back through
// the JavascriptInterface named in build.json::latex.renderer.bridge. No network: KaTeX renders from the bundled script.
(function () {
  "use strict";
  var root = document.getElementById("root");
  var bridge = window.CalcTex || null;

  function opts(display) {
    return { displayMode: display, throwOnError: true, trust: false, strict: "ignore", maxExpand: 1000, output: "html" };
  }
  // Render [tex] into [el]; the parse error text, or "" when it rendered.
  function draw(el, tex, display) {
    try {
      if (typeof katex === "undefined") { throw new Error("renderer missing"); }
      katex.render(tex, el, opts(display));
      return "";
    } catch (e) {
      el.textContent = "";
      return String((e && e.message) || e);
    }
  }
  function report(ok, message) {
    if (bridge && bridge.status) { bridge.status(ok, message || ""); }
    if (bridge && bridge.size) { bridge.size(Math.ceil(document.body.offsetHeight)); }
  }

  // One formula, centred big (detail view, editor preview). An error shows inline and never throws.
  window.showOne = function (tex, fontPx, description) {
    root.innerHTML = "";
    document.body.style.fontSize = (fontPx || 22) + "px";
    var box = document.createElement("div");
    box.className = "one";
    root.appendChild(box);
    if (!tex || !tex.trim()) { report(true, ""); return; }
    var err = draw(box, tex, true);
    if (err) {
      var e = document.createElement("div");
      e.className = "err";
      e.textContent = err;
      root.appendChild(e);
    }
    if (description) {
      var d = document.createElement("div");
      d.className = "desc";
      d.textContent = description;
      root.appendChild(d);
      try {
        renderMathInElement(d, { delimiters: [{ left: "$", right: "$", display: false }], throwOnError: false, strict: "ignore", trust: false });
      } catch (x) { /* the plain text stays */ }
    }
    report(!err, err);
  };

  // The library list: [{id, name, category, tex}]; tapping a row tells Kotlin which one.
  window.showList = function (json, emptyText) {
    var items = [];
    try { items = JSON.parse(json); } catch (x) { items = []; }
    root.innerHTML = "";
    document.body.style.fontSize = "17px";
    if (!items.length) {
      var em = document.createElement("div");
      em.className = "empty";
      em.textContent = emptyText || "";
      root.appendChild(em);
    }
    items.forEach(function (it) {
      var row = document.createElement("div");
      row.className = "item";
      var name = document.createElement("div");
      name.className = "name";
      name.textContent = it.name;
      var tex = document.createElement("div");
      tex.className = "tex";
      var err = draw(tex, it.tex, false);
      if (err) { tex.className = "err"; tex.textContent = it.tex; }
      var cat = document.createElement("div");
      cat.className = "cat";
      cat.textContent = it.category;
      row.appendChild(name); row.appendChild(tex); row.appendChild(cat);
      row.addEventListener("click", function () { if (bridge && bridge.pick) { bridge.pick(it.id); } });
      root.appendChild(row);
    });
    report(true, "");
  };

  // Colours from the app theme: {bg, fg, muted, accent, err} as CSS colours. The share image asks for a light palette.
  window.setTheme = function (json) {
    var t = {};
    try { t = JSON.parse(json); } catch (x) { return; }
    var s = document.documentElement.style;
    ["bg", "fg", "muted", "accent", "err", "line"].forEach(function (k) { if (t[k]) { s.setProperty("--" + k, t[k]); } });
  };

  if (bridge && bridge.ready) { bridge.ready(); }
})();
