# 内蔵エンジン（BigMoeOnEdge + Hexagon NPU）

`app/src/main/jniLibs/arm64-v8a/` の `libbmoe-server.so` と各ライブラリは、
[BigMoeOnEdge](https://github.com/Helldez/BigMoeOnEdge)（Apache-2.0、llama.cpp は MIT）に
下の `patches/` を当ててビルドしたもの。ライセンス本文は `../third_party_licenses/` にある。

## パッチ

| ファイル | 対象 | 内容 |
|---|---|---|
| `llama.cpp-0001-inkling-upstream-cherry-picks.patch` | third_party/llama.cpp（09e3dc4 の上） | upstream の Inkling 修正 2 件（.scale テンソルの自動適用、loop スロット共有）。これが無いと出力が文字化けする |
| `llama.cpp-0002-hexagon-fp16-overflow-prescale.patch` | third_party/llama.cpp | **NPU で計算が壊れる原因の修正**。専門家ごとの倍率 (`.scale`) を後から掛ける行列（Inkling の gate_up）は途中値が最大約 21 万になり、fp16 で積を取る Hexagon HMX で 65504 を超えて Inf になる。入力を 1/32 にしてから掛け、倍率を掛けるときに 32 倍に戻す |
| `llama.cpp-0003-debug-tools-node-stats.patch` | third_party/llama.cpp | 調査用。`llama-node-stats`（ノードごとの合計・最大絶対値・L2・NaN/Inf・入力元を出力）と、Inkling の実形状の MUL_MAT_ID テスト（`TBO_INKLING=1 test-backend-ops`） |
| `bigmoeonedge-0001-nodestats-dump.patch` | BigMoeOnEdge core | 調査用。`BMOE_NODESTATS=<ファイル>` でエンジン内の全ノードの要約を書き出す（未設定なら何もしない） |

## ビルド（Docker / WSL）

```bash
# BigMoeOnEdge を clone して llama.cpp サブモジュールを 09e3dc4 にし、patches を当てる
git -C third_party/llama.cpp am  .../llama.cpp-0001-inkling-upstream-cherry-picks.patch
git -C third_party/llama.cpp apply .../llama.cpp-0002-hexagon-fp16-overflow-prescale.patch
git apply .../bigmoeonedge-0001-nodestats-dump.patch
docker run --rm -e HTP_ARCHS=v81 -v <BigMoeOnEdge>:/workspace \
  ghcr.io/snapdragon-toolchain/arm64-android:v0.7 bash /workspace/scripts/build-hexagon-android.sh
```

`build-hexagon/bin` の `bmoe-server` を `libbmoe-server.so` に改名し、`lib*.so`（vulkan / opencl は除く）、
`libc++_shared.so`、`libggml-htp-v81.so`（DSP 側。strip しない）と一緒に jniLibs へ置く。ホスト側は NDK の
`llvm-strip --strip-unneeded` で縮める。

## アプリからの起動設定（EngineManager）

- 引数: `--prefill-device HTP0 --prefill-best-effort --prefill-slots 1 --prefill-slots-on-demand --prefill-loaders 4 --ubatch 256`
- 環境変数: `GGML_HEXAGON_FA_SELECT=1`（HMX の注意機構カーネルは GQA 3 で計算を誤るので HVX 版を使う）、`GGML_HEXAGON_HOSTBUF=0`、`ADSP_LIBRARY_PATH=<nativeLibraryDir>`
- 設定画面「読み込みをNPUで行う」で切り替え。起動時の確認（64 トークンを CPU と NPU で比べる）に落ちたら CPU に戻る

## 実測（Galaxy Z Fold8 / SM8850、Mix-16G）

| | 結果 |
|---|---|
| 起動時の確認 | 一致率 97%、KL 平均 0.0026 |
| 読み込み 340 トークン（CLI） | NPU ubatch 512: 7.8 tok/s ／ CPU: 4.5〜5.7 tok/s |
| 読み込み 978 トークン（CLI、ubatch 256） | 5.5 tok/s、空きメモリ最低 1.7GB |
| アプリで 1 問目（指示文＋ツール定義 2,301 トークン） | 読み込み 437.8 秒（5 tok/s）、回答 84 トークン 1.4 tok/s |

ubatch 512 は約 1,000 トークンで空きメモリが 1.3GB まで下がったので、アプリは 256 にしている。

## 調査ツール（`tools/`）

- `compare_nodes.py cpu.tsv npu.tsv [閾値]` — 2 つの node-stats 出力を突き合わせ、入力は一致・出力だけずれた最初のノード（発生源）を挙げる
- `ns-watchdog.sh` — スマホ上で node-stats を空きメモリ・RssAnon・DSP エラー・時間で見張って実行する。最後に残ったプロセスを必ず kill する（DSP エラー後の子プロセスが NPU メモリを約 3GB 握ったまま残ったため）
- `hexbuild-tests.sh` / `hexbuild-export.sh` — test-backend-ops と node-stats の Android ビルド、モデルの演算一覧の書き出し
