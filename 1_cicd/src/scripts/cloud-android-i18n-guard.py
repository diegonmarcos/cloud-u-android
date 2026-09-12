#!/usr/bin/env python3
# ╔══════════════════════════════════════════════════════════════════╗
# ║ cloud-android-i18n-guard — fail the build when an app's base     ║
# ║ language is not the fleet's                                      ║
# ║                                                                  ║
# ║ Zero policy here. Every module, the base language, the locale    ║
# ║ filter spellings and every exemption live in                     ║
# ║ 1_cicd/src/i18n-policy.json.                                     ║
# ╚══════════════════════════════════════════════════════════════════╝
#
# WHAT THE RULE IS. English is the fleet's base language: every app comes up in
# English whatever the device asks for. Android does the opposite by default —
# the owner's phone is set to Spanish, so one values-es/ anywhere in an app's
# resource closure wins over values/, and most apps here are clones of upstream
# trees that arrive with 40 to 105 translated locale directories. The only place
# that can settle it is the module that packages the APK: its locale filter is
# applied at merge time and drops the unlisted locales from resources.arsc, its
# own and every library's alike. So the guard reads build.gradle[.kts] and fails
# when a packaging module has no such declaration.
#
# WHY THIS IS NOT `gradle lint`. Lint's MissingTranslation rule answers the
# opposite question (is the Spanish complete?), and it is unreachable here
# anyway for two independent reasons. First, no workflow in .github/workflows/
# ever asks for a lint task — CI runs assembleRelease and stops — so the rule
# never executes at all. Second, every module that configures it has switched it
# off (cloud-vault disables it, cloud-matrix ignores it, cloud-dialer downgrades
# it to a warning), so even a CI that did run lint would print its findings into
# a log and exit green. A guard that only warns changes nothing. This one exits
# non-zero.
#
# WHY IT DISCOVERS MODULES INSTEAD OF READING A LIST OF THEM. A list of apps to
# check can only ever describe the apps that existed when it was written; the
# next app the owner builds would be born exempt and nobody would find out until
# they opened it. So the guard finds every module that owns a values/strings.xml
# and requires the POLICY to have an opinion about it. An undeclared module is a
# failure, which turns "a new app has no locale" from a silent condition into a
# one-line decision somebody has to write down.

import json
import os
import re
import sys
import xml.etree.ElementTree as ET

REPO = os.environ.get("CLOUD_ANDROID_ROOT") or os.path.dirname(
    os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
)
POLICY = os.path.join(REPO, "1_cicd", "src", "i18n-policy.json")

# Directories that hold source we do not own or output we did not write.
SKIP_DIRS = (".git", "build", "z_archive", "node_modules", ".gradle")

# A printf conversion as aapt parses it: an optional positional index, flags,
# width, precision, then the conversion letter. `%%` is a literal percent and
# carries no argument, so it is matched here only to be discarded.
SPECIFIER = re.compile(r"%(?:(\d+)\$)?([-#+ 0,(]*)(\d+)?(?:\.(\d+))?([a-zA-Z%])")


def resource_text(elem):
    """The full text of one resource, inline markup included.

    A translated string may carry <b>, <xliff:g> or a CDATA run, and a format
    specifier can sit inside any of them. Reading only elem.text would miss
    those and report a specifier as dropped when it is merely nested.
    """
    parts = [elem.text or ""]
    for child in elem:
        parts.append(resource_text(child))
        parts.append(child.tail or "")
    return "".join(parts)


def signature(text):
    """The argument list a format string demands, in a comparable form.

    Spanish routinely reorders the sentence around its arguments, which is the
    entire reason positional forms exist, so a positional string is compared as
    an unordered index->conversion mapping. A non-positional string has no
    indices to compare, so its conversions are compared in order — reordering
    those really does hand argument two to slot one.
    """
    found = [m for m in SPECIFIER.finditer(text) if m.group(5) != "%"]
    if any(m.group(1) for m in found):
        return ("positional", frozenset((m.group(1), m.group(5)) for m in found))
    return ("sequential", tuple(m.group(5) for m in found))


def describe(text):
    return " ".join(m.group(0) for m in SPECIFIER.finditer(text) if m.group(5) != "%") or "(none)"


