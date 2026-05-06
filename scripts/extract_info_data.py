"""Extract sprnames[], states[], and mobjinfo[] from info.c into a
generated Java helper class doom.play.InfoData.

Run once at project setup; regenerate if info.c changes (it won't —
it is a frozen reference file).

The C state_t.action union has three function-pointer slots (acv,
acp1, acp2). Java has no union, so each State row carries TWO typed
fields:
  - `action: Consumer<Mobj>`             — for mobj-AI A_* (acp1)
  - `actionPsp: BiConsumer<Player,Pspdef>` — for weapon-action A_* (acp2)

This script binds each C A_X to its Java method-reference based on
ACTION_BINDINGS below. p_pspr.c and p_enemy.c are both ported, so
all 60+ A_* names resolve.
"""

from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "reference" / "linuxdoom-1.10" / "linuxdoom-1.10" / "info.c"
OUT = ROOT / "src" / "main" / "java" / "doom" / "play" / "InfoData.java"

TEXT = SRC.read_text()


def slurp_array(name: str) -> str:
    """Return everything inside the outer braces of `<type> <name>[...] = { ... };`."""
    pattern = rf"\b{name}\b\s*\[[^\]]*\]\s*=\s*\{{"
    m = re.search(pattern, TEXT)
    if not m:
        raise ValueError(f"didn't find {name}[]")
    start = m.end()
    depth = 1
    i = start
    while i < len(TEXT):
        c = TEXT[i]
        if c == "{":
            depth += 1
        elif c == "}":
            depth -= 1
            if depth == 0:
                return TEXT[start:i]
        i += 1
    raise ValueError(f"unterminated {name}[]")


# ---------- sprnames[] ----------
def extract_sprnames() -> list[str]:
    body = slurp_array("sprnames")
    return re.findall(r'"([^"]+)"', body)


# ---------- states[] ----------
STATE_FIELD_RE = re.compile(
    r"\{"
    r"\s*([A-Za-z0-9_]+)"           # sprite (SPR_X)
    r"\s*,\s*([0-9xa-fA-F]+)"       # frame
    r"\s*,\s*(-?\d+)"               # tics
    r"\s*,\s*\{([^}]*)\}"           # action {A_Name} or {NULL}
    r"\s*,\s*([A-Za-z0-9_]+)"       # nextstate
    r"\s*,\s*(\S+)"                 # misc1
    r"\s*,\s*(\S+?)"                # misc2 (non-greedy)
    r"\s*\}"
)


def translate_token(tok: str) -> str:
    """Translate a single C token (S_X, sfx_X, SPR_X, MT_X, MF_X, FRACUNIT,
    bare number) into Java."""
    tok = tok.strip()
    if tok == "NULL":
        return "null"
    if re.fullmatch(r"-?\d+", tok):
        return tok
    if re.fullmatch(r"0x[0-9a-fA-F]+", tok):
        return tok
    if tok == "FRACUNIT":
        return "Fixed.FRACUNIT"
    if tok.startswith(("S_", "SPR_", "MT_")):
        return f"Info.{tok}"
    if tok.startswith("sfx_"):
        return f"Sounds.{tok}"
    if tok.startswith("MF_"):
        return f"Mobj.{tok}"
    return tok  # last resort — likely a bug, but leave it


def translate_expr(expr: str) -> str:
    """Translate an expression (numbers, identifiers, operators) token-by-token."""
    expr = expr.strip()
    parts = re.split(r"(\b)", expr)
    out = []
    for p in parts:
        if not p:
            continue
        if re.match(r"^[A-Za-z0-9_]+$", p):
            out.append(translate_token(p))
        else:
            out.append(p)
    return "".join(out)


