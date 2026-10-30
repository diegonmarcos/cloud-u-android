// Cloud Calc plot expressions: a SAFE expression language, parsed and evaluated by this file alone.
// No eval, no Function, no property access: an identifier is a declared variable, a bound sum/prod index or one of the
// constants; a call is one of the whitelisted functions. Anything else is a parse error. The Kotlin twin (PlotExpr.kt) is
// held to the same behaviour by data/latex/expr-vectors.json, which both test suites run.
// Grammar:  cmp := add [(< | > | <= | >=) add]   add := mul {(+|-) mul}   mul := unary {(*|/) unary}
//           unary := (-|+) unary | pow            pow := primary [^ unary]   primary := number | name | name(args) | (cmp)
// Constants: pi e tau.  sum(i, a, b, body) and prod(i, a, b, body) bind i over the integers a..b (at most 100000 terms).
(function (root) {
  "use strict";
  var STEP_BUDGET = 200000;
  var budget = STEP_BUDGET;
  var CONSTS = { pi: Math.PI, e: Math.E, tau: 2 * Math.PI };

  function ExprError(message) { this.message = message; this.name = "ExprError"; }
  ExprError.prototype = Object.create(Error.prototype);

  function lanczos(z) {
    var c = [0.99999999999980993, 676.5203681218851, -1259.1392167224028, 771.32342877765313, -176.61502916214059, 12.507343278686905, -0.13857109526572012, 9.9843695780195716e-6, 1.5056327351493116e-7];
    var x = c[0];
    for (var i = 1; i < 9; i++) x += c[i] / (z + i);
    return x;
  }
  function gamma(z) {
    if (z < 0.5) return Math.PI / (Math.sin(Math.PI * z) * gamma(1 - z));
    z -= 1;
    var t = z + 7.5;
    return Math.sqrt(2 * Math.PI) * Math.pow(t, z + 0.5) * Math.exp(-t) * lanczos(z);
  }
  function lgamma(z) {
    if (z < 0.5) return Math.log(Math.PI / Math.abs(Math.sin(Math.PI * z))) - lgamma(1 - z);
    z -= 1;
    var t = z + 7.5;
    return 0.5 * Math.log(2 * Math.PI) + (z + 0.5) * Math.log(t) - t + Math.log(lanczos(z));
  }
  function fact(n) {
    if (n >= 0 && n <= 170 && n === Math.floor(n)) { var r = 1; for (var i = 2; i <= n; i++) r *= i; return r; }
    return n < 0 && n === Math.floor(n) ? NaN : gamma(n + 1);
  }
  function erf(x) {
    var a = Math.abs(x);
    if (a >= 4) return x < 0 ? -1 : 1;
    var term = a, sum = a;
    for (var n = 1; n < 120; n++) { term *= -a * a / n; sum += term / (2 * n + 1); if (Math.abs(term) < 1e-17) break; }
    var r = 2 / Math.sqrt(Math.PI) * sum;
    return x < 0 ? -r : r;
  }
  function comb(n, k) {
    if (k < 0 || k > n || n !== Math.floor(n) || k !== Math.floor(k)) return 0;
    k = Math.min(k, n - k);
    var r = 1;
    for (var i = 1; i <= k; i++) r = r * (n - k + i) / i;
    return Math.round(r) === r || r > 1e15 ? r : Math.round(r);
  }
  var sieve = { limit: 1, counts: [0, 0] };
  function primepi(x) {
    var n = Math.floor(x);
    if (!(n >= 0)) return NaN;
    if (n < 2) return 0;
    if (n > 2000000) return NaN;
    if (n > sieve.limit) {
      var lim = Math.min(2000000, Math.max(n, sieve.limit * 2)), comp = new Uint8Array(lim + 1), counts = new Array(lim + 1), c = 0;
      for (var i = 2; i <= lim; i++) {
        if (!comp[i]) { c++; for (var j = i * i; j <= lim; j += i) comp[j] = 1; }
        counts[i] = c;
      }
      counts[0] = 0; counts[1] = 0;
      sieve = { limit: lim, counts: counts };
    }
    return sieve.counts[n];
  }
  function rnd(k) { var s = Math.sin(k * 12.9898 + 78.233) * 43758.5453; return s - Math.floor(s); }
  function agm(a, b, n, second) {
    n = Math.max(0, Math.min(60, Math.floor(n)));
    for (var i = 0; i < n; i++) { var na = (a + b) / 2, nb = Math.sqrt(a * b); a = na; b = nb; }
    return second ? b : a;
  }
  // Lambert's continued fraction for tan x cut at depth n: x / (1 - x^2/(3 - x^2/(5 - ...))).
  function cfan(x, n) {
    n = Math.max(0, Math.min(200, Math.floor(n)));
    var t = 2 * n + 1;
    for (var k = n; k >= 1; k--) t = (2 * k - 1) - x * x / t;
    return x / t;
  }
  var sign = function (x) { return x > 0 ? 1 : x < 0 ? -1 : 0; };

  function table(o) { var t = Object.create(null); for (var k in o) t[k] = o[k]; return t; }
  var F1 = table({
    sin: Math.sin, cos: Math.cos, tan: Math.tan, asin: Math.asin, acos: Math.acos, atan: Math.atan,
    sinh: Math.sinh, cosh: Math.cosh, tanh: Math.tanh, asinh: Math.asinh, acosh: Math.acosh, atanh: Math.atanh,
    exp: Math.exp, ln: Math.log, log10: Math.log10, log2: Math.log2, sqrt: Math.sqrt, cbrt: Math.cbrt, abs: Math.abs,
    sign: sign, floor: Math.floor, ceil: Math.ceil, round: function (x) { return Math.floor(x + 0.5); }, trunc: Math.trunc,
    frac: function (x) { return x - Math.floor(x); }, fact: fact, gamma: gamma, lgamma: lgamma, erf: erf,
    sinc: function (x) { return x === 0 ? 1 : Math.sin(x) / x; }, step: function (x) { return x >= 0 ? 1 : 0; },
    rnd: rnd, primepi: primepi
  });
  var F2 = table({
    atan2: Math.atan2, pow: Math.pow, hypot: Math.hypot, min: Math.min, max: Math.max,
    mod: function (a, b) { return a - b * Math.floor(a / b); }, comb: comb, cfan: cfan
  });
  var F3 = table({
    clamp: function (x, lo, hi) { return Math.min(hi, Math.max(lo, x)); },
    agma: function (a, b, n) { return agm(a, b, n, false); }, agmb: function (a, b, n) { return agm(a, b, n, true); },
    logistic: function (r, x, n) { n = Math.max(0, Math.min(5000, Math.floor(n))); for (var i = 0; i < n; i++) x = r * x * (1 - x); return x; }
  });
  var BINDERS = { sum: 1, prod: 1 };

  function tokenize(src) {
    var out = [], i = 0, m;
    var num = /^(\d+\.?\d*|\.\d+)([eE][+-]?\d+)?/, id = /^[A-Za-z_][A-Za-z0-9_]*/;
    while (i < src.length) {
      var ch = src[i];
      if (ch === " " || ch === "\t" || ch === "\n") { i++; continue; }
      var rest = src.slice(i);
      if ((m = num.exec(rest))) { out.push({ k: "n", v: parseFloat(m[0]) }); i += m[0].length; continue; }
      if ((m = id.exec(rest))) { out.push({ k: "i", v: m[0] }); i += m[0].length; continue; }
      if (rest.indexOf("<=") === 0 || rest.indexOf(">=") === 0) { out.push({ k: "o", v: rest.slice(0, 2) }); i += 2; continue; }
      if ("+-*/^(),<>".indexOf(ch) >= 0) { out.push({ k: "o", v: ch }); i++; continue; }
      throw new ExprError("unexpected character '" + ch + "'");
    }
    return out;
  }

  // compile(src, vars): vars = array of declared variable names. Returns {eval(env), names}: env maps name -> number.
  function compile(src, vars) {
    var toks = tokenize(String(src)), pos = 0, declared = Object.create(null), scope = [], used = Object.create(null);
    (vars || []).forEach(function (v) { declared[v] = true; });
    function peek() { return toks[pos]; }
    function isOp(v) { var t = toks[pos]; return t && t.k === "o" && t.v === v; }
    function take(v) { if (!isOp(v)) throw new ExprError("expected '" + v + "'"); pos++; }
    function cmp() {
      var a = add(), t = peek();
      if (t && t.k === "o" && (t.v === "<" || t.v === ">" || t.v === "<=" || t.v === ">=")) {
        pos++;
        var b = add(), op = t.v;
        return function (env) {
          var x = a(env), y = b(env);
          return (op === "<" ? x < y : op === ">" ? x > y : op === "<=" ? x <= y : x >= y) ? 1 : 0;
        };
      }
      return a;
    }
    function add() {
      var a = mul();
      while (isOp("+") || isOp("-")) {
        var plus = toks[pos++].v === "+", b = mul(), l = a;
        a = plus ? (function (l, b) { return function (e) { return l(e) + b(e); }; })(l, b) : (function (l, b) { return function (e) { return l(e) - b(e); }; })(l, b);
      }
      return a;
    }
    function mul() {
      var a = unary();
      while (isOp("*") || isOp("/")) {
        var times = toks[pos++].v === "*", b = unary(), l = a;
        a = times ? (function (l, b) { return function (e) { return l(e) * b(e); }; })(l, b) : (function (l, b) { return function (e) { return l(e) / b(e); }; })(l, b);
      }
      return a;
    }
    function unary() {
      if (isOp("-")) { pos++; var a = unary(); return function (e) { return -a(e); }; }
      if (isOp("+")) { pos++; return unary(); }
      return pow();
    }
    function pow() {
      var base = primary();
      if (isOp("^")) { pos++; var ex = unary(); return function (e) { return Math.pow(base(e), ex(e)); }; }
      return base;
    }
    function args() {
      var list = [];
      take("(");
      if (!isOp(")")) { list.push(cmp()); while (isOp(",")) { pos++; list.push(cmp()); } }
      take(")");
      return list;
    }
    function primary() {
      var t = toks[pos];
      if (!t) throw new ExprError("unexpected end of expression");
      if (t.k === "n") { pos++; var v = t.v; return function () { return v; }; }
      if (t.k === "o" && t.v === "(") { pos++; var inner = cmp(); take(")"); return inner; }
      if (t.k !== "i") throw new ExprError("unexpected '" + t.v + "'");
      pos++;
      var name = t.v, next = toks[pos];
      if (next && next.k === "o" && next.v === "(") {
        if (BINDERS[name] === 1) return binder(name);
        var a = args();
        if (F1[name] !== undefined && a.length === 1) { var f1 = F1[name], x1 = a[0]; return function (e) { return f1(x1(e)); }; }
        if (F2[name] !== undefined && a.length === 2) { var f2 = F2[name], x2 = a[0], y2 = a[1]; return function (e) { return f2(x2(e), y2(e)); }; }
        if (name === "if" && a.length === 3) { var c = a[0], p = a[1], q = a[2]; return function (e) { return c(e) !== 0 ? p(e) : q(e); }; }
        if (F3[name] !== undefined && a.length === 3) { var f3 = F3[name], x3 = a[0], y3 = a[1], z3 = a[2]; return function (e) { return f3(x3(e), y3(e), z3(e)); }; }
        if (F1[name] !== undefined || F2[name] !== undefined || F3[name] !== undefined || name === "if") throw new ExprError(name + " takes a different number of arguments");
        throw new ExprError("unknown function " + name);
      }
      if (scope.indexOf(name) >= 0 || declared[name]) {
        used[name] = true;
        return function (env) { var x = env[name]; return x === undefined ? NaN : x; };
      }
      if (name in CONSTS && Object.prototype.hasOwnProperty.call(CONSTS, name)) { var cv = CONSTS[name]; return function () { return cv; }; }
      throw new ExprError("unknown identifier " + name);
    }
    function binder(kind) {
      take("(");
      var idt = toks[pos];
      if (!idt || idt.k !== "i") throw new ExprError(kind + " needs an index name first");
      if (declared[idt.v] || scope.indexOf(idt.v) >= 0 || idt.v in CONSTS || F1[idt.v] !== undefined) throw new ExprError("the index " + idt.v + " is already a name");
      pos++; take(",");
      var lo = cmp(); take(","); var hi = cmp(); take(",");
      scope.push(idt.v);
      var body = cmp();
      scope.pop();
      take(")");
      var ix = idt.v, isSum = kind === "sum";
      return function (env) {
        var a = Math.ceil(lo(env)), b = Math.floor(hi(env));
        if (!(isFinite(a) && isFinite(b))) return NaN;
        if (b - a + 1 > 100000) return NaN;
        var had = Object.prototype.hasOwnProperty.call(env, ix), old = env[ix], acc = isSum ? 0 : 1;
        for (var i = a; i <= b; i++) {
          if (--budget < 0) { acc = NaN; break; }
          env[ix] = i;
          var t = body(env);
          acc = isSum ? acc + t : acc * t;
        }
        if (had) env[ix] = old; else delete env[ix];
        return acc;
      };
    }
    var root = cmp();
    if (pos < toks.length) throw new ExprError("unexpected '" + toks[pos].v + "'");
    return {
      names: Object.keys(used),
      eval: function (env) { budget = STEP_BUDGET; return root(env); }
    };
  }

  var api = { compile: compile, ExprError: ExprError, FUNCTIONS: { one: Object.keys(F1), two: Object.keys(F2), three: Object.keys(F3).concat(["if"]), binders: Object.keys(BINDERS) }, CONSTANTS: Object.keys(CONSTS) };
  if (typeof module !== "undefined" && module.exports) module.exports = api; else root.PlotExpr = api;
})(typeof window !== "undefined" ? window : this);
