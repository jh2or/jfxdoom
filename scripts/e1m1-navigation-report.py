#!/usr/bin/env python3
"""Plot the paired stages produced by e1m1-navigation-benchmark.py."""
import csv
import json
from pathlib import Path
import sys
import numpy as np
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt

root = Path(sys.argv[1] if len(sys.argv) > 1 else 'training/e1m1-navigation-stages')
stages = ['baseline', 'switches', 'stairs', 'recovery', 'recovery-refined']
labels = ['Baseline', '+ Switch approaches', '+ Stair reachability (retained)', '+ Recovery (initial)', '+ Recovery (refined)']
data = [json.loads((root / stage / 'results.json').read_text()) for stage in stages]
assert all([r['seed'] for r in rows] == list(range(1, 51)) for rows in data)
summary = [json.loads((root / stage / 'summary.json').read_text()) for stage in stages]
with (root / 'measurements.csv').open('w') as f:
    writer = csv.DictWriter(f, fieldnames=['stage', *data[0][0]])
    writer.writeheader()
    for stage, rows in zip(stages, data):
        writer.writerows(dict(stage=stage, **r) for r in rows)

colors = ['#667085', '#3878bb', '#e49b27', '#c94f56', '#248b70']
fig, axes = plt.subplots(2, 3, figsize=(16, 9), layout='constrained')
for ax, metric, title, unit in [
    (axes[0,0], 'completion_seconds', 'Time of first successful demo', 'Game seconds'),
    (axes[0,1], 'simulated_seconds', 'Total gameplay simulated until success', 'Game seconds, including failed attempts'),
    (axes[1,0], 'process_wall_seconds', 'Elapsed learning time until success', 'Wall seconds, including JVM startup / verification'),
    (axes[1,1], 'first_dwell_seconds', 'Lingering during the first attempt', 'Seconds in low-displacement windows'),
]:
    vals = [[r[metric] for r in rows if r[metric] is not None] for rows in data]
    box = ax.boxplot(vals, patch_artist=True, widths=.5, showmeans=True)
    for patch, color in zip(box['boxes'], colors): patch.set_facecolor(color); patch.set_alpha(.45)
    for i, values in enumerate(vals,1):
        ax.scatter(i + np.linspace(-.15,.15,len(values)), values, s=10, color=colors[i-1], alpha=.6)
    ax.set_title(title); ax.set_ylabel(unit); ax.set_xticks(range(1,len(stages)+1), labels, rotation=15, ha='right')
    ax.grid(axis='y', alpha=.2)
ax = axes[0,2]
x = np.arange(len(stages)); bottom = np.zeros(len(stages))
for key,label,color in [('first_successes','Completed','#248b70'),('first_deaths','Died','#c94f56'),('first_timeouts','Timed out','#e49b27'),('first_other','Other','#667085')]:
    vals = []
    for rows in data:
        vals.append(sum((r['first_outcome']=='COMPLETED') if key=='first_successes' else
                        (r['first_outcome']=='DEAD') if key=='first_deaths' else
                        (r['first_outcome']=='TIMEOUT') if key=='first_timeouts' else
                        (r['first_outcome'] not in ('COMPLETED','DEAD','TIMEOUT')) for r in rows))
    ax.bar(x, vals, bottom=bottom, label=label, color=color); bottom += vals
ax.set_title('First-attempt outcomes (50 seeds per stage)'); ax.set_ylabel('Attempts')
ax.set_xticks(x,labels,rotation=15,ha='right'); ax.legend(fontsize=8)
ax = axes[1,2]
attempts = [s['attempts'] for s in summary]
for i,s in enumerate(summary):
    ax.bar(i,s['completions'],color='#248b70')
    ax.bar(i,s['deaths'],bottom=s['completions'],color='#c94f56')
    ax.bar(i,s['timeouts'],bottom=s['completions']+s['deaths'],color='#e49b27')
    ax.bar(i,s['other'],bottom=s['completions']+s['deaths']+s['timeouts'],color='#667085')
    ax.text(i,attempts[i]+.5,f"{attempts[i]} trials\n{s['mean_attempts']:.2f} per success",ha='center',fontsize=8)
ax.set_ylim(0,max(attempts)*1.3);ax.set_title('All attempts through first completion');ax.set_ylabel('Attempts')
ax.set_xticks(x,labels,rotation=15,ha='right')
fig.suptitle('E1M1: cumulative navigation changes, paired seeds 1–50',fontsize=17)
fig.savefig(root/'comparison.png',dpi=160);fig.savefig(root/'comparison.svg');plt.close(fig)

