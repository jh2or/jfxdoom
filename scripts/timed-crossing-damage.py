#!/usr/bin/env python3
"""Measure actual floor damage in recorded demos, without changing production engine code.

Compile an analysis-only PSpec overlay that logs health/armor deltas immediately
around each floor damage call. Replay first/winning demos and require its full
trace to match ordinary, uninstrumented replay. The logger neither draws random
numbers nor modifies simulation state. Training and its wall timings use the
unmodified engine. Usage: python3 scripts/timed-crossing-damage.py ROOT
"""
import concurrent.futures
import csv
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
    overlay = root / 'damage-overlay'
    overlay.mkdir(exist_ok=True)
    source = (REPO / 'src/main/java/doom/play/PSpec.java').read_text()
    start = source.index('    public static void pPlayerInSpecialSector(')
    end = source.index('    private PSpec()', start)
    body = source[start:end]
    assert body.count('PInter.pDamageMobj(player.mo, null, null,') == 4
    body = body.replace('PInter.pDamageMobj(player.mo, null, null,', 'measuredFloorDamage(player,')
    helper = '''    private static void measuredFloorDamage(Player player, int damage) {
        int health = player.health, armor = player.armorpoints;
        PInter.pDamageMobj(player.mo, null, null, damage);
        System.out.println("FLOOR_DAMAGE," + PTick.leveltime + "," + (health - player.health)
                + "," + (armor - player.armorpoints) + "," + damage);
    }

'''
    (overlay / 'PSpec.java').write_text(source[:start] + body + helper + source[end:])
    base_classes = root / 'E1M1/baseline/classes'
    if not parse_only: subprocess.run(['javac', '--release', '21', '-cp', str(base_classes), '-d', str(overlay / 'classes'),
                    str(overlay / 'PSpec.java')], check=True, cwd=REPO)
    (overlay / 'metadata.json').write_text(json.dumps(dict(
        production_source_sha256=hashlib.sha256(source.encode()).hexdigest(),
        instrumented_source_sha256=hashlib.sha256((overlay / 'PSpec.java').read_bytes()).hexdigest()), indent=2))
    jars = sorted(Path('/tmp/jfxdoom-gradle/caches/modules-2/files-2.1/org.openjfx').glob('**/*-linux.jar'))
    jobs = sorted(root.glob('E1M*/baseline/seed-*')) + sorted(root.glob('E1M*/timed-basin/seed-*'))

    def job(seed):
        stage, level = seed.parent, seed.parent.parent.name
        normal_cp = os.pathsep.join(map(str, [stage / 'classes', REPO / 'build/resources/main', *jars]))
        measured_cp = str(overlay / 'classes') + os.pathsep + normal_cp
        base = ['java', '-XX:ActiveProcessorCount=1', '-Xmx768m', '-cp']
        tail = ['doom.ai.Trainer', '--wad', str(REPO / 'reference-wads/DOOM.WAD'), '--map', level,
                '--skill', '3', '--episode-tics', '21000']
        results, by_hash = {}, {}
        for kind in ('first', 'winning'):
            demo = seed / (kind + '.lmp')
            if not demo.exists(): continue
            digest = hashlib.sha256(demo.read_bytes()).hexdigest()
            if digest in by_hash:
                results[kind] = dict(by_hash[digest], same_demo_as='first')
                continue
            out = seed / ('damage-' + kind)
            out.mkdir(exist_ok=True)
            cmd = base + [measured_cp] + tail + ['--analyze', str(demo), '--output', str(out)]
            if not parse_only:
                with (out / 'measured.log').open('w') as log:
                    subprocess.run(cmd, cwd=REPO, stdout=log, stderr=subprocess.STDOUT, check=True, timeout=180)
            events = [list(map(int, line.split('FLOOR_DAMAGE,',1)[1].split(','))) for line in (out / 'measured.log').read_text().splitlines()
                      if 'FLOOR_DAMAGE,' in line]
            (out / 'events.json').write_text(json.dumps(events))
            measured_trace = (out / 'trace.csv').read_bytes()
            if kind == 'first':
                normal_trace = (seed / 'trace.csv').read_bytes()
            else:
                normal = out / 'normal'
                cmd = base + [normal_cp] + tail + ['--analyze', str(demo), '--output', str(normal)]
                if not parse_only:
                    with (out / 'normal.log').open('w') as log:
                        subprocess.run(cmd, cwd=REPO, stdout=log, stderr=subprocess.STDOUT, check=True, timeout=180)
                normal_trace = (normal / 'trace.csv').read_bytes()
            assert measured_trace == normal_trace, f'Instrumentation changed replay: {demo}'
            result = dict(demo_sha256=digest, calls=len(events), damaging_hits=sum(h > 0 or a > 0 for _, h, a, _ in events),
                          health_lost=sum(h for _, h, _, _ in events), armor_lost=sum(a for _, _, a, _ in events),
                          raw_damage=sum(d for _, _, _, d in events), trace_matches=True)
            results[kind] = by_hash[digest] = result
        (seed / 'floor-damage.json').write_text(json.dumps(results, indent=2) + '\n')
        print(f'{level}/{stage.name}/{seed.name}: ' + json.dumps(results), flush=True)
        return dict(map=level, stage=stage.name, seed=int(seed.name.split('-')[1]), **results)

    with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
        results = list(pool.map(job, jobs))
    (root / 'floor-damage.json').write_text(json.dumps(results, indent=2) + '\n')


if __name__ == '__main__':
    main()
