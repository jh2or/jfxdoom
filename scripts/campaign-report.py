#!/usr/bin/env python3
"""Report a completed, pruned campaign from its immutable success indexes and native audit."""
import csv
import json
from pathlib import Path
import statistics
import sys
import shutil


def read(path):
    with path.open() as stream: return list(csv.DictReader(stream))


def main():
    root=Path(sys.argv[1]).resolve()
    tree=read(root/'tree.csv'); selection=read(root/'selection.csv'); audit=read(root/'native-audit.csv')
    assert (root/'status.txt').read_text().startswith('VERIFIED'), 'Campaign is not complete'
    metadata=json.loads((root/'run-metadata.json').read_text())
    summaries=[]
    for number in range(1,9):
        level=f'E1M{number}'; successes=[r for r in tree if r['map']==level]
        parents=sorted(set(r['parent'] for r in successes))
        attempts=[r for p in parents for r in read(root/'nodes'/p/'attempts.csv')]
        if metadata.get('prior_run'):
            attempts += [r for p in metadata.get('restarted_partial_nodes',[]) if p in parents
                         for r in read(Path(metadata['prior_run'])/'nodes'/p/'attempts.csv')]
        times=[int(r['tics'])/35 for r in successes]
        summaries.append(dict(map=level,nodes=len(parents),attempts=len(attempts),deaths=sum(r['outcome']=='DEAD' for r in attempts),
                              timeouts=sum(r['outcome']=='TIMEOUT' for r in attempts),qualified=len(successes),
                              minimum_seconds=min(times),maximum_seconds=max(times),mean_seconds=statistics.mean(times)))
    assert [s['nodes'] for s in summaries]==[1,3,9,2,6,18,2,6], 'Unexpected campaign frontier sizes'
    assert len(tree)==141 and all(s['qualified']==3*s['nodes'] for s in summaries)
    final_ids=[r['id'] for r in selection if r['decision']=='final']
    assert len(final_ids)==18
    assert len(audit)==8*len(final_ids)
    demos=sorted(set(r['demo'] for r in audit))
    assert len(demos)==18
    totals={name:sum(int(r['tics']) for r in audit if r['demo']==name) for name in demos}
    fastest=min(demos,key=lambda d:(totals[d],d))
    shutil.copyfile(root/'demos'/fastest,root/'episode1.lmp')
    chosen=[r for r in audit if r['demo']==fastest]
    for boundary in (3,6):
        group=[r for r in selection if int(r['completed_maps'])==boundary]
        expected=sorted(group,key=lambda r:(-int(r['health']),-int(r['armor']),int(r['tics']),r['id']))[:2]
        assert {r['id'] for r in expected}=={r['id'] for r in group if r['decision']=='continue'}
    result=dict(maps=summaries,final_demos=len(demos),fastest_source=fastest,fastest_game_seconds=totals[fastest]/35,
                fastest_maps=chosen,all_final_maps_natively_verified=True,pruning_verified=True)
    (root/'summary.json').write_text(json.dumps(result,indent=2)+'\n')
    lines=['# Episode 1 campaign with health pruning','',
           'Fresh E1M1–E1M8, skill 3, speed strategy. Each earned entrance produced three distinct native-verified successes strictly below twice the engine par time. After E1M3 and E1M6, only the two healthiest successes across the whole frontier continued. Ties use armor, then map time, then stable branch ID. E1M9 was excluded by normal-exit admission. No inventory injection or map warps were used.','',
           f"Completed {sum(s['nodes'] for s in summaries)} training nodes in {sum(s['attempts'] for s in summaries)} logged attempts, producing {len(tree)} qualifying map successes and {len(demos)} continuous final demos. All {len(audit)} constituent map completions were independently audited through native demo playback, including normal routing, living completion, and strict 2×PAR limits.",'',
           '| Map | Entrances | Attempts | Deaths | Timeouts | Qualified demos | Time range |',
           '|---|---:|---:|---:|---:|---:|---:|']
    for s in summaries:
        lines.append(f"| {s['map']} | {s['nodes']} | {s['attempts']} | {s['deaths']} | {s['timeouts']} | {s['qualified']} | {s['minimum_seconds']:.2f}–{s['maximum_seconds']:.2f} s |")
    lines += ['', '## Retained entrances', '', '| After | Success ID | Health | Armor | Map time |', '|---|---|---:|---:|---:|']
    for row in selection:
        if int(row['completed_maps']) in (3,6) and row['decision']=='continue':
            lines.append(f"| {row['map']} | {row['id']} | {row['health']} | {row['armor']} | {int(row['tics'])/35:.2f} s |")
    lines += ['', '## Fastest continuous demo', '',
              f"`episode1.lmp` is copied from `demos/{fastest}`. Its eight map times total **{totals[fastest]/35:.2f} game seconds**, excluding intermissions. This is the fastest exported campaign among the collected alternatives, not a global optimum.", '',
              '```bash', f'bash gradlew run --args="-iwad reference-wads/DOOM.WAD -playdemo {root.relative_to(Path.cwd())}/episode1.lmp"', '```', '',
              '| Map | Game time | Exit health | Exit armor | Bullets | Shells | Rockets | Cells |', '|---|---:|---:|---:|---:|---:|---:|---:|']
    for row in chosen:
        lines.append(f"| {row['map']} | {int(row['tics'])/35:.2f} s | {row['health']} | {row['armor']} | {row['bullets']} | {row['shells']} | {row['rockets']} | {row['cells']} |")
    lines += ['', '## Reproduction and validation','',
              'Exact invocation: command.json. Frozen final source/classes: source/ and classes/. See run-metadata.json for provenance and the interrupted predecessor campaign, which retains the original root bootstrap, prior build and every trial. An E1M4 Java cast crash required emulating the original 32-bit C door/lift field alias. Completed success pools were retained and replay-verified with the corrected build. The partial E1M4 node restarted from its identical earned entrance; its earlier logged attempts are included above. The engine-fix restart did not reuse old-engine archives. A subsequent AI-only floor-boundary repair retained unfinished archives after proving that every other compiled class was identical and replay-checking shortest, median and longest archived routes. A further planning-only correction handles closed doors with legal thresholds, with the same replay checks. During E1M7, archive pruning was corrected to preserve rare key progress when budgets permit it; only Archive.class changed, and saved routes were replay-checked again. See checkpoint-migration.csv, checkpoint-door-threshold-migration.csv, checkpoint-archive-retention-migration.csv and run-metadata.json. See campaign-changes.patch for tracked changes after the requested pre-campaign commit; source/ also includes new files.', '',
              'While E1M5 searches continued, nine already earned E1M6 entrances were trained separately with the same learner and qualification policy, then incorporated before later pruning. This changes scheduling only; prefetch/ and audit-source/ retain the commands and provenance.', '',
              'The door/lift field offsets were also checked by compiling static assertions against the original C headers with `gcc -m32`; see audit-source/door_layout.c.', '',
              'Raw records: [tree.csv](tree.csv), [selection.csv](selection.csv), [native-audit.csv](native-audit.csv), [summary.json](summary.json). Each node retains its attempt log, success recordings, exact entrance and inherited inventory. The interrupted predecessor preserves archives from imported completed nodes; new searches retain archives here. Pruned successes remain available.', '',
              'The campaign coordinator supports --resume with the same pruning and exit policy. Budgets may change; the compiled build, WAD, seed and identity settings must match. For this saved campaign, command.json uses frozen classes so later edits do not invalidate its resume identity.', '']
    tests=json.loads((root/'tests.json').read_text())
    lines += [f"Full suite for the final build: {tests['passed']} passed, {tests['skipped']} skipped, {tests['failures']} failures and {tests['errors']} errors.", '']
    (root/'REPORT.md').write_text('\n'.join(lines))
    print(json.dumps(result,indent=2))


if __name__=='__main__': main()
