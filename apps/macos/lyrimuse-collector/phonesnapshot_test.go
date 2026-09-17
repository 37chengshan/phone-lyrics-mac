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
	if err := os.WriteFile(path, data, 0o600); err != nil { t.Fatal(err) }
	state, ok := readPhoneState(path, time.Date(2026, 9, 17, 10, 0, 5, 0, time.UTC))
	if !ok { t.Fatal("expected a fresh phone snapshot") }
	if state["title"] != "Song" || state["bundleIdentifier"] != "com.tencent.qqmusic" {
		t.Fatalf("unexpected state: %#v", state)
	}
}

func TestReadPhoneStateRejectsStaleOrMalformed(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "phone-now-playing.json")
	if err := os.WriteFile(path, []byte(`{"timestamp":"2026-09-17T10:00:00Z"}`), 0o600); err != nil { t.Fatal(err) }
	if _, ok := readPhoneState(path, time.Date(2026, 9, 17, 10, 0, 9, 0, time.UTC)); ok {
		t.Fatal("stale snapshot must be rejected")
	}
	if err := os.WriteFile(path, []byte(`not-json`), 0o600); err != nil { t.Fatal(err) }
	if _, ok := readPhoneState(path, time.Now()); ok { t.Fatal("malformed snapshot must be rejected") }
}
