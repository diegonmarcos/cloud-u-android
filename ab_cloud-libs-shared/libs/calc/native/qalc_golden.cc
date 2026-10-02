// qalc_golden — runs golden.tsv through qalc_core on the CI host. Linked from
// the same qalc_core.cc and the same pinned libqalculate build flags as the
// Android .so, so a row passing here is the shipped code computing that row.
//
//   qalc_golden <golden.tsv> <scratch user dir>
//
// Besides the table it checks the non-eval surface the app relies on:
// completion, the constants/units catalogues, the rate sources and info().
// Exit 0 only when every row and check passed and at least MIN_ROWS rows ran.
#include <cstdio>
#include <fstream>
#include <map>
#include <regex>
#include <sstream>
#include <string>
#include <vector>

#include "qalc_core.h"

namespace {

const int MIN_ROWS = 40;
int failures = 0;

// The string value of "key":"..." in a flat JSON object (enough for qalc_core's own output).
std::string jstr(const std::string &j, const std::string &key) {
    std::string pat = "\"" + key + "\":\"";
    size_t p = j.find(pat);
    if (p == std::string::npos) return "\x01missing";
    std::string out;
    for (size_t i = p + pat.size(); i < j.size(); i++) {
        char c = j[i];
        if (c == '"') return out;
        if (c == '\\' && i + 1 < j.size()) {
            char n = j[++i];
            out += n == 'n' ? '\n' : n == 't' ? '\t' : n;
        } else {
            out += c;
        }
    }
    return out;
}

bool jtrue(const std::string &j, const std::string &key) {
    return j.find("\"" + key + "\":true") != std::string::npos;
}

std::string jarray(const std::string &j, const std::string &key) {
    std::string pat = "\"" + key + "\":[";
    size_t p = j.find(pat);
    if (p == std::string::npos) return "\x01missing";
    size_t e = j.find(']', p);
    return j.substr(p + pat.size(), e - p - pat.size());
}

std::vector<std::string> split(const std::string &s, char d) {
    std::vector<std::string> v;
    std::stringstream ss(s);
    std::string x;
    while (std::getline(ss, x, d)) v.push_back(x);
    return v;
}

void check(bool ok, const std::string &what) {
    std::printf("  %s  %s\n", ok ? "PASS" : "FAIL", what.c_str());
    if (!ok) failures++;
}

qcore::EvalOpts opts(const std::string &s) {
    qcore::EvalOpts o;
    if (s == "-") return o;
    for (const auto &kv : split(s, ';')) {
        size_t eq = kv.find('=');
        std::string k = kv.substr(0, eq);
        int v = std::stoi(kv.substr(eq + 1));
        if (k == "in") o.in_base = v;
        else if (k == "out") o.out_base = v;
        else if (k == "prec") o.precision = v;
        else if (k == "angle") o.angle = v;
        else if (k == "approx") o.approx = v;
        else if (k == "mixed") o.mixed_units = v != 0;
        else if (k == "unicode") o.unicode = v != 0;
        else { std::printf("  FAIL  unknown option %s\n", k.c_str()); failures++; }
    }
    return o;
}

// "4.000" and "4" are the same plotted y; compare numerically to 1e-9.
bool same_numbers(const std::string &got, const std::string &want) {
    auto g = split(got, ','), w = split(want, ',');
    if (g.size() != w.size()) return false;
    for (size_t i = 0; i < g.size(); i++) {
        if (g[i] == "null") return false;
        double a = std::stod(g[i]), b = std::stod(w[i]);
        if (a - b > 1e-9 || b - a > 1e-9) return false;
    }
    return true;
}

}  // namespace

