# Plan mode で詰まる問題の修正

## 症状
Claude Code が plan モードに入ると、プランが表示されず、承認しても先に進めない（戻ってこない）。

## 根本原因（実証済み）
chat-ui は stream-json で 1 プロセスを生かし続ける。デフォルト permission mode は
`bypassPermissions` だが、モデルが自分で `EnterPlanMode` ツールを呼んで動的に plan
モードへ入る。その後の挙動を実機（claude 2.1.160）で確認した：

1. `EnterPlanMode` で plan モードに入る
2. `ExitPlanMode` を呼ぶ。プラン本文は `input.plan` **または** `input.summary` に入る
3. CLI が自動で `tool_result`(is_error=true, content="Exit plan mode?") を返し、`result`
   でターンが終了する（CLI はブロックしない）
4. 同じ生存プロセスに「承認・続行」を **通常のユーザーターンとして** 送るだけで、
   再プランせず実装に移る（再起動も permission mode 変更も不要）と実証

### 現コードの 2 バグ
- A: `StreamEventParser.parseAssistant` が `ExitPlanMode` のプラン本文を捨て、ツール名
  しか拾わない → プランが表示されない
- B: 承認時 `respond()` が「解決済み tool_use への tool_result」を stdin に書くだけ。
  ターンは既に終了し読む sendPrompt ループが無いので SSE に何も流れない → 詰まる

## 修正方針（最小・根本）
- [x] 調査・実証
- [ ] 1. Parser: `ExitPlanMode` を AskUserQuestion と同様に特別扱いし、`plan`/`summary`
      を本文に持つ promptType=`exit_plan_mode` の prompt イベントを返す
- [ ] 2. `LlmProvider` に `default boolean isPlanApproval(String promptId)` を追加
- [ ] 3. `CliLlmProvider`: `exit_plan_mode` prompt を plan 承認 ID として登録、旧 tool_result
      ヒューリスティックを撤去し "Exit plan mode?" tool_result を握り潰す
- [ ] 4. `ChatResource.respond`: plan 承認なら新しい通常ターンをキューに積んで SSE に流す
- [ ] 5. ビルド（rm -rf target → mvn install）・ユニットテスト・実機検証

## レビュー
- [x] 1〜5 すべて完了（このセクションは旧タスク）

---

# tmux TUI driver 実装（新タスク 2026-06-30）

## 実機検証で確定（2026-06-30、案A）
- [x] item1 ツール結果 `⎿` 抽出：`ToolResult`イベント追加（`●`と区別）→ live で `⎿ Wrote 1 lines...` が delta に流れた
- [x] item2 承認継続（方式2）live 成立：Write プロンプト→許可ダイアログ`prompt`→`/api/respond`「1」→継続ターン→**ファイル実際に作成**
- [x] live で見つけ修正したバグ2件：(a)`detectApproval`が`Enter to confirm`限定でツール許可ダイアログ(`Esc to cancel`)を取りこぼし→緩和 (b)`sendPrompt`がダイアログ描画前にsettledでターン終了→**入力待ち(`isInputReady`)でのみ終了**に修正
- [x] ユニット38＋IT3 GREEN、uber-jar 再生成、サーバ/tmux 掃除済
- [ ] 軽微：許可prompt本文が冗長(差分`╌`枠込み)→質問行だけに整形／フッター`high · /effort`等のchrome漏れ
- [ ] item3 任意：auth gating 付き実claude IT
- [ ] **AI workspace 対応**：`quarkus-AI-workspace` の provider 選択肢に `claude-tmux` を追加（起動オプション `-Dchat-ui.provider=claude-tmux`）


仕様: `doc_SCIVICS002/docs/quarkus-chat-ui/020_specs/090_TmuxTuiDriver_260630_oo01`

