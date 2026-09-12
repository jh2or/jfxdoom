#!/usr/bin/env python3
"""Freeze one build and measure independent, paired learning seeds (E1M1 by default).

Run `bash gradlew classes` first, then invoke this script once per cumulative
implementation stage. Each seed starts with an empty archive. Generated files
live under training/; the first-attempt demo is preserved even after retries.
"""
import argparse
import concurrent.futures
import csv
import difflib
import hashlib
import json
import os
from pathlib import Path
import shutil
import statistics
import subprocess
import time

REPO = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("stage")
    parser.add_argument("--root", type=Path, default=REPO / "training/e1m1-navigation-stages")
    parser.add_argument("--snapshot", type=Path, help="Use frozen classes/source from a previous stage instead of the working build")
    parser.add_argument("--seeds", type=int, default=50)
    parser.add_argument("--workers", type=int, default=3)
    parser.add_argument("--map", default="E1M1", help="Map to measure with the same fresh learner seeds")
    args = parser.parse_args()
    root = args.root.resolve() / args.stage
    root.mkdir(parents=True, exist_ok=False)
    classes = args.snapshot.resolve() / "classes" if args.snapshot else REPO / "build/classes/java/main"
    sources = args.snapshot.resolve() / "source" if args.snapshot else REPO / "src/main/java/doom/ai"
    shutil.copytree(classes, root / "classes")
    shutil.copytree(sources, root / "source")
    jars = sorted(Path("/tmp/jfxdoom-gradle/caches/modules-2/files-2.1/org.openjfx").glob("**/*-linux.jar"))
    cp = os.pathsep.join(map(str, [root / "classes", REPO / "build/resources/main", *jars]))
    base = ["java", "-XX:ActiveProcessorCount=1", "-Xmx768m", "-cp", cp, "doom.ai.Trainer",
            "--wad", str(REPO / "reference-wads/DOOM.WAD"), "--map", args.map, "--skill", "3",
            "--strategy", "speed", "--episode-tics", "21000"]
    metadata = dict(stage=args.stage, map=args.map, seeds=args.seeds, workers=args.workers, attempt_limit=100,
                    wall_limit_seconds=600, episode_limit_seconds=600, strategy="speed", skill=3,
                    commit=subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=REPO, text=True).strip(),
                    wad_sha256=hashlib.sha256((REPO / "reference-wads/DOOM.WAD").read_bytes()).hexdigest())
    metadata["source_snapshot"] = str(args.snapshot.resolve()) if args.snapshot else None
    metadata["source_sha256"] = {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(sources.glob("*.java"))}
    (root / "workspace.patch").write_bytes(subprocess.check_output(["git", "diff"], cwd=REPO))
    if args.snapshot:
        patch = ""
        for p in sorted(sources.glob("*.java")):
            current = REPO / "src/main/java/doom/ai" / p.name
            patch += "".join(difflib.unified_diff(current.read_text().splitlines(True) if current.exists() else [],
                                                p.read_text().splitlines(True), fromfile="working/" + p.name, tofile="snapshot/" + p.name))
        (root / "changes.patch").write_text(patch)
    else:
        shutil.copyfile(root / "workspace.patch", root / "changes.patch")
    (root / "experiment.json").write_text(json.dumps(metadata, indent=2) + "\n")

    def job(seed):
        out = root / f"seed-{seed:02d}"
        out.mkdir()
        commands = []
        started = time.monotonic()

        def run(extra, log):
            cmd = base + ["--seed", str(seed), "--output", str(out)] + extra
            commands.append(cmd)
            with (out / log).open("w") as stream:
                subprocess.run(cmd, cwd=REPO, stdout=stream, stderr=subprocess.STDOUT, check=True, timeout=660)

        run(["--attempts", "1", "--minutes", "10", "--first-completion"], "first.log")
        shutil.copyfile(out / "latest.lmp", out / "first.lmp")
        if not (out / "winning.lmp").exists():
            remaining = max(0.01, (600 - (time.monotonic() - started)) / 60)
            run(["--resume", "--attempts", "99", "--minutes", str(remaining), "--first-completion"], "retries.log")
        wall = time.monotonic() - started
        rows = list(csv.DictReader((out / "attempts.csv").open()))
        won = rows[-1]["outcome"] == "COMPLETED"
        # Replay the unoptimized first attempt for comparable positional diagnostics.
        run(["--analyze", str(out / "first.lmp")], "analysis.log")
        trace = list(csv.DictReader((out / "trace.csv").open()))
        # Fixed 2-second windows: <16 units displacement is a dwell proxy,
        # including idle/turning, combat, and small loops. It is not an objective sensor.
        dwell = sum(2 for i in range(70, len(trace), 70)
                    if (float(trace[i]["x"]) - float(trace[i-70]["x"])) ** 2
                    + (float(trace[i]["y"]) - float(trace[i-70]["y"])) ** 2 < 16 ** 2)
        result = dict(seed=seed, completed=won, attempts=len(rows), process_wall_seconds=wall,
                      simulated_seconds=sum(int(r["tics"]) for r in rows) / 35,
                      completion_seconds=int(rows[-1]["tics"]) / 35 if won else None,
                      deaths=sum(r["outcome"] == "DEAD" for r in rows),
                      timeouts=sum(r["outcome"] == "TIMEOUT" for r in rows),
                      other=sum(r["outcome"] not in ("DEAD", "TIMEOUT", "COMPLETED") for r in rows),
                      first_outcome=rows[0]["outcome"], first_seconds=int(rows[0]["tics"]) / 35,
                      first_dwell_seconds=dwell, first_idle_seconds=int(rows[0]["idle_tics"]) / 35,
                      first_blocked_seconds=int(rows[0]["blocked_tics"]) / 35)
        # Each invocation's CSV wall clock starts at zero.
        result["training_wall_seconds"] = float(rows[0]["seconds"]) + (float(rows[-1]["seconds"]) if len(rows) > 1 else 0)
        (out / "commands.json").write_text(json.dumps(commands, indent=2) + "\n")
        (out / "measurement.json").write_text(json.dumps(result, indent=2) + "\n")
        print(f"{args.stage} seed={seed} attempts={len(rows)} outcome={rows[-1]['outcome']} game={result['completion_seconds']} wall={wall:.1f}", flush=True)
        return result

    with concurrent.futures.ThreadPoolExecutor(max_workers=args.workers) as pool:
        results = sorted(pool.map(job, range(1, args.seeds + 1)), key=lambda r: r["seed"])
    (root / "results.json").write_text(json.dumps(results, indent=2) + "\n")
    wins = [r for r in results if r["completed"]]
    summary = dict(stage=args.stage, seeds=len(results), completions=len(wins),
                   attempts=sum(r["attempts"] for r in results), deaths=sum(r["deaths"] for r in results),
                   timeouts=sum(r["timeouts"] for r in results), other=sum(r["other"] for r in results),
                   first_successes=sum(r["first_outcome"] == "COMPLETED" for r in results))
    for metric in ("attempts", "completion_seconds", "process_wall_seconds", "simulated_seconds"):
        values = [r[metric] for r in wins]
        summary["mean_" + metric] = statistics.mean(values) if values else None
        summary["median_" + metric] = statistics.median(values) if values else None
    for metric in ("first_dwell_seconds", "first_idle_seconds", "first_blocked_seconds"):
        summary["mean_" + metric] = statistics.mean(r[metric] for r in results)
    (root / "summary.json").write_text(json.dumps(summary, indent=2) + "\n")
    print(json.dumps(summary, indent=2), flush=True)


if __name__ == "__main__":
    main()