# A_X (C name) → ("PMobj"|"PEnemy"|"PPspr", "aX") + signature kind.
# kind == "mobj"  → method reference goes in `action`    (Consumer<Mobj>)
# kind == "pspr"  → method reference goes in `actionPsp` (BiConsumer<Player, Pspdef>)
ACTION_BINDINGS = {
    # --- pspr signature (player_t*, pspdef_t*) — p_pspr.c + 3 in p_enemy.c ---
    "A_WeaponReady":   ("PPspr",  "aWeaponReady",   "pspr"),
    "A_Lower":         ("PPspr",  "aLower",         "pspr"),
    "A_Raise":         ("PPspr",  "aRaise",         "pspr"),
    "A_Punch":         ("PPspr",  "aPunch",         "pspr"),
    "A_ReFire":        ("PPspr",  "aReFire",        "pspr"),
    "A_CheckReload":   ("PPspr",  "aCheckReload",   "pspr"),
    "A_FirePistol":    ("PPspr",  "aFirePistol",    "pspr"),
    "A_Light0":        ("PPspr",  "aLight0",        "pspr"),
    "A_Light1":        ("PPspr",  "aLight1",        "pspr"),
    "A_Light2":        ("PPspr",  "aLight2",        "pspr"),
    "A_FireShotgun":   ("PPspr",  "aFireShotgun",   "pspr"),
    "A_FireShotgun2":  ("PPspr",  "aFireShotgun2",  "pspr"),
    "A_FireCGun":      ("PPspr",  "aFireCGun",      "pspr"),
    "A_GunFlash":      ("PPspr",  "aGunFlash",      "pspr"),
    "A_FireMissile":   ("PPspr",  "aFireMissile",   "pspr"),
    "A_Saw":           ("PPspr",  "aSaw",           "pspr"),
    "A_FirePlasma":    ("PPspr",  "aFirePlasma",    "pspr"),
    "A_BFGsound":      ("PPspr",  "aBFGsound",      "pspr"),
    "A_FireBFG":       ("PPspr",  "aFireBFG",       "pspr"),
    "A_OpenShotgun2":  ("PEnemy", "aOpenShotgun2",  "pspr"),
    "A_LoadShotgun2":  ("PEnemy", "aLoadShotgun2",  "pspr"),
    "A_CloseShotgun2": ("PEnemy", "aCloseShotgun2", "pspr"),

    # --- mobj signature (mobj_t*) — p_enemy.c (+ A_BFGSpray in p_pspr.c) ---
    "A_BFGSpray":     ("PPspr",  "aBFGSpray",     "mobj"),

    "A_Look":         ("PEnemy", "aLook",         "mobj"),
    "A_Chase":        ("PEnemy", "aChase",        "mobj"),
    "A_FaceTarget":   ("PEnemy", "aFaceTarget",   "mobj"),
    "A_Fall":         ("PEnemy", "aFall",         "mobj"),
    "A_KeenDie":      ("PEnemy", "aKeenDie",      "mobj"),

    "A_PosAttack":    ("PEnemy", "aPosAttack",    "mobj"),
    "A_SPosAttack":   ("PEnemy", "aSPosAttack",   "mobj"),
    "A_CPosAttack":   ("PEnemy", "aCPosAttack",   "mobj"),
    "A_CPosRefire":   ("PEnemy", "aCPosRefire",   "mobj"),
    "A_SpidRefire":   ("PEnemy", "aSpidRefire",   "mobj"),
    "A_BspiAttack":   ("PEnemy", "aBspiAttack",   "mobj"),

    "A_TroopAttack":  ("PEnemy", "aTroopAttack",  "mobj"),
    "A_SargAttack":   ("PEnemy", "aSargAttack",   "mobj"),
    "A_HeadAttack":   ("PEnemy", "aHeadAttack",   "mobj"),
    "A_CyberAttack":  ("PEnemy", "aCyberAttack",  "mobj"),
    "A_BruisAttack":  ("PEnemy", "aBruisAttack",  "mobj"),

    "A_SkelMissile":  ("PEnemy", "aSkelMissile",  "mobj"),
    "A_Tracer":       ("PEnemy", "aTracer",       "mobj"),
    "A_SkelWhoosh":   ("PEnemy", "aSkelWhoosh",   "mobj"),
    "A_SkelFist":     ("PEnemy", "aSkelFist",     "mobj"),

    "A_VileChase":    ("PEnemy", "aVileChase",    "mobj"),
    "A_VileStart":    ("PEnemy", "aVileStart",    "mobj"),
    "A_StartFire":    ("PEnemy", "aStartFire",    "mobj"),
    "A_FireCrackle":  ("PEnemy", "aFireCrackle",  "mobj"),
    "A_Fire":         ("PEnemy", "aFire",         "mobj"),
    "A_VileTarget":   ("PEnemy", "aVileTarget",   "mobj"),
    "A_VileAttack":   ("PEnemy", "aVileAttack",   "mobj"),

    "A_FatRaise":     ("PEnemy", "aFatRaise",     "mobj"),
    "A_FatAttack1":   ("PEnemy", "aFatAttack1",   "mobj"),
    "A_FatAttack2":   ("PEnemy", "aFatAttack2",   "mobj"),
    "A_FatAttack3":   ("PEnemy", "aFatAttack3",   "mobj"),

    "A_SkullAttack":  ("PEnemy", "aSkullAttack",  "mobj"),
    "A_PainAttack":   ("PEnemy", "aPainAttack",   "mobj"),
    "A_PainDie":      ("PEnemy", "aPainDie",      "mobj"),

    "A_Scream":       ("PEnemy", "aScream",       "mobj"),
    "A_XScream":      ("PEnemy", "aXScream",      "mobj"),
    "A_Pain":         ("PEnemy", "aPain",         "mobj"),
    "A_Explode":      ("PEnemy", "aExplode",      "mobj"),
    "A_BossDeath":    ("PEnemy", "aBossDeath",    "mobj"),

    "A_Hoof":         ("PEnemy", "aHoof",         "mobj"),
    "A_Metal":        ("PEnemy", "aMetal",        "mobj"),
    "A_BabyMetal":    ("PEnemy", "aBabyMetal",    "mobj"),

    "A_BrainAwake":   ("PEnemy", "aBrainAwake",   "mobj"),
    "A_BrainPain":    ("PEnemy", "aBrainPain",    "mobj"),
    "A_BrainScream":  ("PEnemy", "aBrainScream",  "mobj"),
    "A_BrainExplode": ("PEnemy", "aBrainExplode", "mobj"),
    "A_BrainDie":     ("PEnemy", "aBrainDie",     "mobj"),
    "A_BrainSpit":    ("PEnemy", "aBrainSpit",    "mobj"),
    "A_SpawnSound":   ("PEnemy", "aSpawnSound",   "mobj"),
    "A_SpawnFly":     ("PEnemy", "aSpawnFly",     "mobj"),

    "A_PlayerScream": ("PEnemy", "aPlayerScream", "mobj"),
}


