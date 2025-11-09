# チャンネル切り替え時の入退室ループ修正案（保留中）

## 概要
この修正は「既に別のチャンネルに接続中の状態でjoinコマンドを実行した場合」の入退室ループを防ぐためのものです。
現在のバグは「新規でチャンネルへ参加したとき」に発生するため、この修正は一旦保留しています。

## 修正内容

### 変更箇所
`src/main/java/net/runee/DiscordAudioStreamBot.java` の `joinAudio()` メソッド

### 追加するコード
```java
public void joinAudio(AudioChannel channel) {
    AudioManager audioManager = channel.getGuild().getAudioManager();
    
    // Check if already connected to the same channel to prevent redundant operations
    if (audioManager.isConnected() && channel.equals(audioManager.getConnectedChannel())) {
        logger.debug("Already connected to channel: " + channel.getName());
        return;
    }
    
    // ============ 以下を追加 ============
    // If connected to a different channel in the same guild, disconnect first
    // This prevents state issues when switching between channels
    if (audioManager.isConnected()) {
        logger.info("Already connected to another channel, disconnecting before joining new channel");
        try {
            audioManager.closeAudioConnection();
            // Give Discord a moment to process the disconnection
            Thread.sleep(500);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            logger.warn("Interrupted while waiting for disconnection", ex);
        } catch (Exception ex) {
            logger.warn("Failed to close existing audio connection", ex);
        }
    }
    // ============ ここまで ============
    
    // Setup handlers before connecting - but don't fail if devices aren't available
    // This allows the bot to connect even without audio devices configured
    try {
        updateSpeakState(audioManager, null, null);
    } catch (Exception ex) {
        logger.warn("Failed to setup speak handler, continuing with connection anyway", ex);
    }
    
    // ... 以下続く
}
```

## 修正の狙い

### 問題点
JDAのAudioManagerは、既に接続中の状態で別のチャンネルに`openAudioConnection()`を呼び出すと、内部状態が不安定になる可能性があります。

### 解決策
1. 既に別のチャンネルに接続している場合、先に明示的に`closeAudioConnection()`を呼び出す
2. 切断後、500msの待機時間を設けてDiscord側の処理完了を待つ
3. その後、クリーンな状態で新しいチャンネルに接続する

## 適用条件
この修正は以下の場合に有効です：
- ボイスチャンネルAに接続中
- `/join`コマンドでボイスチャンネルBに接続しようとする
- follow-audio機能でユーザーがチャンネルを移動した場合

## 注意事項
- チャンネル切り替え時に500msの遅延が発生します
- 新規参加時（どのチャンネルにも接続していない状態）には影響しません

## ステータス
**保留中** - 新規参加時のバグとは別の問題のため、適用は見送り