def load(path):
    """name -> element, for every translatable resource in one strings.xml."""
    out = {}
    for elem in ET.parse(path).getroot():
        if elem.tag not in ("string", "plurals", "string-array"):
            continue
        if elem.get("translatable") == "false":
            continue
        name = elem.get("name")
        if name:
            out[name] = elem
    return out


def discover(repo):
    """Every module that owns a default strings.xml.

    Keyed by the module directory — the one holding src/main/res — because that
    is the unit a locale directory belongs to and the unit the policy names.
    """
    found = set()
    for dirpath, dirnames, filenames in os.walk(repo):
        dirnames[:] = [d for d in dirnames if d not in SKIP_DIRS]
        if os.path.basename(dirpath) != "values" or "strings.xml" not in filenames:
            continue
        rel = os.path.relpath(dirpath, repo)
        # <module>/src/main/res/values -> <module>. Only src/main is checked:
        # src/debug and src/release are build-type overlays that never ship to
        # the owner's phone in a form she reads.
        parts = rel.split(os.sep)
        if len(parts) < 5 or parts[-4:] != ["src", "main", "res", "values"]:
            continue
        found.add(os.sep.join(parts[:-4]))
    return found


def translatable_files(res, policy):
    """Every file in values/ that declares translatable resources.

    NOT just strings.xml. A module is free to put user-facing text in a file of
    its own — libs:keyboard keeps the emoji type-tab labels in
    superapp_media_strings.xml — and a guard that reads one filename hands
    anybody a way to add an English string it will never look at. Those three
    labels sit on the emoji surface and were untranslated the whole time this
    guard was reporting green.

    Files whose name the policy marks as configuration are skipped: values/
    also carries per-locale BEHAVIOUR (which punctuation clusters, which layout
    names) under Android's donottranslate convention, and demanding Spanish for
    those would be demanding a translation of a setting.
    """
    skip = tuple(policy["resource_files"]["skip_name_prefixes"])
    out = []
    for name in sorted(os.listdir(os.path.join(res, "values"))):
        if not name.endswith(".xml") or name.startswith(skip):
            continue
        if load(os.path.join(res, "values", name)):
            out.append(name)
    return out


def check_base_language(repo, module, policy, fail):
    """English is the fleet's base language, and a module that packages an APK is
    the only place that can enforce it.

    Resource filtering happens at the app's merge-and-package step, so this one
    declaration also drops the locales arriving from every library and AAR the
    app merges — which is why libs/keyboard's 103 locale directories need no
    edit of their own. Three spellings are accepted because the fleet spans
    plugin 4.2.2 to 9.3.1; the policy carries the patterns rather than this
    script, so a fourth spelling is a data change.
    """
    rule = policy["base_language"]
    patterns = [re.compile(p) for p in rule["declaration_patterns"]]
    for name in ("build.gradle", "build.gradle.kts"):
        path = os.path.join(repo, module, name)
        if not os.path.exists(path):
            continue
        with open(path, encoding="utf-8") as handle:
            text = handle.read()
        if any(p.search(text) for p in patterns):
            return
        fail(
            "%s/%s declares no %s locale filter — every values-* this app merges, "
            "its own and its libraries', reaches the device and wins over values/ "
            "on a phone set to that language. Add one of: %s"
            % (module, name, rule["locale"], ", ".join(rule["declaration_patterns"]))
        )
        return
    fail("%s is declared `base_language: en` but owns no build.gradle[.kts] — "
         "only a module that packages an APK can filter locales, so this entry "
         "should read `base_language: host`." % module)


def check_module(repo, module, locales, policy, fail):
    res = os.path.join(repo, module, "src", "main", "res")
    quantities = set(policy["locale"]["plural_quantities"])

    for filename in translatable_files(res, policy):
        base = load(os.path.join(res, "values", filename))
        check_file(res, module, filename, base, locales, quantities, fail)


