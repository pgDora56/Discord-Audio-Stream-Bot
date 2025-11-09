# ボイスチャンネル入退室ループ修正 - 実装完了

## 実装日
2025年11月9日

## 問題の概要
- 新規でチャンネルに参加したときに、入退室を高速で繰り返すバグが発生
- ステージチャンネル、通常ボイスチャンネル両方で発生
- 特定のチャンネルでのみ発生し、他のチャンネルでは正常に動作することもある
- 原因が特定できていない状態

## 実装した修正内容

### 修正1: Bot自身のイベント判定を強化（最重要）

**ファイル:** `src/main/java/net/runee/DiscordAudioStreamBot.java`  
**メソッド:** `onGuildVoiceUpdate()`

#### 変更内容
```java
// 変更前（オブジェクトの equals() 比較）
if(event.getMember().getUser().equals(jda.getSelfUser())) {
    return;
}

// 変更後（ID文字列の比較）
if(event.getMember().getId().equals(jda.getSelfUser().getId())) {
    logger.debug("Ignoring bot's own voice state change in guild: " + event.getGuild().getName());
    return;
}
```

#### 理由
- JDA 5.0.0-beta.6（ベータ版）では、Userオブジェクトの `equals()` が不安定な可能性
- ID文字列による比較の方が確実で安全
- Bot自身の参加イベントを確実に無視することで、無限ループを防止

### 修正2: 詳細なログ出力の追加（デバッグ用）

#### 2-1. joinAudio() メソッドのログ追加

接続開始時に以下の情報をログ出力：
- チャンネル名とID
- サーバー名
- チャンネルタイプ（STAGE/VOICE）
- 現在の接続状態
- ハンドラーのセットアップ状況

**ログ例:**
```
=== JOIN AUDIO REQUEST === Channel: 一般 (ID: 123456789), Guild: テストサーバー, Type: VOICE
Not currently connected to any channel in this guild
Speak handler setup completed
Listen handler setup completed
Opening audio connection to channel: 一般 (ID: 123456789)
Audio connection request sent successfully
```

#### 2-2. ConnectionListener のログ詳細化

各接続ステータスで詳細なログを出力：
- `CONNECTED`: 接続成功
- `DISCONNECTED`: 切断
- `ERROR`: エラー発生
- `AUDIO_REGION_CHANGE`: リージョン変更
- `CONNECTING_*`: 接続中の各ステップ

**ログ例:**
```
*** Voice Connection Status Changed *** Status: CONNECTING_AWAITING_ENDPOINT, Channel: 一般, Guild: テストサーバー
Connecting to voice channel (awaiting endpoint)...
*** Voice Connection Status Changed *** Status: CONNECTED, Channel: 一般, Guild: テストサーバー
Successfully CONNECTED to voice channel: 一般
```

#### 2-3. leaveAudio() メソッドのログ追加

切断時の各ステップをログ出力：
- 切断対象のチャンネル情報
- ハンドラーのクリーンアップ状況
- 切断結果

**ログ例:**
```
=== LEAVE AUDIO REQUEST === Guild: テストサーバー
Leaving audio channel: 一般
Speak handler cleaned up
Listen handler cleaned up
Audio connection closed successfully
```

## 期待される効果

### 1. 無限ループの防止
Bot自身のボイス状態変更イベントを確実に無視することで、以下のループを防止：
```
Bot参加 → イベント発火 → joinAudio()呼び出し → Bot参加 → （繰り返し）
```

### 2. 問題の特定が可能に
詳細なログにより、以下の情報が取得可能：
- どのタイミングで `joinAudio()` が呼ばれているか
- 接続のどの段階で問題が発生しているか
- エラーや切断が発生しているか
- ステージチャンネルの `requestToSpeak()` が成功しているか

### 3. チャンネル固有の問題の特定
特定のチャンネルでのみ発生する場合、ログから以下を特定可能：
- チャンネルID（他のチャンネルと比較可能）
- 接続ステータスの遷移の違い
- エラーメッセージの有無

## ログの確認ポイント

バグが発生した場合、以下のログパターンに注目：

### パターン1: 無限ループ
```
=== JOIN AUDIO REQUEST === （繰り返し）
=== JOIN AUDIO REQUEST === （繰り返し）
=== JOIN AUDIO REQUEST === （繰り返し）
```
→ `joinAudio()` が連続して呼ばれている

### パターン2: 接続→即切断
```
*** Voice Connection Status Changed *** Status: CONNECTED
Successfully CONNECTED to voice channel: XXX
*** Voice Connection Status Changed *** Status: DISCONNECTED
Voice connection DISCONNECTED from channel: XXX
```
→ 接続直後に切断されている

### パターン3: エラー発生
```
*** Voice Connection Status Changed *** Status: ERROR
Voice connection ERROR occurred for channel: XXX
```
→ 接続エラーが発生している

### パターン4: ステージチャンネル固有の問題
```
Stage channel detected, requesting to speak...
Failed to request to speak on stage channel: XXX
```
→ スピーカーリクエストが失敗している

## 次のステップ

1. **実際にバグが発生するチャンネルでテスト**
   - ログファイルを確認
   - 上記のパターンのどれに該当するか確認

2. **正常に動作するチャンネルと比較**
   - ログの違いを確認
   - チャンネル設定の違いを確認

3. **ログから特定できた問題に応じて追加修正**
   - 特定のConnectionStatusで問題が発生している場合はそこを修正
   - 権限やリージョンなどの問題が判明した場合は対応

## その他の実装済み修正（現在も有効）

- ステージチャンネルでの `requestToSpeak()` 処理
- デバイスエラー時の接続継続処理
- 重複接続チェック
- エラーハンドリングの強化

## 関連ファイル

- 実装ファイル: `src/main/java/net/runee/DiscordAudioStreamBot.java`
- 保留中の修正案: `CHANNEL_SWITCH_FIX_MEMO.md`

## 注意事項

- ログ出力が増えるため、ログファイルのサイズが大きくなる可能性があります
- デバッグレベルのログは `logger.debug()` を使用しているため、ログレベルの設定により出力されない場合があります
- 問題が解決した後、不要なログは削除または debug レベルに変更することを推奨します

