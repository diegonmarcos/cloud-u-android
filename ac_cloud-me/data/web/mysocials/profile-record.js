/* Record page (Health, DD): renders PORTAL_DATA["profile-<id>"] (the public SCHEMA: labels only, no values).
 * Values never ship with this site. The Cloud Me app injects them after sign-in through the JS bridge:
 *   window.CloudMeProfile.inject('{"schema":"<id>","values":{"<field id>":{"value","status","expires"}}}')
 * Without an injection every field reads "Locked — sign in". Text is always set with textContent. */
(function () {
  var id = document.currentScript.getAttribute("data-schema");
  var schema = (globalThis.PORTAL_DATA || {})["profile-" + id];
  var app = document.getElementById("app");
  var values = null;
  var STATUS = ["pending", "declared", "verified", "expired"];
  var PENDING = "Pending — sample, to be filled/verified";
  function el(t, c, x) { var e = document.createElement(t); if (c) e.className = c; if (x != null) e.textContent = x; return e; }
  function entry(fid) { return values && values[fid] && typeof values[fid] === "object" ? values[fid] : null; }
  function statusOf(e) {
    if (!e || !String(e.value || "").trim()) return "pending";
    var today = new Date().toISOString().slice(0, 10), s = e.status;
    if (s === "verified" || s === "expired") return (s === "expired" || (e.expires && e.expires < today)) ? "expired" : "verified";
    return s === "declared" ? "declared" : "pending";
  }
  function render() {
    app.textContent = "";
    if (!schema) { app.appendChild(el("div", "card", "Schema missing.")); return; }
    var locked = values === null, all = [], n = 0, filled = 0, counts = {};
    STATUS.forEach(function (s) { counts[s] = 0; });
    schema.sections.forEach(function (s) { s.fields.forEach(function (f) {
      n++; var e = entry(f.id), st = statusOf(e); if (e && String(e.value || "").trim()) filled++; counts[st]++; all.push(st);
    }); });
    var head = el("div", "card" + (locked ? " lock" : ""));
    head.appendChild(el("h1", null, schema.title + (locked ? "" : " · " + Math.round(100 * filled / n) + "%")));
    head.appendChild(el("div", "dim", locked
      ? "Locked — sign in. This public page shows the structure only; the values stay in the private store and appear here after sign-in in Cloud Me."
      : filled + " of " + n + " fields filled"));
    if (!locked) { var ch = el("div", "chips"); STATUS.forEach(function (s) { ch.appendChild(el("span", "chip " + s, s + " " + counts[s])); }); head.appendChild(ch); }
    var b = el("button", "act", "Export PDF"); b.onclick = function () { window.print(); }; head.appendChild(b);
    if (globalThis.CloudMeBridge && globalThis.CloudMeBridge.importData) { // only inside the Cloud Me app
      var im = el("button", "act", "Import data"); im.style.marginLeft = "8px"; im.onclick = function () { globalThis.CloudMeBridge.importData(); }; head.appendChild(im);
    }
    app.appendChild(head);
    schema.sections.forEach(function (s) {
      var c = el("div", "card"); c.appendChild(el("h2", null, s.title));
      s.fields.forEach(function (f) {
        var e = entry(f.id), r = el("div", "row"), l = el("div"), has = e && String(e.value || "").trim();
        l.appendChild(el("small", null, f.label));
        l.appendChild(el("div", "v" + (has ? "" : " empty"), has ? String(e.value) + (e.expires ? " (expires " + e.expires + ")" : "") : (locked ? "Locked — sign in" : PENDING)));
        r.appendChild(l); r.appendChild(el("span", "chip " + (locked ? "locked" : statusOf(e)), locked ? "locked" : statusOf(e)));
        c.appendChild(r);
      });
      app.appendChild(c);
    });
  }
  globalThis.CloudMeProfile = {
    inject: function (doc) {
      try { var d = typeof doc === "string" ? JSON.parse(doc) : doc; } catch (x) { return false; }
      if (!d || d.schema !== id || !d.values || typeof d.values !== "object") return false;
      values = d.values; render(); return true;
    },
    lock: function () { values = null; render(); }
  };
  render();
})();
