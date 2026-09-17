import Foundation

public enum PhoneConnectionStatus: Equatable, Sendable {
    case waiting
    case connected
    case unstable
    case offline
}

public enum PhoneClearReason: Equatable, Sendable {
    case stopped
    case offline
}

public struct PhonePlaybackSnapshot: Equatable, Sendable {
    public let envelope: PhonePlaybackEnvelope
    public let receivedAtMs: Int64
    public let anchor: PhoneProgressAnchor

    public init(envelope: PhonePlaybackEnvelope, receivedAtMs: Int64, anchor: PhoneProgressAnchor) {
        self.envelope = envelope
        self.receivedAtMs = receivedAtMs
        self.anchor = anchor
    }
}

public enum PhonePlaybackDecision: Equatable, Sendable {
    case accept(PhonePlaybackSnapshot, clearPreviousTrack: Bool)
    case ignoreOutOfOrder
    case clear(PhoneClearReason)
    case none
}

public struct PhonePlaybackStateMachine: Sendable {
    public private(set) var currentTrackId: String?
    public private(set) var currentSnapshot: PhonePlaybackSnapshot?
    private var sessionId: String?
    private var lastSequence: Int64 = 0
    private var lastReceivedAtMs: Int64?
    private var anchor: PhoneProgressAnchor?
    private var offlineClearEmitted = false

    public init() {}

    public mutating func ingest(_ envelope: PhonePlaybackEnvelope, receivedAtMs: Int64) -> PhonePlaybackDecision {
        if sessionId == envelope.sessionId {
            guard envelope.sequence > lastSequence else { return .ignoreOutOfOrder }
        } else {
            sessionId = envelope.sessionId
            lastSequence = 0
        }
        guard envelope.sequence > 0 else { return .ignoreOutOfOrder }

        lastSequence = envelope.sequence
        lastReceivedAtMs = receivedAtMs
        offlineClearEmitted = false

        if envelope.event == .stop || envelope.playback.state == .stopped {
            currentTrackId = nil
            currentSnapshot = nil
            anchor = nil
            return .clear(.stopped)
        }

        let trackChanged = currentTrackId != nil && currentTrackId != envelope.track.trackId
            || envelope.event == .trackChanged
        let nextAnchor = resolvedAnchor(for: envelope, receivedAtMs: receivedAtMs, forceReset: trackChanged)
        let snapshot = PhonePlaybackSnapshot(envelope: envelope, receivedAtMs: receivedAtMs, anchor: nextAnchor)
        currentTrackId = envelope.track.trackId
        currentSnapshot = snapshot
        anchor = nextAnchor
        return .accept(snapshot, clearPreviousTrack: trackChanged)
    }

    public func position(atMs nowMs: Int64) -> Int64? {
        anchor?.position(atMs: nowMs)
    }

    public func connectionStatus(atMs nowMs: Int64) -> PhoneConnectionStatus {
        guard let lastReceivedAtMs else { return .waiting }
        let age = max(0, nowMs - lastReceivedAtMs)
        if age < 3_000 { return .connected }
        if age < 8_000 { return .unstable }
        return .offline
    }

    public mutating func checkTimeout(atMs nowMs: Int64) -> PhonePlaybackDecision {
        guard connectionStatus(atMs: nowMs) == .offline, !offlineClearEmitted else { return .none }
        offlineClearEmitted = true
        currentTrackId = nil
        currentSnapshot = nil
        anchor = nil
        return .clear(.offline)
    }

    private func resolvedAnchor(
        for envelope: PhonePlaybackEnvelope,
        receivedAtMs: Int64,
        forceReset: Bool
    ) -> PhoneProgressAnchor {
        let playback = envelope.playback
        let direct = PhoneProgressAnchor(
            positionMs: playback.positionMs,
            receivedAtMs: receivedAtMs,
            speed: playback.speed,
            durationMs: envelope.track.durationMs,
            state: playback.state)
        guard !forceReset, envelope.event != .seek, envelope.event != .play,
              playback.state == .playing, let anchor
        else { return direct }

        let predicted = anchor.position(atMs: receivedAtMs)
        let error = playback.positionMs - predicted
        let magnitude = abs(error)
        if magnitude <= 150 { return anchor }
        if magnitude <= 600 {
            return PhoneProgressAnchor(
                positionMs: predicted,
                receivedAtMs: receivedAtMs,
                speed: playback.speed,
                durationMs: envelope.track.durationMs,
                state: playback.state,
                correctionMs: error,
                correctionDurationMs: 1_000)
        }
        return direct
    }
}
