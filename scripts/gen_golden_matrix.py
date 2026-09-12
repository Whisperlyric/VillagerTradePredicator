# -*- coding: utf-8 -*-
"""Generates the cross-validation fixture consumed by PythonMatrixParityTest.

Runs the validated CLI predictor (refs/Villager-Trade-Calculator) for a matrix of
3 random seeds x 13 professions x 2 offsets, capturing every level's predicted offers
as tokens identical to the Java test's format:

    book:<enchant>:<level>:<price>     for enchanted-book offers
    minecraft:<prof>/<level>/<entry>   for plain offers (entry = trade id suffix)
    RAW:<cli line>                     for anything the CLI prints differently
                                       (enchanted equipment etc.) — these cells end up
                                       outside the exact-match tier in the Java test

Usage:  python scripts/gen_golden_matrix.py
Output: src/test/resources/golden_matrix.json
"""
import json
import os
import re
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
PROJECT = os.path.dirname(HERE)
TOOL_DIR = os.path.normpath(os.path.join(PROJECT, "..", "refs", "Villager-Trade-Calculator"))
TOOL = os.path.join(TOOL_DIR, "villager_trade_predictor.py")
OUT = os.path.join(PROJECT, "src", "test", "resources", "golden_matrix.json")

SEEDS = [1234567890123456789, -4592854367812309587, 812345678901234567]
PROFESSIONS = ["armorer", "butcher", "cartographer", "cleric", "farmer", "fisherman",
               "fletcher", "leatherworker", "librarian", "mason", "shepherd",
               "toolsmith", "weaponsmith"]
OFFSETS = [0, 1]

LEVEL_RE = re.compile(r"^  Level (\d+):$")
LINE_RE = re.compile(r"^    (.+?)\s*$")
BOOK_RE = re.compile(r"^(\w+) (\d+) \((\d+) emeralds\)(?: \[treasure\])?$")

JAR = os.path.join(TOOL_DIR, "refs", "minecraft-merged-b5df1ea0fb-26.1.jar")


def build_pools():
    """Ordered full trade ids per (profession, level), resolved from the jar's tag files.

    The CLI prints only entry suffixes, but entry ids can live in other folders
    (e.g. minecraft:smith/2/emerald_bell inside the armorer tag) — the Java side
    compares full trade ids, so the mapping must come from the real pool data.
    The ORDER is significant: nextInt(pool) maps positions to entries, and 26.2
    reordered some pools relative to 26.1.
    """
    import zipfile
    pools = {}
    with zipfile.ZipFile(JAR) as z:
        for prof in PROFESSIONS:
            for level in range(1, 6):
                path = "%s/level_%d" % (prof, level)

                def read_values(ref, seen):
                    assert ref not in seen, "cyclic tag %s" % ref
                    seen.add(ref)
                    values = json.loads(z.read("data/minecraft/tags/villager_trade/%s.json" % ref))["values"]
                    out = []
                    for value in values:
                        if value.startswith("#"):
                            out.extend(read_values(value[1:].split(":", 1)[-1], seen))
                        else:
                            out.append(value)
                    return out

                ids = []
                for value in read_values(path, set()):
                    full = value if value.startswith("minecraft:") else "minecraft:" + value
                    suffix = value.split("/")[-1]
                    ids.append((suffix, full))
                suffixes = [s for s, _ in ids]
                assert len(suffixes) == len(set(suffixes)), "duplicate suffix in %s" % path
                pools[(prof, level)] = ids
    return pools


def capture(seed, profession, offset, pools):
    result = subprocess.run(
        [sys.executable, TOOL, str(seed), "-p", profession, "--offset", str(offset)],
        cwd=TOOL_DIR, capture_output=True, text=True, encoding="utf-8")
    if result.returncode != 0 and "Predicted trades" not in result.stdout:
        raise RuntimeError("CLI failed for %s/%s/%s: %s" % (seed, profession, offset, result.stderr[:400]))
    levels = {}
    current = None
    for line in result.stdout.splitlines():
        m = LEVEL_RE.match(line)
        if m:
            current = int(m.group(1))
            levels[current] = []
            continue
        m = LINE_RE.match(line)
        if m and current is not None:
            text = m.group(1)
            bm = BOOK_RE.match(text)
            if bm:
                levels[current].append("book:%s:%s:%s" % (bm.group(1), bm.group(2), bm.group(3)))
            elif text.startswith("enchanted "):
                # enchanted-equipment lines ("enchanted fishing_rod (+16 emeralds)")
                levels[current].append("RAW:" + text)
            else:
                mapping = dict(pools[(profession, current)])
                full = mapping.get(text)
                assert full is not None, "unknown entry %s in %s L%d" % (text, profession, current)
                levels[current].append(full)
    return levels


def main():
    pools = build_pools()
    ordered = {"%s/%d" % key: [full for _, full in ids] for key, ids in pools.items()}
    cells = []
    for seed in SEEDS:
        for profession in PROFESSIONS:
            for offset in OFFSETS:
                levels = capture(seed, profession, offset, pools)
                for level in range(1, 6):
                    tokens = levels.get(level, [])
                    cells.append({
                        "seed": seed,
                        "profession": profession,
                        "level": level,
                        "offset": offset,
                        "expected": tokens,
                    })
                    print("%s %s L%s off%s -> %d offers" % (seed, profession, level, offset, len(tokens)))
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8") as f:
        json.dump({"seeds": SEEDS, "pools": ordered, "cells": cells}, f, indent=1)
    print("wrote %d cells to %s" % (len(cells), OUT))


if __name__ == "__main__":
    main()
