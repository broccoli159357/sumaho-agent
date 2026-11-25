#!/usr/bin/env bash
set -euo pipefail
SRC=/workspace/third_party/llama.cpp
OUT=/out/llama-host
cmake -S "$SRC" -B "$OUT" -G Ninja -DCMAKE_BUILD_TYPE=Release -DGGML_NATIVE=OFF \
  -DLLAMA_BUILD_TESTS=ON -DLLAMA_BUILD_EXAMPLES=OFF -DLLAMA_BUILD_SERVER=OFF -DLLAMA_BUILD_TOOLS=OFF \
  -DLLAMA_CURL=OFF -DLLAMA_OPENSSL=OFF -DBUILD_SHARED_LIBS=OFF
cmake --build "$OUT" --target test-export-graph-ops -j "$(nproc)"
"$OUT/bin/test-export-graph-ops" -m /models/Inkling-2-Mini-abliterated-NPU.gguf -c 4096 -ub 64 -o /out/inkling-ops.txt 2>&1 | tail -5
wc -l /out/inkling-ops.txt