## Phase 1: ScreenExtractor 抽出ロジック（純粋 Java・ユニットテスト先行）
- [x] 新モジュール `provider-claude-tmux`（artifactId `chat-ui-provider-claude-tmux`）
- [x] root pom の modules に登録
- [x] イベント型: `ExtractedEvent`(sealed) / `AssistantMessage` / `ApprovalRequested`
- [x] `ScreenExtractor`: chrome 除去・差分・マーカー解析・許可検出（純粋関数）
- [x] フィクスチャ: probe で採取した capture テキスト（settled / trust ダイアログ）
- [x] ユニットテスト `ScreenExtractorTest`（`@Tag("TmuxTuiDriver_Extract_260630_oo01")`）
- [x] `rm -rf provider-claude-tmux/target && mvn -pl provider-claude-tmux test` で GREEN（6件・Failures 0）
- [x] 達成確認 `mvn -pl provider-claude-tmux test -Dgroups="TmuxTuiDriver_Extract_260630_oo01"` 成立

## Phase 2a: tmux I/O 境界（完了）
- [x] `TmuxCommands`（純粋 argv 組み立て）＋ ユニットテスト `@Tag("TmuxTuiDriver_Commands_260630_oo01")`（7件 GREEN）
- [x] `TmuxSession`（ProcessBuilder で tmux 実行）＋ `TmuxException`
- [x] 実 tmux 往復の統合テスト `TmuxSessionIT`（`@Tag("TmuxTuiDriver_Session_260630_oo01")`、failsafe、claude不要・tmux無ければskip）
- [x] `mvn -pl provider-claude-tmux verify` → ユニット13＋IT1 全 GREEN

## 方針変更（2026-06-30、ユーザ判断）
- 統合形態：**既存 `LlmProvider` SPI を実装する新 provider `TmuxLlmProvider`（id=`claude-tmux`）**。ChatActor/REST/Qute 流用。独立先行は撤回。
- 出力監視：**案B＝`pipe-pane` の生バイトを仮想スレッドで blocking read**。案A（capture ポーリング）不採用。
- 大スコープ：**plan/許可の「会話ずれ」を構造的に直す**。画面状態（番号ダイアログ vs 空 `❯`）で許可/plan を統一し、`sendPrompt` を承認をまたいで開いたまま継続（同一 emitter）。
- 注：既存 `QuiescenceTracker`（capture スナップショット等価）は案A寄り。案Bの主信号は pipe-pane の byte-idle。Tracker は「画面安定の二次確認」として残置可、主経路ではない。

## Phase 2b: pipe-pane 監視と無音検出
- [x] `IdleDebouncer`（純粋・時間ベース）＋ユニットテスト `@Tag("TmuxTuiDriver_Idle_260630_oo01")`（6件）
- [x] `TmuxCommands` に `pipePane`/`pipePaneOff` 追加＋ユニットテスト
- [x] `ProcRunner`（外部コマンド実行ユーティリティ）
- [x] `PipePaneReader`：`tmux pipe-pane 'cat >> <fifo>'`＋仮想スレッド blocking read、バイト到来で activity、fifo ライフサイクル管理
- [x] 実 tmux pipe-pane 往復 IT `PipePaneReaderIT`（`@Tag("TmuxTuiDriver_PipePane_260630_oo01")`、bash substrate）
- [x] `mvn -pl provider-claude-tmux verify` → ユニット26＋IT2 全 GREEN
- [x] `OutputWatcher`（POJO, actor 化）：capture を `Supplier<String>` で受け、recordActivity(reader→tell)＋tick(caller→tell)。状態はアクターに閉じロックレス。tick 駆動は呼び出し側（provider の blocking sendPrompt）が行う＝Scheduler 不要
- [x] `OutputWatcherTest`（fixture 駆動・tmux不要、`@Tag("TmuxTuiDriver_Watcher_260630_oo01")`）3件
- [x] `OutputWatcherIT`（実 tmux＋アクター配線、`@Tag("TmuxTuiDriver_WatcherWiring_260630_oo01")`）：pipe-pane→activity→settle→capture→AssistantMessage を通す
- [x] `mvn -pl provider-claude-tmux verify` → ユニット29＋IT3 全 GREEN

