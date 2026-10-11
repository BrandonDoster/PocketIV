"""Regenerate app/src/main/assets/species.tsv and moves.tsv from the PvPoke game master.

uv run tools/gen_species.py
species.tsv columns: name, dex, family (candy name, A-Z only), atk, def, sta, types (comma separated)
moves.tsv columns: name, type
"""
import json
import pathlib
import re
import urllib.request

URL = "https://raw.githubusercontent.com/pvpoke/pvpoke/master/src/data/gamemaster.min.json"
OUT = pathlib.Path(__file__).resolve().parent.parent / "app/src/main/assets/species.tsv"

gm = json.load(urllib.request.urlopen(URL))
rows = {}
for p in gm["pokemon"]:
    tags = p.get("tags", [])
    if "shadow" in tags or "mega" in tags or p["speciesId"].endswith("_shadow"):
        continue  # shadow = same stats; megas never show on the appraisal screen
    fam = p.get("family", {}).get("id", "").removeprefix("FAMILY_") or p["speciesName"].split(" (")[0].upper()
    fam = re.sub("[^A-Z]", "", fam)  # legendaries have no family; candy is named after the species
    b = p["baseStats"]
    types = ",".join(t for t in p["types"] if t != "none")
    rows[p["speciesName"]] = f'{p["speciesName"]}\t{p["dex"]}\t{fam}\t{b["atk"]}\t{b["def"]}\t{b["hp"]}\t{types}'
OUT.parent.mkdir(parents=True, exist_ok=True)
OUT.write_text("\n".join(rows.values()) + "\n", encoding="utf-8")
print(len(rows), "species ->", OUT)

moves = {m["name"]: f'{m["name"]}\t{m["type"]}' for m in gm["moves"]}
(OUT.parent / "moves.tsv").write_text("\n".join(moves.values()) + "\n", encoding="utf-8")
print(len(moves), "moves")
