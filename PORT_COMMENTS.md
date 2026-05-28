# Port Comments

This document collects observations, deviations from plan, and
notes about the *experience* of porting `linuxdoom-1.10` to
Java / JavaFX. It complements:

- [`PORT_STATUS.md`](PORT_STATUS.md) — per-module completion tracker
- [`CLAUDE.md`](CLAUDE.md) — live agent instructions (translation rules)

Add new entries at the bottom with a short heading; keep them
brief — the value is in capturing *why* things turned out
differently than planned, not exhaustive history.

---

## Model routing scheme — planned, never used

An early draft of `CLAUDE.md` (§8, since removed) sketched a
multi-tier model-routing scheme:

- **Cheap tier** (Haiku 4.5, Codestral) for trivial translations,
  data tables, Javadoc passes.
- **Mid tier** (Sonnet 4.6) as the default for `p_*.c` playsim
  code and most wiring.
- **Heavy tier** (Opus 4.7) for the hard renderer files
  (`r_draw.c`, `r_segs.c`, `r_things.c`), demo-compatibility
  bugs, and architectural decisions.
- A per-module classification table (§9 of the old `CLAUDE.md`)
  to pick a starting tier, plus an escalation ladder.

In practice none of this was used. The entire port was developed
on **Claude Opus 4.7 only**.

Two reasons it stayed that way:

1. *Coordination overhead.* Routing between tiers per module
   means human or tooling glue to decide which model gets which
   file, when to escalate, and how to feed prior context to the
   next tier. That glue would have cost more time than the
   model-cost savings recovered.
2. *Reliability.* Opus produced byte-accurate translations in
   one shot for the modules attempted, including the renderer
   files originally marked **Hard**. Switching to a cheaper
   model for "easy" files would have introduced inconsistent
   review burden — every cheap-tier output would have needed
   re-verification on a mid- or heavy-tier pass, which is the
   same as just doing it on Opus once.

`CLAUDE.md` §8 and §9 (the classification table) were removed
during the public-release scrub; sections §10 onwards were
renumbered. The "escalate one model tier" step in §13 (now §11,
"When Stuck") was dropped at the same time.
