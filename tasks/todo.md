# 会話順序ずれの修正 — 案A（idle monitor）＋ ガード

## Problem
`claude` CLI は stream-json で常駐し、1本の裏スレッドが全イベント（プロンプト応答＋自律イベント）を単一 `eventQueue` に積む。`sendPrompt` は `result` を見て return（`busy=false`）。バックグラウンド完了などの自律イベントを拾う受け皿 `CliProcess.pollEvent()` は呼び出し元ゼロで未配線。よって:
- 完了通知が誰にも消費されず `eventQueue` に滞留する
- 次のユーザープロンプトが `sendPrompt` で滞留イベントを先に吸い、前ステップの出力が新プロンプトの返事として配られる → 1つずれてちぐはぐ

対象は quarkus-chat-ui（provider=claude/CLI）のみ。chat-ui3 は openai-compat で自律イベント無し。

## 設計（案A＋ガード）
- ガード＝**イベントの経路分離**: `CliProcess` に turnQueue / autonomousQueue を持たせ、`turnActive` で振り分ける。prompt ターン中(=turnActive)のイベントは turnQueue、それ以外は autonomousQueue。`result` で reader スレッドが turnActive=false にする。→ 滞留自律イベントが prompt 応答に混ざる経路が構造的に消える。
- 案A＝**idle monitor**: 既存 `watchdogTimer` に2秒周期のtickを追加し、`chatActorRef.tell(a -> a.pollAutonomousActivity(ref))`。ChatActor が idle かつ autonomous 活動があれば、ブロッキングなドレインを managed pool へ委譲し、独立したアシスタントターンとして履歴記録＋SSE配信する。
- POJO-actor 原則遵守: 判定(`hasAutonomousActivity`)はアクタースレッドで軽く読むだけ、ドレインは `providerRef.ask(..., getManagedThreadPool())`。
- フロント変更不要: `handleDelta/handleThinking` は `currentAssistantMsg` が無ければ生成、`handleResult` が確定・null化。ユーザー吹き出し無しの自律ターンをそのまま描画する。

## Tasks
- [x] CliProcess: turnQueue/autonomousQueue 分離、`turnActive`、`routeEvent`/`beginTurn`/`pollTurnEvent`、`pollAutonomousEvent`/`hasAutonomousEvent`、`cancel`/restart で両方クリア
- [x] LlmProvider: `supportsAutonomousEvents()` / `hasAutonomousActivity()` / `drainAutonomousActivity(emitter)` を default no-op で追加（ProviderCapabilities record は不変更）
- [x] CliLlmProvider: 上記3メソッドを override（drain は既存 `dispatch()` を再利用し result まで1ターン分ドレイン）
- [x] ChatActor: `emitToSse`, `pollAutonomousActivity`, `onAutonomousComplete`, `recordAutonomousTurn`
- [x] ChatUiActorSystem: watchdog ブロック内（CLIのみ）で idle-monitor tick を 2s 周期で登録
- [x] Unit tests: `CliProcessRoutingTest`(4), `ChatActorAutonomousTest`(2)
- [x] rm -rf target && mvn install（全12モジュール・全テスト green）→ jar を ~/works へ配置済み

## Review
- ガード（経路分離）は確実に効く：post-result のイベントは autonomousQueue に入り、次の prompt が
  それを吸えない。`CliProcessRoutingTest` で「result 後のイベントは autonomous」を検証済み。これが
  「1つずれてちぐはぐ」の直接原因の除去。
- idle monitor は 2秒周期で idle 時に自律出力をドレインし、独立アシスタントターンとして
  履歴記録＋SSE配信。`ChatActorAutonomousTest` でドレイン→履歴・SSE・busy解放を検証済み。
- **未検証の前提**: `claude` CLI が stream-json 常駐時に、裏ジョブ完了で stdout に自律ターンを
  実際に emit するか否かは実機未確認。emit するなら「完了通知を待って進む」が機能する。emit しない
  なら idle monitor は何も拾わない（害はない）。この場合の本命は案C（workflow がジョブを監視し
  QueueActor へ継続ターンを enqueue）。→ 実機で1回検証する。
- 再起動は未実施（サーバプロセスを勝手に止めない方針）。どのポートを新 jar で再起動するか要相談。
