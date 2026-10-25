# ─── GENERATED: do not edit — edit 1_cicd/src/scripts/cloud-android-nav-walk.py ───
#!/usr/bin/env python3
"""cloud-android-nav-walk: the shared nav pattern, enforced at RUNTIME on a real emulator.

The static guards (ChromeScrollGuardTest, nav-shape-guard) read source; this reads the screen. For ONE installed app it
walks EVERY section and page its build.json::ui declares (ui.bottom_nav ids, ui.sections[].pages, nested pages too) and,
for each, checks what the owner keeps finding by hand:

  P1 no crash: the process is alive, the app owns the foreground and logcat holds no FATAL EXCEPTION for it.
  P2 the first row's title dropdown (the breadcrumb) opens, lists the page, and closes again.
  P3 drag up on the content and HOLD the finger down: if the page scrolled the chrome MUST be HIDDEN (first row, second
     row and bottom bar all gone: the three-state scroll machine's HIDDEN). Lift the finger: SHRUNK (the first row stays
     gone, every non-selected bottom label is gone, the bottom bar is shorter, and so are the second row's tabs when the
     page has one). Swipe down: DEFAULT, all of it is back. A page that did not scroll (nothing moved, nothing collapsed) is reported
     `fixed` and only has to leave the chrome intact: a page that scrolls but leaves the chrome expanded FAILS.
  P5 a section declared vertical_side_menu shows the side menu and its toggle flips expanded <-> rail.
  P4 the pick landed: after choosing the page in the dropdown the title path ends in its label.

Nothing here is relaxed to get green: a failing page is reported with its reason, a screenshot and the UI dump.

It drives the app through UiAutomator (`uiautomator dump`, `input`), so it needs no instrumentation APK and no Gradle. The
bars expose their test tags as resource ids (libs:ui-nav-bars sets testTagsAsResourceId on the scaffold).

  cloud-android-nav-walk.py --app ac_cloud-calc --build-json ac_cloud-calc/build.json --pkg com.x --out out/
Exit 0 when every page passed, 1 when any failed, 2 when the app could not be driven at all (counted as failed pages).
"""
import argparse
import json
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

# The shared bars' tags (libs:bottomnav TopBarTags / BottomNavTags, libs:ui-nav-bars TopFirstRowTags).
TITLE, CONFIGS, MORE = "topbar_title", "topbar_configs", "navtop1_more"
CRUMB0 = "navtop1_crumb_0"
ENTRY = "navtop1_entry_"
ITEM, LABEL = "bottomnav_item_", "bottomnav_label_"
ISLAND = "bottomnav_island"
TAB = "pagetabs_tab_"
STRIP = "pagetabs_strip"


def adb(*a, timeout=90):
    return subprocess.run(["adb", *a], capture_output=True, text=True, timeout=timeout)


def shell(cmd, timeout=90):
    return adb("shell", cmd, timeout=timeout).stdout


class Node:
    __slots__ = ("rid", "text", "desc", "l", "t", "r", "b", "scrollable", "clickable", "pkg", "cls")

    def __init__(self, e):
        a = e.attrib
        self.rid = a.get("resource-id", "").split("/")[-1]
        self.text, self.desc = a.get("text", ""), a.get("content-desc", "")
        m = re.match(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]", a.get("bounds", ""))
        self.l, self.t, self.r, self.b = (int(x) for x in m.groups()) if m else (0, 0, 0, 0)
        self.scrollable, self.clickable = a.get("scrollable") == "true", a.get("clickable") == "true"
        self.pkg, self.cls = a.get("package", ""), a.get("class", "")

    @property
    def cx(self): return (self.l + self.r) // 2

    @property
    def cy(self): return (self.t + self.b) // 2

    @property
    def h(self): return self.b - self.t


def dump(tries=8, _depth=0):
    """The visible UI as a list of Node (the active window: the dropdown's popup while it is open), or None."""
    for _ in range(tries):
        out = shell("uiautomator dump /sdcard/nw.xml >/dev/null 2>&1; cat /sdcard/nw.xml")
        k = out.find("<?xml")
        if k >= 0 and "<hierarchy" in out:
            try:
                nodes = [Node(e) for e in ET.fromstring(out[k:].strip()).iter("node")]
            except ET.ParseError:
                nodes = None
            if nodes is not None:
                if _depth < 4 and dismiss_emulator_prompt(nodes):
                    return dump(tries, _depth + 1)
                return nodes
        time.sleep(1.2)
    return None


