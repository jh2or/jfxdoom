#!/usr/bin/env python3
"""Audit accepted crossings with diagnostic logging; require identical first-demo bytes.

Run after timed-crossing-damage.py has built the read-only PSpec logging overlay.
Only isolated analysis classes are instrumented, never production sources/classes.
"""
import concurrent.futures
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys

REPO = Path(__file__).resolve().parents[1]


def main():
    root = Path(sys.argv[1]).resolve()
    parse_only = '--parse-only' in sys.argv[2:]
    overlay = root / 'crossing-audit'
    overlay.mkdir(exist_ok=True)
    snapshot = root / 'E1M1/timed-basin'
    source = (snapshot / 'source/LocalController.java').read_text()
    needle = '''        if (timedCrossing != null && (timedFrame >= timedCrossing.frames().size()'''
    log = '''        if (timedCrossing != null && (timedFrame >= timedCrossing.frames().size()
                || !timedCrossing.frames().get(timedFrame).matches(o, now)))
            System.out.println("TIMED_END," + now + "," + (timedFrame >= timedCrossing.frames().size() && !FloorSafety.hazardous(o.x(), o.y()) ? "finished" : "interrupted"));
'''
    assert needle in source
    source = source.replace(needle, log + needle)
    needle = '        if (timedCrossing == null) return null;'
    source = source.replace(needle, needle + '''
        if (timedFrame == 0) System.out.println("TIMED_BEGIN," + now + "," + timedCrossing.dryTic());''')
    (overlay / 'LocalController.java').write_text(source)
    if not parse_only: subprocess.run(['javac', '--release', '21', '-cp', str(snapshot / 'classes'), '-d', str(overlay / 'classes'),
                    str(overlay / 'LocalController.java')], check=True, cwd=REPO)
    jars = sorted(Path('/tmp/jfxdoom-gradle/caches/modules-2/files-2.1/org.openjfx').glob('**/*-linux.jar'))
    jobs = [(level, seed) for level, count in (('E1M1',20),('E1M3',20),('E1M2',10)) for seed in range(1,count+1)]

    def job(pair):
        level, seed = pair
        measured = root / level / 'timed-basin' / f'seed-{seed:02d}'
        out = overlay / level / f'seed-{seed:02d}'
        out.mkdir(parents=True, exist_ok=True)
        cp = os.pathsep.join(map(str, [overlay / 'classes', root / 'damage-overlay/classes', measured.parent / 'classes',
                                      REPO / 'build/resources/main', *jars]))
        cmd = ['java', '-XX:ActiveProcessorCount=1', '-Xmx768m', '-cp', cp, 'doom.ai.Trainer', '--wad',
               str(REPO / 'reference-wads/DOOM.WAD'), '--map', level, '--skill', '3', '--strategy', 'speed',
               '--episode-tics', '21000', '--seed', str(seed), '--output', str(out),
               '--attempts', '1', '--minutes', '10', '--first-completion']
        if not parse_only:
            with (out / 'audit.log').open('w') as log:
                subprocess.run(cmd, cwd=REPO, stdout=log, stderr=subprocess.STDOUT, check=True, timeout=660)
        assert (out / 'latest.lmp').read_bytes() == (measured / 'first.lmp').read_bytes(), str(out)
        lines = (out / 'audit.log').read_text().splitlines()
        starts = [list(map(int,l.split('TIMED_BEGIN,',1)[1].split(','))) for l in lines if 'TIMED_BEGIN,' in l]
        # Trainer may replay successes for verification, yielding duplicate FLOOR_DAMAGE events.
        # Unique level-tic/health/armor tuples suffice for membership in accepted crossing windows.
        hits = set(tuple(map(int,l.split('FLOOR_DAMAGE,',1)[1].split(','))) for l in lines if 'FLOOR_DAMAGE,' in l)
        crossing_hits = [list(hit) for hit in sorted(hits) if any(start <= hit[0] <= end for start,end in starts)]
        result = dict(map=level,seed=seed,accepted=len(starts),windows=starts,
                      finished=sum(l.endswith(',finished') for l in lines if 'TIMED_END,' in l),
                      interrupted=sum(l.endswith(',interrupted') for l in lines if 'TIMED_END,' in l),
                      floor_damage_calls_in_windows=crossing_hits,
                      identical_demo=True, demo_sha256=hashlib.sha256((out/'latest.lmp').read_bytes()).hexdigest())
        (out/'audit.json').write_text(json.dumps(result,indent=2)+'\n')
        print(json.dumps(result),flush=True)
        return result
    with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
        results = list(pool.map(job,jobs))
    (root/'crossing-audit.json').write_text(json.dumps(results,indent=2)+'\n')


if __name__ == '__main__':
    main()
