"""Exercise installer integrity gates with the signed APK and mocked root/install tools.

Usage: python scripts/test-verified-install.py /path/to/bash /path/to/signed.apk
No root commands or network requests are made.
"""
import os
from pathlib import Path
import subprocess
import sys
import tempfile


def shell_path(path):
    value = str(Path(path).resolve()).replace("\\", "/")
    return "/" + value[0].lower() + value[2:] if os.name == "nt" else value


bash, apk = sys.argv[1:]
installer = Path(__file__).resolve().with_name("install-verified-v0.5.0.sh")
with tempfile.TemporaryDirectory(prefix="linex-install-test-") as folder:
    work = Path(folder).resolve()
    assert work.parent == Path(tempfile.gettempdir()).resolve()
    mocks = work / "bin"
    mocks.mkdir()
    stage = work / "stage"
    stage.mkdir()
    corrupt = work / "corrupt.apk"
    corrupt.write_bytes(Path(apk).read_bytes() + b"changed")
    tools = {
        "curl": '''#!/bin/sh
while [ "$#" -gt 0 ]; do
    if [ "$1" = --output ]; then destination=$2; shift 2; else shift; fi
done
cp "$MOCK_APK" "$destination"
if [ "$MOCK_MODE" = corrupt_download ]; then printf changed >> "$destination"; fi
''',
        "su": '''#!/bin/sh
printf invoked > "$SU_MARKER"
command=$(printf '%s' "$2" | sed "s|/data/local/tmp|$MOCK_STAGE|g; s|/system/bin/pm|$MOCK_BIN/pm|g; s|/system/bin/am|$MOCK_BIN/am|g")
exec /bin/sh -c "$command"
''',
        "cat": '''#!/bin/sh
/bin/cat "$@"
if [ "$MOCK_MODE" = corrupt_stage ]; then printf changed; fi
''',
        "pm": '''#!/bin/sh
if [ "$1" = path ]; then
    if [ "$MOCK_MODE" = missing_package ]; then exit 1; fi
    if [ "$MOCK_MODE" = installed_corrupt ]; then printf 'package:%s\\n' "$MOCK_CORRUPT";
    else printf 'package:%s\\n' "$MOCK_APK"; fi
    exit 0
fi
printf '%s\\n' "$*" > "$PM_MARKER"
if [ "$MOCK_MODE" = install_failure ]; then echo 'Failure [test rejection]'; exit 1; fi
echo Success
''',
        "am": '''#!/bin/sh
if [ "$MOCK_MODE" = invalid_user ]; then echo unexpected; else echo 10; fi
''',
    }
    for name, content in tools.items():
        stub = mocks / name
        stub.write_text(content)
        stub.chmod(0o755)
    env = os.environ.copy()
    env.update(MOCK_BIN=shell_path(mocks), MOCK_STAGE=shell_path(stage),
               MOCK_APK=shell_path(apk), INSTALLER=shell_path(installer),
               MOCK_CORRUPT=shell_path(corrupt),
               TMPDIR=shell_path(work), SU_MARKER=shell_path(work / "su-called"),
               PM_MARKER=shell_path(work / "pm-called"))

    def run(arguments):
        return subprocess.run([bash, "-c",
            'export PATH="$MOCK_BIN:/usr/bin:/bin"; sh "$INSTALLER" "$@"',
            "test", *arguments], env=env, capture_output=True, text=True, timeout=30)

    env["MOCK_MODE"] = "success"
    assert run(["--check-file", shell_path(apk)]).returncode == 0
    assert run(["--check-file", shell_path(corrupt)]).returncode != 0
    for mode in ["success", "corrupt_download", "corrupt_stage", "install_failure",
                 "missing_package", "installed_corrupt", "invalid_user"]:
        env["MOCK_MODE"] = mode
        for marker in [work / "su-called", work / "pm-called"]:
            marker.unlink(missing_ok=True)
        result = run([])
        assert (result.returncode == 0) == (mode == "success"), result.stdout + result.stderr
        pm_called = mode in ["success", "install_failure", "missing_package", "installed_corrupt"]
        assert (work / "pm-called").exists() == pm_called
        if pm_called:
            assert (work / "pm-called").read_text().startswith("install -r --user 10 ")
        assert (work / "su-called").exists() == (mode != "corrupt_download")
        assert not list(stage.iterdir()), "Root staging files were not cleaned up"
        assert not list(work.glob("linex-download.*")), "Download files were not cleaned up"
        print(mode + ": passed")
print("9 verified installer checks passed; no real root/install operations performed.")
