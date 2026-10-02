// qalc_core — see qalc_core.h. libqalculate (GPL-2.0-or-later) does the maths;
// this file only chooses options, drains messages and writes JSON.
#include "qalc_core.h"

#include <libqalculate/qalculate.h>

#include <cctype>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <vector>

#ifndef QALC_VERSION
#define QALC_VERSION "unknown"
#endif

namespace qcore {
namespace {

std::string lower(std::string s) {
    for (auto &c : s) c = (char) std::tolower((unsigned char) c);
    return s;
}

bool starts_ci(const std::string &s, const std::string &prefix) {
    return s.size() >= prefix.size() && lower(s.substr(0, prefix.size())) == lower(prefix);
}

// libqalculate joins nested categories with '/' ("Electricity/Electric Current"), so a
// category is matched at its start or at the start of any nested segment.
bool in_category(const std::string &cat, const std::string &prefix) {
    if (starts_ci(cat, prefix)) return true;
    std::string lc = lower(cat), lp = "/" + lower(prefix);
    return lc.find(lp) != std::string::npos;
}

std::string q(const std::string &s) { return "\"" + json_escape(s) + "\""; }

std::string num(double d) {
    if (!std::isfinite(d)) return "null";
    char buf[40];
    std::snprintf(buf, sizeof buf, "%.10g", d);
    return buf;
}

// Every queued message as a JSON array; sets *had_error on any MESSAGE_ERROR.
std::string drain(bool *had_error) {
    std::string out = "[";
    bool first = true;
    while (CALCULATOR->message()) {
        CalculatorMessage *m = CALCULATOR->message();
        const char *type = m->type() == MESSAGE_ERROR ? "error"
                         : m->type() == MESSAGE_WARNING ? "warning" : "info";
        if (m->type() == MESSAGE_ERROR && had_error) *had_error = true;
        if (!first) out += ",";
        first = false;
        out += "{\"type\":\"" + std::string(type) + "\",\"text\":" + q(m->message()) + "}";
        CALCULATOR->nextMessage();
    }
    return out + "]";
}

std::string row(ExpressionItem *it, const std::string &name, const char *kind) {
    return "{\"name\":" + q(name) + ",\"title\":" + q(it->title(true)) + ",\"kind\":\"" + kind +
           "\",\"category\":" + q(it->category()) + "}";
}

template <typename T>
void collect(const std::vector<T *> &v, const char *kind, const std::string &prefix, int max,
             std::string &out, int &n) {
    for (T *it : v) {
        if (n >= max) return;
        if (!it->isActive() || it->isHidden()) continue;
        for (size_t i = 1; i <= it->countNames(); i++) {
            const std::string &name = it->getName(i).name;
            if (starts_ci(name, prefix)) {
                out += (n++ ? "," : "") + row(it, name, kind);
                break;
            }
        }
    }
}

template <typename T>
void by_category(const std::vector<T *> &v, const char *kind, const std::string &cat, int max,
                 std::string &out, int &n) {
    for (T *it : v) {
        if (n >= max) return;
        if (!it->isActive() || it->isHidden() || !in_category(it->category(), cat)) continue;
        out += (n++ ? "," : "") + row(it, it->name(), kind);
    }
}

ApproximationMode approx_mode(int a) {
    return a <= 0 ? APPROXIMATION_EXACT : a == 1 ? APPROXIMATION_TRY_EXACT : APPROXIMATION_APPROXIMATE;
}

// The evaluation and print settings qalc itself starts from (src/qalc.cc load_preferences),
// so a golden row means what the same line means in qalc and in libqalculate's own
// tests/*.batch. One deliberate difference: show_ending_zeroes is off, so an approximate 0.5
// prints as 0.5 and not 0.5000000000.
EvaluationOptions qalc_evalops() {
    EvaluationOptions eo = default_user_evaluation_options;
    eo.parse_options.parsing_mode = PARSING_MODE_ADAPTIVE;
    eo.sync_units = true;
    eo.structuring = STRUCTURING_SIMPLIFY;
    eo.parse_options.unknowns_enabled = false;
    eo.parse_options.read_precision = DONT_READ_PRECISION;
    eo.allow_complex = true;
    eo.allow_infinite = true;
    eo.auto_post_conversion = POST_CONVERSION_OPTIMAL;
    eo.assume_denominators_nonzero = true;
    eo.warn_about_denominators_assumed_nonzero = true;
    eo.mixed_units_conversion = MIXED_UNITS_CONVERSION_DEFAULT;
    eo.complex_number_form = COMPLEX_NUMBER_FORM_RECTANGULAR;
    eo.local_currency_conversion = true;
    eo.interval_calculation = INTERVAL_CALCULATION_VARIANCE_FORMULA;
    return eo;
}

PrintOptions qalc_printops() {
    PrintOptions po = default_print_options;
    po.use_min_decimals = false;
    po.use_max_decimals = false;
    po.min_exp = EXP_PRECISION;
    po.indicate_infinite_series = false;
    po.show_ending_zeroes = false;
    po.number_fraction_format = FRACTION_DECIMAL;
    po.abbreviate_names = true;
    po.use_unit_prefixes = true;
    po.spacious = true;
    po.short_multiplication = true;
    po.place_units_separately = true;
    po.exp_display = EXP_UPPERCASE_E;
    po.base_display = BASE_DISPLAY_NORMAL;
    po.twos_complement = true;
    po.division_sign = DIVISION_SIGN_SLASH;
    po.multiplication_sign = MULTIPLICATION_SIGN_X;
    po.spell_out_logical_operators = true;
    po.interval_display = INTERVAL_DISPLAY_SIGNIFICANT_DIGITS;
    return po;
}

AngleUnit angle_unit(int a) {
    switch (a) {
        case 0: return ANGLE_UNIT_NONE;
        case 2: return ANGLE_UNIT_DEGREES;
        case 3: return ANGLE_UNIT_GRADIANS;
        default: return ANGLE_UNIT_RADIANS;
    }
}

}  // namespace

std::string json_escape(const std::string &s) {
    std::string o;
    o.reserve(s.size() + 8);
    for (unsigned char c : s) {
        switch (c) {
            case '"': o += "\\\""; break;
            case '\\': o += "\\\\"; break;
            case '\n': o += "\\n"; break;
            case '\r': o += "\\r"; break;
            case '\t': o += "\\t"; break;
            default:
                if (c < 0x20) {
                    char buf[8];
                    std::snprintf(buf, sizeof buf, "\\u%04x", c);
                    o += buf;
                } else {
                    o += (char) c;
                }
        }
    }
    return o;
}

std::string init(const std::string &user_dir) {
    if (CALCULATOR) return info();
    setenv("QALCULATE_USER_DIR", user_dir.c_str(), 1);
    new Calculator(true);  // ignore_locale: '.' is the decimal point whatever the phone's locale
    // The rates are shown with their date in the app; a stale-rates warning
    // appended to every currency result would only repeat it.
    CALCULATOR->setExchangeRatesWarningEnabled(false);
    CALCULATOR->useIntervalArithmetic(true);
    CALCULATOR->setTemperatureCalculationMode(TEMPERATURE_CALCULATION_HYBRID);
    CALCULATOR->loadExchangeRates();
    bool ok = CALCULATOR->loadGlobalDefinitions();
    CALCULATOR->loadLocalDefinitions();
    drain(nullptr);
    if (!ok) return "{\"ok\":false,\"error\":\"libqalculate could not load its compiled-in definitions\"}";
    return info();
}

std::string info() {
    if (!CALCULATOR) return "{\"ok\":false,\"error\":\"not initialised\"}";
    return "{\"ok\":true,\"version\":" + q(QALC_VERSION) +
           ",\"functions\":" + std::to_string(CALCULATOR->functions.size()) +
           ",\"variables\":" + std::to_string(CALCULATOR->variables.size()) +
           ",\"units\":" + std::to_string(CALCULATOR->units.size()) +
           ",\"rates_time\":" + std::to_string((long long) CALCULATOR->getExchangeRatesTime()) + "}";
}

std::string eval(const std::string &expr, const EvalOpts &o) {
    if (!CALCULATOR) return "{\"ok\":false,\"error\":\"not initialised\"}";
    auto t0 = std::chrono::steady_clock::now();
    EvaluationOptions eo = qalc_evalops();
    eo.approximation = approx_mode(o.approx);
    eo.parse_options.base = o.in_base;
    eo.parse_options.angle_unit = angle_unit(o.angle);
    if (!o.mixed_units) eo.mixed_units_conversion = MIXED_UNITS_CONVERSION_NONE;
    PrintOptions po = qalc_printops();
    po.base = o.out_base;
    po.use_unicode_signs = o.unicode ? 1 : 0;
    // Exact mode keeps 1/3 a fraction; approximate prints decimals.
    po.number_fraction_format = o.approx >= 2 ? FRACTION_DECIMAL : FRACTION_DECIMAL_EXACT;
    CALCULATOR->setPrecision(o.precision);
    drain(nullptr);  // nothing from a previous call may leak into this answer

    std::string parsed;
    bool comparison = false;
    std::string result = CALCULATOR->calculateAndPrint(
        CALCULATOR->unlocalizeExpression(expr, eo.parse_options), o.timeout_ms, eo, po,
        AUTOMATIC_FRACTION_OFF, AUTOMATIC_APPROXIMATION_OFF, &parsed, -1, &comparison);
    bool error = false;
    std::string messages = drain(&error);
    bool ok = !error && !result.empty();
    long ms = (long) std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now() - t0).count();
    return std::string("{\"ok\":") + (ok ? "true" : "false") + ",\"result\":" + q(result) +
           ",\"parsed\":" + q(parsed) + ",\"comparison\":" + (comparison ? "true" : "false") +
           ",\"messages\":" + messages + ",\"ms\":" + std::to_string(ms) + "}";
}

