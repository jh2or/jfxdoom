#!/usr/bin/env python3
"""Report the three matched cohorts from the navigator graph-cache experiment.

Analysis only: requires NumPy and Matplotlib. The learner has no new dependencies.
"""
import csv
import hashlib
import json
from pathlib import Path
import sys
import numpy as np
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt

root = Path(sys.argv[1] if len(sys.argv) > 1 else 'training/e1m1-graph-cache')
stages = ['baseline', 'connectivity', 'objectives']
labels = ['Retained fixes', '+ Connectivity cache', '+ Objective connections']
colors = ['#667085', '#3878bb', '#248b70']
data = [json.loads((root / stage / 'results.json').read_text()) for stage in stages]
seeds = [r['seed'] for r in data[0]]
assert seeds and all([r['seed'] for r in rows] == seeds for rows in data)
summary = [json.loads((root / stage / 'summary.json').read_text()) for stage in stages]
with (root / 'measurements.csv').open('w') as f:
    writer = csv.DictWriter(f, fieldnames=['stage', *data[0][0]])
    writer.writeheader()
    for stage, rows in zip(stages, data):
        writer.writerows(dict(stage=stage, **r) for r in rows)

def digest(stage, seed):
    return hashlib.sha256((root / stage / f'seed-{seed:02d}' / 'first.lmp').read_bytes()).hexdigest()

rng = np.random.default_rng(20260912)
comparisons = []
for old, new in [(0, 1), (1, 2), (0, 2)]:
    delta = np.array([b['process_wall_seconds'] - a['process_wall_seconds'] for a, b in zip(data[old], data[new])])
    boot = rng.choice(delta, (10000, len(delta)), replace=True).mean(axis=1)
    comparisons.append(dict(before=stages[old], after=stages[new],
        mean_wall_change_seconds=float(delta.mean()),
        mean_wall_change_percent=100 * float(delta.mean()) / summary[old]['mean_process_wall_seconds'],
        paired_seed_bootstrap_95_percent=list(map(float, np.percentile(boot, [2.5, 97.5]))),
        identical_first_demos=sum(digest(stages[old], seed) == digest(stages[new], seed) for seed in seeds)))
(root / 'comparisons.json').write_text(json.dumps(comparisons, indent=2) + '\n')

fig, axes = plt.subplots(2, 2, figsize=(13, 9), layout='constrained')
for ax, metric, title, unit in [
    (axes[0, 0], 'completion_seconds', 'First successful demo', 'Game seconds'),
    (axes[0, 1], 'process_wall_seconds', 'Processing time until first success', 'Wall seconds (startup and verification included)'),
]:
    vals = [[r[metric] for r in rows if r[metric] is not None] for rows in data]
    box = ax.boxplot(vals, patch_artist=True, widths=.5, showmeans=True)
    for patch, color in zip(box['boxes'], colors):
        patch.set_facecolor(color); patch.set_alpha(.4)
    for i, values in enumerate(vals, 1):
        ax.scatter(i + np.linspace(-.14, .14, len(values)), values, s=12, color=colors[i-1], alpha=.5)
        ax.text(i, .96, f'mean {np.mean(values):.2f} s', transform=ax.get_xaxis_transform(), ha='center', va='top', fontsize=9)
    ax.margins(y=.15); ax.set_title(title); ax.set_ylabel(unit)
    ax.set_xticks(range(1, 4), labels, rotation=10, ha='right'); ax.grid(axis='y', alpha=.2)
ax = axes[1, 0]
x = np.arange(3); bottom = np.zeros(3)
for outcome, label, color in [('COMPLETED', 'Completed', '#248b70'), ('DEAD', 'Died', '#c94f56'), ('TIMEOUT', 'Timed out', '#e49b27'), ('OTHER', 'Other', '#667085')]:
    vals = np.array([sum((r['first_outcome'] == outcome) if outcome != 'OTHER' else r['first_outcome'] not in ('COMPLETED', 'DEAD', 'TIMEOUT') for r in rows) for rows in data])
    ax.bar(x, vals, bottom=bottom, label=label, color=color); bottom += vals
ax.set_title(f'First-attempt outcomes ({len(seeds)} fresh seeds per stage)')
ax.set_ylim(0, len(seeds) * 1.1)
ax.set_ylabel('Attempts'); ax.set_xticks(x, labels, rotation=10, ha='right'); ax.legend(fontsize=9, loc='lower right')
ax = axes[1, 1]
for offset, metric, label, color in [(-.18, 'first_dwell_seconds', 'Low-displacement windows', '#667085'), (.18, 'first_blocked_seconds', 'Blocked input', '#3878bb')]:
    vals = [np.mean([r[metric] for r in rows]) for rows in data]
    ax.bar(x + offset, vals, width=.34, label=label, color=color)
    for i, value in enumerate(vals): ax.text(i + offset, value + .012, f'{value:.3f}', ha='center', fontsize=9)
ax.set_title('Movement diagnostics (proxies, not error counts)'); ax.set_ylabel('Mean game seconds per first attempt')
ax.set_ylim(0, max(ax.get_ylim()[1], .6)); ax.set_xticks(x, labels, rotation=10, ha='right'); ax.legend(fontsize=9)
fig.suptitle(f'E1M1 graph caching: {len(seeds)} matched fresh seeds per stage', fontsize=16)
fig.savefig(root / 'comparison.png', dpi=160); fig.savefig(root / 'comparison.svg'); plt.close(fig)

