#!/usr/bin/env bash
set -euo pipefail

# Sources are pinned to the versions used by the Java 1.2.3.0 JNI bindings.
# Run under Linux/WSL with cmake, ninja, git, curl, unzip, python3 and perl.
script_dir=$(cd "$(dirname "$0")" && pwd)
work_dir=${TORFILX_NATIVE_WORK:-"$HOME/torfilx-native"}
abi=${1:?Usage: build_android.sh armeabi-v7a|arm64-v8a|x86|x86_64}
case "$abi" in
  armeabi-v7a) ssl_target=android-arm ;;
  arm64-v8a) ssl_target=android-arm64 ;;
  x86) ssl_target=android-x86 ;;
  x86_64) ssl_target=android-x86_64 ;;
  *) echo "Unsupported ABI: $abi" >&2; exit 1 ;;
esac
ndk="$work_dir/android-ndk-r25c"
export ANDROID_NDK_ROOT="$ndk"
export ANDROID_NDK_HOME="$ndk"
export PATH="$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin:$PATH"
prefix="$work_dir/prefix/$abi"
mkdir -p "$prefix/lib" "$work_dir/build/$abi"

if [[ ! -f "$prefix/lib/libssl.a" ]]; then
  ssl_src="$work_dir/openssl-$abi"
  if [[ ! -d "$ssl_src" ]]; then cp -a "$work_dir/openssl-1.1.1w" "$ssl_src"; fi
  (
    cd "$ssl_src"
    ./Configure "$ssl_target" -D__ANDROID_API__=24 no-shared no-tests --prefix="$prefix" -fPIC
    make -j"${TORFILX_NATIVE_JOBS:-8}"
    make install_sw
  )
fi
# Boost.System has been header-only since Boost 1.69. An empty target archive
# satisfies the older libtorrent CMake package discovery without a host library.
printf '!<arch>\n' > "$prefix/lib/libboost_system.a"
cmake -S "$script_dir" -B "$work_dir/build/$abi" -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE="$ndk/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI="$abi" -DANDROID_PLATFORM=android-24 -DANDROID_STL=c++_static \
  -DCMAKE_BUILD_TYPE=Release \
  -DLIBTORRENT_SOURCE="$work_dir/libtorrent" \
  -DLIBTORRENT4J_SOURCE="$work_dir/libtorrent4j" \
  -DOPENSSL_ROOT_DIR="$prefix" -DOPENSSL_USE_STATIC_LIBS=TRUE \
  -DOPENSSL_INCLUDE_DIR="$prefix/include" \
  -DOPENSSL_CRYPTO_LIBRARY="$prefix/lib/libcrypto.a" \
  -DOPENSSL_SSL_LIBRARY="$prefix/lib/libssl.a" \
  -DBOOST_ROOT="$work_dir/boost_1_72_0" -DBoost_NO_SYSTEM_PATHS=ON \
  -DBoost_NO_BOOST_CMAKE=ON -DBoost_INCLUDE_DIR="$work_dir/boost_1_72_0" \
  -DBoost_SYSTEM_LIBRARY_RELEASE="$prefix/lib/libboost_system.a" \
  -DBoost_SYSTEM_LIBRARY_DEBUG="$prefix/lib/libboost_system.a"
cmake --build "$work_dir/build/$abi" --parallel "${TORFILX_NATIVE_JOBS:-8}"
llvm-strip --strip-unneeded "$work_dir/build/$abi/libtorrent4j-android11.so"
