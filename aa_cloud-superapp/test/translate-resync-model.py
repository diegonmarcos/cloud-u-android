#!/usr/bin/env python3
"""Executable model of the translate bar's output re-sync engine.

WHAT THIS IS. A faithful transcription of the state machine in
TranslateBarView.kt (Output.NONE/OWNED/LOST, pushOutput, releaseOutput,
onHostOutputDropped) against a mini InputConnection that implements composing
regions the way the platform does. It is a MODEL, not the Kotlin: this runner
has no JVM, so the alternative was an assertion nobody ever watched fail.
It proves the DESIGN terminates and does not duplicate. It cannot prove the
Kotlin compiles — CI does that — and it cannot prove how typing feels.

WHY A MODEL EARNS ITS KEEP HERE. Every symptom the owner reported is a state
machine bug, not a rendering bug: text arriving twice, an update re-entering
its own handler, a caret moved by something that should not touch it. Those
are exactly the properties a model can hold to.

SECOND HALF: THE BOX AS AN EDITOR, IN UTF-16. Sections H onwards model
TextBoxEditor — the one caret/selection buffer both bars now share. Kotlin
Strings are UTF-16 and Python strings are codepoints, so the model does NOT
use Python indexing: it holds the buffer as a list of UTF-16 CODE UNITS, the
same units Kotlin's String.length and substring count in, which is the only
way the surrogate-splitting bug can be reproduced here at all.

WHAT THAT HALF DOES AND DOES NOT PROVE. The Kotlin asks the platform's ICU
BreakIterator where the grapheme boundaries are; this model asks a small
segmenter written below from unicodedata. So it proves the ARITHMETIC AROUND
the segmenter — that offsets from outside are snapped, that a caret steps a
whole cluster, that a backspace removes a whole cluster, that the re-sync loop
still terminates with an emoji in the text — and it does not prove ICU's
tables. Getting the boundary set wrong is a rendering bug; getting the
arithmetic wrong is the corruption the owner reported.

Every assertion below was watched failing before it was kept — restore
origin/main's pushOutput (retract by length, commit either way, no ownership
state) and A2, A3, C1, D2, D3, D4, E1 and G1 all go red, D3 printing the
duplicate the owner reported. For H onwards, LEGACY_STEP and LEGACY_WORD below
are origin/main's own arithmetic, kept as executable exhibits so the assertions
that name them fail against the code that shipped.

Run:  python3 translate-resync-model.py
"""

FAIL = []


def check(name, cond, detail=""):
    if cond:
        print(f"  ok: {name}")
    else:
        FAIL.append(name)
        print(f"  FAIL: {name}{('  — ' + detail) if detail else ''}")


class HostField:
    """The app's text field, as an InputConnection exposes it.

    The composing region is the part that matters: it is a live span the IME
    owns, the platform maintains it across edits, and setComposingText replaces
    exactly it. Anything the HOST does to its own text drops the region, and the
    IME learns that through onUpdateSelection reporting a span of -1.
    """

    def __init__(self, text="", listener=None):
        self.text = text
        self.cursor = len(text)
        self.composing = None          # (start, end) or None
        self.listener = listener       # takes composing_span_end, or -1
        self.ic_calls = 0

    # ── what the IME calls ────────────────────────────────────────────────
    def set_composing_text(self, s):
        self.ic_calls += 1
        lo, hi = self.composing if self.composing is not None else (self.cursor, self.cursor)
        self.text = self.text[:lo] + s + self.text[hi:]
        self.composing = (lo, lo + len(s))
        self.cursor = lo + len(s)
        self._notify()

    def finish_composing_text(self):
        self.ic_calls += 1
        self.composing = None
        self._notify()

    def commit_text(self, s):
        self.ic_calls += 1
        lo, hi = self.composing if self.composing is not None else (self.cursor, self.cursor)
        self.text = self.text[:lo] + s + self.text[hi:]
        self.composing = None
        self.cursor = lo + len(s)
        self._notify()

    def text_before_cursor(self, n):
        return self.text[max(0, self.cursor - n):self.cursor]

    def delete_surrounding_text(self, before, after):
        self.ic_calls += 1
        lo = max(0, self.cursor - before)
        self.text = self.text[:lo] + self.text[self.cursor + after:]
        self.cursor = lo
        self.composing = None
        self._notify()

    # ── what the HOST APP does on its own ─────────────────────────────────
    def host_edits(self, new_text):
        """The user tapped into the app, or the app rewrote its own field.

        Either way the composing region is dropped — that is the platform
        behaviour the whole design hangs on.
        """
        self.text = new_text
        self.cursor = len(new_text)
        self.composing = None
        self._notify()

    def _notify(self):
        if self.listener:
            self.listener(self.composing[1] if self.composing else -1)