lines = ['# E1M1 graph-cache experiment', '',
    f'{len(seeds)} fresh learner seeds per cumulative stage, speed strategy, skill 3, pistol start. Seeds are paired across stages; no imported routes. Stop at first verified success, with caps of 100 attempts / 600 wall seconds per seed and 600 game seconds per attempt. Three separate JVM workers per cohort; cohorts run sequentially without builds or tests competing for CPU.', '',
    '| Stage | First successes | Deaths | Timeouts | Mean demo (s) | Mean process wall (s) | Mean blocked (s) | Mean dwell (s) |',
    '|---|---:|---:|---:|---:|---:|---:|---:|']
for label, s in zip(labels, summary):
    lines.append(f"| {label} | {s['first_successes']}/{s['seeds']} | {s['deaths']} | {s['timeouts']} | {s['mean_completion_seconds']:.4f} | {s['mean_process_wall_seconds']:.4f} | {s['mean_first_blocked_seconds']:.4f} | {s['mean_first_dwell_seconds']:.4f} |")
lines += ['', '![Comparison](comparison.png)', '', '## Changes', '',
    '1. Connectivity: retain the existing implicit 16-unit, eight-neighbor grid; cache line-to-cell overlaps, directed stair feasibility and per-node terrain costs. Stair checks are computed on demand rather than precomputing paths between every pair of nodes. Floor/ceiling changes invalidate height-dependent results; floor appearance changes refresh terrain costs.',
    '2. Objectives: cache connections to usable switch/mechanism standing positions and nearby key/trajectory destination nodes. Recheck blocked nodes and search reachability each plan. Geometry and switch appearance changes invalidate affected cache categories; pickup movement rebuilds its connections. Map/attempt reset clears caches. Other pickup selection and local movement remain as before.', '',
    'Both stages retain route costs, goal priorities, iteration/tie order and the switch/stair fixes. No recovery behavior is added. Locks and nonshootable solid objects remain live search overlays. The implicit graph is directed: ascent and descent can have different feasibility. Dynamic mechanisms are still hypotheses until successfully used.', '', '## Comparisons', '']
for c in comparisons:
    lo, hi = c['paired_seed_bootstrap_95_percent']
    lines.append(f"- {c['before']} → {c['after']}: mean processing change {c['mean_wall_change_seconds']:+.4f} s ({c['mean_wall_change_percent']:+.1f}%); paired-seed bootstrap 95% interval [{lo:+.4f}, {hi:+.4f}] s. Byte-identical first demos: {c['identical_first_demos']}/{len(seeds)}.")
lines += ['', '## Limits and reproduction', '',
    'The graph makes the same planning work cheaper. Unchanged demos indicate preserved behavior, not improved play speed or fewer navigation mistakes. All successful recordings were verified by the trainer through native demo playback. Death/timeout counts apply to these samples only.', '',
    'Process wall time includes JVM startup, training, checkpoint writes and winning-demo verification; the additional trace replay is excluded. Host load and cohort order can affect timings. Bootstrap intervals describe variation across paired seeds, not uncertainty from host drift or other maps. Box plots show medians/quartiles, triangles show means and dots show seeds.', '',
    'Dwell is a fixed two-second window with less than 16 map units of net displacement. Combat and turning can contribute. Blocked input is the existing trainer counter. Neither is a direct count of navigation errors.', '',
    'Frozen classes, AI sources, source hashes/diffs, commands, per-attempt CSVs, per-tic traces and demos are stored in each stage directory. measurements.csv combines all seed metrics; comparisons.json includes demo identity checks and timing intervals.', '',
    'Build each cumulative implementation with `bash gradlew classes`, then collect baseline, connectivity and objectives cohorts in a new root:', '',
    '```bash', 'python3 scripts/e1m1-navigation-benchmark.py STAGE --root training/NEW-GRAPH-EXPERIMENT',
    'python3 scripts/e1m1-graph-cache-report.py training/NEW-GRAPH-EXPERIMENT', '```', '',
    'The report needs NumPy/Matplotlib; these are analysis-only dependencies. The Java AI adds no libraries.']
if (root / 'validation.json').exists():
    validation = json.loads((root / 'validation.json').read_text())
    t = validation['tests']
    lines += ['', '## Validation', '',
        f"Full suite: {t['tests'] - t['skipped']} passed, {t['skipped']} skipped, {t['failures']} failures, {t['errors']} errors. Final source and class files match the measured objectives snapshot; baseline sources match the starting commit.", '',
        'Additional seed-1 cross-map checks compare fresh attempts against the baseline. These are behavior regression checks, not statistical performance cohorts:', '',
        '| Map | Strategy | Baseline / final outcome | Baseline / final tics | Identical demo |',
        '|---|---|---|---:|---|']
    for r in validation['cross_map']:
        lines.append(f"| {r['map']} | {r['strategy']} | {r['baseline_outcome']} / {r['final_outcome']} | {r['baseline_tics']} / {r['final_tics']} | {r['identical_demo']} |")
    lines += ['', 'See validation.json, full-tests.log and cross-map/ for the recorded evidence.']
(root / 'REPORT.md').write_text('\n'.join(lines) + '\n')
print(json.dumps(comparisons, indent=2))