int main(int argc, char **argv) {
    if (argc != 3) {
        std::fprintf(stderr, "usage: qalc_golden <golden.tsv> <scratch user dir>\n");
        return 2;
    }
    // Unbuffered: if a row crashes the process, the last RUN line above the crash names it.
    std::setvbuf(stdout, nullptr, _IONBF, 0);
    std::string init = qcore::init(argv[2]);
    check(jtrue(init, "ok"), "init: " + init);

    std::ifstream in(argv[1]);
    if (!in) {
        std::fprintf(stderr, "cannot read %s\n", argv[1]);
        return 2;
    }
    std::map<std::string, std::pair<int, int>> by_mode;  // mode -> (rows, failed)
    std::string line;
    int rows = 0;
    while (std::getline(in, line)) {
        if (line.empty() || line[0] == '#') continue;
        auto f = split(line, '\t');
        if (f.size() != 4 || f[0].empty()) {
            check(false, "malformed row: " + line);
            continue;
        }
        const std::string &mode = f[0], &expr = f[2], &want = f[3];
        rows++;
        int before = failures;
        std::printf("  RUN   %s | %s\n", mode.c_str(), expr.c_str());
        if (expr.rfind("plot:", 0) == 0) {
            auto p = split(expr.substr(5), ';');
            std::string j = qcore::plot(p[0], std::stod(p[1]), std::stod(p[2]), std::stoi(p[3]), 5000);
            std::string y = jarray(j, "y");
            check(jtrue(j, "ok") && same_numbers(y, want), mode + " | plot " + p[0] + " -> [" + y + "] want [" + want + "]");
        } else {
            std::string j = qcore::eval(expr, opts(f[1]));
            std::string got = jstr(j, "result");
            bool ok;
            if (want == "!error") ok = !jtrue(j, "ok") && j.find("\"type\":\"error\"") != std::string::npos;
            else if (want == "!message") ok = j.find("\"messages\":[]") == std::string::npos;
            else if (want.rfind("re:", 0) == 0) ok = jtrue(j, "ok") && std::regex_match(got, std::regex(want.substr(3)));
            else ok = jtrue(j, "ok") && got == want;
            check(ok, mode + " | " + expr + " -> \"" + got + "\" want \"" + want + "\"" + (ok ? "" : "  " + j));
        }
        auto &m = by_mode[mode];
        m.first++;
        if (failures > before) m.second++;
    }

    // The catalogues the app's modes are built from.
    std::string c = qcore::complete("sqr", 50);
    check(c.find("\"name\":\"sqrt\"") != std::string::npos, "complete(sqr) offers sqrt: " + c.substr(0, 200));
    check(qcore::complete("", 50) == "[]", "complete of an empty prefix offers nothing");
    std::string k = qcore::items("variable", "Physical Constants", 1000);
    check(k.find("\"kind\":\"variable\"") != std::string::npos, "items(variable, Physical Constants) is not empty");
    std::string u = qcore::items("unit", "Length", 1000);
    check(u.find("\"title\":\"Meter\"") != std::string::npos, "items(unit, Length) lists Meter");
    std::string a = qcore::items("unit", "Electric Current", 1000);
    check(a.find("\"title\":\"Ampere\"") != std::string::npos, "items(unit, Electric Current) reaches a nested category: " + a.substr(0, 200));
    std::string cur = qcore::items("unit", "Currency", 1000);
    check(cur.find("\"name\":\"EUR\"") != std::string::npos && cur.find("\"name\":\"USD\"") != std::string::npos,
          "items(unit, Currency) lists EUR and USD by the names the converter types");
    std::string r = qcore::rates_sources();
    check(r.find("ecb.europa.eu") != std::string::npos && r.find("eurofxref-daily.xml") != std::string::npos,
          "rates_sources names libqalculate's ECB source and file: " + r);
    check(jtrue(qcore::reload_rates(), "ok"), "reload_rates falls back to the compiled-in rates");
    std::string i = qcore::info();
    check(jtrue(i, "ok") && i.find("\"functions\":0") == std::string::npos, "info counts loaded definitions: " + i);

    std::printf("\nPER MODE\n");
    for (const auto &m : by_mode)
        std::printf("  %-12s %2d row(s), %d failed\n", m.first.c_str(), m.second.first, m.second.second);
    if (rows < MIN_ROWS) {
        std::printf("FAIL  only %d golden rows ran, need at least %d — the table was not read\n", rows, MIN_ROWS);
        failures++;
    }
    std::printf("── %d golden row(s), %d failure(s) ──\n", rows, failures);
    return failures ? 1 : 0;
}
