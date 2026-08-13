#!/usr/bin/env python3
"""Turn a compact roster table into clubs.json club objects.

Why this exists: Série C's 17 new clubs need ~370 players. Hand-tuning 28 numbers per player
would mean inventing most of them while presenting the result as researched. Here the NAMES,
POSITIONS and AGES are researched per club; the numbers are derived from a per-club strength
target so the tier lands on its band by construction (spec 5.2).

Mean preservation is the whole trick: each position's offset vector sums to zero, so a player's
mean over all 28 values equals their quality q. The club's top-11 mean therefore equals the mean
of its 11 highest q values, which is exactly what the calibration test measures.

Usage:  python3 tools/seed/generate_squad.py roster.json
Output: one compact JSON object per club on its own line, ready to splice into clubs.json.
"""
import json
import sys

# The 22 authored attributes, in SeedAttributes field order.
ATTRS = [
    "drible", "cruzamento", "cabeceio", "marcacao", "bolaParada", "chuteLonge",
    "visao", "posicionamento", "decisao", "compostura", "concentracao", "determinacao",
    "agressividade", "lideranca",
    "aceleracao", "forca", "resistencia", "equilibrio", "agilidade",
    "posicionamentoGk", "saidaDeGol", "jogoComOsPes",
]
ANCHORS = ["pace", "finishing", "passing", "tackling", "goalkeeping", "stamina"]
KEYS = ANCHORS + ATTRS  # all 28, the set the calibration measure averages over

# Per-position offsets from the player's quality q. Each vector is normalised to sum to zero
# below, so the 28-value mean stays exactly q.
PROFILES = {
    "GK": {"goalkeeping": 34, "posicionamentoGk": 32, "saidaDeGol": 28, "jogoComOsPes": 8,
           "concentracao": 14, "compostura": 12, "lideranca": 8, "forca": 10, "equilibrio": 6,
           "pace": -18, "finishing": -30, "tackling": -20, "marcacao": -22, "drible": -22,
           "cruzamento": -30, "cabeceio": -14, "bolaParada": -24, "chuteLonge": -12,
           "aceleracao": -16, "agilidade": -4, "agressividade": -12},
    "DF": {"tackling": 22, "marcacao": 24, "cabeceio": 18, "forca": 16, "posicionamento": 14,
           "concentracao": 10, "determinacao": 8, "agressividade": 10, "lideranca": 6,
           "equilibrio": 8,
           "goalkeeping": -34, "posicionamentoGk": -34, "saidaDeGol": -34, "jogoComOsPes": -20,
           "finishing": -20, "drible": -14, "cruzamento": -6, "chuteLonge": -8, "visao": -6},
    "MF": {"passing": 20, "visao": 20, "decisao": 14, "resistencia": 16, "compostura": 12,
           "drible": 10, "bolaParada": 10, "chuteLonge": 10, "concentracao": 8, "posicionamento": 8,
           "marcacao": 6, "determinacao": 6, "agilidade": 6,
           "goalkeeping": -34, "posicionamentoGk": -34, "saidaDeGol": -34, "jogoComOsPes": -18,
           "cabeceio": -10, "forca": -8, "finishing": -4},
    "FW": {"finishing": 26, "pace": 20, "aceleracao": 20, "drible": 18, "agilidade": 12,
           "compostura": 10, "cabeceio": 8, "chuteLonge": 8, "cruzamento": 6, "equilibrio": 6,
           "goalkeeping": -34, "posicionamentoGk": -34, "saidaDeGol": -34, "jogoComOsPes": -18,
           "tackling": -22, "marcacao": -24, "forca": -4, "resistencia": -4, "visao": -4},
}

# Squad role -> quality offset from the club's target. Tuned so the top 11 average the target.
ROLE_DELTA = {"starter": 2, "rotation": -3, "fringe": -8}


def _normalised(profile):
    """Return a full 28-key offset map that sums to zero."""
    full = {k: profile.get(k, 0) for k in KEYS}
    drift = sum(full.values()) / len(KEYS)
    return {k: v - drift for k, v in full.items()}


def build_player(entry, target):
    q = target + ROLE_DELTA[entry["role"]]
    offsets = _normalised(PROFILES[entry["group"]])
    values = {k: max(1, min(99, round(q + offsets[k]))) for k in KEYS}
    return {
        "name": entry["name"],
        "position": entry["position"],
        "age": entry["age"],
        "nationality": entry.get("nationality", "Brasil"),
        "positions": entry["positions"],
        "roles": entry["roles"],
        **{k: values[k] for k in ANCHORS},
        "attributes": {k: values[k] for k in ATTRS},
    }


def top11_mean(players):
    means = sorted((sum(p[k] for k in ANCHORS) + sum(p["attributes"][k] for k in ATTRS)) / len(KEYS)
                   for p in players)
    return sum(means[-11:]) / 11


def build_club(spec):
    players = [build_player(e, spec["target"]) for e in spec["roster"]]
    realised = top11_mean(players)
    if abs(realised - spec["target"]) > 1.0:
        raise SystemExit(
            f"{spec['shortName']}: top-11 mean {realised:.2f} misses target {spec['target']} "
            f"by more than 1.0 — adjust the starter/rotation/fringe split")
    club = {k: spec[k] for k in (
        "name", "shortName", "commonName", "nickname", "stadium", "city", "state",
        "climateZone", "stadiumCapacity", "primaryColor", "secondaryColor",
        "formation", "mentality", "pressing", "marking")}
    if spec.get("style"):
        club["style"] = spec["style"]
    club["players"] = players
    return club, realised


def main():
    specs = json.load(open(sys.argv[1], encoding="utf-8"))
    for spec in specs:
        club, realised = build_club(spec)
        print(f"// {club['shortName']} top-11 = {realised:.2f}", file=sys.stderr)
        # One player per line, matching clubs.json's existing style.
        head = json.dumps({k: v for k, v in club.items() if k != "players"},
                          ensure_ascii=False)[:-1]
        lines = ",\n".join("        " + json.dumps(p, ensure_ascii=False) for p in club["players"])
        print(head + ', "players": [\n' + lines + "\n      ]}")


if __name__ == "__main__":
    main()
