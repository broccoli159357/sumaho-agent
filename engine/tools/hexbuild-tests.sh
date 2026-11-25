#!/usr/bin/env bash
# llama.cpp 単体を Hexagon 付きでビルドし、計算ごとの検証ツール test-backend-ops と v81 の DSP skel を作る
set -euo pipefail
SRC=/workspace/third_party/llama.cpp
OUT=/out/llama-hex
cmake -S "$SRC" -B "$OUT" -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_ROOT/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-29 \
  -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_C_FLAGS="-D_GNU_SOURCE" -DCMAKE_CXX_FLAGS="-D_GNU_SOURCE" \
  -DGGML_NATIVE=OFF -DGGML_OPENMP=OFF -DGGML_LLAMAFILE=OFF \
  -DGGML_HEXAGON=ON -DGGML_BACKEND_DL=OFF \
  -DHEXAGON_SDK_ROOT="$HEXAGON_SDK_ROOT" -DHEXAGON_TOOLS_ROOT="$HEXAGON_TOOLS_ROOT" \
  -DPREBUILT_LIB_DIR=android_aarch64 \
  -DLLAMA_BUILD_TESTS=ON -DLLAMA_BUILD_EXAMPLES=ON -DLLAMA_BUILD_SERVER=OFF -DLLAMA_BUILD_TOOLS=OFF \
  -DLLAMA_CURL=OFF -DLLAMA_OPENSSL=OFF
cmake --build "$OUT" --target test-backend-ops llama-node-stats htp-v81 -j "$(nproc)"
cp -f "$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so" "$OUT/"
echo "built:"; find "$OUT" -maxdepth 6 \( -name 'test-backend-ops' -o -name 'lib*.so' \) | sort