## 承認モデル（2026-06-30 ユーザ要件で確定）
- **方式2＝ターンを返す**（方式1=開いたまま待つ は撤回）。理由：承認者は人間ボタンだけでなく **Queue / Turing workflow** でありうる＝非同期・遅い・別の場所。ブロックするとスレッド+SSEを握り破綻。
- ダイアログ検出→`ChatEvent.prompt`→`sendPrompt` は戻る。未解決承認を **`promptId` キーの永続保留状態**に記録。
- 待機中はサーバ資源ゼロ（claude TUI がダイアログ前で生きたまま状態保持）。回答が来たら send-keys で選択→**新ターン**で続き。
- desync 修正の本質は「画面状態で判断（推測しない）」で方式2でも成立。
- 既存非同期パターン（MCP `submitPrompt` promptId / `QueueActor`）と同型。Turing workflow 用に「保留承認一覧／承認(promptId,選択)」をツール化。継続ターンの出力はブラウザへ stream＋履歴へ永続化。

## 共存モデル（2026-06-30 確定）＝起動オプション、タブではない
- chat-ui は「1インスタンス＝1プロバイダ」を維持。`-p` claude と tmux claude の共存は **AI workspace が `-Dchat-ui.provider=claude` と `=claude-tmux` で別インスタンスを起動**して実現。
- `chat-ui.provider` はランタイム設定（`@ConfigProperty`、ビルド時固定でない）。生成点は `app/.../LlmProviderProducer.java`。launch パラメータ化は `app/.../workspace/WorkspacePlugin.java`（`${DEFAULT_PROVIDER}`）に既存。
- chat-ui 側統合は2点：①`LlmProviderProducer` に `claude-tmux` 分岐追加（app→module 依存）②AI workspace の provider 選択肢に `claude-tmux` 追加（別リポジトリ `quarkus-AI-workspace`）。
- タブ／ChatActor 複数化／会話ごと routing は**不要・スコープ外**。

## Phase 2b 補足（完了）
- [x] `OutputWatcher.tick` を `TickResult`（settled＋events）返却へ（provider が emit を判断できる形）。sink 廃止。unit/IT 更新、verify GREEN

## Phase 2c: provider 統合（段階に分割）
### 2c-1 provider 本体（完了・compile/unit で検証、live は実 claude 必要）
- [x] module に core 依存追加
- [x] `TmuxCommands.sendKey`/`TmuxSession.sendKey`（cancel の Escape 用）＋ユニット
- [x] `TmuxLlmProvider implements LlmProvider`（sendPrompt 方式2ループ／respond=選択キー投入／cancel=Escape／lifecycle ensureStarted+waitForReady／capabilities slashCommands=false）
- [x] `TmuxLlmProviderTest`（toPromptEvent / mapResponseToChoice / identity、`@Tag("TmuxTuiDriver_Provider_260630_oo01")`）
- [x] `mvn -pl provider-claude-tmux verify` → ユニット35＋IT3 全 GREEN
- [x] 設計判断を spec Under the Hood に記録（ターン終了判定の前提・slashCommands=false）
- 旧:
      lifecycle（lazy に tmux で claude 起動・trust ダイアログ承認・PipePaneReader/OutputWatcher 起動、provider 内に小 ActorSystem）／
      sendPrompt（send-keys→tickループで TickResult→AssistantMessage=delta、settled かつ非ダイアログ=result で return／ダイアログ=prompt 流して return）／
      detect 用に ScreenExtractor に「入力待ち idle 判定」不要（settled＋非ダイアログで代替）／
      getSessionId=tmux session、supportsInteractivePrompts=true、capabilities、isCommand/handleCommand（/compact 等は TUI へ1行送る）
