"""The app families of the fleet (#1335), read from 1_cicd/src/data/app-families.json: ONE declaration of the folder prefixes
(aa_ SuperApp, ab_ shared libs, ac_ the Cloud app family, ad_ the Lnktree app family), of how each family names its applications
(build.json::name pattern) and of how a fleet id is derived from a folder. Every guard that used to spell a[abc]_ or ac_cloud- reads it here."""
import json
import os
import re

DATA = "1_cicd/src/data/app-families.json"


def load(root):
    with open(os.path.join(root, DATA), encoding="utf-8") as f:
        return json.load(f)


def prefix_class(root):
    """Regex character class of the declared folder prefixes' second letter: a[abcd]_."""
    letters = "".join(p[1] for p in load(root)["folder_prefixes"])
    return "a[%s]_" % letters


def top_dirs(root):
    """Top-level directories that carry one of the declared folder prefixes."""
    cfg = load(root)
    return sorted(d for d in os.listdir(root)
                  if os.path.isdir(os.path.join(root, d)) and any(d.startswith(p) for p in cfg["folder_prefixes"]))


def family_of(root, dirname):
    for fam in load(root)["families"]:
        if dirname.startswith(fam["folder_prefix"]):
            return fam
    return None


def fleet_id(root, dirname):
    """The fleet id of an app folder: the folder without its family's strip prefix (lower-cased when the family says so)."""
    fam = family_of(root, dirname)
    if fam is None:
        return dirname
    out = dirname
    for strip in fam.get("id_strip", []):
        if out.startswith(strip):
            out = out[len(strip):]
            break
    return out.lower() if fam.get("lowercase_id") else out


def folder_of(root, member):
    """The app folder of a fleet member id (inverse of fleet_id), or None."""
    for d in top_dirs(root):
        if os.path.isfile(os.path.join(root, d, "build.json")) and fleet_id(root, d) == member:
            return d
    return None


def folder_of_name(root, name):
    """The app folder whose build.json::name is `name` (the id an extapp: target carries), or None."""
    for d in top_dirs(root):
        p = os.path.join(root, d, "build.json")
        if os.path.isfile(p):
            try:
                if json.load(open(p, encoding="utf-8")).get("name") == name:
                    return d
            except ValueError:
                pass
    return None