class Bar:
    """TranslateBarView, reduced to the parts that own text."""

    NONE, OWNED, LOST = "NONE", "OWNED", "LOST"

    def __init__(self, field):
        self.field = field
        self.buffer = ""
        self.sel_start = 0
        self.sel_end = 0
        self.output = self.NONE
        self.buttons_visible = False
        # instrumentation, not behaviour
        self.user_edit_entries = 0
        self.drop_handler_entries = 0

    # ── user-origin edits: the ONLY things that touch `buffer` ────────────
    def append(self, s):
        self.user_edit_entries += 1
        lo, hi = min(self.sel_start, self.sel_end), max(self.sel_start, self.sel_end)
        self.buffer = self.buffer[:lo] + s + self.buffer[hi:]
        self.sel_start = self.sel_end = lo + len(s)

    def backspace(self):
        self.user_edit_entries += 1
        if self.sel_start > 0:
            self.buffer = self.buffer[:self.sel_start - 1] + self.buffer[self.sel_start:]
            self.sel_start = self.sel_end = self.sel_start - 1

    # ── program-origin: writes the FIELD, never `buffer` ──────────────────
    def push_output(self, out):
        if self.output == self.LOST:
            return
        if out == "":
            self.release_output(keep=False)
            return
        self.output = self.OWNED
        self.field.set_composing_text(out)

    def release_output(self, keep):
        if self.output != self.OWNED:
            self.output = self.NONE
            return
        # NONE before the calls: finish_composing_text reports a span of -1, the very
        # signal a host takeover sends, so releasing while still OWNED would have the
        # bar read its own hand-over as the app stealing the text. Asserted by F3.
        self.output = self.NONE
        if not keep:
            self.field.set_composing_text("")
        self.field.finish_composing_text()

    # ── the one thing that reacts to the field ───────────────────────────
    def on_host_output_dropped(self, composing_span_end):
        if composing_span_end >= 0:
            return
        self.drop_handler_entries += 1
        if self.output != self.OWNED:
            return
        self.output = self.LOST
        self.buttons_visible = True    # touches views only; no IC call, no buffer write


def new_bar(host_text=""):
    field = HostField(host_text)
    bar = Bar(field)
    field.listener = bar.on_host_output_dropped
    return field, bar


# ══════════════════════════════════════════════════════════════════════════
print("== translate bar: output re-sync engine (state-machine model) ==")

# A. LOOP TERMINATION — a programmatic update never re-enters the user-edit path.
field, bar = new_bar()
for ch, out in [("h", "a"), ("e", "ab"), ("l", "abc"), ("l", "abcd"), ("o", "abcde")]:
    bar.append(ch)
    bar.push_output(out)
check("A1 five keystrokes enter the user-edit path exactly five times",
      bar.user_edit_entries == 5,
      f"entries={bar.user_edit_entries}")
check("A2 the bar's own writes never reach the drop handler's body",
      bar.output == Bar.OWNED,
      f"output={bar.output} after {bar.drop_handler_entries} notifications")
check("A3 the sequence terminates with one composing region, not five",
      field.text == "abcde" and field.composing == (0, 5),
      f"text={field.text!r} composing={field.composing}")

