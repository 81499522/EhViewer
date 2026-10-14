# Android verification tools

This independent Kotlin/JVM build contains build and regression tools, not another
application platform. Run it with the repository Gradle Wrapper and JDK 25; its
bytecode target is Java 17. It uses Gradle's bundled Kotlin DSL and Groovy JSON
library. No Python installation or additional shell helper is required.

```sh
./gradlew -p tools/verification check installDist
tools/verification/build/install/verification/bin/verification architecture
tools/verification/build/install/verification/bin/verification native-sources
tools/verification/build/install/verification/bin/verification bytecode
tools/verification/build/install/verification/bin/verification apks app/build/outputs/apk/release --sdk "$ANDROID_HOME"
tools/verification/build/install/verification/bin/verification diagnostics --sdk "$ANDROID_HOME"
tools/verification/build/install/verification/bin/verification release-input-tests --sdk "$ANDROID_HOME"
```

Build Debug, androidTest, Release and benchmark outputs before the artifact
checks. `apks` also accepts `--debug --application-id moe.tarsin.ehviewer.debug`
and an optional `--version-code`. It verifies actual manifests, signatures,
certificate continuity, ABI/JNI exports, ELF LOAD alignment and ZIP alignment.
The release integration tests reject mismatched versions, missing/extra APKs and
a genuinely tampered signature without creating a public payload.

`check` also executes the actual Release publication script against a stateful
Kotlin GitHub CLI mock. These offline tests require Bash and jq, cover
first publication, draft retries, lightweight/annotated tags, creation races and
API failures, and never contact GitHub or publish assets.

```sh
tools/verification/build/install/verification/bin/verification prepare-release --tag v1.15.8 \
  --sdk "$ANDROID_HOME" --output release-dry-run/apks --private-dir "$PWD/.private-release" --retention-days 180
tools/verification/build/install/verification/bin/verification device --serial emulator-5560 \
  --sdk "$ANDROID_HOME" --output reports/api26
```

`prepare-release` accepts the actual AGP metadata's three ABI splits plus
universal APK, or exactly one architecture selected with `--abi` for a matrix part. It copies mapping and matching Rust Core/JNI symbols into a
version/commit directory with permissions 0700 and files 0600, records SHA-256
and the retention deadline, and refuses unsafe overwrites. Use a canonical local
path without symlink ancestors. Retention requires the owner's backup and
deletion policy; this tool does not provide durable remote storage or a scheduler.
The `Release` workflow automatically follows successful main push CI and builds
that exact commit. Its `build` matrix contains `arm64-v8a`, `armeabi-v7a`,
`x86_64` and `universal`. `-PreleaseAbi=ABI` builds one APK; universal includes
all three native ABIs. Each part verifies and encrypts its own mapping and native
symbols, so independently linked binaries retain their matching diagnostics.
`combine-release` rejects missing/extra parts, mixed commits/versions, dirty sources
and checksum/signature mismatches before staging exactly four APKs. All four encrypted
diagnostic bundles are retained for 90 days before publication.
Manual runs default to a dry run; checking `publish` requires
main and successful CI for the same commit. A version already published is
skipped, tags pointing elsewhere are rejected, and only the separate publication
job receives write permission. GitHub provides source archives itself.
Publication explicitly creates a missing tag at the verified source commit,
reuses a matching draft, rejects duplicate drafts and conflicting tags, and
checks the tag again after uploading the four APKs. Existing tags are never moved.

Production signing reads `EHVIEWER_KEYSTORE`, `EHVIEWER_STORE_PASSWORD`,
`EHVIEWER_KEY_ALIAS` and `EHVIEWER_KEY_PASSWORD`. For local builds, keep the
original keystore at `.private-signing/androidkey.jks` and the three properties
`storePassword`, `keyAlias`, `keyPassword` in `.private-signing/signing.properties`
(directory 0700, files 0600). These files must be backed up separately. Never
replace the production key. Trusted push CI builds restore the original signing key; pull requests and
secret-free manual CI builds use the debug signing key;
`apks --test-signing` accepts this only for SNAPSHOT versions. `-Prelease` fails
without the original external signing material, and final APK verification
requires the historical certificate digest.

