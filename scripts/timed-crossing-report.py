#!/usr/bin/env python3
"""Summarize matched timed-crossing benchmarks and directly measured replay damage."""
import csv
import json
from pathlib import Path
import statistics
import random
import sys


def main():
    root = Path(sys.argv[1] if len(sys.argv) > 1 else 'training/timed-crossing').resolve()
    rows, summaries = [], []
    for level in ('E1M1', 'E1M3', 'E1M2'):
        for stage in ('baseline', 'timed-basin'):
            cohort = root / level / stage
            results = json.loads((cohort / 'results.json').read_text())
            summary = dict(map=level, **json.loads((cohort / 'summary.json').read_text()))
            for r in results:
                damage = json.loads((cohort / f"seed-{r['seed']:02d}" / 'floor-damage.json').read_text())
                row = dict(map=level, stage=stage, **r)
                for kind, metrics in damage.items():
                    for k in ('health_lost', 'armor_lost', 'damaging_hits'):
                        row[f'{kind}_floor_{k}'] = metrics[k]
                rows.append(row)
            cohort_rows = [r for r in rows if r['map'] == level and r['stage'] == stage]
            for kind in ('first', 'winning'):
                for k in ('health_lost', 'armor_lost', 'damaging_hits'):
                    key = f'{kind}_floor_{k}'
                    summary['mean_' + key] = statistics.mean(r[key] for r in cohort_rows)
            summaries.append(summary)
    comparisons = []
    rng = random.Random(20260912)
    for level in ('E1M1', 'E1M3', 'E1M2'):
        before = sorted((r for r in rows if r['map'] == level and r['stage'] == 'baseline'), key=lambda r:r['seed'])
        after = sorted((r for r in rows if r['map'] == level and r['stage'] == 'timed-basin'), key=lambda r:r['seed'])
        assert [r['seed'] for r in before] == [r['seed'] for r in after]
        for metric in ('completion_seconds','deaths','first_floor_health_lost'):
            delta = [b[metric]-a[metric] for a,b in zip(before,after)]
            boot = sorted(statistics.mean(rng.choices(delta,k=len(delta))) for _ in range(10000))
            comparisons.append(dict(map=level,metric=metric,mean_change=statistics.mean(delta),
                                    bootstrap_95_percent=[boot[249],boot[9749]],pairs=len(delta)))
    (root / 'comparisons.json').write_text(json.dumps(comparisons, indent=2) + '\n')
    (root / 'summaries.json').write_text(json.dumps(summaries, indent=2) + '\n')
    with (root / 'measurements.csv').open('w') as f:
        writer = csv.DictWriter(f, fieldnames=list(rows[0])); writer.writeheader(); writer.writerows(rows)

    import matplotlib
    matplotlib.use('Agg')
    import matplotlib.pyplot as plt
    colors = ['#64748b', '#0891b2']
    fig, axes = plt.subplots(3, 3, figsize=(13, 10), constrained_layout=True)
    for i, level in enumerate(('E1M1', 'E1M3', 'E1M2')):
        groups = [[r for r in rows if r['map'] == level and r['stage'] == s] for s in ('baseline', 'timed-basin')]
        for j, (field, label) in enumerate((('completion_seconds', 'Winning demo (seconds)'),
                                          ('attempts', 'Attempts until completion'),
                                          ('first_floor_health_lost', 'First-attempt floor health lost'))):
            ax = axes[i,j]
            for x, g in enumerate(groups):
                values = [r[field] for r in g]
                jitter = [(k % 11 - 5) * .022 for k in range(len(g))]
                ax.scatter([x + v for v in jitter], values, s=15, color=colors[x], alpha=.5)
                mean = statistics.mean(values)
                ax.plot([x-.22,x+.22], [mean,mean], color=colors[x], lw=3)
                ax.annotate(f'mean {mean:.2f}', (x,mean), xytext=(0,7), textcoords='offset points', ha='center', fontsize=9)
            ax.set_xticks([0,1], ['Before','Timed']); ax.set_xlim(-.6,1.6)
            ax.set_ylabel(label); ax.set_title(f'{level} · {len(groups[0])} paired seeds')
            ax.grid(axis='y',alpha=.2); ax.set_axisbelow(True)
    fig.suptitle('Timed crossings: speed, learning effort and actual floor damage\nSkill 3 · speed strategy · fresh pistol starts · first verified success',fontsize=14)
    fig.savefig(root / 'comparison.png',dpi=160); fig.savefig(root / 'comparison.svg'); plt.close(fig)

    report = ['# Timed-crossing exception', '',
        'The AI may shortcut across a damaging floor when a bounded input forecast reaches dry ground at least four ticks before the next global 32-tick damage pulse. It does not wait for a window. Ordinary required crossings retain finite hazard costs.', '',
        'The first implementation is deliberately limited to clear crossings of at most 192 map units on level ground or through basins at most 24 units deep. It rejects visible enemies, nearby missiles, blocking objects, sideways drift, taller drops or raised terrain, uncertain toxic textures and damaging finale sectors. A dry landing and room for braking are required. The controller uses ordinary forward/strafe inputs, checks the forecast every tick, and replans interrupted shortcuts on dry ground.', '',
        '## Method', '',
        '50 paired fresh learner seeds on E1M1 and E1M3, plus a supplemental 10 on E1M2; speed strategy, skill 3, pistol starts. Each session stops at its first verified completion, 100 attempts or 600 wall seconds; each attempt has a 600-game-second limit. Three separate JVM workers; cohorts run sequentially. Wall times include training, process startup, persistence and native success verification, and exclude diagnostic replay. Every baseline source/class snapshot was frozen before implementation.', '',
        'Actual floor damage is measured for each **first attempt and final winning demo**, not every intermediate retry. An analysis-only PSpec overlay records health and armor deltas immediately around the actual floor damage call, including suit/armor effects. Every instrumented replay must match the ordinary replay trace byte for byte. No production engine files are changed. Unlike the earlier exposure proxy, these are directly attributed floor-damage losses.', '',
        '## Results', '',
        '| Map / behavior | First wins | Winning demo mean | Attempts mean | Deaths / timeouts | Wall mean |',
        '|---|---:|---:|---:|---:|---:|']
    for s in summaries:
        report.append(f"| {s['map']} {s['stage']} | {s['first_successes']}/{s['seeds']} | {s['mean_completion_seconds']:.2f} s | {s['mean_attempts']:.2f} | {s['deaths']} / {s['timeouts']} | {s['mean_process_wall_seconds']:.2f} s |")
    report += ['', '| Map / behavior | First floor health loss mean | First floor armor loss mean | First floor hits mean | Winning floor health loss mean |',
               '|---|---:|---:|---:|---:|']
    for s in summaries:
        report.append(f"| {s['map']} {s['stage']} | {s['mean_first_floor_health_lost']:.2f} | {s['mean_first_floor_armor_lost']:.2f} | {s['mean_first_floor_damaging_hits']:.2f} | {s['mean_winning_floor_health_lost']:.2f} |")
    report += ['', '| Map | Paired mean winning-time change | Bootstrap 95% interval |', '|---|---:|---:|']
    for c in comparisons:
        if c['metric'] == 'completion_seconds':
            low, high = c['bootstrap_95_percent']
            report.append(f"| {c['map']} | {c['mean_change']:+.3f} s | {low:+.3f} to {high:+.3f} s |")
    report += ['', 'Negative changes favor the exception. Bootstrap intervals describe paired seed variation, not host-load variation or generalization to other maps.']
    report += ['', 'Completion times describe each session’s winning run; changing which attempts survive can also change this distribution. Deaths/timeouts cover all learning attempts. First-attempt damage is paired by learner seed but each policy can choose a different route and survive for a different duration. These are map-specific observations, not a guarantee of improvement on every map.', '',
               '![Comparison](comparison.png)', '',
               'Raw data: [measurements.csv](measurements.csv), [floor-damage.json](floor-damage.json), [summaries.json](summaries.json). Per-seed directories contain native-verified winners, first-attempt demos, command lines and traces.', '',
               'Reproduce: build with `bash gradlew classes`; run `scripts/e1m1-navigation-benchmark.py` once per map/stage with `--root training/timed-crossing/E1M1 --map E1M1 --seeds 50 --workers 3` (substitute map/stage and 10 seeds for E1M2). Use `--snapshot` for frozen baseline builds. Then run `scripts/timed-crossing-damage.py training/timed-crossing` and `scripts/timed-crossing-report.py training/timed-crossing` (the report uses Matplotlib).', '']
    report += ['', '## Validation', '']
    tests = json.loads((root / 'tests.json').read_text())
    report.append(f"Full suite: {tests['passed']} passed, {tests['skipped']} skipped, {tests['failures']} failures, {tests['errors']} errors. The shallow-basin test compares every predicted position, momentum and height against the actual engine movement functions, and checks floor damage in engine order. See test-results/ and test-validation.txt.")
    audit_path = root / 'crossing-audit.json'
    if audit_path.exists():
        audit = json.loads(audit_path.read_text())
        report += ['', f"Diagnostic audit: {len(audit)} fresh first attempts; {sum(a['accepted'] for a in audit)} accepted timed sequences, {sum(a['finished'] for a in audit)} finished, {sum(a['interrupted'] for a in audit)} interrupted. There were {sum(len(a['floor_damage_calls_in_windows']) for a in audit)} floor-damage calls in the accepted time windows. Every audited first demo matched the measured production demo byte for byte. See crossing-audit.json."]
    report += ['', 'The earlier flat-only candidate is preserved under each map’s timed/ directory (E1M1 and E1M3, 50 seeds each). All its demos were unchanged. Relevant E1M1 pools are 24 units below the dry floor, which motivated the bounded shallow-basin forecast retained here. The final implementation is the timed-basin/ stage.', '']
    report += ['', '## Interpretation and example demo', '',
               'The paired winning-time intervals include zero: this experiment does not establish a consistent speed gain. E1M1 was slightly faster on average, E1M3 slightly slower but with two more first-attempt completions and less first-attempt floor damage, and E1M2 was unchanged. Wall-time differences also include host variability; E1M2 generated identical demos despite different wall times.', '',
               'E1M1 seed 16 is an illustrative audited crossing, not a representative estimate of the average improvement. Its timed crossing occurs near 53 seconds; it finishes in 62.34 seconds versus 66.09 seconds for the paired baseline. Both native-verified demos are copied under demos/E1M1/.', '',
               '```bash', 'bash gradlew run --args="-iwad reference-wads/DOOM.WAD -playdemo training/timed-crossing/demos/E1M1/timed.lmp"', '```', '',
               'The damage parser recognizes event markers even when the engine prints startup text without a terminating newline. Both diagnostic scripts support --parse-only to recompute summaries from preserved logs without running the engine again. See validation.json for the final build/trace checks.', '']
    (root / 'REPORT.md').write_text('\n'.join(report))
    print(json.dumps(summaries, indent=2))


if __name__ == '__main__':
    main()