std::string plot(const std::string &expr, double xmin, double xmax, int steps, int timeout_ms) {
    if (!CALCULATOR) return "{\"ok\":false,\"error\":\"not initialised\"}";
    if (steps < 1 || steps > 4000 || !(xmax > xmin))
        return "{\"ok\":false,\"error\":\"need xmin < xmax and 1 <= steps <= 4000\"}";
    drain(nullptr);
    // min, max and the step as EXACT rationals (parsed from decimal text, never from a
    // binary double), so libqalculate's x += step walk lands on xmax and yields steps+1
    // points; its int-steps overload samples adaptively and returns a different count.
    char lo[40], hi[40];
    std::snprintf(lo, sizeof lo, "%.12g", xmin);
    std::snprintf(hi, sizeof hi, "%.12g", xmax);
    EvaluationOptions exact;
    exact.approximation = APPROXIMATION_EXACT;
    MathStructure mmin = CALCULATOR->calculate(lo, exact);
    MathStructure mmax = CALCULATOR->calculate(hi, exact);
    MathStructure mstep = CALCULATOR->calculate(
        "(" + std::string(hi) + " - " + lo + ") / " + std::to_string(steps), exact);
    MathStructure xv;
    MathStructure yv = CALCULATOR->expressionToPlotVector(
        CALCULATOR->unlocalizeExpression(expr, default_parse_options), mmin, mmax, mstep, &xv, "x",
        default_parse_options, timeout_ms);
    bool error = false;
    std::string messages = drain(&error);
    std::string xs = "[", ys = "[";
    size_t n = yv.isVector() ? yv.size() : 0;
    for (size_t i = 0; i < n; i++) {
        const MathStructure &y = yv[i];
        double yd = (y.isNumber() && y.number().isReal()) ? y.number().floatValue() : NAN;
        double xd = (i < xv.size() && xv[i].isNumber()) ? xv[i].number().floatValue() : NAN;
        xs += (i ? "," : "") + num(xd);
        ys += (i ? "," : "") + num(yd);
    }
    bool ok = !error && n > 0;
    return std::string("{\"ok\":") + (ok ? "true" : "false") + ",\"x\":" + xs + "],\"y\":" + ys +
           "],\"messages\":" + messages + "}";
}