Repository secrets: `ANDROID_KEYSTORE_BASE64`, `ANDROID_STORE_PASSWORD`,
`ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`. Repository variable:
`DIAGNOSTICS_PUBLIC_KEY` (RSA public PEM; never the private key).

```sh
tools/verification/build/install/verification/bin/verification diagnostics-key --output "$PWD/.private-release/keys"
tools/verification/build/install/verification/bin/verification encrypt-diagnostics \
  --private-dir "$PWD/.private-release/VERSION-COMMIT" \
  --public-key "$PWD/.private-release/keys/public.pem" --output diagnostics.enc
tools/verification/build/install/verification/bin/verification decrypt-diagnostics \
  --input diagnostics.enc --private-key "$PWD/.private-release/keys/private.pem" \
  --output "$PWD/.private-release/restored/diagnostics.zip"
```

The diagnostics envelope uses RSA-3072 OAEP (SHA-256 and MGF1-SHA256) and
AES-256-GCM with a random key/nonce and an authenticated header. It contains
mapping, matching native symbols and their version/commit/checksum manifest.
Only ciphertext is retained in Actions for 90 days; plaintext is removed from
the runner. Public repository artifacts are not private storage. Back up the
owner-only private key offline and download encrypted artifacts before expiry.
The decrypt command authenticates the complete envelope before retaining a
0600 plaintext archive in an owner-only directory.

`device` runs all discovered instrumentation tests on an already booted dedicated
local device and captures its API, page size, APK digests and logs. The removed
CI emulator matrix and its AVD provisioning code are no longer maintained here.

The host JNI performance workload is reproducible with JDK 25 and the same
client for original C and Rust libraries. Fixtures are generated locally:

```sh
tools/verification/build/install/verification/bin/verification native-benchmark-inputs --output /private/tmp/ehviewer-benchmark
JAVA_OPTS='--enable-native-access=ALL-UNNAMED --add-opens=java.base/java.io=ALL-UNNAMED' \
  tools/verification/build/install/verification/bin/verification native-benchmark \
  --library /absolute/path/to/libehviewer_rust.dylib --archive /private/tmp/ehviewer-benchmark/large-stored.zip \
  --warmup 50 --loops 300
```

Add `--samples` to retain all measured operation durations in original order and
report JVM GC count/time during the measured interval. Collection and serialization
occur outside operation timers. Use `--warmup 50 --loops 300 --retained 0` and
`--retained 64` for the existing comparison protocol, interleave independent JVM
trials, and stop build/emulator work before collecting performance results.
GC counters cannot attribute native tails or explain an earlier run that did not
record them; process-wide RSS/fault counts also include JVM startup.
`--mutate` adds a deterministic byte write to each extracted page as a separate
dirty-view stress workload. Keep it disabled for the original comparison and do
not compare its results to read-only trials. The original C implementation does
not provide the Rust implementation's independent writable-buffer guarantees.
`--cpu-samples` adds per-operation current-thread CPU durations and start offsets
for a separate diagnostic run (and implies `--samples`). It requires supported,
enabled JVM thread CPU timing. Combine it with JDK safepoint logging when
investigating wall-clock outliers. Do not mix instrumented CPU trials into the
original latency comparison.
The page-touch loop and timed extract/touch/release method are separate from
optional statistics, so adding diagnostic output does not enlarge their JIT
compilation bodies. Capture GC counters before allocating output sample lists.
Historical measurements still require caution: a fixed 50-operation warmup is
not proof that JIT compilation, CPU scheduling or the machine has stabilized.

Repeat independent JVM trials for both ZIP inputs, alternating library order.
`--retained N` measures extraction with other page buffers still outstanding.
The workload touches each 4-KiB page and releases it; it does not measure Android
rendering, real-device latency or long-session memory. Keep libraries, generated
inputs and raw results outside Git.

Regression archives, expected data and licenses are restored into the ignored
`.local-test-fixtures/` directory from immutable Git baseline
`8fdfb6c5550fab506759a5587b1c8ee4050f507e`. Rust tests restore their own inputs;
Gradle prepares Android test assets automatically. A full Git history is required
(`git fetch --unshallow` for shallow clones). No fixture data is tracked in the
current tree or included in production APKs. Parser inputs stay inline in Rust.
