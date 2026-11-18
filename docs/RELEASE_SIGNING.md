# KayX release signing

KayX release APKs are signed with a private keystore owned by the maintainer. The keystore and passwords must never be committed to this repository.

## 1. Create the keystore on your own device

Install a JDK that provides `keytool`, then run:

```sh
keytool -genkeypair -v \
  -keystore kayx-release.jks \
  -alias kayx \
  -keyalg RSA \
  -keysize 4096 \
  -validity 10000
```

Keep `kayx-release.jks`, the keystore password, key alias, and key password in a safe offline backup. If this key is lost, future updates signed with a different key cannot update installations signed with this key.

## 2. Add GitHub Actions secrets

Open the KayX repository → Settings → Secrets and variables → Actions → New repository secret.

Add these four secrets:

- `KAYX_KEYSTORE_BASE64`: output of `base64 kayx-release.jks | tr -d '\n'`
- `KAYX_KEYSTORE_PASSWORD`: keystore password entered during key creation
- `KAYX_KEY_ALIAS`: `kayx` (or the alias you chose)
- `KAYX_KEY_PASSWORD`: password for that key

Do not paste passwords or the base64 keystore into chat, issues, source files, or workflow YAML.

## 3. Build a signed release

After all four secrets are saved, open Actions → KayX Signed Release → Run workflow. The workflow builds the Rust native libraries, runs JVM unit tests, assembles the release APK, verifies its APK signature, and uploads the signed APK as the `KayX-signed-release` artifact.

If the secrets are missing, the workflow intentionally stops before building. Debug builds remain available from the regular Build and test KayX workflow.

## 4. Updating an existing installation

The regular GitHub Actions debug APK is signed with the runner's debug key. The first move from that debug APK to the owner-signed release APK may require uninstalling the debug build once. After installing the owner-signed release, keep using the same keystore for every future release; this preserves Android update compatibility.

Never generate a new release keystore for each version.
