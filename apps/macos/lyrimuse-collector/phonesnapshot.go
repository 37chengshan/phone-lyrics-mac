package main

import (
	"encoding/json"
	"os"
	"time"
)

const phoneSnapshotMaxAge = 8 * time.Second

// readPhoneState reads the atomic handoff written by the Mac receiver. A stale
// file is treated as offline, preventing the collector from scrobbling a phone
// session after connectivity was lost.
func readPhoneState(path string, now time.Time) (map[string]any, bool) {
	data, err := os.ReadFile(path)
	if err != nil { return nil, false }
	var state map[string]any
	if err := json.Unmarshal(data, &state); err != nil { return nil, false }
	title, titleOK := state["title"].(string)
	_, artistOK := state["artist"].(string)
	stamp, stampOK := state["timestamp"].(string)
	if !titleOK || !artistOK || title == "" || !stampOK { return nil, false }
	when, err := time.Parse(time.RFC3339Nano, stamp)
	if err != nil || now.Sub(when) > phoneSnapshotMaxAge || when.Sub(now) > time.Second {
		return nil, false
	}
	return state, true
}

func getPhoneState() (map[string]any, bool) {
	return readPhoneState(configFilePath("phone-now-playing.json"), time.Now())
}
