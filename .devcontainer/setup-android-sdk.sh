#!/usr/bin/env bash
set -euo pipefail

sdk_root="${ANDROID_HOME:-$HOME/.android-sdk}"
cmdline_tools="$sdk_root/cmdline-tools/latest"
workspace="$(git rev-parse --show-toplevel)"

if [[ ! -x "$cmdline_tools/bin/sdkmanager" ]]; then
    archive="$(mktemp)"
    unpack="$(mktemp -d)"
    trap 'rm -f "$archive"; rm -rf "$unpack"' EXIT

    curl --fail --location --retry 3 \
        https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip \
        --output "$archive"
    mkdir -p "$sdk_root/cmdline-tools/latest"
    unzip -q "$archive" -d "$unpack"
    mv "$unpack/cmdline-tools/"* "$cmdline_tools/"
fi

set +o pipefail
yes | "$cmdline_tools/bin/sdkmanager" --sdk_root="$sdk_root" --licenses >/dev/null
set -o pipefail
"$cmdline_tools/bin/sdkmanager" --sdk_root="$sdk_root" \
    "platform-tools" \
    "platforms;android-34" \
    "build-tools;34.0.0"

printf 'sdk.dir=%s\n' "$sdk_root" > "$workspace/local.properties"