ANRS = [0]


def anr_evidence(pkg=""):
    """Say WHY a "not responding" dialog came: the system's own ANR line, its CPU load (an overloaded emulator shows a load far above its
    cores) and the app's main-thread frames, so real main-thread work can be told from a slow emulator."""
    out = shell("logcat -d -b main,system,events -v brief 2>/dev/null | grep -E 'ANR in|Reason:|Load:|am_anr' | tail -6", timeout=60).strip()
    traces = shell("cat /data/anr/anr_* 2>/dev/null | grep -A12 '\"main\" prio' | head -16", timeout=60).strip()
    cores = shell("nproc").strip()
    print("  ANR evidence (emulator cores %s):\n    %s\n    main thread: %s" % (cores, out.replace("\n", "\n    ") or "none in logcat", traces.replace("\n", "\n    ") or "no /data/anr trace"), flush=True)


def dismiss_emulator_prompt(nodes):
    """The emulator's own "Allow wireless debugging on this network?" dialog covers any app that starts after the network comes up."""
    wait = find(nodes, "aerr_wait")
    if wait is not None and ANRS[0] < 40:  # an emulator under load reports "not responding": wait it out, and count it in the report
        ANRS[0] += 1
        anr_evidence()
        tap(wait)
        time.sleep(3.0)
        return True
    title = find(nodes, "alertTitle")
    if title is not None and "wireless debugging" in title.text.lower():
        always, allow = find(nodes, "alwaysUse"), find(nodes, "button1")
        if always is not None:
            tap(always)
            time.sleep(0.5)
        if allow is not None:
            tap(allow)
            time.sleep(1.0)
            return True
    return False


def find(nodes, rid):
    return next((n for n in nodes if n.rid == rid), None)


def findall(nodes, prefix):
    return [n for n in nodes if n.rid.startswith(prefix)]


def texts_in(nodes, box):
    return " ".join(n.text for n in nodes if n.text and n.l >= box.l and n.r <= box.r and n.t >= box.t and n.b <= box.b) + " " + box.text + " " + box.desc


BLOCKER_TEXTS = ("Continue with local files", "Skip", "Not now", "Later", "Got it", "No thanks", "Maybe later", "Dismiss", "While using the app", "Allow", "OK")


def clear_blockers(nodes):
    """First-run screens, the default-app role prompt and permission dialogs: dismiss ONE and say so. Used only while the shared bars are missing."""
    wait = find(nodes, "aerr_wait")
    if wait is not None:  # "<app> isn't responding": a slow start on a shared emulator is given the chance to finish; a hang still fails the launch
        tap(wait)
        return True
    title = find(nodes, "title") or find(nodes, "alertTitle")
    if title is not None and ("default" in title.text.lower() or "caller id" in title.text.lower()):
        cancel = find(nodes, "button2")
        if cancel is not None:
            tap(cancel)
            return True
    dismiss = find(nodes, "signin_fre_dismiss_button")  # a browser first-run screen an app handed off to: leave it, the app is relaunched
    if dismiss is not None:
        tap(dismiss)
        return True
    for want in BLOCKER_TEXTS:
        hits = [n for n in nodes if n.text == want]
        hits.sort(key=lambda n: not (n.clickable or n.cls.endswith("Button")))
        if hits:
            tap(hits[0])
            return True
    return False


def tap(n):
    shell("input tap %d %d" % (n.cx, n.cy))


def swipe(x, y1, y2, ms=350):
    shell("input swipe %d %d %d %d %d" % (x, y1, x, y2, ms))


def motion(action, x, y):
    shell("input motionevent %s %d %d" % (action, x, y))


def settle(s=0.9):
    time.sleep(s)


def screen_size():
    m = re.search(r"(\d+)x(\d+)", shell("wm size"))
    return (int(m.group(1)), int(m.group(2))) if m else (1080, 1920)


