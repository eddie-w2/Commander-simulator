"""Packages the Forge resources the on-device simulator needs into one zip.

Layout of the output zip:
  VERSION                 Forge commit
  cardindex.tsv           lowercase card/face name <TAB> path inside cardsfolder.zip
  cardsfolder.zip         every card script (the app extracts only the ones a simulation needs)
  editions/ tokenscripts/ blockdata/ ai/ lists/ (type lists, keyword lists, AI card lists)
  languages/en-US.properties
"""
import io
import os
import sys
import zipfile

res, out, sha = sys.argv[1], sys.argv[2], sys.argv[3]
# Bump when the bundle's contents change for the same Forge commit, so phones unpack it again.
BUNDLE_REV = 2
os.makedirs(os.path.dirname(out), exist_ok=True)

cards_dir = os.path.join(res, "cardsfolder")
index = {}
cards_buf = io.BytesIO()
count = 0
with zipfile.ZipFile(cards_buf, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as cz:
    for letter in sorted(os.listdir(cards_dir)):
        d = os.path.join(cards_dir, letter)
        if not os.path.isdir(d) or letter in ("rebalanced", "upcoming"):
            continue
        for f in sorted(os.listdir(d)):
            if not f.endswith(".txt"):
                continue
            path = os.path.join(d, f)
            rel = f"{letter}/{f}"
            with open(path, "rb") as fh:
                data = fh.read()
            cz.writestr(rel, data)
            count += 1
            names = [l[5:].strip() for l in data.decode("utf-8", "replace").splitlines() if l.startswith("Name:")]
            for n in names:
                index.setdefault(n.lower(), rel)
            if len(names) >= 2:
                index.setdefault(" // ".join(names[:2]).lower(), rel)

with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as z:
    z.writestr("VERSION", f"{sha}-r{BUNDLE_REV}")
    z.writestr("cardindex.tsv", "".join(f"{k}\t{v}\n" for k, v in sorted(index.items())))
    z.writestr(zipfile.ZipInfo("cardsfolder.zip"), cards_buf.getvalue(), compress_type=zipfile.ZIP_STORED)
    for folder in ("editions", "tokenscripts", "blockdata", "ai"):
        base = os.path.join(res, folder)
        for dirpath, _, files in os.walk(base):
            for f in files:
                p = os.path.join(dirpath, f)
                z.write(p, os.path.relpath(p, res))
    z.write(os.path.join(res, "languages", "en-US.properties"), "languages/en-US.properties")
    for f in ("TypeLists.txt", "NonStackingKWList.txt", "ai-card-roles.txt", "commander-bracket-combos.txt", "ai-commander-combos.txt"):
        z.write(os.path.join(res, "lists", f), f"lists/{f}")

print(f"Packaged {count} card scripts, {len(index)} names -> {out} ({os.path.getsize(out) / 1e6:.1f} MB)")