# B. CARET SURVIVES a programmatic update.
field, bar = new_bar()
bar.append("hello")
bar.sel_start = bar.sel_end = 2          # user puts the caret mid-word
before = (bar.sel_start, bar.sel_end, bar.buffer)
bar.push_output("translated text of a quite different length")
check("B1 caret and buffer are untouched by a programmatic push",
      (bar.sel_start, bar.sel_end, bar.buffer) == before,
      f"{before} -> {(bar.sel_start, bar.sel_end, bar.buffer)}")

# C. THE COMPOSING REGION IS NEVER CLOBBERED — revisions replace it in place,
#    and it always describes exactly the bar's own text.
field, bar = new_bar("existing note. ")
bar.append("hi")
bar.push_output("olá")
bar.append("!")
bar.push_output("olá!")
# Guarded, not unpacked: on a build that owns no region this must REPORT a
# failure, not raise and take every assertion after it down with it.
region = field.text[field.composing[0]:field.composing[1]] if field.composing else None
check("C1 the composing region still spans exactly the bar's output",
      region == "olá!",
      f"region={region!r}")
check("C2 the app's own text before it is intact",
      field.text == "existing note. olá!",
      f"text={field.text!r}")

# D. THE SAME SOURCE TEXT TWICE DOES NOT DUPLICATE.
field, bar = new_bar()
bar.append("hello")
bar.push_output("olá")
bar.backspace()
bar.push_output("ol")
bar.append("o")
bar.push_output("olá")                   # same output as the first push
check("D1 the same translation pushed twice leaves one copy",
      field.text == "olá",
      f"text={field.text!r}")

# D2. The regression the owner reported: the host moves underneath, and the next
#     push must not append a second copy.
field, bar = new_bar()
bar.append("hello")
bar.push_output("olá")
field.host_edits("the app rewrote everything")     # drops the composing region
check("D2 a host-side rewrite is noticed",
      bar.output == Bar.LOST, f"output={bar.output}")
calls_before = field.ic_calls
bar.append("!")
bar.push_output("olá!")
check("D3 after the host takes over, the bar writes NOTHING",
      field.ic_calls == calls_before and field.text == "the app rewrote everything",
      f"calls+{field.ic_calls - calls_before} text={field.text!r}")
check("D4 and offers Insert/Replace instead of going silent",
      bar.buttons_visible)

# E. TERMINALITY — LOST is one-way, so the handler does its work at most once.
field, bar = new_bar()
bar.append("x")
bar.push_output("y")
field.host_edits("gone")
for _ in range(50):
    field.host_edits("gone again")
check("E1 fifty further host edits change nothing more",
      bar.output == Bar.LOST and bar.user_edit_entries == 1,
      f"output={bar.output} userEdits={bar.user_edit_entries}")

# F. APPLY hands the region over instead of committing a second copy.
field, bar = new_bar("note: ")
bar.append("hi")
bar.push_output("olá")
bar.release_output(keep=True)            # what apply() does when OWNED
check("F1 apply leaves exactly one copy, no longer composing",
      field.text == "note: olá" and field.composing is None,
      f"text={field.text!r} composing={field.composing}")
check("F2 and the bar no longer claims the span",
      bar.output == Bar.NONE, f"output={bar.output}")

# F3. The ordering in release_output, held to explicitly: handing the region over
#     must not be mistaken for the host taking it.
field, bar = new_bar("note: ")
bar.append("hi")
bar.push_output("olá")
bar.release_output(keep=True)
check("F3 releasing the region is not read as a host takeover",
      bar.output == Bar.NONE and not bar.buttons_visible,
      f"output={bar.output} buttons={bar.buttons_visible}")

# G. An emptied buffer takes the bar's text back out, and nothing else.
field, bar = new_bar("note: ")
bar.append("hi")
bar.push_output("olá")
bar.push_output("")
check("G1 clearing the buffer removes only the bar's own output",
      field.text == "note: " and field.composing is None,
      f"text={field.text!r}")