rng = np.random.default_rng(20260912)
comparisons=[]
for i in range(1,len(stages)):
    differences=np.array([new['completion_seconds']-old['completion_seconds'] for old,new in zip(data[i-1],data[i]) if old['completed'] and new['completed']])
    boot=rng.choice(differences,(10000,len(differences)),replace=True).mean(axis=1)
    comparisons.append(dict(stage=stages[i],mean_change_seconds=float(differences.mean()),
                            paired_seed_bootstrap_95_percent=list(map(float,np.percentile(boot,[2.5,97.5]))),
                            faster=int((differences<0).sum()),slower=int((differences>0).sum()),unchanged=int((differences==0).sum())))
(root/'comparisons.json').write_text(json.dumps(comparisons,indent=2)+'\n')
lines=['# E1M1 navigation experiment','',
'50 independent fresh learning runs per cumulative stage; speed strategy, skill 3, pistol start, learner seeds 1–50. No imported routes. Stop at first verified completion, cap 100 attempts / 600 wall seconds per seed and 600 game seconds per attempt. Three separate JVM workers per stage. Each stage completed before the next production change. Frozen classes, AI sources, source diff, commands, logs and demos are retained under each stage directory.','',
'| Stage | First-attempt successes | Attempts | Deaths | Timeouts | Mean attempts to success | Mean successful demo (s) | Median (s) | Mean total simulated (s) | Mean wall (s) |',
'|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|']
for label,s in zip(labels,summary):
    lines.append(f"| {label} | {s['first_successes']}/50 | {s['attempts']} | {s['deaths']} | {s['timeouts']} | {s['mean_attempts']:.2f} | {s['mean_completion_seconds']:.2f} | {s['median_completion_seconds']:.2f} | {s['mean_simulated_seconds']:.2f} | {s['mean_process_wall_seconds']:.2f} |")
lines += ['', '**Retained implementation: switch approaches plus stair reachability.** Both recovery variants were rejected: their observed mean and long-tail completion times were worse, without better success/death/timeout outcomes. Their source snapshots and demos are preserved.', '', '![Comparison](comparison.png)', '', '## Incremental changes', '']
for label,c in zip(labels[1:],comparisons):
    lo,hi=c['paired_seed_bootstrap_95_percent']
    lines.append(f"- {label}: mean successful-demo change {c['mean_change_seconds']:+.2f} s; paired-seed bootstrap 95% interval [{lo:+.2f}, {hi:+.2f}] s. Faster/slower/unchanged seeds: {c['faster']}/{c['slower']}/{c['unchanged']}.")
lines += ['', '## Interpretation and limitations', '',
'Box plots show medians and quartiles; triangles mark means and dots show individual seeds. The horizontal order is cumulative implementation stages, not successive attempts within a single learning run.', '',
'Learning time and demo completion time are different measurements. Total simulated time includes failed attempts and replayed prefixes; wall time includes process startup, training, checkpoints and successful-demo verification, and excludes the extra diagnostic replay. Wall time depends on host load and the fixed three-worker setup. All timeout/death counts are observed outcomes, not claims of zero future risk.', '',
'The lingering proxy counts nonoverlapping two-second windows with less than 16 map units of net displacement. It includes combat, turning and small loops; it does not establish that an objective was unreachable. Baseline and all stages use exactly the same offline calculation. No-position-change cannot be inferred from the existing blocked-input counter alone.', '',
'These are cumulative changes. Incremental comparisons are against the preceding stage; this is not a factorial ablation. Paired seeds control learner initialization, but changed actions alter gameplay RNG and subsequent decisions. The bootstrap intervals describe variation across these E1M1 learner seeds, not other maps or arbitrary initial conditions. No inference about survival strategy is made.', '',
'Every successful recording is verified through native demo playback by the trainer. `seed-01/first.lmp` in each stage preserves a directly comparable first attempt. All raw attempt rows are in `seed-*/attempts.csv`; per-tic first-attempt diagnostics are in `seed-*/trace.csv`.', '',
'## Reproduction', '', 'Build each source stage with `bash gradlew classes`, then run:', '', '```bash',
'python3 scripts/e1m1-navigation-benchmark.py STAGE --root training/NEW-EXPERIMENT',
'# After collecting baseline, switches, stairs, recovery, recovery-refined:',
'python3 scripts/e1m1-navigation-report.py training/NEW-EXPERIMENT', '```', '',
'The plotting script needs NumPy and Matplotlib; these are analysis tools only, not AI/runtime dependencies. Production changes are not committed by this experiment. Each snapshot includes its original commit and working-tree diff.']
if (root/'validation.json').exists():
    v=json.loads((root/'validation.json').read_text());t=v['tests']
    lines += ['', '## Validation', '', f"Full test suite: {t['tests']-t['skipped']} passed, {t['skipped']} skipped, {t['failures']} failures, {t['errors']} errors. Retained AI sources and compiled classes exactly match the measured stair-stage snapshot. Each of the five presented seed-1 demos was independently reverified through the final engine. See validation.json and per-demo verification logs."]
(root/'REPORT.md').write_text('\n'.join(lines)+'\n')
print(json.dumps(summary,indent=2));print(json.dumps(comparisons,indent=2))
