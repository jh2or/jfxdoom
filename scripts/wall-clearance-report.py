#!/usr/bin/env python3
"""Compare matched E1M1/E1M3 cohorts from e1m1-navigation-benchmark.py.

Requires NumPy/Matplotlib for analysis only. Pass the experiment root as argv[1].
"""
import csv
import json
from pathlib import Path
import shutil
import statistics
import struct
import sys
import numpy as np
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt

root = Path(sys.argv[1] if len(sys.argv) > 1 else 'training/wall-clearance')
maps = ['E1M1', 'E1M3']
stages = ['baseline', 'clearance']
labels = ['Current graph', '+ Wall clearance']
colors = ['#667085', '#3878bb']
# Read fixed wall segments from the same WAD used for the recorded traces.
wad = (Path(__file__).resolve().parents[1] / 'reference-wads/DOOM.WAD').read_bytes()
_, lump_count, directory = struct.unpack_from('<4sii', wad)
entries = []
for index in range(lump_count):
    offset, size, name = struct.unpack_from('<ii8s', wad, directory + 16 * index)
    entries.append((name.rstrip(b'\0').decode('ascii'), offset, size))

def walls(mapname):
    at = next(i for i, e in enumerate(entries) if e[0] == mapname)
    lumps = {name: wad[offset:offset + size] for name, offset, size in entries[at + 1:at + 11]}
    vertices = np.frombuffer(lumps['VERTEXES'], dtype='<i2').reshape(-1, 2).astype(float)
    lines = np.frombuffer(lumps['LINEDEFS'], dtype='<u2').reshape(-1, 7)
    fixed = lines[((lines[:, 2] & 1) != 0) | (lines[:, 6] == 65535)]
    a, b = vertices[fixed[:, 0]], vertices[fixed[:, 1]]
    return a, b - a

wall_segments = {mapname: walls(mapname) for mapname in maps}

def wall_proximity(trace_path, mapname):
    with trace_path.open() as stream:
        points = [(float(r['x']), float(r['y'])) for i, r in enumerate(csv.DictReader(stream))
                  if i % 7 == 0 and (int(r['forward']) != 0 or int(r['side']) != 0)]
    if not points:
        return 0.0
    a, v = wall_segments[mapname]
    near = 0
    for start in range(0, len(points), 256):
        delta = np.array(points[start:start + 256])[:, None, :] - a
        t = np.clip(np.sum(delta * v, axis=2) / np.maximum(np.sum(v * v, axis=1), 1), 0, 1)
        d2 = np.sum((delta - t[:, :, None] * v) ** 2, axis=2).min(axis=1)
        near += int((d2 < 32 ** 2).sum())
    return 100 * near / len(points)

data = {}
summaries = []
for mapname in maps:
    for stage in stages:
        folder = root / mapname / stage
        rows = json.loads((folder / 'results.json').read_text())
        for row in rows:
            attempts = list(csv.DictReader((folder / f"seed-{row['seed']:02d}" / 'attempts.csv').open()))
            row['first_near_wall_percent'] = wall_proximity(folder / f"seed-{row['seed']:02d}" / 'trace.csv', mapname)
            row['first_blocked_percent'] = 100 * row['first_blocked_seconds'] / row['first_seconds']
            row['first_dwell_percent'] = 100 * row['first_dwell_seconds'] / row['first_seconds']
            row['completion_blocked_seconds'] = int(attempts[-1]['blocked_tics']) / 35 if row['completed'] else None
            row['completion_idle_seconds'] = int(attempts[-1]['idle_tics']) / 35 if row['completed'] else None
        data[mapname, stage] = rows
        successes = [r for r in rows if r['completed']]
        summary = dict(map=mapname, stage=stage, seeds=len(rows), completions=len(successes),
                       first_completions=sum(r['first_outcome'] == 'COMPLETED' for r in rows),
                       first_deaths=sum(r['first_outcome'] == 'DEAD' for r in rows),
                       first_timeouts=sum(r['first_outcome'] == 'TIMEOUT' for r in rows))
        for metric in ['attempts', 'deaths', 'timeouts', 'other']:
            summary['total_' + metric] = sum(r[metric] for r in rows)
        for metric in ['attempts', 'simulated_seconds', 'process_wall_seconds', 'first_blocked_seconds',
                       'first_dwell_seconds', 'first_blocked_percent', 'first_dwell_percent', 'first_near_wall_percent']:
            summary['mean_' + metric] = statistics.mean(r[metric] for r in rows)
        for metric in ['completion_seconds', 'completion_blocked_seconds', 'completion_idle_seconds']:
            summary['mean_' + metric] = statistics.mean(r[metric] for r in successes) if successes else None
        summary['median_completion_seconds'] = statistics.median(r['completion_seconds'] for r in successes) if successes else None
        summaries.append(summary)
    assert [r['seed'] for r in data[mapname, 'baseline']] == [r['seed'] for r in data[mapname, 'clearance']]