# ══════════════════════════════════════════════════════════════════════════
# UTF-16, CODEPOINTS AND GRAPHEMES — the second half's foundation.
#
# A Kotlin String is a sequence of UTF-16 code units, and every length,
# substring and index in the code being modelled counts in those. `😶` is
# U+1F636, outside the Basic Multilingual Plane, so it is TWO of them: a high
# surrogate and a low surrogate. Splitting that pair does not give you half an
# emoji, it gives you two things that are not characters at all.
# ══════════════════════════════════════════════════════════════════════════
import unicodedata


def u16(text):
    """A Python str as the list of UTF-16 code units Kotlin would hold."""
    raw = text.encode("utf-16-le")
    return [raw[i] | (raw[i + 1] << 8) for i in range(0, len(raw), 2)]


def from_u16(units):
    """Back to a str. Deliberately lenient: a split pair has to be OBSERVABLE."""
    raw = bytearray()
    for unit in units:
        raw += bytes((unit & 0xFF, unit >> 8))
    return bytes(raw).decode("utf-16-le", errors="surrogatepass")


def has_lone_surrogate(units):
    """True when the units contain half of a pair — the corruption itself."""
    i = 0
    while i < len(units):
        unit = units[i]
        if 0xD800 <= unit <= 0xDBFF:                       # high surrogate
            if i + 1 >= len(units) or not (0xDC00 <= units[i + 1] <= 0xDFFF):
                return True
            i += 2
            continue
        if 0xDC00 <= unit <= 0xDFFF:                       # low surrogate, unpaired
            return True
        i += 1
    return False


def codepoints_with_offsets(units):
    """(codepoint, utf-16 offset) pairs, pairing surrogates as the platform does."""
    out = []
    i = 0
    while i < len(units):
        unit = units[i]
        if 0xD800 <= unit <= 0xDBFF and i + 1 < len(units) and 0xDC00 <= units[i + 1] <= 0xDFFF:
            out.append((0x10000 + ((unit - 0xD800) << 10) + (units[i + 1] - 0xDC00), i))
            i += 2
        else:
            out.append((unit, i))
            i += 1
    return out


def extends_cluster(cp, previous):
    """Does [cp] join the cluster that [previous] is in, rather than start a new one?

    A model of the extended grapheme cluster rules, covering the cases the box
    actually meets: combining marks, variation selectors, skin tone modifiers,
    zero-width-joiner sequences and flags. ICU does this properly in the Kotlin.
    """
    if previous is None:
        return False
    if 0xFE00 <= cp <= 0xFE0F or 0xE0100 <= cp <= 0xE01EF:      # variation selectors
        return True
    if 0x1F3FB <= cp <= 0x1F3FF:                                 # skin tone modifiers
        return True
    if cp == 0x200D:                                             # zero width joiner
        return True
    if previous == 0x200D:                                       # ...and what follows one
        return True
    if unicodedata.category(chr(cp)) in ("Mn", "Me", "Mc"):      # combining marks
        return True
    return False


def boundaries(units):
    """Every legal caret position, as UTF-16 offsets. Always contains 0 and len."""
    marks = {0, len(units)}
    previous = None
    regional_run = 0
    for cp, offset in codepoints_with_offsets(units):
        if 0x1F1E6 <= cp <= 0x1F1FF:                             # regional indicator
            # A flag is a PAIR of them; a third starts a new flag.
            if regional_run % 2 == 0:
                marks.add(offset)
            regional_run += 1
            previous = cp
            continue
        regional_run = 0
        if not extends_cluster(cp, previous):
            marks.add(offset)
        previous = cp
    return sorted(marks)


