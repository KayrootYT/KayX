# KayX audit – status (interrupted early by request)

## DONE
**Rust core** (compiled + tested with rustc): axml.rs overflow/32-bit wrap, depth (256) and output (64MB) caps, attr clamp;
new verify.rs byte-compare so MD5 collisions can't create false "duplicates"; scan skips .KayXRecycleBin; overflow-checks=true.
**ARSC/AXML Kotlin** (JUnit run, 9 pass): Long-safe chunk math, compact entries, richer config labels, depth/output caps.
**Written but only partly compiled (no smali/BC/commons-compress/Android libs offline)**: DexRepository (checksum, MUTF-8, caps),
ApkPatcher (keeps resources.arsc STORED+aligned – old code made APKs uninstallable on Android 11+), ApkSignRepo (no silent key
regeneration, key/cert match check, in!=out), SigningKeyUtil, ArchiveRepository/MultiExtract cumulative byte budget,
ArchiveEdit bounded reads, ApkBundleRepository (session close, receiver + manifest entry), SmaliRepository atomic save.
New tests added: ArscParserTest, AxmlDecoderTest.

## NOT DONE
1. Shizuku/root: AdvancedAccessRepository auto-runs `su` on Settings (no timeout, violates own plan); RootShell timeout/child
   kill/unbounded output/stdin; ShizukuUserService NUL-separator breaks binary `head`, UTF-16 Binder size overflow, timeout param ignored;
   `--` before paths, refuse `rm -rf /`.
2. Recycle bin 30-day auto-purge (purgeExpired, orphan handling, crash-safe trash(), expiry in UI, call on app start).
3. Private space encryption (AES-GCM chunked vault, PIN+Keystore wrapped key, migration of existing plaintext, vault UI).
4. Unit tests for ApkPatcher/DexRepository/SigningKeyUtil/archive budget; run full Gradle build (needs network).

## Integration note
This file is the status recorded by the supplied partial audit archive. It is not a claim that the listed incomplete items have since been implemented or device-verified. Re-audit each item against the current source and CI before release.