std::string complete(const std::string &prefix, int max) {
    if (!CALCULATOR) return "[]";
    std::string out = "[";
    int n = 0;
    if (!prefix.empty()) {
        collect(CALCULATOR->functions, "function", prefix, max, out, n);
        collect(CALCULATOR->variables, "variable", prefix, max, out, n);
        collect(CALCULATOR->units, "unit", prefix, max, out, n);
    }
    return out + "]";
}

std::string items(const std::string &kind, const std::string &category_prefix, int max) {
    if (!CALCULATOR) return "[]";
    std::string out = "[";
    int n = 0;
    if (kind == "function") by_category(CALCULATOR->functions, "function", category_prefix, max, out, n);
    else if (kind == "variable") by_category(CALCULATOR->variables, "variable", category_prefix, max, out, n);
    else if (kind == "unit") by_category(CALCULATOR->units, "unit", category_prefix, max, out, n);
    return out + "]";
}

std::string rates_sources() {
    if (!CALCULATOR) return "[]";
    std::string out = "[";
    for (int i = 1; i <= 4; i++) {
        std::string url = CALCULATOR->getExchangeRatesUrl(i);
        if (url.empty()) continue;
        out += (out.size() > 1 ? "," : "") + std::string("{\"index\":") + std::to_string(i) +
               ",\"url\":" + q(url) + ",\"file\":" + q(CALCULATOR->getExchangeRatesFileName(i)) + "}";
    }
    return out + "]";
}

std::string reload_rates() {
    if (!CALCULATOR) return "{\"ok\":false,\"error\":\"not initialised\"}";
    bool ok = CALCULATOR->loadExchangeRates();
    drain(nullptr);
    return std::string("{\"ok\":") + (ok ? "true" : "false") +
           ",\"time\":" + std::to_string((long long) CALCULATOR->getExchangeRatesTime()) + "}";
}

}  // namespace qcore
