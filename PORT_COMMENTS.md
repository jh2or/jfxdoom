# Port Comments

This document collects observations, deviations from plan, and
notes about the _experience_ of porting `linuxdoom-1.10` to
Java / JavaFX. It complements:

- [`PORT_STATUS.md`](PORT_STATUS.md) — per-module completion tracker
- [`CLAUDE.md`](CLAUDE.md) — live agent instructions (translation rules)

---

## General Experience [HUMAN TEXT]

- 99% of the port was planned and executed by Opus 4.7
- For the original prompt/conversation, see [`INITIAL_PROMPT.md`](INITIAL_PROMPT.md)
- Opus 4.7 was used, as it was new (at that time) and i wanted to test it's capabilities
- Opus 4.7 was running in "thinking mode" with "Effort" set to (at least) "extra high". There might be some instances where it was set to max, but i don't remember.
- The "subtask"/"model routing" was never used, despite it being in the CLAUDE.md. See this [explanation](#model-routing-scheme--planned-never-used)
- I own the Anthropic "Pro" subscription. That would allow me to execute 1-4 prompts, and then i would hit my 5-hour-limit. (In later stages of the port, i would hit 100% at about 2 prompt executions)
- Speaking of prompts: 90% were just "continue with the port". It was very important to let Opus create plan before starting the actual port work.
- Twice i tried to use token/api based usage when i hit my limit. I blew 20 euros in 5 seconds and i did get nothing in return. That happened in later stages of the port. Token usage must have been crazy.
- I often hit my weekly limit within 3-4 days. In total, the port required 5-6 weeks of time (see the commit history)
- I did not do any kind of thorough code review of the commits. Of course, sometimes i did look at the code, but i choose not to intervene (manually, or by forcing the LLM to rewrite parts)
- I choose Java as target language because i have been using Java professionally for over a decade. So if there would be some kind of issue, i would be able to debug/rewrite it mayself. I can also read/write a bit of C, but i am not very familiar with it.
- I only have a superficial understanding of the Doom source code. Most of it by watching Doom videos made by the youtube user "decino".
- JavaFX as "I/O layer" was chosen because i hoped it would deliver the whole "package": usable keyboard support, decent drawing performance and sound/MIDI support.
- It was the right decision by the original Opus planning to keep the render engine "as-is" and not to use more complex JavaFX capabilities. That way, the port feels "original", and any kind of very expensive render engine rewrite could be avoided.
- In general, the port needed minimal user interaction to succeed. Almost all of it happened during the test/verify phase at the end of the whole port process. See the next [section](#required-human-interaction-human-text)

## Required Human Interaction [HUMAN TEXT]

- At some point in the middle of the port Opus got stuck while executing a test. A timeout was set unnecessarily high (and was increased with each "fail"). I stepped in and used visualvm to identify a condition that caused an endless loop. I pointed Opus at the right direction and the issue was fixed
- Up to the tag "first*playtest", i did not peek into the game. When i did, the game was \_almost* playable. I added some reference screenshots and described the visual glitches and Opus was able to fix them correctly.
- This process took some iterations. Luckily, i did not detect any kind of regression during that phase. With each commit, the port improved in quality.
- I also had to "explain" some audio glitches: music was too loud/sfx barely audible. Also some distortion was audible in the sfx. That was all fixed quickly.
- Configurable display scaling, render resolution scaling and the "launchpad" were my ideas that were also implemented by Opus without much trouble.
- The Ultimate Doom WAD was used for initial playtesting. The Doom 2 WAD was used in the later stages. However, some additional minor adjustments had to be made to make the Doom 2 WAD run without visual glitches.

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

1. _Coordination overhead._ Routing between tiers per module
   means human or tooling glue to decide which model gets which
   file, when to escalate, and how to feed prior context to the
   next tier. That glue would have cost more time than the
   model-cost savings recovered.
2. _Reliability._ Opus produced byte-accurate translations in
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