(root / 'summaries.json').write_text(json.dumps(summaries, indent=2) + '\n')
with (root / 'measurements.csv').open('w') as f:
    writer = csv.DictWriter(f, fieldnames=['map', 'stage', *next(iter(data.values()))[0]])
    writer.writeheader()
    for (mapname, stage), rows in data.items():
        writer.writerows(dict(map=mapname, stage=stage, **r) for r in rows)

rng = np.random.default_rng(20260912)
comparisons = []
for mapname in maps:
    for metric in ['completion_seconds', 'attempts', 'deaths', 'timeouts', 'simulated_seconds',
                   'process_wall_seconds', 'first_blocked_percent', 'first_dwell_percent', 'first_near_wall_percent']:
        pairs = [(a[metric], b[metric]) for a, b in zip(data[mapname, 'baseline'], data[mapname, 'clearance'])
                 if a[metric] is not None and b[metric] is not None]
        if not pairs:
            continue
        values = np.array(pairs); delta = values[:, 1] - values[:, 0]
        boot = rng.choice(delta, (10000, len(delta)), replace=True).mean(axis=1)
        comparisons.append(dict(map=mapname, metric=metric, pairs=len(pairs), mean_change=float(delta.mean()),
            bootstrap_95_percent=list(map(float, np.percentile(boot, [2.5, 97.5]))),
            lower=int((delta < 0).sum()), higher=int((delta > 0).sum()), unchanged=int((delta == 0).sum())))
(root / 'comparisons.json').write_text(json.dumps(comparisons, indent=2) + '\n')

def distributions(ax, mapname, metric, title, unit):
    vals = [[r[metric] for r in data[mapname, stage] if r[metric] is not None] for stage in stages]
    box = ax.boxplot(vals, patch_artist=True, widths=.5, showmeans=True)
    for patch, color in zip(box['boxes'], colors):
        patch.set_facecolor(color); patch.set_alpha(.4)
    for i, v in enumerate(vals, 1):
        ax.scatter(i + np.linspace(-.14, .14, len(v)), v, s=10, color=colors[i-1], alpha=.5)
        if v:
            ax.text(i, .97, f'mean {np.mean(v):.2f}', transform=ax.get_xaxis_transform(), ha='center', va='top', fontsize=9)
    ax.margins(y=.2); ax.set_title(f'{mapname}: {title}'); ax.set_ylabel(unit)
    ax.set_xticks([1, 2], labels); ax.grid(axis='y', alpha=.2)

fig, axes = plt.subplots(2, 3, figsize=(15, 9), layout='constrained')
for i, mapname in enumerate(maps):
    distributions(axes[i, 0], mapname, 'completion_seconds', 'First successful demo', 'Game seconds (successful seeds only)')
    distributions(axes[i, 1], mapname, 'simulated_seconds', 'Total gameplay simulated', 'Game seconds through success or budget limit')
    distributions(axes[i, 2], mapname, 'process_wall_seconds', 'Processing time', 'Wall seconds through success or budget limit')
fig.suptitle('Wall-clearance preference: 50 matched fresh seeds per map and version', fontsize=16)
fig.savefig(root / 'comparison.png', dpi=160); fig.savefig(root / 'comparison.svg'); plt.close(fig)

fig, axes = plt.subplots(2, 3, figsize=(15, 9), layout='constrained')
for i, mapname in enumerate(maps):
    ax = axes[i, 0]; bottom = np.zeros(2)
    for outcome, label, color in [('COMPLETED', 'Completed', '#248b70'), ('DEAD', 'Died', '#c94f56'), ('TIMEOUT', 'Timed out', '#e49b27'), ('OTHER', 'Other', '#667085')]:
        values = np.array([sum((r['first_outcome'] == outcome) if outcome != 'OTHER' else r['first_outcome'] not in ('COMPLETED', 'DEAD', 'TIMEOUT') for r in data[mapname, stage]) for stage in stages])
        ax.bar([0, 1], values, bottom=bottom, label=label, color=color); bottom += values
    ax.set_ylim(0, max(bottom) * 1.1); ax.set_xticks([0, 1], labels)
    ax.set_title(f'{mapname}: first attempts'); ax.set_ylabel('Seeds'); ax.legend(fontsize=8, loc='lower right')
    ax = axes[i, 1]; bottom = np.zeros(2)
    for metric, label, color in [('completions', 'Completed', '#248b70'), ('total_deaths', 'Died', '#c94f56'), ('total_timeouts', 'Timed out', '#e49b27'), ('total_other', 'Other', '#667085')]:
        selected = [next(s for s in summaries if s['map'] == mapname and s['stage'] == stage) for stage in stages]
        values = np.array([s[metric] / s['seeds'] for s in selected])
        ax.bar([0, 1], values, bottom=bottom, label=label, color=color); bottom += values
    for j, total in enumerate(bottom): ax.text(j, total + .04, f'{total:.2f}', ha='center')
    ax.set_ylim(0, max(bottom) * 1.15); ax.set_xticks([0, 1], labels)
    ax.set_title(f'{mapname}: attempts used'); ax.set_ylabel('Mean attempts per seed'); ax.legend(fontsize=8, loc='lower right')
    ax = axes[i, 2]
    for offset, metric, label, color in [(-.18, 'first_blocked_percent', 'Blocked input', '#3878bb'), (.18, 'first_dwell_percent', 'Low-displacement windows', '#667085')]:
        values = [statistics.mean(r[metric] for r in data[mapname, stage]) for stage in stages]
        ax.bar(np.arange(2) + offset, values, width=.34, label=label, color=color)
        for j, v in enumerate(values): ax.text(j + offset, v, f'{v:.2f}%', ha='center', va='bottom', fontsize=9)
    ax.margins(y=.3); ax.set_xticks([0, 1], labels)
    ax.set_title(f'{mapname}: movement proxies'); ax.set_ylabel('Mean % of first-attempt game time'); ax.legend(fontsize=8)
