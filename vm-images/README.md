# VM image installation

`VmImageInstaller(filesDir)` installs a catalogue-supplied `PinnedVmImage` and
reports bounded byte/stage progress through a callback. The application owns
foreground scheduling, notification throttling and catalogue release approval.
Cancellation is honoured during streaming and before publication; HTTPS reads
have a 15-second timeout. No guest disk is a cleanup target.

Boot assets are SHA-256 checked and published together under
`filesDir/vm-images/<imageId>/<revision>/{kernel,initramfs}`. They are shared
immutable assets. The private mutable disk and installation record are under
`filesDir/vm-instances/<instanceId>/{disk.raw,READY}`. Install before creating
serial/control files in the instance directory; an existing directory without a
valid installation record is preserved and reported as an error.

Downloads enforce HTTPS, exact lengths and pinned hashes. Expansion enforces an
exact expanded length/hash and decoder integrity checks, with a 64 MiB XZ memory
limit. Zero chunks become sparse holes where the filesystem supports them.
Published directories are mode 0700 and regular files are mode 0600. Symlinks,
foreign ownership and multiple hard links are rejected.

`readInstalled(instanceId)` verifies immutable boot bytes and the mutable disk's
type/length; it does not compare the running user's disk to the factory hash.
Reinstalling an already ready instance never replaces the user's disk.
`clone(sourceId, newId, progress)` snapshots a stopped mutable disk, assigns a
fresh instance identity and retains shared boot files. `delete(instanceId,
progress)` removes only the known instance files after the application obtains
the user's confirmation. Both acquire the same `engine.lock` as the VM service
and immediately refuse an active engine. Deletion also refuses unexpected data
or a live serial listener. It moves the checked instance out of the launch
namespace before removing known files; shared boot files and caches are retained.
The engine additionally enforces its production disk-size/alignment and RAM
bounds. This module cannot mark an unproved factory candidate release-ready.
