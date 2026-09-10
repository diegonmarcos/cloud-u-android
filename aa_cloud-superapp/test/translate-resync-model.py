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

Every assertion below was watched failing before it was kept — restore
origin/main's pushOutput (retract by length, commit either way, no ownership
state) and A2, A3, C1, D2, D3, D4, E1 and G1 all go red, D3 printing the
duplicate the owner reported.

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

print()
if FAIL:
    print(f"FAILED {len(FAIL)}: " + ", ".join(FAIL))
    raise SystemExit(1)
print("all model assertions passed")