def pages_of(section):
    """Every destination page of a declared section, depth-first: [(id, label, is_launch, ancestor ids)]."""
    out = []

    def walk(pages, up):
        for p in pages or []:
            out.append((p["id"], p.get("label", p["id"]), bool(p.get("action")), up))
            walk(p.get("pages"), up + [p["id"]])

    walk(section.get("pages"), [])
    return out


class Walk:
    def __init__(self, a):
        self.a = a
        self.pkg = a.pkg
        self.out = a.out
        os.makedirs(os.path.join(self.out, "shots"), exist_ok=True)
        os.makedirs(os.path.join(self.out, "dumps"), exist_ok=True)
        ui = json.load(open(a.build_json, encoding="utf-8"))["ui"]
        self.bottom = list(ui.get("bottom_nav") or [])
        self.sections = {s["id"]: s for s in ui.get("sections", [])}
        self.w, self.h = screen_size()
        self.results = []

    # ── driving ──────────────────────────────────────────────────────────────
    def launch(self):
        act = shell("cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER %s" % self.pkg).strip().splitlines()
        act = act[-1].strip() if act else ""
        if "/" not in act:
            raise RuntimeError("no launcher activity for %s (%s)" % (self.pkg, act))
        shell("am force-stop %s" % self.pkg)
        # The CI emulator has 2 cores: its LAUNCHER often stops answering ("Input dispatching timed out") and the system's "isn't
        # responding" dialog then covers whichever app is walked. The dialogs are switched off; an ANR of the app under test is
        # still found, in the events log (app_anr), and fails its page with the emulator's load beside it.
        shell("settings put global hide_error_dialogs 1")
        shell("logcat -c")
        shell("am start -W -n %s" % act, timeout=120)
        deadline = time.time() + 90
        while time.time() < deadline:
            nodes = dump(2)
            if nodes and any(n.rid.startswith(ITEM) for n in nodes):
                settle(1.5)
                return nodes
            if nodes and clear_blockers(nodes):
                settle(1.5)
                continue
            if nodes and nodes[0].pkg not in (self.pkg, "android", "com.android.systemui") and not self.foreground():
                shell("monkey -p %s -c android.intent.category.LAUNCHER 1" % self.pkg)  # brought back to the front after a hand-off
            settle(2)
        return None

    def alive(self):
        return bool(shell("pidof %s" % self.pkg).strip())

    def foreground(self):
        return self.pkg in shell("dumpsys activity activities | grep -E 'topResumedActivity|mResumedActivity'")

    def app_anr(self):
        """An ANR of the app under test since the last `logcat -c`, with the emulator's load: real main-thread work shows up as an
        am_anr line for THIS package; an overloaded emulator shows ANRs of the launcher or of system apps and a load above its cores."""
        mine = shell("logcat -d -b events -v brief 2>/dev/null | grep am_anr | grep -F %s" % self.pkg, timeout=60).strip()
        if not mine:
            return None
        load = shell("cat /proc/loadavg").strip()
        return "%s (emulator load %s, %s cores)" % (mine.splitlines()[-1][-160:], load, shell("nproc").strip())

    def crashed(self):
        log = shell("logcat -d -b crash -v brief") + shell("logcat -d -v brief AndroidRuntime:E *:S")
        return ("Process: %s" % self.pkg in log or "FATAL EXCEPTION" in log and self.pkg in log), log

    def ime_up(self):
        return "mInputShown=true" in shell("dumpsys input_method | grep -m1 mInputShown")

    def hide_ime(self):
        if self.ime_up():
            shell("input keyevent KEYCODE_BACK")
            settle(0.8)

    def evidence(self, name):
        safe = re.sub(r"[^A-Za-z0-9_.-]", "_", name)
        adb("shell", "screencap -p /sdcard/nw.png")
        adb("pull", "/sdcard/nw.png", os.path.join(self.out, "shots", safe + ".png"))
        adb("pull", "/sdcard/nw.xml", os.path.join(self.out, "dumps", safe + ".xml"))

    def open_dropdown(self):
        """Open the first row's title dropdown. A host whose title is an input (Cloud Browser) opens it from the chevron."""
        nodes = dump()
        if nodes is None:
            return None
        if find(nodes, CRUMB0) or findall(nodes, ENTRY):
            return nodes
        opener = find(nodes, MORE) or find(nodes, TITLE)
        if opener is None:
            return None
        tap(opener)
        settle(1.0)
        nodes = dump()
        if nodes and (find(nodes, CRUMB0) or findall(nodes, ENTRY)):
            return nodes
        if self.ime_up():
            shell("input keyevent KEYCODE_BACK")
            settle(0.6)
        return None

    def close_dropdown(self):
        shell("input keyevent KEYCODE_BACK")
        settle(0.8)
        nodes = dump()
        return nodes is not None and not (find(nodes, CRUMB0) or findall(nodes, ENTRY))

    def pick(self, pid):
        """Tap [pid]'s entry in the open dropdown, scrolling the dropdown; None when it is not listed (the dropdown lists the
        siblings and children of the page shown, so a page elsewhere in the tree is reached through its ancestors)."""
        nodes = dump()
        if nodes is None:
            return False
        for _ in range(6):
            e = find(nodes, ENTRY + pid)
            if e is not None:
                tap(e)
                settle(1.6)
                return True
            entries = findall(nodes, ENTRY)
            if not entries:
                break
            top, bottom = min(n.t for n in entries), max(n.b for n in entries)
            swipe(entries[0].cx, bottom - 20, max(top + 20, bottom - 500), 300)
            settle(0.6)
            again = dump() or nodes
            if sorted(n.rid for n in findall(again, ENTRY)) == sorted(n.rid for n in entries):
                break  # the list did not move: that is all of it
            nodes = again
        return False

    def goto(self, pid, label, up=()):
        """Pick page [pid] in the dropdown; when it is not listed from here, climb to the section through the path crumb and descend through its ancestors."""
        if self.open_dropdown() is None:
            return "the title dropdown did not open"
        if self.pick(pid):
            return None
        nodes = dump() or []
        crumb = find(nodes, "navtop1_crumb_1")
        if crumb is None:
            shell("input keyevent KEYCODE_BACK")
            return "the dropdown lists no entry for page %s" % pid
        tap(crumb)  # the section: its children are listed next
        settle(1.6)
        for step in list(up) + [pid]:
            if self.open_dropdown() is None:
                return "the title dropdown did not open (descending to %s)" % pid
            if not self.pick(step):
                shell("input keyevent KEYCODE_BACK")
                return "the dropdown lists no entry for page %s (via %s)" % (pid, step)
        return None

    # ── chrome state ─────────────────────────────────────────────────────────
    def chrome(self, nodes, selected=None):
        title = find(nodes, TITLE)
        items = [n for n in nodes if n.rid.startswith(ITEM)]
        labels = {n.rid[len(LABEL):] for n in nodes if n.rid.startswith(LABEL)}
        tabs = [n for n in nodes if n.rid.startswith(TAB)]
        island, strip = find(nodes, ISLAND), find(nodes, STRIP)
        return {
            "row": title is not None,
            "items": len(items),
            "labels": labels,
            # the bars' own boxes: a tab or item is reported at Compose's 48 dp touch target, which a shrunk bar does not go below
            "bar_h": island.h if island else max((n.h for n in items), default=0),
            "tab_h": strip.h if strip else 0,
            "tabs": len(tabs),
            "scrollable": [n for n in nodes if n.scrollable and not n.rid.startswith(("pagetabs_", "navtop", "navside", "topbar", "bottomnav_")) and n.h >= 150],
        }

    def content_box(self, nodes):
        """The area between the first row (or the top) and the bottom bar: where the page lives."""
        title = find(nodes, TITLE)
        items = [n for n in nodes if n.rid.startswith(ITEM)]
        top = title.b if title else 0
        bottom = min((n.t for n in items), default=self.h)
        return top, bottom

    def signature(self, nodes, top, bottom):
        """What the page shows, with where: used to tell whether a swipe scrolled it. Chrome nodes are left out."""
        return sorted((n.text, n.l, n.t - top) for n in nodes if (n.text or n.desc) and n.t >= top and n.b <= bottom
                      and not n.rid.startswith(("bottomnav_", "pagetabs_", "navtop", "topbar")))

    # ── one page ─────────────────────────────────────────────────────────────
    def has_pages(self, section):
        return bool((self.sections.get(section) or {}).get("pages"))

    def check_page(self, section, label, via):
        """Run P1-P4 on the page now shown. Returns (checks dict, [failures])."""
        checks, fails = {}, []
        nodes = dump()
        if nodes is None:
            return checks, ["the UI could not be read (uiautomator never returned a hierarchy)"]
        if not self.foreground():
            fails.append("P1 the app is not in the foreground after opening the page")
        title = find(nodes, TITLE)
        if title is None:
            fails.append("P4 the shared first row is not on screen (no %s)" % TITLE)
        elif via and label.lower() not in texts_in(nodes, title).lower():
            fails.append("P4 the title path does not end in '%s' after picking it (shows: %s)" % (label, texts_in(nodes, title).strip()[:80]))
        # P2 the dropdown opens, lists this page's neighbourhood, and closes
        dd = self.open_dropdown()
        if dd is None:
            fails.append("P2 the title dropdown did not open")
        else:
            if self.has_pages(section) and not findall(dd, ENTRY):
                fails.append("P2 the dropdown opened with no page entries")
            if not self.close_dropdown():
                fails.append("P2 the dropdown did not close on Back")
            elif not self.foreground() or not self.alive():
                fails.append("P2 closing the dropdown left the app")
        checks["dropdown"] = "ok" if not any(f.startswith("P2") for f in fails) else "FAIL"
        # P5 a section declared vertical_side_menu draws the menu (expanded or rail) and its toggle flips between the two states
        if (self.sections.get(section) or {}).get("nav_mode") == "vertical_side_menu" and self.has_pages(section):
            side = dump() or nodes
            if find(side, "navside_menu") is None:
                fails.append("P5 the section declares vertical_side_menu but no side menu is on screen")
            else:
                rail0 = bool(findall(side, "navside_rail_"))
                toggle = find(side, "navside_toggle")
                if toggle is None:
                    fails.append("P5 the side menu has no expand/collapse toggle")
                else:
                    tap(toggle)
                    settle(0.9)
                    flipped = dump() or []
                    if bool(findall(flipped, "navside_rail_")) == rail0:
                        fails.append("P5 the side menu's toggle did not switch between the expanded menu and the rail")
                    t2 = find(flipped, "navside_toggle")
                    if t2 is not None:
                        tap(t2)
                        settle(0.6)
            checks["sidemenu"] = "ok" if not any(f.startswith("P5") for f in fails) else "FAIL"
        # P3 scroll the content up: the chrome must collapse when the page scrolled
        nodes = dump() or nodes
        before = self.chrome(nodes)
        top, bottom = self.content_box(nodes)
        if not before["row"] or before["items"] == 0 or bottom - top < 200:
            checks["scroll"] = "n/a"
            return checks, fails
        sig0 = self.signature(nodes, top, bottom)
        x = self.w // 2
        y1, y2 = int(top + (bottom - top) * 0.8), int(top + (bottom - top) * 0.2)
        # HIDDEN: drag up and keep the finger on the screen; everything must be gone while it is down
        motion("DOWN", x, y1)
        steps = 8
        for i in range(1, steps + 1):
            motion("MOVE", x, y1 + (y2 - y1) * i // steps)
            time.sleep(0.05)
        # the exit animation of the bars may need a moment on the 2-core emulator: poll for the bars to be gone
        held = []
        for _ in range(6):
            settle(0.8)
            held = dump() or []
            h0 = self.chrome(held)
            if not (h0["row"] or find(held, ISLAND) is not None or h0["items"] or find(held, STRIP) is not None or h0["tabs"]):
                break
        motion("UP", x, y2)
        settle(0.5)
        swipe(x, y1, y2)
        settle(1.2)
        self.hide_ime()   # a long press in a text page may raise the keyboard, which hides the bar by design
        nodes2 = dump()
        if nodes2 is None:
            fails.append("P3 the UI could not be read after scrolling")
            return checks, fails
        after = self.chrome(nodes2)
        checks["metrics"] = "bar %d->%d tabs %d->%d labels %d->%d" % (before["bar_h"], after["bar_h"], before["tab_h"], after["tab_h"], len(before["labels"]), len(after["labels"]))
        collapsed = not after["row"]
        sig1 = self.signature(nodes2, *self.content_box(nodes2)) if after["row"] else None
        if collapsed:
            checks["scroll"] = "scrolled"
            h = self.chrome(held)
            if h["row"] or find(held, ISLAND) is not None or h["items"] or find(held, STRIP) is not None or h["tabs"]:
                fails.append("P3 hidden: while the finger is down every bar must be away (first row %s, bottom items %d, second-row tabs %d)" % (h["row"], h["items"], h["tabs"]))
            checks["states"] = "default>hidden>shrunk"
            if not after["items"]:
                fails.append("P3 shrunk: after the finger lifted the bottom bar did not come back")
            if len(after["labels"]) > 1:
                fails.append("P3 scrolled: the first row hid but non-selected bottom labels stayed (%s)" % ", ".join(sorted(after["labels"])))
            if after["bar_h"] >= before["bar_h"]:
                fails.append("P3 scrolled: the bottom bar did not shrink (%d -> %d px)" % (before["bar_h"], after["bar_h"]))
            if before["tab_h"] and after["tab_h"] and after["tab_h"] >= before["tab_h"]:
                fails.append("P3 scrolled: the second row's tabs did not shrink (%d -> %d px)" % (before["tab_h"], after["tab_h"]))
            for _ in range(3):
                swipe(x, y2, y1)
                settle(0.5)
            settle(1.0)
            nodes3 = dump() or []
            back = self.chrome(nodes3)
            if not back["row"]:
                fails.append("P3 scrolled back up: the first row did not return")
            if len(back["labels"]) < before["items"]:
                fails.append("P3 scrolled back up: bottom labels did not return (%d of %d)" % (len(back["labels"]), before["items"]))
            if abs(back["bar_h"] - before["bar_h"]) > 3:
                fails.append("P3 scrolled back up: the bottom bar did not restore (%d vs %d px)" % (back["bar_h"], before["bar_h"]))
        else:
            moved = sig1 != sig0
            if moved and before["scrollable"]:
                checks["scroll"] = "scrolled"
                fails.append("P3 the content scrolled but the chrome did not collapse (first row still on screen)")
            elif moved:
                # something moved but the page offers no scrollable container: a map or canvas panned, not a list scrolled
                checks["scroll"] = "panned"
                if after["items"] != before["items"] or len(after["labels"]) != len(before["labels"]):
                    fails.append("P3 a page that does not scroll changed the bottom bar")
            else:
                checks["scroll"] = "fixed"
                if after["items"] != before["items"] or len(after["labels"]) != len(before["labels"]):
                    fails.append("P3 a page that does not scroll changed the bottom bar")
        return checks, fails

    def ensure_chrome(self, section):
        """A page that left the chrome collapsed must not fail every page after it: bring it back, relaunching if it will not come."""
        nodes = dump() or []
        if find(nodes, TITLE) is not None:
            return
        x = self.w // 2
        for _ in range(3):
            swipe(x, int(self.h * 0.3), int(self.h * 0.8))
            settle(0.8)
        nodes = dump() or []
        if find(nodes, TITLE) is None:
            self.relaunch_to(section)

    def run_page(self, section, pid, label, via_dropdown, up=()):
        name = "%s/%s" % (section, pid)
        t0 = time.time()
        shell("logcat -c")
        self.hide_ime()
        self.ensure_chrome(section)
        err = self.goto(pid, label, up) if via_dropdown else None
        checks, fails = ({}, [err]) if err else self.check_page(section, label, via_dropdown)
        died = not self.alive()
        crashed, log = self.crashed()
        anr = self.app_anr()
        if anr:
            fails.insert(0, "P1 the app did not respond (ANR in %s)" % anr)
        if crashed or died:
            fails.insert(0, "P1 the app %s" % ("crashed (FATAL EXCEPTION in logcat)" if crashed else "died"))
            with open(os.path.join(self.out, "dumps", re.sub(r"\W", "_", name) + ".logcat.txt"), "w") as f:
                f.write(log)
        checks["alive"] = "FAIL" if (crashed or died) else "ok"
        if fails:
            self.evidence(name)
        self.results.append({"section": section, "page": pid, "label": label, "status": "FAIL" if fails else "PASS",
                             "checks": checks, "failures": fails, "seconds": round(time.time() - t0, 1)})
        print("%s %-34s %s" % ("FAIL" if fails else "ok  ", name, "; ".join(fails) or checks), flush=True)
        if crashed or died:
            self.relaunch_to(section)

    def relaunch_to(self, section):
        if self.launch() is not None and section in self.bottom:
            nodes = dump() or []
            n = find(nodes, ITEM + section)
            if n:
                tap(n)
                settle(1.2)

    def visit_section(self, sid):
        sec = self.sections.get(sid)
        if sec is None:
            self.results.append({"section": sid, "page": "-", "label": sid, "status": "FAIL", "checks": {},
                                 "failures": ["build.json::ui.bottom_nav names a section ui.sections does not declare"], "seconds": 0})
            return
        on_bar = sid in self.bottom
        self.hide_ime()
        nodes = dump() or []
        if not any(n.rid.startswith(ITEM) for n in nodes) and clear_blockers(nodes):
            settle(1.5)
            nodes = dump() or []
        opener = find(nodes, ITEM + sid) if on_bar else find(nodes, CONFIGS)
        if opener is None:
            self.results.append({"section": sid, "page": "-", "label": sec.get("label", sid), "status": "FAIL", "checks": {},
                                 "failures": ["P4 no %s on screen to open the section" % ((ITEM + sid) if on_bar else CONFIGS)], "seconds": 0})
            self.evidence("%s_no_opener" % sid)
            return
        tap(opener)
        settle(1.6)
        pages = [p for p in pages_of(sec)]
        # the section itself (its first page is what it opens on)
        self.run_page(sid, sid, sec.get("label", sid), False)
        for pid, label, launch, up in pages:
            if launch:
                self.results.append({"section": sid, "page": pid, "label": label, "status": "SKIP", "checks": {"note": "launch hand-off to another app, not a page"},
                                     "failures": [], "seconds": 0})
                continue
            if pid == sid:
                continue
            self.run_page(sid, pid, label, True, up)

    def run(self):
        if self.launch() is None:
            self.evidence("launch")
            self.results.append({"section": "-", "page": "launch", "label": "launch", "status": "FAIL", "checks": {},
                                 "failures": ["the shared bottom nav never appeared within 60 s of launch (no %s*)" % ITEM], "seconds": 60})
        else:
            for sid in self.bottom + [s for s in self.sections if s not in self.bottom]:
                try:
                    self.visit_section(sid)
                except Exception as ex:  # a driver fault must show as a failed page, never as silence
                    self.results.append({"section": sid, "page": "-", "label": sid, "status": "FAIL", "checks": {},
                                         "failures": ["driver error: %r" % ex], "seconds": 0})
                    self.evidence("%s_driver_error" % sid)
        self.report()

    def report(self):
        app = self.a.app
        with open(os.path.join(self.out, "report.json"), "w") as f:
            json.dump({"app": app, "pkg": self.pkg, "results": self.results}, f, indent=1)
        n = {k: sum(1 for r in self.results if r["status"] == k) for k in ("PASS", "FAIL", "SKIP")}
        lines = ["### %s (%s): %d passed, %d failed, %d skipped (%d 'not responding' dialogs waited out)" % (app, self.pkg, n["PASS"], n["FAIL"], n["SKIP"], ANRS[0]), "",
                 "| section / page | result | dropdown | scroll | metrics (px) | detail |", "|---|---|---|---|---|---|"]
        for r in self.results:
            c = r["checks"]
            lines.append("| %s/%s | %s | %s | %s | %s | %s |" % (r["section"], r["page"], r["status"], c.get("dropdown", "-"), c.get("scroll", "-"), c.get("metrics", "-"),
                                                          ("; ".join(r["failures"]) or c.get("note", "")).replace("|", "/")))
        open(os.path.join(self.out, "report.md"), "w").write("\n".join(lines) + "\n")
        print("\n".join(lines))
        sys.exit(1 if n["FAIL"] else 0)


if __name__ == "__main__":
    p = argparse.ArgumentParser()
    p.add_argument("--app", required=True)
    p.add_argument("--build-json", required=True)
    p.add_argument("--pkg", required=True)
    p.add_argument("--out", required=True)
    Walk(p.parse_args()).run()