def resolve_action(c_action: str) -> tuple[str, str]:
    """Translate the C action token into (action_expr, actionPsp_expr).

    Both are Java expressions returning the right field value (`null`
    or `Class::method`) for the State 8-arg constructor.
    """
    tok = c_action.strip()
    if tok in ("NULL", "0"):
        return "null", "null"
    binding = ACTION_BINDINGS.get(tok)
    if binding is None:
        raise ValueError(f"unknown action token {tok!r} — extend ACTION_BINDINGS")
    cls, method, kind = binding
    ref = f"{cls}::{method}"
    if kind == "mobj":
        return ref, "null"
    return "null", ref


def extract_states() -> list[tuple[str, str, str, str, str, str, str, str]]:
    body = slurp_array("states")
    states = []
    for m in STATE_FIELD_RE.finditer(body):
        sprite, frame, tics, action, nextstate, misc1, misc2 = m.groups()
        action_expr, action_psp_expr = resolve_action(action)
        states.append((
            translate_token(sprite),
            translate_expr(frame),
            tics,                       # tics is a small signed int
            action_expr,
            action_psp_expr,
            translate_token(nextstate),
            translate_expr(misc1),
            translate_expr(misc2),
        ))
    return states


# ---------- mobjinfo[] ----------
def extract_mobjinfos() -> list[list[str]]:
    body = slurp_array("mobjinfo")
    entries = []
    depth = 0
    i = 0
    start = -1
    while i < len(body):
        c = body[i]
        if c == "{":
            if depth == 0:
                start = i + 1
            depth += 1
        elif c == "}":
            depth -= 1
            if depth == 0:
                inner = body[start:i]
                entries.append(inner)
        i += 1

    parsed = []
    for entry in entries:
        cleaned = re.sub(r"//[^\n]*", "", entry)
        fields = []
        depth = 0
        cur = []
        for ch in cleaned:
            if ch == "{":
                depth += 1
            elif ch == "}":
                depth -= 1
            if ch == "," and depth == 0:
                fields.append("".join(cur).strip())
                cur = []
            else:
                cur.append(ch)
        last = "".join(cur).strip()
        if last:
            fields.append(last)

        if len(fields) != 23:
            raise ValueError(f"mobjinfo entry has {len(fields)} fields, want 23:\n{entry}")

        parsed.append([translate_expr(f) for f in fields])
    return parsed