class Editor:
    """TextBoxEditor: one buffer, one caret, one selection, in UTF-16 units.

    THE RULE, and the whole reason this class exists: an offset the editor
    COMPUTES from its own edit is exact; an offset arriving from OUTSIDE — a
    touch, a whitespace scan, a caller's arithmetic — is snapped to a grapheme
    boundary on the way in, by [boundary_at], which every entry point calls.
    """

    def __init__(self, text=""):
        self.units = u16(text)
        self.sel_start = len(self.units)
        self.sel_end = len(self.units)
        self._undo = None

    # ── reading ──────────────────────────────────────────────────────────
    @property
    def text(self):
        return from_u16(self.units)

    @property
    def length(self):
        return len(self.units)

    def has_selection(self):
        return self.sel_start != self.sel_end

    def sel_lo(self):
        return min(self.sel_start, self.sel_end)

    def sel_hi(self):
        return max(self.sel_start, self.sel_end)

    def selected_text(self):
        if not self.has_selection():
            return self.text
        return from_u16(self.units[self.sel_lo():self.sel_hi()])

    def can_undo(self):
        return self._undo is not None

    # ── boundaries ───────────────────────────────────────────────────────
    def boundary_at(self, at):
        """Snap an offset from outside onto a boundary, towards the start."""
        p = max(0, min(at, self.length))
        marks = boundaries(self.units)
        if p in marks:
            return p
        return max(m for m in marks if m < p)

    def boundary_after(self, at):
        """Snap towards the end — for the far end of a selection."""
        p = max(0, min(at, self.length))
        marks = boundaries(self.units)
        if p in marks:
            return p
        return min(m for m in marks if m > p)

    def next_boundary(self, at):
        marks = boundaries(self.units)
        later = [m for m in marks if m > max(0, min(at, self.length))]
        return later[0] if later else self.length

    def prev_boundary(self, at):
        marks = boundaries(self.units)
        earlier = [m for m in marks if m < max(0, min(at, self.length))]
        return earlier[-1] if earlier else 0

    # ── caret and selection ──────────────────────────────────────────────
    def _set_caret_exact(self, at):
        p = max(0, min(at, self.length))
        self.sel_start = p
        self.sel_end = p

    def set_caret(self, at):
        self._set_caret_exact(self.boundary_at(at))

    def select(self, anchor, extent):
        a = self.boundary_at(anchor)
        raw = max(0, min(extent, self.length))
        self.sel_start = a
        self.sel_end = self.boundary_after(raw) if raw >= a else self.boundary_at(raw)

    def select_all(self):
        self.sel_start = 0
        self.sel_end = self.length

    def move_caret(self, steps, select=False):
        if steps == 0:
            return
        back = steps < 0
        if select:
            p = self.sel_end
        elif not self.has_selection():
            p = self.sel_start
        else:
            p = self.sel_lo() if back else self.sel_hi()
        remaining = abs(steps)
        if not select and self.has_selection():
            remaining -= 1
        for _ in range(remaining):
            p = self.prev_boundary(p) if back else self.next_boundary(p)
        if select:
            self.sel_end = p
        else:
            self._set_caret_exact(p)

    def step_caret(self, direction):
        self.move_caret(-1 if direction < 0 else 1)

    # ── words ────────────────────────────────────────────────────────────
    def _is_space(self, index):
        return from_u16([self.units[index]]).isspace()

    def word_start(self, at):
        i = max(0, min(at, self.length))
        while i > 0 and self._is_space(i - 1):
            i -= 1
        while i > 0 and not self._is_space(i - 1):
            i -= 1
        return self.boundary_at(i)

    def word_end(self, at):
        i = max(0, min(at, self.length))
        while i < self.length and self._is_space(i):
            i += 1
        while i < self.length and not self._is_space(i):
            i += 1
        return self.boundary_after(i)

    def select_word_at(self, at):
        if self.length == 0:
            return
        p = max(0, min(at, self.length))
        if p < self.length and not self._is_space(p):
            inside = p
        elif p > 0 and not self._is_space(p - 1):
            inside = p - 1
        else:
            lo = hi = p
            while lo > 0 and self._is_space(lo - 1):
                lo -= 1
            while hi < self.length and self._is_space(hi):
                hi += 1
            self.sel_start, self.sel_end = lo, hi
            return
        self.sel_start = self.word_start(inside + 1)
        self.sel_end = self.word_end(inside)

    # ── edits ────────────────────────────────────────────────────────────
    def insert(self, s):
        if not s:
            return
        lo, hi = self.sel_lo(), self.sel_hi()
        added = u16(s)
        self.units[lo:hi] = added
        self._set_caret_exact(lo + len(added))

    def delete_selection(self):
        if not self.has_selection():
            return False
        lo, hi = self.sel_lo(), self.sel_hi()
        del self.units[lo:hi]
        self._set_caret_exact(lo)
        return True

    def delete_backward(self):
        if self.delete_selection():
            return
        at = self.sel_lo()
        if at <= 0:
            return
        start = self.prev_boundary(at)
        del self.units[start:at]
        self._set_caret_exact(start)

    def replace_all(self, s):
        if self.units:
            self._undo = (list(self.units), self.sel_start)
        self.units = u16(s)
        self._set_caret_exact(self.length)

    def clear(self):
        self.replace_all("")

    def undo(self):
        if self._undo is None:
            return False
        units, caret = self._undo
        self._undo = None
        self.units = list(units)
        self._set_caret_exact(caret)
        return True