fig.suptitle('Outcomes and movement: current graph versus wall-clearance preference', fontsize=16)
fig.savefig(root / 'outcomes.png', dpi=160); fig.savefig(root / 'outcomes.svg'); plt.close(fig)

# Pick paired, completed seeds by the median of their average before/after demo times.
demos = []
for mapname in maps:
    pairs = [(a, b) for a, b in zip(data[mapname, 'baseline'], data[mapname, 'clearance']) if a['completed'] and b['completed']]
    if not pairs:
        continue
    middle = statistics.median((a['completion_seconds'] + b['completion_seconds']) / 2 for a, b in pairs)
    before, after = min(pairs, key=lambda p: (abs((p[0]['completion_seconds'] + p[1]['completion_seconds']) / 2 - middle), p[0]['seed']))
    folder = root / 'demos' / mapname; folder.mkdir(parents=True, exist_ok=True)
    for stage, row, label in [('baseline', before, 'before'), ('clearance', after, 'after')]:
        source = root / mapname / stage / f"seed-{row['seed']:02d}"
        shutil.copyfile(source / 'winning.lmp', folder / f'{label}.lmp')
        shutil.copyfile(source / 'first.lmp', folder / ('firstold.lmp' if label == 'before' else 'firstnew.lmp'))
        demos.append(dict(map=mapname, version=label, seed=row['seed'], attempts=row['attempts'],
                          seconds=row['completion_seconds'], path=str(folder / f'{label}.lmp')))
(root / 'demos.json').write_text(json.dumps(demos, indent=2) + '\n')

fmt = lambda x: '—' if x is None else f'{x:.2f}'
lines = ['# Wall-clearance experiment', '',
    '50 matched fresh seeds on each of E1M1 and E1M3, for baseline and candidate (200 learning sessions). Speed strategy, skill 3, pistol starts, seeds 1–50. No imported routes. Stop at first verified completion or 100 attempts / 600 wall seconds; each attempt has a 600-game-second cap. Three JVM workers per cohort. Cohorts run separately, without builds/tests competing for CPU.', '',
    '## Candidate', '',
    'One change to path cost: at grid node distance d from the nearest fixed wall, add 0.25 × clamp((48 − d) / 32, 0, 1)² to the terrain multiplier. The distance is measured from the player center, in map units. This is at most a 25% surcharge on normal floor travel, tapering to zero at 48 units. The cached field uses one-sided or ML_BLOCKING linedefs; it does not hard-block nodes or change collision checks. Dynamic doors/lifts, switch/stair fixes, objective priorities, movement lookahead and pickup/combat controllers remain as before. No parameter tuning across the measured seeds.', '',
    '## Results', '',
    '| Map / version | Seeds completed | First wins / deaths / timeouts | Attempts | All deaths / timeouts | Mean winning demo (s) | Mean simulated work (s) | Mean wall (s) |',
    '|---|---:|---:|---:|---:|---:|---:|---:|']
for s in summaries:
    lines.append(f"| {s['map']} / {s['stage']} | {s['completions']}/{s['seeds']} | {s['first_completions']} / {s['first_deaths']} / {s['first_timeouts']} | {s['total_attempts']} | {s['total_deaths']} / {s['total_timeouts']} | {fmt(s['mean_completion_seconds'])} | {s['mean_simulated_seconds']:.2f} | {s['mean_process_wall_seconds']:.2f} |")
lines += ['', '![Time comparison](comparison.png)', '', '![Outcomes and movement](outcomes.png)', '',
    '| Map / version | Mean blocked first (s) | Mean dwell first (s) | Mean blocked winning (s) | Mean idle winning (s) | Near-wall movement first (%) |',
    '|---|---:|---:|---:|---:|---:|']
