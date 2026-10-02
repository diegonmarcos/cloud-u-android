// qalc_core — the whole of Cloud-Lib-Calc's native surface, as plain C++.
//
// Every function takes and returns UTF-8 std::strings, and every answer is ONE
// JSON document. Nothing here knows about JNI or Android: qalc_jni.cc is a thin
// marshalling layer over these, and qalc_golden.cc drives the SAME functions on
// the CI host, so the golden calculations prove the code that ships rather than
// a host-only twin of it.
//
// NOT THREAD-SAFE, by libqalculate's design: there is one global Calculator.
// The caller serialises (QalcNative.kt holds one lock around every call).
#pragma once
#include <string>

namespace qcore {

struct EvalOpts {
    int in_base = 10;        // 2, 8, 10, 16 — how bare digits in the input are read
    int out_base = 10;       // 2, 8, 10, 16 — how the result is printed (an "x to hex" suffix still wins)
    int precision = 10;      // significant digits
    int angle = 1;           // 0 none, 1 radians, 2 degrees, 3 gradians
    int approx = 1;          // 0 exact (CAS: keeps ln(3)/ln(5)), 1 exact where possible, 2 always decimal
    bool mixed_units = true; // "1 kg to lb" as "2 lb + 3.27 oz" (true) or "2.204622622 lb" (false)
    bool unicode = false;    // × − √ in the printed result; off keeps output re-parseable by any client
    int timeout_ms = 5000;
};

// Creates the Calculator and loads the compiled-in definitions, then the user
// dir's own saved variables and any exchange-rate files a client fetched into
// it. user_dir becomes QALCULATE_USER_DIR. Idempotent: a second call answers
// info() without reloading.
std::string init(const std::string &user_dir);

// {"ok","result","parsed","comparison","messages":[{"type","text"}],"ms"}.
// ok is false when the expression produced an error message or no result.
// ponytail: no engine-side `ans`. calculateAndPrint never hands back the value,
// and re-parsing the printed text is wrong for binary output ("0011 0100").
// The app's history re-inserts a previous result's text, as qalc's own ans key
// does; add a real `ans` here only by carrying the MathStructure out of the
// calculation. Named variables (`a := 5`) are libqalculate's own; they land in
// its Temporary category, which saveDefinitions() skips, so they last as long
// as the engine process.
std::string eval(const std::string &expr, const EvalOpts &o);

// y = f(x) at steps+1 evenly spaced x over [xmin, xmax] (x_i computed directly, never
// accumulated, so the last point is xmax): {"ok","x":[...],"y":[...]} — a y that is not a
// finite real is null.
std::string plot(const std::string &expr, double xmin, double xmax, int steps, int timeout_ms);

// Active, non-hidden functions/variables/units whose name starts with prefix
// (ASCII case-insensitive), at most max: [{"name","title","kind","category"}].
std::string complete(const std::string &prefix, int max);

// Every active, non-hidden item of one kind ("function", "variable", "unit")
// whose category, or any nested segment of it ("Electricity/Electric Current"),
// starts with category_prefix: same row shape as complete().
std::string items(const std::string &kind, const std::string &category_prefix, int max);

// The rate sources libqalculate itself reads: [{"index","url","file"}]. A
// client downloads url into file and calls reload_rates(); the URLs are
// libqalculate's own, so they are never restated anywhere else.
std::string rates_sources();

// Re-reads the rate files: {"ok","time"} (oldest rate, epoch seconds).
std::string reload_rates();

// {"ok","version","functions","variables","units","rates_time"}.
std::string info();

// Escapes s as the body of a JSON string (no surrounding quotes).
std::string json_escape(const std::string &s);

}  // namespace qcore
