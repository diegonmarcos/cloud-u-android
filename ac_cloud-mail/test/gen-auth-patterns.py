#!/usr/bin/env python3
"""Regenerate AuthPatterns.kt from the vendored pattern set. `--check` exits 1 when the file is stale."""
import json, os, sys
HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.join(HERE, "..", "core", "data")
pat = json.load(open(os.path.join(ROOT, "auth-patterns.json"), encoding="utf-8"))
out = os.path.join(ROOT, "src/main/kotlin/app/sterna/core/data/text/AuthPatterns.kt")

def lst(name, items):
    return f"    val {name}: List<String> = listOf(\n" + "".join(f'        "{x}",\n' for x in items) + "    )\n"

head = open(out, encoding="utf-8").read().split("internal object AuthPatterns {")[0]
src = head + "internal object AuthPatterns {\n" + lst("LINK_PHRASES", pat["link_phrases"]) + "\n" + lst("URL_TOKENS", pat["url_tokens"]) + "\n" + lst("CODE_SUBJECT_PHRASES", pat["code_subject_phrases"]) + "}\n"
if "--check" in sys.argv:
    sys.exit(0 if open(out, encoding="utf-8").read() == src else 1)
open(out, "w", encoding="utf-8").write(src)
