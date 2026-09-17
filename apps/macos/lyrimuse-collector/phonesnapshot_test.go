package main

import (
	"os"
	"path/filepath"
	"testing"
	"time"
)

func TestReadPhoneState(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "phone-now-playing.json")
	data := []byte(`{"title":"Song","artist":"Singer","album":"Album","duration":180,"elapsedTime":12.5,"playing":true,"playbackRate":1,"bundleIdentifier":"com.tencent.qqmusic","timestamp":"2026-09-17T10:00:00Z"}`)
	if err := os.WriteFile(path, data, 0o600); err != nil {
		t.Fatal(err)
	}
	state, ok := readPhoneState(path, time.Date(2026, 9, 17, 10, 0, 5, 0, time.UTC))
	if !ok {
		t.Fatal("expected a fresh phone snapshot")
	}
	if state["title"] != "Song" || state["bundleIdentifier"] != "com.tencent.qqmusic" {
		t.Fatalf("unexpected state: %#v", state)
	}
}

func TestReadPhoneStateRejectsStaleOrMalformed(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "phone-now-playing.json")
	if err := os.WriteFile(path, []byte(`{"timestamp":"2026-09-17T10:00:00Z"}`), 0o600); err != nil {
		t.Fatal(err)
	}
	if _, ok := readPhoneState(path, time.Date(2026, 9, 17, 10, 0, 9, 0, time.UTC)); ok {
		t.Fatal("stale snapshot must be rejected")
	}
	if err := os.WriteFile(path, []byte(`not-json`), 0o600); err != nil {
		t.Fatal(err)
	}
	if _, ok := readPhoneState(path, time.Now()); ok {
		t.Fatal("malformed snapshot must be rejected")
	}
}

// TestAndroidQQMusicIsATrackedSource 钉住一个会让整条歌词链路静默失效的缺口。
//
// 手机快照里的 bundleIdentifier 是 Android 端 QQ 音乐的包名(com.tencent.qqmusic),跟 Mac 版
// (com.tencent.QQMusicMac)不是同一个字符串。isTracked() 不认它的话,每一拍都被判成"不是我
// 关心的来源":配对正常、心跳正常、快照正常落盘,而 Mac 上永远不解析歌词 —— 没有任何一条
// 线索指向这里。2026-09-17 真机联调时正是这样一颗雷。
func TestAndroidQQMusicIsATrackedSource(t *testing.T) {
	if !isKnownPlayerBundleID(qqMusicAndroidBundleID) {
		t.Fatalf("%q 必须是已知播放器,否则手机来源的播放会被 isTracked() 丢掉", qqMusicAndroidBundleID)
	}
	// 唯一来源这条路必须真的能走通:自动识别 + 手机包名 = 认。
	prev := features.Players
	features.Players = map[string]bool{playerAuto: true}
	defer func() { features.Players = prev }()
	p := &poller{cfg: &config{}}
	p.cur.Title, p.cur.Artist = "Song", "Singer"
	p.cur.Bundle = qqMusicAndroidBundleID
	if !p.isTracked() {
		t.Fatal("手机端 QQ 音乐的播放必须被 isTracked() 认下")
	}
	// Mac 版那个字符串仍然要认(上游行为不能因为这次修复而回退)。
	p.cur.Bundle = qqMusicBundleID
	if !p.isTracked() {
		t.Fatal("Mac 端 QQ 音乐的播放仍然要被认下")
	}
}

// TestMediaPlayerLabelSeparatesPhoneFromMac 钉住来源标签:两个 QQ 音乐必须各报各的。
// 混成一个会让 ListenBrainz / Last.fm 上的来源统计失真,而那是用户唯一能核对的地方。
func TestMediaPlayerLabelSeparatesPhoneFromMac(t *testing.T) {
	if got := mediaPlayerLabel(qqMusicAndroidBundleID); got != "QQ Music (Android)" {
		t.Fatalf("手机端标签 = %q,期望 \"QQ Music (Android)\"", got)
	}
	if got := mediaPlayerLabel(qqMusicBundleID); got != "QQ Music (macOS)" {
		t.Fatalf("Mac 端标签 = %q,期望 \"QQ Music (macOS)\"", got)
	}
}
