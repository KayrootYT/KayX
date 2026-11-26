# Third-party notices

## smali / baksmali 2.5.2

KayX uses `org.smali:baksmali:2.5.2` for DEX-to-Smali disassembly.
This library is distributed under the BSD 3-Clause License. Copyright (c) 2010 Ben Gruver.

Redistribution and use in source and binary forms, with or without modification, are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice, this list of conditions and the following disclaimer.
2. Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following disclaimer in the documentation and/or other materials provided with the distribution.
3. Neither the name of the copyright holder nor the names of its contributors may be used to endorse or promote products derived from this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDER AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE.

## smali assembler

- Artifact: `org.smali:smali:2.5.2`
- License: BSD 3-Clause
- Purpose: Assemble Smali source files into Dalvik DEX bytecode.
- Project: https://github.com/JesusFreke/smali

- Android apksig (Apache 2.0), Bouncy Castle (MIT), Apache Commons Compress (Apache 2.0), XZ for Java (public domain).


## Network storage libraries

- `com.github.mwiede:jsch:0.2.20` — BSD 3-Clause License. Used for SFTP with strict OpenSSH `known_hosts` verification. Project: https://github.com/mwiede/jsch
- `com.hierynomus:smbj:0.13.0` — Apache License 2.0. Used for SMB2/3; KayX configures SMB3 encryption and signing as required. Project: https://github.com/hierynomus/smbj


## Remaining application and test dependencies

The following direct dependencies are also declared in `app/build.gradle.kts`. The license files linked below are authoritative; retain their notices when redistributing the corresponding binaries.

| Component | Version | License | Project / license source | Purpose |
|---|---:|---|---|---|
| AndroidX Core KTX, Activity Compose, Lifecycle, DocumentFile, WorkManager | versions declared by Gradle | Apache-2.0 | https://developer.android.com/jetpack/androidx |
| Jetpack Compose UI, Foundation, Animation, Material, Material 3 | Compose BOM 2024.09.00 | Apache-2.0 | https://developer.android.com/jetpack/compose |
| Android apksig | 8.5.2 | Apache-2.0 | https://android.googlesource.com/platform/tools/apksig/ |
| Bouncy Castle `bcprov-jdk18on`, `bcpkix-jdk18on` | 1.78.1 | Bouncy Castle Licence; Maven metadata also lists Apache-2.0 | https://www.bouncycastle.org/licence.html |
| Apache Commons Compress | 1.21 | Apache-2.0 | https://commons.apache.org/proper/commons-compress/ |
| Junrar | 8.1.2 | UnRAR License (not a conventional OSI open-source license) | https://github.com/junrar/junrar/blob/master/LICENSE |
| XZ for Java | 1.9 | Public domain | https://tukaani.org/xz/java.html |
| JSch (mwiede fork) | 0.2.20 | BSD-3-Clause | https://github.com/mwiede/jsch |
| SMBJ | 0.13.0 | Apache-2.0 | https://github.com/hierynomus/smbj |
| Shizuku API and provider | 13.1.5 | Apache-2.0 | https://github.com/RikkaApps/Shizuku-API |
| ZXing Android Embedded | 4.3.0 | Apache-2.0 | https://github.com/journeyapps/zxing-android-embedded |
| JUnit | 4.13.2 (test only) | EPL-1.0 | https://github.com/junit-team/junit4 |
| kotlinx.coroutines | 1.8.1 (test only) | Apache-2.0 | https://github.com/Kotlin/kotlinx.coroutines |
| SLF4J API | transitive runtime dependency of Junrar | MIT | https://www.slf4j.org/license.html |

### Junrar / UnRAR restriction

KayX uses Junrar only to read and extract RAR archives. The UnRAR license permits use of its source to handle RAR archives, but prohibits using it to recreate the proprietary RAR compression algorithm or develop a RAR-compatible archiver. KayX does not create RAR archives.

### Scope and release check

This table records the direct application and explicitly declared test dependencies. AndroidX, Compose, SMBJ, Junrar and other artifacts may bring transitive dependencies. The release dependency graph must be checked when dependency versions change; this document must not be treated as a substitute for the license texts shipped by those transitive artifacts. CI's APK permission allowlist is maintained separately in `docs/APK_PERMISSION_ALLOWLIST.txt`.
