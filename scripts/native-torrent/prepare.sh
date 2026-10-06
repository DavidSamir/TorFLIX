#!/usr/bin/env bash
set -euo pipefail
script_dir=$(cd "$(dirname "$0")" && pwd)
work_dir=${TORFILX_NATIVE_WORK:-"$HOME/torfilx-native"}
mkdir -p "$work_dir"
cd "$work_dir"
download() {
  local file=$1 url=$2 checksum=$3
  if [[ ! -f "$file" ]]; then curl -L --fail --retry 3 -o "$file" "$url"; fi
  printf '%s  %s\n' "$checksum" "$file" | sha256sum --check
}
download ndk.zip https://dl.google.com/android/repository/android-ndk-r25c-linux.zip \
  769ee342ea75f80619d985c2da990c48b3d8eaf45f48783a2d48870d04b46108
download boost.tar.bz2 https://archives.boost.io/release/1.72.0/source/boost_1_72_0.tar.bz2 \
  59c9b274bc451cf91a9ba1dd2c7fdcaf5d60b1b3aa83f2c9fa143417cc660722
download openssl.tar.gz https://github.com/openssl/openssl/releases/download/OpenSSL_1_1_1w/openssl-1.1.1w.tar.gz \
  cf3098950cb4d853ad95c0841f1f9c6d3dc102dccfcacd521d93925208b76ac8
[[ -d android-ndk-r25c ]] || unzip -q ndk.zip
[[ -d boost_1_72_0 ]] || tar -xf boost.tar.bz2
[[ -d openssl-1.1.1w ]] || tar -xf openssl.tar.gz
if [[ ! -d libtorrent ]]; then
  git clone --depth 1 --branch libtorrent-1_2_3 https://github.com/arvidn/libtorrent.git
fi
if [[ ! -d libtorrent4j ]]; then
  git clone --depth 1 --branch v1.2.3.0 https://github.com/aldenml/libtorrent4j.git
fi
[[ $(git -C libtorrent rev-parse HEAD) == 078f7b956ef0d992948852a801e626b1118a1fc5 ]]
[[ $(git -C libtorrent4j rev-parse HEAD) == f966d9e81be4aa73dea1e8d284505eee844e77d7 ]]
if [[ ! -f .android11-patched ]]; then
  python3 "$script_dir/patch_android.py" "$work_dir/libtorrent"
  touch .android11-patched
fi
