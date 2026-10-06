"""Reuse only this repository's successful build with unchanged compiler inputs."""
import argparse
import json
from pathlib import Path
import re
import subprocess


NATIVE_INPUTS = (
    ".github/workflows/vm-engine.yml", "scripts/vm/build-engine.sh",
    "scripts/vm/patch-qemu.py", "scripts/vm/native-sources.json",
    "scripts/vm/build-tools.txt", "scripts/vm/patches", "scripts/vm/verify-engine.py",
    "scripts/vm/test_native_build.py", "vm-engine/src/main/jni",
)


def validate_metadata(run, jobs, repository, run_id):
    if not re.fullmatch(r"[1-9][0-9]{0,19}", run_id) or run.get("id") != int(run_id):
        raise ValueError("Invalid or mismatched source run ID")
    if repository != "lupixele/linex" or any(
        run.get(field, {}).get("full_name") != repository
        for field in ("repository", "head_repository")
    ):
        raise ValueError("Source repository identity mismatch")
    if run.get("path") != ".github/workflows/vm-engine.yml" or run.get("event") != "workflow_dispatch":
        raise ValueError("Source workflow identity mismatch")
    sha = run.get("head_sha", "")
    if not re.fullmatch(r"[0-9a-f]{40}", sha):
        raise ValueError("Invalid immutable source commit")
    if run.get("status") != "completed":
        raise ValueError("Source build is not completed")
    job_ids = {}
    for abi in ("arm64-v8a", "x86_64"):
        matches = [job for job in jobs if job.get("name") == f"native-engine ({abi})"]
        if len(matches) != 1:
            raise ValueError(f"Missing or duplicated native job for {abi}")
        job = matches[0]
        if (job.get("status") != "completed" or job.get("conclusion") != "success"
                or job.get("run_id") != int(run_id) or job.get("head_sha") != sha):
            raise ValueError(f"Unverified native job for {abi}")
        job_ids[abi] = job["id"]
    return {"run_id": int(run_id), "head_sha": sha, "native_jobs": job_ids,
            "workflow": run["path"], "repository": repository,
            "source_run_conclusion": run.get("conclusion")}


def validate_inputs(sha, repository):
    for revisions in ((sha, "HEAD"), ("HEAD",)):
        result = subprocess.run(
            ["git", "diff", "--quiet", *revisions, "--", *NATIVE_INPUTS], cwd=repository,
            check=False, timeout=30,
        )
        if result.returncode == 1:
            raise ValueError("Native compiler inputs changed; run the full vm-engine.yml workflow")
        if result.returncode != 0:
            raise RuntimeError("Could not verify immutable native compiler inputs")
    # Even ignored source additions can alter a native build. Do not let
    # pending untracked input files bypass the two tracked-file comparisons.
    untracked = subprocess.check_output(
        ["git", "ls-files", "--others", "--", *NATIVE_INPUTS], cwd=repository,
        text=True, timeout=30,
    )
    if untracked:
        raise ValueError("Native compiler inputs changed: untracked input files")


def github(path):
    result = subprocess.run(["gh", "api", path], check=True, capture_output=True, text=True, timeout=60)
    return json.loads(result.stdout)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--repository", required=True)
    parser.add_argument("--evidence", type=Path, required=True)
    args = parser.parse_args()
    if not re.fullmatch(r"[1-9][0-9]{0,19}", args.run_id) or args.repository != "lupixele/linex":
        raise ValueError("Invalid source run ID or repository")
    prefix = f"repos/{args.repository}/actions/runs/{args.run_id}"
    run = github(prefix)
    jobs = github(prefix + "/jobs?filter=latest&per_page=100")
    if jobs.get("total_count") != len(jobs.get("jobs", [])):
        raise ValueError("Native job metadata is incomplete")
    evidence = validate_metadata(run, jobs["jobs"], args.repository, args.run_id)
    repository = Path(__file__).resolve().parents[2]
    subprocess.run(["git", "fetch", "--no-tags", "origin", evidence["head_sha"]],
                   cwd=repository, check=True, timeout=60)
    validate_inputs(evidence["head_sha"], repository)
    evidence["unchanged_inputs"] = list(NATIVE_INPUTS)
    evidence["proof_checkout"] = subprocess.check_output(
        ["git", "rev-parse", "HEAD"], cwd=repository, text=True, timeout=30,
    ).strip()
    args.evidence.parent.mkdir(parents=True, exist_ok=True)
    args.evidence.write_text(json.dumps(evidence, indent=2) + "\n")
