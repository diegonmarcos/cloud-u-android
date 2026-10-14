/* Cloud Tasks agents: the screenshot-event parser.
 *
 * Input: screenshot records as CalBridge.agentsRecords() hands them over
 *   { id, text, takenAt (epoch ms or 0), place, lat, lon, source }
 * Output: event candidates
 *   { key, title, start, end, allDay, location, sourceId, source, snippet, hasTime }
 *
 * Pure functions only (no DOM, no bridge), so test/test-agents-parser.sh can
 * run them under node. `key` identifies a candidate across screenshots and
 * runs: normalised title + day + time, so the same event seen twice is one.
 */
(function (root) {
    'use strict';

    var MONTHS = {
        jan: 0, january: 0, januar: 0, janvier: 0, enero: 0,
        feb: 1, february: 1, februar: 1, fevrier: 1, febrero: 1,
        mar: 2, march: 2, marz: 2, maerz: 2, mars: 2, marzo: 2,
        apr: 3, april: 3, avril: 3, abril: 3,
        may: 4, mai: 4, mayo: 4,
        jun: 5, june: 5, juni: 5, juin: 5, junio: 5,
        jul: 6, july: 6, juli: 6, juillet: 6, julio: 6,
        aug: 7, august: 7, aout: 7, agosto: 7,
        sep: 8, sept: 8, september: 8, septembre: 8, septiembre: 8,
        oct: 9, october: 9, okt: 9, oktober: 9, octobre: 9, octubre: 9,
        nov: 10, november: 10, novembre: 10, noviembre: 10,
        dec: 11, december: 11, dez: 11, dezember: 11, decembre: 11, dic: 11, diciembre: 11
    };

    function fold(s) {
        return String(s || '').normalize('NFD').replace(/[̀-ͯ]/g, '').toLowerCase();
    }

    function pad(n) { return (n < 10 ? '0' : '') + n; }

    function validDay(y, m, d) {
        if (m < 0 || m > 11 || d < 1 || d > 31) return false;
        var t = new Date(y, m, d);
        return t.getMonth() === m && t.getDate() === d;
    }

    /* A year-less date takes the screenshot's year; one that would then sit
       more than 60 days before the screenshot is read as next year. */
    function resolveYear(m, d, ref) {
        var y = ref.getFullYear();
        var t = new Date(y, m, d);
        if (t.getTime() < ref.getTime() - 60 * 864e5) y += 1;
        return y;
    }

    function fixYear(y) { return y < 100 ? 2000 + y : y; }

    /* Every date found in one line: [{y,m,d,index}] */
    function findDates(line, ref) {
        var out = [], m, re;
        var f = fold(line);
        re = /\b(\d{4})-(\d{1,2})-(\d{1,2})\b/g;
        while ((m = re.exec(f))) out.push({ y: +m[1], m: +m[2] - 1, d: +m[3], index: m.index });
        re = /\b(\d{1,2})[./](\d{1,2})[./](\d{2,4})\b/g;
        while ((m = re.exec(f))) out.push({ y: fixYear(+m[3]), m: +m[2] - 1, d: +m[1], index: m.index });
        re = /\b(\d{1,2})\.(\d{1,2})\.(?!\d)/g;
        while ((m = re.exec(f))) {
            var mo = +m[2] - 1, da = +m[1];
            out.push({ y: resolveYear(mo, da, ref), m: mo, d: da, index: m.index });
        }
        re = /\b(\d{1,2})(?:st|nd|rd|th|\.)?\s+(?:de\s+)?([a-z]{3,10})\.?,?(?:\s+(?:de\s+)?(\d{4}))?\b/g;
        while ((m = re.exec(f))) {
            if (!(m[2] in MONTHS)) continue;
            var mm = MONTHS[m[2]], dd = +m[1];
            out.push({ y: m[3] ? +m[3] : resolveYear(mm, dd, ref), m: mm, d: dd, index: m.index });
        }
        re = /\b([a-z]{3,10})\.?\s+(\d{1,2})(?:st|nd|rd|th)?\b(?!:)(?:,?\s+(\d{4}))?/g;
        while ((m = re.exec(f))) {
            if (!(m[1] in MONTHS)) continue;
            var m2 = MONTHS[m[1]], d2 = +m[2];
            out.push({ y: m[3] ? +m[3] : resolveYear(m2, d2, ref), m: m2, d: d2, index: m.index });
        }
        var rel = /\b(today|heute|hoy|tomorrow|morgen|manana)\b/.exec(f);
        if (rel && ref) {
            var add = /today|heute|hoy/.test(rel[1]) ? 0 : 1;
            var r = new Date(ref.getFullYear(), ref.getMonth(), ref.getDate() + add);
            out.push({ y: r.getFullYear(), m: r.getMonth(), d: r.getDate(), index: rel.index });
        }
        return out.filter(function (x) { return validDay(x.y, x.m, x.d); });
    }

    /* Times in one line: [{h,mi}] with an optional end. */
    function findTimes(line) {
        var f = fold(line), out = [], m;
        var re = /\b(\d{1,2})(?::|\.|h)(\d{2})\s*(am|pm|a\.m\.|p\.m\.)?|\b(\d{1,2})\s*(am|pm|a\.m\.|p\.m\.|uhr|h)\b/g;
        while ((m = re.exec(f))) {
            var h, mi, ap;
            if (m[1] != null) { h = +m[1]; mi = +m[2]; ap = m[3]; }
            else { h = +m[4]; mi = 0; ap = m[5]; }
            // "14.10" right after a dd.mm reading is a date, not a time.
            if (m[1] != null && m[0].indexOf('.') >= 0 && /^\s*\.?\d/.test(f.slice(re.lastIndex))) continue;
            if (ap && ap.charAt(0) === 'p' && h < 12) h += 12;
            if (ap && ap.charAt(0) === 'a' && h === 12) h = 0;
            if (h > 23 || mi > 59) continue;
            out.push({ h: h, mi: mi, index: m.index });
        }
        return out;
    }

    var PLACE_LABEL = /^\s*(?:location|venue|place|address|where|ort|adresse|wo|lugar|direccion|lieu)\s*[:\-]\s*(.+)$/i;
    var ADDRESS = /([A-Za-zÀ-ſ.\- ]{3,}(?:stra(?:ss|ß)e|str\.|weg|platz|allee|damm|ufer|street|st\.|avenue|ave\.?|road|rd\.|calle|rue)\s*\d+[a-z]?\b.*|\b\d{5}\s+[A-Z][A-Za-zÀ-ſ\- ]{2,})/;
    var NOISE = /^(?:\d{1,2}:\d{2}\s*(?:[\d%]|$)|\W*$|.*\b(?:battery|wifi|lte|5g|4g)\b)/i;

    function isMostlyDateOrTime(line, ref) {
        var stripped = fold(line)
            .replace(/\b(\d{4})-(\d{1,2})-(\d{1,2})\b|\b\d{1,2}[./]\d{1,2}[./]?\d{0,4}\b|\b\d{1,2}(?::|h)\d{2}\b|\b\d{1,2}\s*(?:am|pm|uhr)\b/g, '')
            .replace(/\b(?:mon|tue|wed|thu|fri|sat|sun|monday|tuesday|wednesday|thursday|friday|saturday|sunday|mo|di|mi|do|fr|sa|so|montag|dienstag|mittwoch|donnerstag|freitag|samstag|sonntag|today|tomorrow|heute|morgen|from|to|bis|ab|um|uhr|at|on|am|pm|de|del|until)\b/g, '');
        var words = stripped.split(/[^a-zÀ-ſ]+/).filter(function (w) { return w.length > 2 && !(w in MONTHS); });
        return words.length === 0;
    }

    function cleanTitle(s) {
        return String(s || '').replace(/\s+/g, ' ').replace(/^[\s\-–•*#:|]+|[\s\-–•*#:|]+$/g, '').slice(0, 80);
    }

    function pickTitle(lines, dateLine, ref) {
        var best = '', bestScore = -1e9;
        lines.forEach(function (l, i) {
            var t = cleanTitle(l);
            if (t.length < 3 || NOISE.test(t) || PLACE_LABEL.test(t) || isMostlyDateOrTime(t, ref)) return;
            if (/^(?:https?:|www\.)/i.test(t)) return;
            var letters = (t.match(/[A-Za-zÀ-ſ]/g) || []).length;
            if (letters < 3) return;
            var score = -Math.abs(i - dateLine) * 2 + Math.min(letters, 30) / 10;
            if (i <= dateLine) score += 1;           // titles usually sit above the date
            if (/^(?:title|event|termin|evento)\s*:/i.test(t)) score += 10;
            if (score > bestScore) { bestScore = score; best = t.replace(/^(?:title|event|termin|evento)\s*:\s*/i, ''); }
        });
        return best;
    }

    function pickPlace(lines, record) {
        for (var i = 0; i < lines.length; i++) {
            var m = PLACE_LABEL.exec(lines[i]);
            if (m) return cleanTitle(m[1]);
        }
        for (var j = 0; j < lines.length; j++) {
            var a = ADDRESS.exec(lines[j]);
            if (a) return cleanTitle(lines[j]);
        }
        for (var k = 0; k < lines.length; k++) {
            var at = /^\s*(?:@|at |in |im |en )\s*([A-Z][^,]{2,60})$/.exec(lines[k]);
            if (at) return cleanTitle(at[1]);
        }
        return '';
    }

    function normKey(s) {
        return fold(s).replace(/[^a-z0-9]+/g, ' ').trim().split(' ').slice(0, 6).join(' ');
    }

    function candidateKey(c) {
        var d = new Date(c.start);
        var day = d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate());
        return normKey(c.title) + '|' + day + '|' + (c.allDay ? 'allday' : pad(d.getHours()) + ':' + pad(d.getMinutes()));
    }

    /* One record -> up to 3 candidates (one per distinct day it names). */
    function extract(record) {
        var text = String((record && record.text) || '');
        if (!text.trim()) return [];
        var ref = new Date(record.takenAt > 0 ? record.takenAt : Date.now());
        var lines = text.split(/\r?\n/).map(function (l) { return l.trim(); }).filter(Boolean);
        var seenDay = {}, out = [];
        lines.forEach(function (line, i) {
            findDates(line, ref).forEach(function (d) {
                var dayKey = d.y + '-' + d.m + '-' + d.d;
                if (seenDay[dayKey] || out.length >= 3) return;
                seenDay[dayKey] = true;
                // Look for a time on the date line, then on its neighbours.
                var times = findTimes(line);
                if (!times.length && lines[i + 1]) times = findTimes(lines[i + 1]);
                if (!times.length && lines[i - 1]) times = findTimes(lines[i - 1]);
                var allDay = !times.length;
                var start = allDay ? new Date(d.y, d.m, d.d) : new Date(d.y, d.m, d.d, times[0].h, times[0].mi);
                var end;
                if (allDay) end = new Date(d.y, d.m, d.d + 1);
                else if (times[1] && (times[1].h * 60 + times[1].mi) > (times[0].h * 60 + times[0].mi))
                    end = new Date(d.y, d.m, d.d, times[1].h, times[1].mi);
                else end = new Date(start.getTime() + 3600e3);
                var title = pickTitle(lines, i, ref) || 'Event from screenshot';
                var c = {
                    title: title,
                    start: start.getTime(),
                    end: end.getTime(),
                    allDay: allDay,
                    hasTime: !allDay,
                    location: pickPlace(lines, record) || String(record.place || ''),
                    sourceId: String(record.id || ''),
                    source: String(record.source || '') + (record.sourceApp ? ' (' + record.sourceApp + ')' : ''),
                    snippet: lines.slice(Math.max(0, i - 1), i + 2).join(' · ').slice(0, 160)
                };
                c.key = candidateKey(c);
                out.push(c);
            });
        });
        return out;
    }

    function sameish(a, b) {
        var ka = normKey(a), kb = normKey(b);
        if (!ka || !kb) return false;
        return ka === kb || ka.indexOf(kb) === 0 || kb.indexOf(ka) === 0;
    }

    /* All records -> the candidates worth asking about: no duplicates, nothing
       dismissed or already accepted, nothing already in the calendar on that
       day under the same title, nothing that has already ended. */
    function scan(records, opts) {
        opts = opts || {};
        var dismissed = {}, taken = {};
        (opts.dismissed || []).forEach(function (k) { dismissed[k] = true; });
        (opts.accepted || []).forEach(function (e) { if (e && e.key) taken[e.key] = true; });
        var existing = opts.existing || [];
        var now = opts.now != null ? opts.now : Date.now();
        var byKey = {}, list = [];
        (records || []).forEach(function (r) {
            extract(r).forEach(function (c) {
                if (dismissed[c.key] || taken[c.key] || c.end < now) return;
                var day = new Date(c.start).toDateString();
                var clash = existing.some(function (e) {
                    return new Date(+e.start).toDateString() === day && sameish(e.title, c.title);
                });
                if (clash) return;
                if (byKey[c.key]) { byKey[c.key].count += 1; return; }
                c.count = 1;
                byKey[c.key] = c;
                list.push(c);
            });
        });
        return list.sort(function (a, b) { return a.start - b.start; });
    }

    var api = { extract: extract, scan: scan, candidateKey: candidateKey, findDates: findDates, findTimes: findTimes };
    if (typeof module !== 'undefined' && module.exports) module.exports = api;
    else root.AgendaAgents = api;
})(this);
