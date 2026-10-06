"""A reused native build must retain its own repository and compiler inputs."""
import copy
import importlib.util
from pathlib import Path
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("native_provenance", Path(__file__).with_name("verify-native-provenance.py"))
VERIFIER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(VERIFIER)


class NativeProvenanceTests(unittest.TestCase):
    def setUp(self):
        self.run = {"id": 42, "status": "completed", "conclusion": "failure", "event": "workflow_dispatch",
                    "path": ".github/workflows/vm-engine.yml", "head_sha": "a" * 40,
                    "repository": {"full_name": "lupixele/linex"}, "head_repository": {"full_name": "lupixele/linex"}}
        self.jobs = [{"id": i, "name": f"native-engine ({abi})", "run_id": 42,
                      "head_sha": "a" * 40, "status": "completed", "conclusion": "success"}
                     for i, abi in enumerate(("arm64-v8a", "x86_64"), 1)]

    def validate(self):
        return VERIFIER.validate_metadata(self.run, self.jobs, "lupixele/linex", "42")

    def test_successful_native_jobs_allow_failed_android_job(self):
        self.assertEqual("a" * 40, self.validate()["head_sha"])

    def test_foreign_run_or_head_repository_is_rejected(self):
        for field in ("repository", "head_repository"):
            original = copy.deepcopy(self.run)
            self.run[field]["full_name"] = "someone/linex"
            with self.assertRaisesRegex(ValueError, "repository"):
                self.validate()
            self.run = original

    def test_wrong_source_workflow_or_event_is_rejected(self):
        for field, value in (("path", ".github/workflows/untrusted.yml"), ("event", "pull_request")):
            original = copy.deepcopy(self.run)
            self.run[field] = value
            with self.assertRaisesRegex(ValueError, "workflow"):
                self.validate()
            self.run = original

    def test_missing_or_failed_native_job_is_rejected(self):
        self.jobs.pop()
        with self.assertRaisesRegex(ValueError, "native job"):
            self.validate()
        self.jobs[0]["conclusion"] = "failure"
        with self.assertRaisesRegex(ValueError, "native job"):
            self.validate()

    def test_job_wrong_run_or_commit_is_rejected(self):
        for field, value in (("run_id", 43), ("head_sha", "b" * 40)):
            original = copy.deepcopy(self.jobs)
            self.jobs[0][field] = value
            with self.assertRaisesRegex(ValueError, "native job"):
                self.validate()
            self.jobs = original

    def test_duplicate_native_job_is_rejected(self):
        self.jobs.append(copy.deepcopy(self.jobs[0]))
        with self.assertRaisesRegex(ValueError, "native job"):
            self.validate()

    def test_noninteger_run_and_invalid_commit_are_rejected(self):
        with self.assertRaisesRegex(ValueError, "run ID"):
            VERIFIER.validate_metadata(self.run, self.jobs, "lupixele/linex", "42; echo unsafe")
        self.run["head_sha"] = "--unsafe-option"
        with self.assertRaisesRegex(ValueError, "commit"):
            self.validate()

    def test_actual_git_diff_allows_kotlin_and_rejects_changed_compiler_inputs(self):
        scratch = ROOT / "build/test-scratch"
        scratch.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(prefix="native-provenance-", dir=scratch) as directory:
            repository = Path(directory)
            def git(*arguments):
                return subprocess.check_output(["git", *arguments], cwd=repository, text=True).strip()
            git("init", "--quiet")
            git("config", "user.name", "Linex CI proof test")
            git("config", "user.email", "ci-proof@example.invalid")
            native = repository / "scripts/vm/build-engine.sh"
            native.parent.mkdir(parents=True)
            native.write_text("original native input\n")
            git("add", ".")
            git("commit", "--quiet", "-m", "native baseline")
            baseline = git("rev-parse", "HEAD")
            kotlin = repository / "VmHostProcessStats.kt"
            kotlin.write_text("Kotlin-only change\n")
            VERIFIER.validate_inputs(baseline, repository)
            git("add", ".")
            git("commit", "--quiet", "-m", "runtime fix")
            VERIFIER.validate_inputs(baseline, repository)
            native.write_text("changed native input\n")
            with self.assertRaisesRegex(ValueError, "inputs changed"):
                VERIFIER.validate_inputs(baseline, repository)
            git("add", ".")
            with self.assertRaisesRegex(ValueError, "inputs changed"):
                VERIFIER.validate_inputs(baseline, repository)
            git("commit", "--quiet", "-m", "changed compiler input")
            with self.assertRaisesRegex(ValueError, "inputs changed"):
                VERIFIER.validate_inputs(baseline, repository)
            native.write_text("original native input\n")
            workflow = repository / ".github/workflows/vm-engine.yml"
            workflow.parent.mkdir(parents=True)
            workflow.write_text("changed compiler job definition\n")
            git("add", ".")
            git("commit", "--quiet", "-m", "changed native workflow")
            with self.assertRaisesRegex(ValueError, "inputs changed"):
                VERIFIER.validate_inputs(baseline, repository)

    def test_untracked_and_ignored_native_inputs_are_rejected(self):
        scratch = ROOT / "build/test-scratch"
        scratch.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(prefix="native-untracked-", dir=scratch) as directory:
            repository = Path(directory)
            def git(*arguments):
                return subprocess.check_output(["git", *arguments], cwd=repository, text=True).strip()
            git("init", "--quiet")
            git("config", "user.name", "Linex CI proof test")
            git("config", "user.email", "ci-proof@example.invalid")
            (repository / ".gitignore").write_text("*.h\n")
            git("add", ".")
            git("commit", "--quiet", "-m", "baseline")
            baseline = git("rev-parse", "HEAD")
            addition = repository / "vm-engine/src/main/jni/new-input.c"
            addition.parent.mkdir(parents=True)
            addition.write_text("pending native fix\n")
            with self.assertRaisesRegex(ValueError, "untracked"):
                VERIFIER.validate_inputs(baseline, repository)
            addition.rename(addition.with_suffix(".h"))
            with self.assertRaisesRegex(ValueError, "untracked"):
                VERIFIER.validate_inputs(baseline, repository)


if __name__ == "__main__":
    unittest.main()
