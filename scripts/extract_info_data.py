"""Extract sprnames[], states[], and mobjinfo[] from info.c into a
generated Java helper class doom.play.InfoData.

Run once at project setup; regenerate if info.c changes (it won't —
it is a frozen reference file).

The C action function pointers (A_WeaponReady, A_Punch, ...) all
become `null` in the State entries because none of the A_* functions
are ported yet (they live in p_pspr.c and p_enemy.c). When those
modules port, this script rewrites itself to bind method references.
For now the data tables are correct except for the action field.
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


def extract_states() -> list[tuple[str, str, str, str, str, str, str]]:
    body = slurp_array("states")
    states = []
    for m in STATE_FIELD_RE.finditer(body):
        sprite, frame, tics, action, nextstate, misc1, misc2 = m.groups()
        states.append((
            translate_token(sprite),
            translate_expr(frame),
            tics,                       # tics is a small signed int
            "null",                     # action — deferred
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
    lines.append("// State.action is null for every entry: the A_* function")
    lines.append("// pointers (p_pspr.c, p_enemy.c) aren't ported yet. Once")
    lines.append("// they are, regenerate this file with action bindings.")
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
        sprite, frame, tics, action, nextstate, misc1, misc2 = st
        comma = "," if i < len(states) - 1 else ""
        lines.append(f"        new State({sprite}, {frame}, {tics}, {action}, "
                     f"{nextstate}, {misc1}, {misc2}){comma}")
    lines.append("    };")
    lines.append("")

    lines.append("    static final MobjInfo[] mobjinfo = {")
    for i, mi in enumerate(mobjs):
        comma = "," if i < len(mobjs) - 1 else ""
        joined = ", ".join(mi)
        lines.append(f"        new MobjInfo({joined}){comma}")
    lines.append("    };")
    lines.append("}")

    OUT.write_text("\n".join(lines) + "\n")
    print(f"wrote {OUT}: {len(states)} states, {len(mobjs)} mobjinfo, {len(sprnames)} sprnames")


if __name__ == "__main__":
    emit()
