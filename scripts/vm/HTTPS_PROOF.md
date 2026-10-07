The separate HTTPS fixture adds pinned BIND `dig` and OpenSSL to the already verified
Alpine ARM64 userspace. Build it with `python scripts/vm/build-https-fixture.py`.
It reads exact SHA256 package pins and licenses from `https-fixture-packages.json`,
verifies package names, versions and dependency closure, and builds newc bytes
without host archive extraction or running APK install scripts. APK signatures are
retained in the hashed downloaded archives; this builder does not independently
verify their cryptographic signatures. Individual `.PKGINFO` notices include
upstream URLs and Alpine recipe commits. These are proof assets, not a desktop image.

The test-only TLS key in `https-test-tls/server-key.pk8` is deliberately public and
must never be used by a product server. Its CA is installed only at
`/linex-test-ca.pem`, never in the system certificate store. The original public CA
bundle is preserved and its digest recorded. OpenSSL checks chain, hostname and
certificate time; the host controller sets the accurate guest clock rather than
disabling time verification. Requests and handshakes have deadlines and bounded
output files.

`VmHttpsProofTest` expects six `https/` assets: kernel, the compressed initramfs
renamed to `https-proof.initramfs`, manifest.json, ca.pem, server.pem and
server-key.pk8. It independently proves the following through actual guest traffic:

- Four concurrently launched DNS clients reuse transaction ID4660 across UDP/TCP
  A/AAAA queries. Successful responses must retain ID, question and nonempty answer.
  This proves correct responses for separate guest sockets; pending-request overlap
  is not measured by this test.
- Public example.com HTTPS must verify using the original CA bundle and return
  HTTP200. A networking or certificate failure fails the gate; no insecure retry exists.
- An instrumentation-owned loopback TLS server independently receives a valid
  guest request. The guest must reject both wrong hostname and untrusted root.
- A distinct instrumentation-owned blackhole TCP endpoint proves the guest TLS
  command terminates within its deadline. This is not a DNS resolver cancellation test.
- Complete host observations remain at zero children; guest child cleanup and
  managed Binder death are verified before successful evidence is written.

Run the API33 emulator test twice for the Private DNS matrix, keeping separate
`vm-https-proof.json`, `.log` and launch3 exit artifacts for each run. CI may configure
the emulator's system Private DNS through its existing shell privileges; the app
and test do not change user-device settings or require root. The `expectedPrivateDns`
instrumentation argument accepts `off` or `strict`: the test verifies Android
`LinkProperties` has the matching active/strict state before and after guest probes.
Strict mode requires a reachable system-configured DoT provider and an active
validated network. If those cannot be established, record the matrix as unproved;
do not substitute a hardcoded guest resolver or label a skipped test as successful.
The normal run without this argument records observed Private DNS state only.

Native/Android broker cancellation, timeout and generation fencing are separate
deterministic tests using controlled backends. Public DNS cannot guarantee a slow
pending request, so it cannot establish resolver cancellation by timing alone.

Primary API references:
[BIND dig options](https://bind9.readthedocs.io/en/v9.20.23/manpages.html),
[OpenSSL s_client verification](https://docs.openssl.org/3.5/man1/openssl-s_client/),
[Android LinkProperties](https://developer.android.com/reference/android/net/LinkProperties).
