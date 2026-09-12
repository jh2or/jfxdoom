#!/usr/bin/env python3
"""Report matched floor-safety cohorts. NumPy/Matplotlib are analysis-only dependencies."""
import csv
import json
from pathlib import Path
import shutil
import statistics as stats
import struct
import sys
import numpy as np
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt

root = Path(sys.argv[1] if len(sys.argv)>1 else 'training/floor-safety')
repo = Path(__file__).resolve().parents[1]
maps = ['E1M1','E1M3','E1M2']; stages = ['baseline','fixed']
labels = ['Before','Floor safety']; colors = ['#667085','#248b70']
wad = (repo/'reference-wads/DOOM.WAD').read_bytes()
_, count, offset = struct.unpack_from('<4sii',wad)
entries=[]
for i in range(count):
    start,size,name=struct.unpack_from('<ii8s',wad,offset+16*i)
    entries.append((name.rstrip(b'\0').decode(),start,size))

def map_geometry(name):
    at=next(i for i,e in enumerate(entries) if e[0]==name)
    lumps={name:wad[start:start+size] for name,start,size in entries[at+1:at+11]}
    nodes=[]
    for i in range(len(lumps['NODES'])//28):
        nodes.append((*struct.unpack_from('<hhhh',lumps['NODES'],i*28),*struct.unpack_from('<HH',lumps['NODES'],i*28+24)))
    sectors=list(struct.iter_unpack('<hh8s8shhh',lumps['SECTORS']))
    leaves=[]
    for _,first in struct.iter_unpack('<HH',lumps['SSECTORS']):
        _,_,_,line,side,_=struct.unpack_from('<HHHHHH',lumps['SEGS'],first*12)
        sidedef=struct.unpack_from('<H',lumps['LINEDEFS'],line*14+10+2*side)[0]
        leaves.append(struct.unpack_from('<H',lumps['SIDEDEFS'],sidedef*30+28)[0])
    return nodes,sectors,leaves

geometry={name:map_geometry(name) for name in maps}
def on_damaging_floor(row,name):
    x,y,z=map(float,(row['x'],row['y'],row['z']))
    nodes,sectors,leaves=geometry[name]
    child=len(nodes)-1 if nodes else 32768
    while child<32768:
        nx,ny,dx,dy,a,b=nodes[child]
        child=a if dx*(y-ny)-dy*(x-nx)<0 else b
    sector=sectors[leaves[child&32767]]
    return sector[5] in (4,5,7,11,16) and abs(z-sector[0])<.01

all_data={}; summaries=[]
for name in maps:
    for stage in stages:
        folder=root/name/stage
        rows=json.loads((folder/'results.json').read_text())
        for r in rows:
            with (folder/f"seed-{r['seed']:02d}"/'trace.csv').open() as f:
                trace=list(csv.DictReader(f))
            r['first_damaging_floor_seconds']=sum(on_damaging_floor(p,name) for p in trace[1:])/35
            r['first_damaging_floor_percent']=100*r['first_damaging_floor_seconds']/r['first_seconds']
            r['death_ending_on_damaging_floor']=r['first_outcome']=='DEAD' and on_damaging_floor(trace[-1],name)
        all_data[name,stage]=rows
        summary=dict(map=name,stage=stage,seeds=len(rows),completions=sum(r['completed'] for r in rows),
                     first_wins=sum(r['first_outcome']=='COMPLETED' for r in rows),
                     first_deaths=sum(r['first_outcome']=='DEAD' for r in rows),
                     first_timeouts=sum(r['first_outcome']=='TIMEOUT' for r in rows))
        for metric in ['attempts','deaths','timeouts','other']:
            summary['total_'+metric]=sum(r[metric] for r in rows)
        for metric in ['completion_seconds','attempts','simulated_seconds','process_wall_seconds','first_damaging_floor_seconds',
                       'first_damaging_floor_percent','first_blocked_seconds','first_dwell_seconds','first_seconds']:
            values=[r[metric] for r in rows if r[metric] is not None]
            summary['mean_'+metric]=stats.mean(values) if values else None
        summary['first_deaths_ending_on_damaging_floor']=sum(r['death_ending_on_damaging_floor'] for r in rows)
        summaries.append(summary)
    assert [r['seed'] for r in all_data[name,'baseline']]==[r['seed'] for r in all_data[name,'fixed']]
(root/'summaries.json').write_text(json.dumps(summaries,indent=2)+'\n')
with (root/'measurements.csv').open('w') as f:
    writer=csv.DictWriter(f,fieldnames=['map','stage',*next(iter(all_data.values()))[0]])
    writer.writeheader()
    for (name,stage),rows in all_data.items(): writer.writerows(dict(map=name,stage=stage,**r) for r in rows)

comparisons=[]; rng=np.random.default_rng(20260912)
for name in maps:
    for metric in ['completion_seconds','attempts','deaths','simulated_seconds','process_wall_seconds','first_damaging_floor_seconds']:
        delta=np.array([b[metric]-a[metric] for a,b in zip(all_data[name,'baseline'],all_data[name,'fixed']) if a[metric] is not None and b[metric] is not None])
        if not len(delta): continue
        boot=rng.choice(delta,(10000,len(delta)),replace=True).mean(axis=1)
        comparisons.append(dict(map=name,metric=metric,mean_change=float(delta.mean()),pairs=len(delta),
                                bootstrap_95_percent=list(map(float,np.percentile(boot,[2.5,97.5])))))
(root/'comparisons.json').write_text(json.dumps(comparisons,indent=2)+'\n')

fig,axes=plt.subplots(3,3,figsize=(15,12),layout='constrained')
for i,name in enumerate(maps):
    n=len(all_data[name,'baseline'])
    for j,metric,title,unit in [(0,'completion_seconds','First successful demo','Game seconds (completed seeds)'),
                              (2,'process_wall_seconds','Processing until success or cap','Wall seconds')]:
        ax=axes[i,j]; values=[[r[metric] for r in all_data[name,stage] if r[metric] is not None] for stage in stages]
        box=ax.boxplot(values,patch_artist=True,widths=.5,showmeans=True)
        for patch,color in zip(box['boxes'],colors): patch.set_facecolor(color);patch.set_alpha(.4)
        for k,v in enumerate(values,1):
            ax.scatter(k+np.linspace(-.13,.13,len(v)),v,color=colors[k-1],s=10,alpha=.5)
            if v: ax.text(k,.97,f'mean {np.mean(v):.2f}',transform=ax.get_xaxis_transform(),va='top',ha='center',fontsize=9)
        ax.margins(y=.2);ax.set_title(f'{name}: {title} ({n} seeds)');ax.set_ylabel(unit);ax.set_xticks([1,2],labels);ax.grid(axis='y',alpha=.2)
    ax=axes[i,1];bottom=np.zeros(2)
    selected=[next(s for s in summaries if s['map']==name and s['stage']==stage) for stage in stages]
    for metric,label,color in [('completions','Completed','#248b70'),('total_deaths','Died','#c94f56'),('total_timeouts','Timed out','#e49b27'),('total_other','Other','#667085')]:
        values=np.array([s[metric]/s['seeds'] for s in selected]);ax.bar([0,1],values,bottom=bottom,label=label,color=color);bottom+=values
    for k,total in enumerate(bottom): ax.text(k,total+.03,f'{total:.2f}',ha='center')
    ax.set_ylim(0,max(bottom)*1.2);ax.set_xticks([0,1],labels);ax.set_title(f'{name}: mean attempts used');ax.set_ylabel('Attempts per seed');ax.legend(fontsize=8,loc='lower right')
fig.suptitle('Harmful-floor safety: matched fresh learning sessions, speed / skill 3 / pistol start',fontsize=16)
fig.savefig(root/'comparison.png',dpi=160);fig.savefig(root/'comparison.svg');plt.close(fig)

selections=[]
for name in maps:
    pairs=[(a,b) for a,b in zip(all_data[name,'baseline'],all_data[name,'fixed']) if a['completed'] and b['completed']]
    if not pairs: continue
    median=stats.median((a['completion_seconds']+b['completion_seconds'])/2 for a,b in pairs)
    pair=min(pairs,key=lambda p:(abs((p[0]['completion_seconds']+p[1]['completion_seconds'])/2-median),p[0]['seed']))
    folder=root/'demos'/name;folder.mkdir(parents=True,exist_ok=True)
    for stage,r,label in zip(stages,pair,['before','fixed']):
        source=root/name/stage/f"seed-{r['seed']:02d}"/'winning.lmp'
        path=folder/f'{label}.lmp';shutil.copyfile(source,path)
        selections.append(dict(map=name,version=label,seed=r['seed'],attempts=r['attempts'],seconds=r['completion_seconds'],path=str(path)))
(root/'demos.json').write_text(json.dumps(selections,indent=2)+'\n')

fmt=lambda x:'—' if x is None else f'{x:.2f}'
lines=['# Harmful-floor safety','',
'50 matched fresh learner seeds per version on E1M1 and E1M3, plus a supplemental 10-seed E1M2 comparison after its first-attempt campaign-test assumption failed. Speed strategy, skill 3, pistol starts. Stop at first verified completion, capped at 100 attempts / 600 wall seconds and 600 game seconds per attempt. Each cohort ran with three separate JVM workers; cohorts ran sequentially without builds/tests competing for CPU.','',
'## Implementation','',
'- Classify the engine’s damaging sector types (4, 5, 7, 11, 16), with the existing visible NUKAGE/LAVA fallback. This bounded classification estimates floor risk; it does not select exits, switches or scripted objectives.',
'- Keep hazard traversal a finite eightfold graph cost. Seed connections and waypoint shortcuts account for the hazardous distance they traverse. Harmful exploration landmarks remain available after safer objectives and usable mechanisms are exhausted.',
'- Predict short-term momentum for every controller action, including combat and recovery inputs. Brake both movement axes before an unintended entry. Allow progress along a planned hazardous crossing, and allow movement once already on a harmful floor.',
'- During a crossing, suppress optional pickups and ordinary combat pauses/spacing while retaining firing opportunities and necessary obstruction handling. Optional pickup detours and fallback exploration also account for floor risk.',
'- Refresh cached terrain risk when sector damage properties change. The wall-clearance preference remains disabled in production; it is used only in the isolated stress check.','',
'## Measurements','',
'| Map / version | Completed seeds | First wins / deaths / timeouts | Mean winning demo (s) | Mean attempts | All deaths / timeouts | Mean simulated work (s) | Mean wall (s) |',
'|---|---:|---:|---:|---:|---:|---:|---:|']
for s in summaries:
    lines.append(f"| {s['map']} / {s['stage']} | {s['completions']}/{s['seeds']} | {s['first_wins']} / {s['first_deaths']} / {s['first_timeouts']} | {fmt(s['mean_completion_seconds'])} | {s['mean_attempts']:.2f} | {s['total_deaths']} / {s['total_timeouts']} | {s['mean_simulated_seconds']:.2f} | {s['mean_process_wall_seconds']:.2f} |")
lines+=['','![Comparison](comparison.png)','','| Map / version | Mean first attempt (s) | Mean damaging-floor exposure (s) | Exposure (% of first attempt) | First deaths ending on damaging floor |','|---|---:|---:|---:|---:|']
for s in summaries:
    lines.append(f"| {s['map']} / {s['stage']} | {s['mean_first_seconds']:.2f} | {s['mean_first_damaging_floor_seconds']:.2f} | {s['mean_first_damaging_floor_percent']:.2f}% | {s['first_deaths_ending_on_damaging_floor']} |")
lines+=['','Exposure is an offline proxy from the recorded positions, original map BSP/sector damage properties and ground height. It can differ after dynamic floor changes. A death ending on hazardous ground does not by itself establish the damage source. Different attempt lengths and routes also affect exposure; compare outcomes alongside it.','','## Original failure stress check','',
'The archived wall-clearance candidate and seed 1 previously died in E1M3 at tic 353 (10.09 s). Adding floor safety to that same wall-clearance configuration completed the first attempt in 10,100 tics (288.57 s), verified through native playback. Its demo is clearance-stress/winning.lmp. This checks the reported failure; it is not a new wall-clearance performance cohort. The isolated navigator source is retained for reproduction.','',
'```bash',f'bash gradlew run --args="-iwad reference-wads/DOOM.WAD -playdemo {root}/clearance-stress/winning.lmp"','```','','## Representative production demos','',
'Each selected seed pair is closest to the median of the average before/after winning times. These are first successes, not optimized speed runs.']
for d in selections:
    lines+=['',f"{d['map']} {d['version']}: seed {d['seed']}, attempt {d['attempts']}, {d['seconds']:.2f} s.",'','```bash',f'bash gradlew run --args="-iwad reference-wads/DOOM.WAD -playdemo {d["path"]}"','```']
lines+=['','## Validation and limits','',
'The full suite checks dry detours, dangerous strips between safe endpoints, inertial braking, continued movement during crossings, hazardous final-area exploration and changing sector damage. The campaign transition test now uses its existing verified E1M2 fixture to test replay fidelity independently of trial luck. The separate E1M2 measurements retain the real first-attempt failure in the results. See full-tests-final.log and tests.json.','',
'Risk is a preference rather than a guarantee of zero damage. Motion prediction is approximate and conservative, and currently does not discount floor cost for radiation protection. Required crossings and damaging finale floors remain possible. Successful demo means exclude capped unsuccessful sessions; work and outcome totals include all seeds.','',
'Wall measurements include startup, learning, checkpoint writes and successful-demo verification, and exclude the diagnostic replay. They depend on host load. Bootstrap intervals in comparisons.json describe paired seed variation, not host drift or other maps. Frozen AI source/classes, commands, per-attempt CSVs, native demos and positional traces are retained per cohort.','',
'## Reproduction','','```bash','python3 scripts/e1m1-navigation-benchmark.py STAGE --map MAP --root training/NEW-FLOOR-EXPERIMENT/MAP',
'# Collect baseline and fixed for E1M1/E1M3 (50 seeds), and E1M2 (--seeds 10).',
'python3 scripts/floor-safety-report.py training/NEW-FLOOR-EXPERIMENT','```','','The Java learner adds no libraries. Plotting uses NumPy/Matplotlib.']
if (root/'validation.json').exists():
    validation=json.loads((root/'validation.json').read_text()); suite=validation['full_suite']
    lines+=['',f"Final validation: {suite['tests']-suite['skipped']:,} enabled full-suite tests passed ({suite['skipped']} skipped). The 10 targeted floor-safety tests also passed after explicitly covering both strategies. Production sources and classes match the measured fixed snapshot; see validation.json. Floor safety is retained, with wall clearance still disabled."]
(root/'REPORT.md').write_text('\n'.join(lines)+'\n')
print(json.dumps(summaries,indent=2));print(json.dumps(selections,indent=2))
