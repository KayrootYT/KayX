# KayX security and storage audit status

This status separates code changes from validation. A GitHub CI pass does not replace Android device testing.

## Completed in the current follow-up (pending CI and device verification)

### Rust core
- Rust duplicate detection uses hashes only as candidate filters, then verifies bytes; the Kotlin fallback now uses SHA-256 and byte-for-byte comparison too.
- Bounded native recursive scan to 200,000 files, 200,000 directories, 1,000,000 entries, 32 levels and 32 MiB serialized JNI output; symlink directories are not followed.
- Native scans now skip `.KayXRecycleBin` as well as Android/Trash thumbnail directories.
- Hardened AXML chunk arithmetic, declared-file bounds, string-pool offset/length checks, 256-level nesting and 64 MiB input/output limits.
- Added JNI limits for AXML byte arrays, duplicate path count/size and hex-dump output.

### Root and Shizuku
- Settings status checks no longer execute `su`; root authorization is requested only when a user explicitly runs a root operation.
- Root command stdout/stderr are drained concurrently with per-stream caps; execution timeout is clamped and timed-out processes are forcibly terminated.
- Shizuku AIDL protocol v2 now forwards the requested timeout and uses newline-separated Base64 fields instead of NUL separators, preserving binary data through Binder.
- Shizuku output is capped at 192 KiB per stream to keep the encoded transaction under Binder limits.
- Root path arguments use shell quoting and `--`; recursive delete refuses blank paths and critical filesystem roots.
- Root terminal cwd quoting and per-command output memory limits were added.
- Binary `head` previews are encoded before the text transport and decoded losslessly in the caller.

### Recycle Bin
- Added 30-day expiry calculation, daily unique WorkManager purge with retry/backoff, and an app-resume fallback.
- Added expiry countdown to the Recycle Bin UI.
- Index writes are staged and synced; previous index is recoverable after interrupted replacement.
- Trash intent is journaled before moving the source; missing payload records are cleaned, and old unindexed payloads are purged only after the retention window.
- Purge is repeatable and does not purge entries younger than 30 days.

### Private Space
- Added streaming AES-256-GCM per-file encryption with authenticated decryption; unauthenticated plaintext is never published from the temporary output.
- Random vault key is wrapped first with a PBKDF2-HMAC-SHA256 PIN-derived key, then with a non-exportable Android Keystore AES-GCM key.
- Existing app-private plaintext files are migrated on first successful PIN unlock. Encrypted files are verified by decrypting to a temporary tree and comparing SHA-256 before the vault is committed.
- Plaintext staging is wiped after successful lock; app backgrounding and leaving the private browser trigger lock attempts, and the UI requires PIN again.
- Locked private-space paths are not exposed to file-browser move operations; the home tile counts encrypted files while locked.
- Added AES-GCM round-trip, tamper rejection and wrong-key tests.

## Still requires validation / remaining risks

1. **CI:** Run full Android unit tests, Rust `cargo test`, AIDL generation, and debug APK build on the PR commit. The Rust NDK packaging path must also be checked in CI.
2. **Root/Shizuku device tests:** Verify real root prompt behavior, Shizuku v2 service binding, binary preview bytes, command timeout, stop behavior and output truncation on Android 8–16. Current timeout forcibly terminates the direct shell/service process; descendant process-tree termination still needs device validation and may need a process-group strategy.
3. **Private Space device tests:** Verify Android Keystore key creation/recovery on supported devices, first-unlock migration of legacy plaintext, large-file streaming, wrong PIN, tampered ciphertext, app background/foreground and process-death recovery. Android flash storage cannot guarantee forensic secure erase; the app deletes plaintext staging after vault verification but this is not a claim of physical-media sanitization.
4. Private Space filenames and directory names remain visible inside KayX's app-private vault directory; file contents are encrypted. Do not back up or export the app's Keystore key.
5. Recycle-bin automatic purge runs when KayX returns to the foreground; it is not a background WorkManager job when the app is never opened.
6. Manual destructive-operation checks and real-device regression tests remain required before release.

## Earlier completed work retained
- APK signing identity matching and transactional key/certificate replacement.
- DEX parsing bounds/checksum validation and APK DEX replacement verification.
- Existing ARSC/AXML Kotlin parser hardening and previous PIN-hash migration.
- Existing file-browser/search changes.

No release-ready claim is made until the fresh CI run and physical-device tests pass.


## Network storage hardening (Phase 6 completion branch)

- FTP/explicit FTPS: FTPS performs AUTH TLS before credentials, verifies certificate and hostname, protects data channels, and fails closed without TLS downgrade. Active data/control sockets can be cancelled.
- SFTP: requires a user-imported OpenSSH `known_hosts` file and `StrictHostKeyChecking=yes`; unknown host keys are rejected.
- SMB: configured for SMB 3.0+ only, with encryption and signing required and bounded socket/operation timeouts.
- WebDAV: HTTPS only, platform certificate/hostname verification, redirects disabled, response-size bounds, DOCTYPE/external entity protections, and same-origin response href checks.
- Remembered network passwords are opt-in and encrypted with an Android Keystore AES-GCM key; credential ciphertext is bound to the connection profile ID.
- The unified Network Storage screen supports browsing and common file operations; transfer cancellation closes the active protocol connection/request.
- **Still pending before Phase 6 completion:** latest full Build & Test on the PR head, resumable transfer support, disposable real-server integration tests for all protocols, and physical-device/Keystore validation. CI success alone is not a substitute for server/device tests.


## Static scanner findings reviewed

- MD5/SHA-1 in `ChecksumRepository` are compatibility outputs for user-requested legacy checksums only; they are not used for authentication or duplicate proof.
- `PBKDF2WithHmacSHA1` remains only to verify an existing legacy PIN before migrating the stored verifier to PBKDF2-HMAC-SHA256.
- PackageInstaller's install-status PendingIntent is intentionally mutable on Android 12+ because the package installer fills status extras into the callback Intent.
- Cleartext networking is limited to `127.0.0.1` and `localhost` for the in-app local preview server.
- MobSF's task-hijacking warning must be cross-checked against the built/merged manifest: Gradle sets targetSdk 34 and MainActivity has an empty taskAffinity. Confirm this in the produced APK before release.
- Informational MobSF checks for screenshots, root detection, TLS pinning and certificate transparency remain product-hardening decisions; they are not treated as build blockers for this offline file manager.
