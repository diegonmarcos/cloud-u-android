#!/usr/bin/env bash
# The screenshot agent's parser (app/src/main/assets/agents.js): dates, times,
# titles and places come out of OCR text, duplicates collapse, dismissed and
# accepted candidates stay gone. Pure JS, runs under node.
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
node - "$here/../app/src/main/assets/agents.js" <<'JS'
const A = require(process.argv[2]);
let fail = 0;
function eq(name, got, want) {
  const g = JSON.stringify(got), w = JSON.stringify(want);
  if (g === w) console.log('ok   ' + name);
  else { fail++; console.log('FAIL ' + name + '\n     got  ' + g + '\n     want ' + w); }
}
const taken = new Date(2026, 9, 10, 12, 0).getTime();
const fmt = ms => { const d = new Date(ms); const p = n => String(n).padStart(2, '0');
  return d.getFullYear() + '-' + p(d.getMonth() + 1) + '-' + p(d.getDate()) + ' ' + p(d.getHours()) + ':' + p(d.getMinutes()); };

let c = A.extract({ id: 's1', takenAt: taken, place: 'Berlin', text:
  '9:41 ● 87%\nJazz Night at Quasimodo\nSat 14 Nov 2026, 20:00 - 23:00\nKantstraße 12, 10623 Berlin\nBuy tickets' });
eq('english: one candidate', c.length, 1);
eq('english: title', c[0].title, 'Jazz Night at Quasimodo');
eq('english: start', fmt(c[0].start), '2026-11-14 20:00');
eq('english: end', fmt(c[0].end), '2026-11-14 23:00');
eq('english: place from address', c[0].location, 'Kantstraße 12, 10623 Berlin');

c = A.extract({ id: 's2', takenAt: taken, place: '', text:
  'Zahnarzt Termin\n21.10.2026 um 9:30 Uhr\nOrt: Praxis Dr. Klein' });
eq('german: start', fmt(c[0].start), '2026-10-21 09:30');
eq('german: title', c[0].title, 'Zahnarzt Termin');
eq('german: place label', c[0].location, 'Praxis Dr. Klein');

c = A.extract({ id: 's3', takenAt: taken, place: 'Kreuzberg', text: 'Flohmarkt Mauerpark\n18. Oktober' });
eq('year-less, all day', [fmt(c[0].start), c[0].allDay], ['2026-10-18 00:00', true]);
eq('metadata place used as fallback', c[0].location, 'Kreuzberg');

c = A.extract({ id: 's4', takenAt: taken, text: 'Dinner with Ana\nJan 5 at 7:30 pm' });
eq('year rolls forward, pm', fmt(c[0].start), '2027-01-05 19:30');

eq('no date, no candidate', A.extract({ id: 's5', takenAt: taken, text: 'Groceries\nmilk eggs' }).length, 0);
eq('no text, no candidate', A.extract({ id: 's6', takenAt: taken }).length, 0);

const recs = [
  { id: 'a', takenAt: taken, text: 'Jazz Night at Quasimodo\n14 Nov 2026 20:00' },
  { id: 'b', takenAt: taken, text: 'Jazz Night at Quasimodo\n14.11.2026 20:00' },
  { id: 'c', takenAt: taken, text: 'Zahnarzt Termin\n21.10.2026 9:30' },
  { id: 'd', takenAt: taken, text: 'Old thing\n1.10.2026 9:30' }
];
const now = new Date(2026, 9, 12).getTime();
let s = A.scan(recs, { now });
eq('duplicates collapse, past dropped', s.map(x => x.title), ['Zahnarzt Termin', 'Jazz Night at Quasimodo']);
eq('duplicate counted', s[1].count, 2);
s = A.scan(recs, { now, dismissed: [s[0].key] });
eq('dismissed stays gone', s.map(x => x.title), ['Jazz Night at Quasimodo']);
s = A.scan(recs, { now, accepted: [{ key: s[0].key }] });
eq('accepted stays gone', s.map(x => x.title), ['Zahnarzt Termin']);
s = A.scan(recs, { now, existing: [{ title: 'Zahnarzt termin', start: new Date(2026, 9, 21, 9, 30).getTime() }] });
eq('already in calendar', s.map(x => x.title), ['Jazz Night at Quasimodo']);

if (fail) { console.log(fail + ' failed'); process.exit(1); }
console.log('all passed');
JS
