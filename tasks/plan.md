# vm-engine implementation plan

Implements the approved SPEC-vm-engine.md. User approval explicitly authorized
implementation including QEMU and scoped Linux CI. Tasks: tasks/todo.md.

Dependency order: verified fixture + pinned native build → private managed service
boot → control/restart/process proof → network validation → engine acceptance.

Parallel work: fixture tooling; native source/build pipeline; Kotlin service and
validated launch request. The parent coordinates Gradle wiring and QMP tests.
No VM instance setting or desktop claim before the engine acceptance gate.

The first checkpoint is actual fixture boot plus Android library build. A Linux
host boot validates only the fixture. The next checkpoint is instrumentation of
the same kernel inside the Android-managed service, with guest64 forks, host
children observation, pause/resume, stop and a fresh PID second boot.

Risks: Android portability dependencies may require iterative compiler fixes;
pin current Termux patches and NDKr30, inspect all linked dependencies. QEMU
global state requires a fresh service process each launch. Private DNS requires
an Android resolver bridge, not merely guest DNS configuration. Missing phone
evidence is recorded, never replaced with mock success. CPU/GPU performance is
measured in later desktop integration rather than inferred from emulator tests.

After each coherent slice: focused tests, appropriate build, review, commit.
CI creates proof artifacts only; no automated APK release or user-data migration.