# ---------- emit ----------
def emit():
    sprnames = extract_sprnames()
    states = extract_states()
    mobjs = extract_mobjinfos()

    if len(sprnames) != 138:
        raise ValueError(f"sprnames length {len(sprnames)}, want 138")
    if len(states) != 967:
        raise ValueError(f"states length {len(states)}, want 967")
    if len(mobjs) != 137:
        raise ValueError(f"mobjinfo length {len(mobjs)}, want 137")

    lines = []
    lines.append("// AUTOGENERATED by scripts/extract_info_data.py from")
    lines.append("// reference/linuxdoom-1.10/info.c. Do not edit by hand.")
    lines.append("//")
    lines.append("// Each State row binds either `action` (Consumer<Mobj>, for")
    lines.append("// monster-AI A_* in p_enemy.c / a few in p_pspr.c) or")
    lines.append("// `actionPsp` (BiConsumer<Player, Pspdef>, for weapon A_*)")
    lines.append("// — the C state_t.action union mapped onto two parallel")
    lines.append("// typed fields (deviation 25). At most one is non-null.")
    lines.append("package doom.play;")
    lines.append("")
    lines.append("import doom.fixed.Fixed;")
    lines.append("import doom.sound.Sounds;")
    lines.append("")
    lines.append("final class InfoData {")
    lines.append("    private InfoData() {}")
    lines.append("")

    lines.append("    static final String[] sprnames = {")
    chunk = ""
    for i, s in enumerate(sprnames):
        token = f'"{s}"'
        if i < len(sprnames) - 1:
            token += ","
        if len(chunk) + len(token) + 1 > 76:
            lines.append("        " + chunk.rstrip())
            chunk = ""
        chunk += token + " "
    if chunk.strip():
        lines.append("        " + chunk.rstrip())
    lines.append("    };")
    lines.append("")

    lines.append("    static final State[] states = {")
    for i, st in enumerate(states):
        sprite, frame, tics, action, action_psp, nextstate, misc1, misc2 = st
        comma = "," if i < len(states) - 1 else ""
        lines.append(
            f"        new State({sprite}, {frame}, {tics}, {action}, "
            f"{action_psp}, {nextstate}, {misc1}, {misc2}){comma}")
    lines.append("    };")
    lines.append("")

    lines.append("    static final MobjInfo[] mobjinfo = {")
    for i, mi in enumerate(mobjs):
        comma = "," if i < len(mobjs) - 1 else ""
        joined = ", ".join(mi)
        lines.append(f"        new MobjInfo({joined}){comma}")
    lines.append("    };")
    lines.append("}")

    OUT.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"wrote {OUT}: {len(states)} states, {len(mobjs)} mobjinfo, {len(sprnames)} sprnames")


if __name__ == "__main__":
    emit()