# ── origin/main's arithmetic, kept runnable so the assertions can name it ──
def LEGACY_STEP(units, at, direction):
    """The caret step that shipped: surrogate pairs only, nothing else."""
    if direction < 0:
        if at > 1 and 0xDC00 <= units[at - 1] <= 0xDFFF and 0xD800 <= units[at - 2] <= 0xDBFF:
            return at - 2
        return at - 1
    if at < len(units) - 1 and 0xD800 <= units[at] <= 0xDBFF and 0xDC00 <= units[at + 1] <= 0xDFFF:
        return at + 2
    return at + 1


def LEGACY_WORD(units, at):
    """The word selection that shipped: both scans run outwards from [at]."""
    def space(i):
        return from_u16([units[i]]).isspace()
    start = min(at + 1, len(units))
    while start > 0 and space(start - 1):
        start -= 1
    while start > 0 and not space(start - 1):
        start -= 1
    end = at
    while end < len(units) and space(end):
        end += 1
    while end < len(units) and not space(end):
        end += 1
    return start, end


# ══════════════════════════════════════════════════════════════════════════
print()
print("== the bar's box as an editor: UTF-16, codepoints, graphemes ==")

# H. THE OWNER'S OWN EXAMPLE, IN THE UNITS THE CODE COUNTS IN.
OWNER = "BIEN? \U0001F636 o no?"
check("H1 the owner's example is longer in UTF-16 than it is in characters",
      len(u16(OWNER)) == 14 and len(boundaries(u16(OWNER))) - 1 == 13,
      f"units={len(u16(OWNER))} graphemes={len(boundaries(u16(OWNER)))-1}")

# H2. A touch resolving to the middle of the pair. This is the offset that used
#     to go straight in as a caret.
box = Editor(OWNER)
inside = OWNER.index("\U0001F636")          # 6 in codepoints AND in UTF-16 here
box.set_caret(inside + 1)                   # between the high and the low surrogate
check("H2 a caret aimed inside the surrogate pair lands on its edge, not in it",
      box.sel_start == inside,
      f"asked for {inside + 1}, got {box.sel_start}")

# H3. One backspace after the emoji removes the WHOLE emoji, and leaves no half.
box = Editor(OWNER)
box.set_caret(inside + 2)                   # just after the emoji
box.delete_backward()
check("H3 one backspace after the emoji deletes the emoji, not half of it",
      box.text == "BIEN?  o no?" and not has_lone_surrogate(box.units),
      f"text={box.text!r} loneSurrogate={has_lone_surrogate(box.units)}")

# H4. Stepping right from before the emoji clears it in ONE press.
box = Editor(OWNER)
box.set_caret(inside)
box.step_caret(1)
check("H4 one right step crosses the whole emoji",
      box.sel_start == inside + 2, f"caret={box.sel_start}")