- [ ] 小ユニット（イベント→ChatEvent 写像など、tmux 不要部分）
### 2c-2 承認継続（core 小改修・方式2の山）＝完了
- [x] SPI に `resolveApprovalToContinuation(promptId,response)` 既定 null 追加（既存挙動不変）
- [x] `TmuxLlmProvider` がオーバーライド＝回答→選択文字列。`ChatResource.respond` が非nullなら `respondViaContinuation` で継続ターンを enqueue→`sendPrompt(選択)` が TUI に打ち込み続きを SSE
- [x] 保留は provider の `pendingApprovals`（promptId→ApprovalRequested）。Queue/Turing workflow は同じ `/api/respond` で解決可
- [x] core+provider を install、全テスト GREEN
- ( ) 「保留承認一覧」GET は任意・未実装（将来）
### 2c-3 配線と実機
- [x] `LlmProviderProducer` に `claude-tmux` 分岐＋app 依存＋root dependencyManagement＋`chat-ui.tmux.program` 設定
- [x] 全11モジュール `package` 成功＝CDI augmentation 通過・uber-jar `quarkus-chat-ui-2.4.0.jar` 生成
- [x] **実機検証（案A・私が実施 2026-06-30）**：使い捨てインスタンス(18099)で実 claude に通し、1往復(delta/result)・`/compact`がTUIで実行・trust自動承認・アイドル復帰を確認。サーバ/tmux掃除済
- [x] 実機で見つけた `waitForReady` 20秒待ち→`ScreenExtractor.isInputReady`で早期break修正（ユニット36+IT3 GREEN、uber-jar再生成済）
- [ ] **既知ギャップ（次の改善）**：`⎿` ツール結果行が未抽出＝ツール結果や`/compact`応答がSSEに出ない。`ToolResult`イベント抽出を追加（`●`に加えて`⎿`をparse）
- [ ] 未検証（次回実機）：plan mode／ツール許可ダイアログの承認継続（方式2の`respondViaContinuation`）を実 claude で通す
- [ ] 任意：実 claude の `*IT`（auth gating 付き）

## Phase 2c: provider 統合（旧メモ）
- [ ] `TmuxLlmProvider implements LlmProvider`：sendPrompt（send-keys→blocking read→delta/thinking/prompt→ダイアログで `prompt` 流して**return**／空 `❯` で `result`）、respond（promptId+選択→send-keys→新ターンで継続）、supportsInteractivePrompts=true、getSessionId、isCommand/handleCommand（/compact 等を TUI へ）
- [ ] 承認の永続保留状態（promptId→session/dialog）。Queue/Turing workflow から解決できる入口
- [ ] provider 登録（module 依存・SPI 配線）
- [ ] 実 claude での1往復＋plan承認＋ツール許可の *IT（mvn verify、要 claude 認証）
- [ ] 既存 UI（Qute/SSE）で表示確認
- 変更ファイル:
  - `provider-claude-code/.../StreamEventParser.java`: `parseExitPlanMode` 追加。
    `ExitPlanMode` を `plan`/`summary` 本文付き `exit_plan_mode` prompt に変換
  - `core/.../provider/LlmProvider.java`: `isPlanApproval` / `clearPlanApproval` を追加
  - `provider-claude-code/.../CliLlmProvider.java`: plan 承認 ID 集合を追加。
    旧 "Exit plan mode?" tool_result ヒューリスティックを撤去し握り潰し。
    prompt dispatch で `exit_plan_mode` を plan 承認として登録
  - `core/.../rest/ChatResource.java`: `respond` が plan 承認を検出したら
    `respondToPlan` で新しい通常ターンをキューに積み SSE へストリーミング
  - テスト追加: parser 3 件 + dispatch 3 件（全 BUILD SUCCESS、Failures 0）
- ビルド: `rm -rf target` → `mvn install`（全 10 モジュール SUCCESS）。jar = `app/target/quarkus-chat-ui-2.3.0.jar`
- 残: ユーザーが chat-ui プロセスを再起動して実 UI で plan→承認→実装の動作確認
- 注意: 起動 permission mode を `plan` に固定する設定（既定は `bypassPermissions`）の
  場合はセッション全体が plan のままになり本修正の対象外。既定運用では問題なし
