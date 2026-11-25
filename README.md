# Sumaho Agent（v0.1）

Z Fold8 などの Android で動くエージェントアプリ。推論先を切り替えられ、ツール（MCP含む）を使い、難しいときは外部AIに相談して推論を続ける。

## 構成

```
[Android アプリ]  エージェントループ / ツール / MCPクライアント / 確認ダイアログ
      │
      ├─ 自宅PC（Tailscale）   llama-server + Inkling 2 Mini   ← 自動ルーティングの優先先
      ├─ スマホ内               BigMoeOnEdge（localhost:8765）  ← 自宅PCに繋がらないとき
      └─ クラウド（任意）        OpenAI / Claude / Gemini        ← 会話ごとに手動選択 or 相談ツール
```

- **推論先の自動選択**: 自宅PC → だめならスマホ内。クラウドは自動では使わない（画面上部の「推論先」で明示選択したときだけ）
- **外部への相談（consult_external）**: ローカルモデルが自分で判断して呼ぶツール。会話履歴は送らず、質問と前提だけを送る。回答はツール結果として戻り、推論が続く。画面の「☁ 〜に聞く」ボタンでも手動で呼べる
- **危険度ごとの確認**: 読み取り系は自動 / 書き込み・MCP は確認 / 外部送信は設定次第 / 端末操作は枠だけ（無効）

## 1. 自宅PCで Inkling を動かす（RTX A4000 16GB）

llama.cpp **b12633 以降**（`inkling2_loop` 対応）の CUDA 版を使う。

```powershell
llama-server -m Inkling-2-Mini-abliterated-Mix-16G.gguf -c 16384 --jinja -ngl 99 --n-cpu-moe 4 --host 100.x.y.z --port 8080 --chat-template-kwargs '{"reasoning_effort": "low"}'
```

- `--n-cpu-moe N`: VRAMに載り切らない分のエキスパートをメインメモリに置く層数。VRAM不足エラーが出たら増やし、余裕があれば減らす
- `--host` は PC の **Tailscale のIP** にする（`0.0.0.0` だとLAN全体に公開される）
- スマホに Tailscale を入れ、同じアカウントでログイン

## 2. スマホ内推論（任意）

BigMoeOnEdge を入れて `Mix-16G.gguf` を読み込み、サーバー（ポート 8765, OpenAI互換）を起動する。

## 3. アプリのインストール

PCでビルド → USBデバッグを有効にした Fold8 を接続して:

```powershell
.\gradlew.bat installDebug
```

（このフォルダは日本語パスのため `gradle.properties` に `android.overridePathCheck=true` を入れてある）

## 4. アプリの初期設定

1. 設定 → 推論先 → **自宅PC** の URL を `http://<PCのTailscale IP>:8080/v1` に
2. クラウドを使うなら、使う推論先を有効化し、モデル名と APIキーを入力（キーは Android Keystore で暗号化保存）
3. 外部への相談 → 相談先を選ぶ（既定: Claude）
4. Web検索 → SearXNG（自宅PCで動かすのがおすすめ）/ Brave / Tavily のどれか
5. MCPサーバー → 「＋サーバー追加」で URL（Streamable HTTP）とトークン

## ツール一覧

| ツール | 危険度 |
|---|---|
| web_search / fetch_url | 自動 |
| run_js（隔離されたJSで計算） | 自動 |
| memo_save / memo_delete | 確認 |
| memo_search / memo_read | 自動 |
| memory_save / memory_forget（長期記憶。システムプロンプトに常に入る） | 確認 |
| workspace_list / workspace_read | 自動 |
| workspace_write | 確認 |
| document_list / document_read（📎 や共有で受け取ったPDF・テキスト） | 自動 |
| consult_external（外部AIへの相談） | 設定次第 |
| MCP の各ツール | 確認（サーバーごとに「確認なし」可） |

## v0.1 で未対応・既知の制限

- MCP の OAuth 認証は未対応（固定トークンのヘッダー認証のみ）。stdio 型サーバーは PC 側で HTTP に変換して使う
- Gemini はストリーミングなし（応答が来てから一括表示）
- 会話途中で推論先を切り替えると、各社固有の思考データはその推論先以外には渡らない
- デバッグ版 APK は約100MB（リリースビルドで縮小予定）
- 検証済み: エミュレータ上でモックのOpenAI互換サーバー・モックMCPサーバーを相手にした一連の流れ（ストリーミング、思考表示、run_js、MCPツール＋確認ダイアログ、最終回答）。Galaxy Z Fold8 実機で内蔵エンジン（BigMoeOnEdge、NPU 読み込み）による回答。**Claude/OpenAI/Gemini の実API は未検証**
- 内蔵エンジンは最初の 1 問目が遅い（指示文＋ツール定義 約 2,300 トークンの読み込みに約 7 分）。詳細とビルド方法は [engine/README.md](engine/README.md)

## ライセンス

MIT（[LICENSE](LICENSE)）。同梱のエンジン（BigMoeOnEdge: Apache-2.0、llama.cpp: MIT）はそれぞれのライセンスに従う（[third_party_licenses/](third_party_licenses/)）。