def check_file(res, module, filename, base, locales, quantities, fail):
    for locale in locales:
        values_dir = "values" if locale == "default" else "values-" + locale
        path = os.path.join(res, values_dir, filename)
        if not os.path.exists(path):
            fail(
                "%s: no %s/%s — %d strings render in English on a %s device"
                % (module, values_dir, filename, len(base), locale)
            )
            continue
        loc = load(path)
        # Every message below names the FILE, not just the directory: with more
        # than one translatable file per module, "values-es" alone does not say
        # where to go and fix it.
        where = "%s/%s" % (values_dir, filename)

        for name in sorted(set(base) - set(loc)):
            fail("%s %s: missing key `%s` (%s)"
                 % (module, where, name, repr(resource_text(base[name])[:60])))

        for name in sorted(set(loc) - set(base)):
            fail("%s %s: key `%s` translates nothing — no such key in values/"
                 % (module, where, name))

        for name in sorted(set(base) & set(loc)):
            src, dst = base[name], loc[name]
            if src.tag != dst.tag:
                fail("%s %s: `%s` is <%s> in values/ but <%s> here"
                     % (module, where, name, src.tag, dst.tag))
                continue

            if src.tag == "string":
                pairs = [("", src, dst)]
            elif src.tag == "plurals":
                have = {i.get("quantity") for i in dst}
                for missing in sorted(quantities - have):
                    fail("%s %s: plurals `%s` has no quantity=\"%s\" — %s needs it"
                         % (module, where, name, missing, locale))
                by_src = {i.get("quantity"): i for i in src}
                # `other` carries the argument in every quantity Android will
                # pick, so it is the English item every translated item is
                # compared against.
                model = by_src.get("other")
                if model is None:
                    model = src[0] if len(src) else None
                pairs = [(" quantity=%s" % i.get("quantity"), model, i)
                         for i in dst if model is not None]
            else:
                pairs = list(zip(
                    (" item[%d]" % i for i in range(len(list(dst)))),
                    list(src), list(dst),
                ))

            for part, s, d in pairs:
                if s is None or d is None:
                    continue
                s_text, d_text = resource_text(s), resource_text(d)
                if signature(s_text) != signature(d_text):
                    fail("%s %s: `%s`%s format specifiers changed — values/ has %s, this has %s"
                         % (module, where, name, part, describe(s_text), describe(d_text)))
                # aapt rejects a bare apostrophe outside a quoted run, and it is
                # the single easiest thing to get wrong writing Spanish.
                if re.search(r"(?<!\\)'", d_text) and not d_text.startswith('"'):
                    fail("%s %s: `%s`%s has an unescaped apostrophe — aapt will reject it; write \\'"
                         % (module, where, name, part))


def main():
    with open(POLICY) as handle:
        policy = json.load(handle)
    modules = {k: v for k, v in policy["modules"].items() if not k.startswith("_")}

    failures = []
    fail = failures.append

    discovered = discover(REPO)

    for module in sorted(discovered - set(modules)):
        fail(
            "%s owns a values/strings.xml but is not declared in 1_cicd/src/i18n-policy.json. "
            "Add it with `base_language` (\"en\" if the module packages an APK, \"host\" if it is "
            "a library whose locales the packaging app filters out) or `exempt` "
            "(one sentence saying why the base-language rule does not bite)." % module
        )

    for module in sorted(set(modules) - discovered):
        fail("1_cicd/src/i18n-policy.json declares %s, which owns no "
             "src/main/res/values/strings.xml — drop the entry." % module)

    checked = 0
    filtered = 0
    for module in sorted(discovered & set(modules)):
        rule = modules[module]
        if rule.get("base_language") == "en":
            check_base_language(REPO, module, policy, fail)
            filtered += 1
        if "require" not in rule:
            continue
        check_module(REPO, module, rule["require"], policy, fail)
        checked += 1

    if failures:
        print("i18n guard: %d problem(s)\n" % len(failures), file=sys.stderr)
        for line in failures:
            print("  FAIL  " + line, file=sys.stderr)
        print(
            "\nEnglish is the fleet's base language. An app that does not filter "
            "locales at package time resolves to whatever language the phone asks "
            "for, from any values-* in its resource closure.",
            file=sys.stderr,
        )
        return 1

    print("i18n guard: %d module(s) filter to the %s base language, %d with a "
          "required translation, %d discovered."
          % (filtered, policy["base_language"]["locale"], checked, len(discovered)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