for s in summaries:
    lines.append(f"| {s['map']} / {s['stage']} | {s['mean_first_blocked_seconds']:.3f} | {s['mean_first_dwell_seconds']:.3f} | {fmt(s['mean_completion_blocked_seconds'])} | {fmt(s['mean_completion_idle_seconds'])} | {s['mean_first_near_wall_percent']:.2f} |")
lines += ['', '## Paired comparisons', '', 'Candidate minus baseline; negative means lower. Completion comparisons include only seeds where both versions completed.', '']
for c in comparisons:
    lo, hi = c['bootstrap_95_percent']
    lines.append(f"- {c['map']} {c['metric']}: {c['mean_change']:+.3f}; paired bootstrap 95% interval [{lo:+.3f}, {hi:+.3f}], {c['pairs']} pairs.")
lines += ['', '## Representative native demos', '',
    'For each map, choose the completed seed pair closest to the median of the average before/after winning times. This rule does not select the largest improvement. Winning demos may come from different attempt numbers. firstold.lmp and firstnew.lmp preserve the same seed’s initial attempt as well.', '']
for d in demos:
    lines += [f"- {d['map']} {d['version']}: seed {d['seed']}, attempt {d['attempts']}, {d['seconds']:.2f} game seconds.", '',
              '```bash', f'bash gradlew run --args="-iwad reference-wads/DOOM.WAD -playdemo {d["path"]}"', '```', '']
lines += ['## Interpretation and limits', '',
    'Successful-demo means describe successful seeds only. Attempts, simulated work and wall time include every seed through success or its budget limit; a censored session is not a success. Simulated work sums full trial lengths, including replayed prefixes. Wall time includes JVM startup, training, checkpoint writes and winning-demo verification, excluding the extra diagnostic replay. Wall time depends on host load and three-worker scheduling.', '',
    'Blocked input is the existing trainer metric. Dwell counts fixed two-second windows with less than 16 units of net displacement, including combat, turning and small loops. The movement graph divides these first-attempt metrics by attempt length to make differing survival durations visible. Neither metric directly counts navigation errors or literal wall contact. Near-wall movement samples the first trace every seven tics, keeps samples with forward/strafe input, and counts player centers less than 32 units from a fixed wall. It reads the original WAD segments and is an approximate proximity measure, not collision detection.', '',
    'Seed pairing controls initialization, but changed movement changes later game RNG, combat and archive branches. Bootstrap intervals describe variation across these seed pairs, not other maps or host drift. First-success demos are not speed-optimized routes. No inference about the survival strategy is made.', '',
    'All successful recordings are verified by the trainer through native playback. Each cohort retains frozen source/classes, source hashes, commands, attempts.csv, traces and demos. measurements.csv contains all seed metrics.', '',
    '## Reproduction', '', 'Build/freeze each version, then:', '', '```bash',
    'python3 scripts/e1m1-navigation-benchmark.py baseline --map E1M1 --root training/NEW-EXPERIMENT/E1M1 --snapshot BASELINE-SNAPSHOT',
    '# Repeat baseline for E1M3, then build the candidate and run clearance for both maps.',
    'python3 scripts/wall-clearance-report.py training/NEW-EXPERIMENT', '```', '',
    'Plotting requires NumPy and Matplotlib; the Java learner adds no dependencies.']
if (root / 'decision.json').exists():
    decision = json.loads((root / 'decision.json').read_text())
    lines += ['', '## Decision', '', decision['explanation']]
if (root / 'early-death.json').exists():
    diagnosis = json.loads((root / 'early-death.json').read_text())
    lines += ['', '## Early E1M3 death', '',
        'All 50 candidate first attempts have the same demo hash and die at tic 353 (10.09 seconds). '
        'The seed-1 trace loses 20 health at tics 225, 257, 289, 321 and 353. The damage positions are on '
        'sector 66, floor NUKAGE3, special 16; PSpec.pPlayerInSpecialSector applies 20 damage every 32 tics '
        'there. This is an offline diagnosis; the AI does not read those special identifiers. '
        'Reduced first-attempt blocked/dwell time is therefore not evidence of improved overall navigation. '
        'See early-death.json and inspect-sector.py.', '',
        '```bash', f'bash gradlew run --args="-iwad reference-wads/DOOM.WAD -playdemo {root}/demos/E1M3/firstnew.lmp"', '```']
if (root / 'validation.json').exists():
    lines += ['', '## Validation', '', 'See validation.json and the saved test logs for suite results and source/build provenance.']
(root / 'REPORT.md').write_text('\n'.join(lines) + '\n')
print(json.dumps(summaries, indent=2)); print(json.dumps(demos, indent=2))