# H5. The arithmetic that shipped, on a grapheme it was never taught about.
tone = u16("\U0001F44D\U0001F3FD")           # thumbs up + medium skin tone: 4 units
check("H5 origin/main's step lands INSIDE a skin-toned emoji (this is the bug)",
      LEGACY_STEP(tone, 0, 1) == 2 and Editor("\U0001F44D\U0001F3FD").next_boundary(0) == 4,
      f"legacy={LEGACY_STEP(tone, 0, 1)} shared={Editor(chr(0x1F44D)+chr(0x1F3FD)).next_boundary(0)}")

# I. MULTI-CODEPOINT GRAPHEMES: one character to the user, several to the machine.
MULTI = [
    ("a flag", "\U0001F1EA\U0001F1F8", 4),                       # ES
    ("a skin-toned thumb", "\U0001F44D\U0001F3FD", 4),
    ("a variation-selector face", "☺️", 2),
    ("a zero-width-joined family", "\U0001F468‍\U0001F469‍\U0001F467", 8),
    ("a combining accent", "é", 2),
]
for label, glyph, units_expected in MULTI:
    box = Editor(glyph)
    check(f"I1 {label} is {units_expected} UTF-16 units and ONE grapheme",
          box.length == units_expected and boundaries(box.units) == [0, units_expected],
          f"length={box.length} boundaries={boundaries(box.units)}")
    box.set_caret(0)
    box.step_caret(1)
    check(f"I2 one right step crosses {label} whole",
          box.sel_start == units_expected, f"caret={box.sel_start}")
    box = Editor(glyph)
    box.delete_backward()
    check(f"I3 one backspace removes {label} whole",
          box.text == "" and box.length == 0, f"left={box.text!r}")
    box = Editor("x" + glyph + "y")
    box.select(1, 2)                     # a drag stopping one unit into the glyph
    check(f"I4 a drag stopping inside {label} takes all of it",
          box.selected_text() == glyph, f"selected={box.selected_text()!r}")

# J. THE TERMINATION PROOF, RE-RUN WITH AN EMOJI IN THE TEXT.
#    The proof that shipped was over well-formed ASCII. It has to hold here too,
#    and the reason it does is structural, not textual: the drop handler reads
#    state and writes neither the field nor the buffer, so nothing it does can
#    produce the update it reacts to — no comparison of text is involved at all.
field, bar = new_bar()
box = Editor()
for ch, out in [("B", "B"), ("I", "BI"), ("\U0001F636", "BI\U0001F636"),
                ("!", "BI\U0001F636!"), ("?", "BI\U0001F636!?")]:
    box.insert(ch)
    bar.append(ch)
    bar.push_output(out)
check("J1 five keystrokes including an emoji enter the user-edit path five times",
      bar.user_edit_entries == 5, f"entries={bar.user_edit_entries}")
check("J2 the loop terminates with ONE composing region, emoji intact",
      field.text == "BI\U0001F636!?" and field.composing == (0, 5)
      and not has_lone_surrogate(u16(field.text)),
      f"text={field.text!r} composing={field.composing}")
check("J3 the drop handler never did any work",
      bar.drop_handler_entries == 0 and bar.output == Bar.OWNED,
      f"entries={bar.drop_handler_entries} output={bar.output}")
check("J4 the box holds exactly what was typed, in whole graphemes",
      box.text == "BI\U0001F636!?" and not has_lone_surrogate(box.units),
      f"box={box.text!r}")

# J5. A provider that re-renders the emoji on every reply — the churn case. The
#     bar has no source-vs-output comparison to churn on, so N replies cost N
#     writes and nothing else moves.
field, bar = new_bar()
bar.append("hola \U0001F636")
variants = ["hi \U0001F636", "hi ☺️", "hi", "hi \U0001F636️", "hi \U0001F636"]
before = field.ic_calls
for variant in variants:
    bar.push_output(variant)
check("J5 five emoji-only re-renderings cost five writes and no state change",
      field.ic_calls - before == len(variants)
      and bar.output == Bar.OWNED and bar.drop_handler_entries == 0,
      f"calls+{field.ic_calls - before} output={bar.output} drops={bar.drop_handler_entries}")

