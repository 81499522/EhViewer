# Rust native boundary

This Cargo workspace builds **Android only** for the app. Host builds below are regression tools, not additional app platform targets. KMP/CMP and Android bytecode settings remain in Gradle.

- `core/` (`ehviewer_core`): parser/DTOs, image slices and WebP codec FFI, SHA-1, natural sort, GIF bytes, descriptor I/O and safe libarchive sessions. No JNI, `ndk`, `android_logger`, Bitmap or HardwareBuffer dependency.
- Root package (`ehviewer_rust`): static library containing the existing JNI exports. `src/ffi/` translates Java types/errors and owns direct-buffer registrations and Android locks. Feature names `jvm`, `android`, `android-26` are retained.
- `../third-party/CMakeLists.txt`: pinned libarchive/liblzma/Nettle/WebP and Corrosion builds, then links `libehviewer.so`. The generated empty assembly unit is only a CMake/AGP link anchor; no app C algorithm or C JNI wrapper remains.

`ArchiveSession::open` is unsafe only at the file-mapping boundary: the file must remain unmodified/untruncated until the session **and returned mapped page buffers** are gone. Closing/unlinking the caller's descriptor/file is safe. Core sessions are independent; the unchanged Kotlin JNI API still exposes one active session. Concurrent reads retain their own `Arc`. Native libarchive readers move exclusively through a mutex-protected pool; `Reader: Send` is justified against libarchive's thread-safety contract and does not imply `Sync`.

Release uses `panic=unwind` and builds std with `panic-unwind`, so JNI guards can catch Rust panics. Results become the existing null/false/zero archive sentinels, or Java RuntimeException for throwing APIs. Allocation failure or a third-party C fault is not a catchable Rust panic.

A returned ByteBuffer stays alive until `releaseByteBuffer`/`munmap` receives **the original returned object**. Release is idempotent for that object, including after its native address is reused. After release, callers must not access it or its views. Compressed pages decode directly into exclusively owned storage. Stored pages use owned copies while a 4-MiB outstanding-copy budget permits, then fall back to independent private COW mappings. Released COW views up to 16 MiB can be reused: the entire view is compared with the immutable source before reuse, and any modified view is discarded and remapped. No live writable mapping is shared between leases. Close/reopen does not invalidate outstanding buffers. Do not truncate a mapped file, including after `closeArchive` while an output view remains live.

Each session caches at most four decode allocations totaling 16 MiB, four released private views totaling 16 MiB, and a separate read-only input-view LRU limited to four views/16 MiB. These input views avoid making every copied page resident in the full input mapping. Cached bytes, live copies and third-party decoder windows are separate memory costs; these limits are not a process-RSS bound. Mutable decode bytes are refilled before every reused lease is published. Pages larger than the view-cache budget retain the uncached mapping path. The copied path remains preferable for a single small outstanding page; comparison-based private-view reuse targets retained-page pressure rather than claiming zero-copy C parity.

Ownership is transferred into and out of caches. Source bytes are borrowed for copying/comparison; `Arc::clone` only pins sessions, views or password storage across a lock boundary and does not copy their data. Passwords are no longer deep-cloned when constructing a reader.
Once a private view is found modified, a four-entry admission history temporarily
bypasses reuse for that page. Repeated writers therefore use fresh private maps
instead of repeatedly paying a full comparison before remapping. This is a
bounded optimization hint; correctness always requires either a complete
comparison or a fresh private mapping.

The JNI registry indexes allocation addresses, accounts published bytes incrementally and verifies Java object identity on release. GIF rewrites retain the containing allocation (including sliced views) under a per-allocation mutex; independent allocations do not hold the global registry lock while rewriting. Callers still must not mutate or release their own buffer concurrently with other accesses.

Defaults: archive <=8 GiB (also `isize::MAX`), image <=256 MiB, declared regular-file expansion <=64 GiB, <=100,000 headers, filename/password <=4096 bytes, <=4 simultaneous decoders, <=20 cached contexts, <=128/512 MiB published JNI buffers (pooled allocation capacity is charged, not only the visible slice). The latter is an outstanding-buffer budget, not a hard process-RSS cap; in-flight allocations and third-party decoder windows consume additional memory. Unsafe paths and unsupported/oversized inputs fail explicitly.

```sh
# Host development libraries: libarchive + libwebp; macOS may require LIBRARY_PATH.
cargo fmt --all -- --check
cargo test --locked -p ehviewer_core
cargo test --locked --features jvm --lib
for target in aarch64-linux-android thumbv7neon-linux-androideabi x86_64-linux-android; do
  cargo clippy --locked --workspace --all-features --target "$target" -- -D warnings
done
# Host-only dynamic JNI library for the unchanged JDK25 clients:
cargo rustc --locked --release --features jvm --lib --crate-type cdylib
```

Archive regression fixtures and their licenses are restored locally into the ignored `.local-test-fixtures/` directory from immutable baseline `8fdfb6c5550fab506759a5587b1c8ee4050f507e`. Rust tests restore their inputs automatically; Android test assets are prepared by Gradle. A full Git history is required (`git fetch --unshallow` for shallow clones). Reproducible Kotlin verification tools are documented in `../../tools/verification/README.md`. Migration logs and temporary C comparison drivers are retained locally outside the repository.