# K. THE DECISION, HELD TO EXPLICITLY: a difference that is ONLY emoji is not a
#    reason to act. Sending "olá 😶" and getting "olá 🙂" back changes nothing
#    about the SOURCE, and the source is the only thing re-sync is keyed on.
field, bar = new_bar()
bar.append("hola \U0001F636")
source_after_send = bar.buffer
bar.push_output("olá \U0001F642")     # provider swapped the emoji
check("K1 an emoji-only difference in the REPLY leaves the source untouched",
      bar.buffer == source_after_send, f"buffer={bar.buffer!r}")
check("K2 and does not make the bar give up its span",
      bar.output == Bar.OWNED and bar.drop_handler_entries == 0,
      f"output={bar.output} drops={bar.drop_handler_entries}")

# L. THE DEFECT THE PREVIOUS AGENT LEFT OPEN: long-press exactly on a space.
box = Editor("hola mundo")
space = 4
box.select_word_at(space)
check("L1 long-pressing on a space selects ONE word, not both",
      box.selected_text() == "hola", f"selected={box.selected_text()!r}")
check("L2 origin/main selected both words at the same offset (the defect)",
      LEGACY_WORD(u16("hola mundo"), space) == (0, 10),
      f"legacy={LEGACY_WORD(u16('hola mundo'), space)}")
box = Editor("  mundo")
box.select_word_at(0)
check("L3 with no word before the space, the run of spaces is what is selected",
      box.selected_text() == "  ", f"selected={box.selected_text()!r}")
box = Editor("hola mundo")
box.select_word_at(7)
check("L4 inside a word still selects that word, and only that word",
      (box.sel_start, box.sel_end) == (5, 10) and box.selected_text() == "mundo",
      f"selected={box.selected_text()!r} range=({box.sel_start}, {box.sel_end})")

# M. UNDO OF A PROGRAMMATIC REPLACEMENT — the text the user typed comes back.
box = Editor()
box.insert("my own draft \U0001F636")
box.set_caret(3)
box.replace_all("a model's rewrite that is nothing like it")
check("M1 a generated text lands in the box",
      box.text == "a model's rewrite that is nothing like it" and box.can_undo())
check("M2 undo gives the user's own text back, emoji and caret included",
      box.undo() and box.text == "my own draft \U0001F636" and box.sel_start == 3,
      f"text={box.text!r} caret={box.sel_start}")
check("M3 undo is offered once, not forever",
      not box.can_undo())
box = Editor()
box.replace_all("first thing ever generated")
check("M4 nothing was lost, so nothing is offered back",
      not box.can_undo())

# N. WHAT THE ENHANCE BAR GETS. Both bars hold the SAME class; there is nothing
#    for one of them to have that the other does not, and this is the assertion
#    that says so rather than a claim in a commit message.
translate_box = Editor()
enhance_box = Editor()
for box in (translate_box, enhance_box):
    box.insert("hola \U0001F1EA\U0001F1F8 mundo")
    box.set_caret(7)                            # aimed INSIDE the flag: 5..9 is one grapheme
    box.delete_backward()                       # so this must take the space before it, whole
    box.select_word_at(4)                       # long-press exactly on a space
check("N1 the two bars' boxes behave identically because they ARE one class",
      translate_box.text == enhance_box.text
      and (translate_box.sel_start, translate_box.sel_end) == (enhance_box.sel_start, enhance_box.sel_end)
      and type(translate_box) is type(enhance_box),
      f"{translate_box.text!r}/{enhance_box.text!r}")
check("N2 a backspace aimed inside the flag leaves no half of it in either box",
      translate_box.text == "hola\U0001F1EA\U0001F1F8 mundo"
      and not has_lone_surrogate(translate_box.units)
      and not has_lone_surrogate(enhance_box.units),
      f"text={translate_box.text!r} lone={has_lone_surrogate(translate_box.units)}")

print()
if FAIL:
    print(f"FAILED {len(FAIL)}: " + ", ".join(FAIL))
    raise SystemExit(1)
print("all model assertions passed")
