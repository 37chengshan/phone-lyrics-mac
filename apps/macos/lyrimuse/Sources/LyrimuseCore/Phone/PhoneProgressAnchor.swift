import Foundation

public struct PhoneProgressAnchor: Equatable, Sendable {
    public let positionMs: Int64
    public let receivedAtMs: Int64
    public let speed: Double
    public let durationMs: Int64
    public let state: PhonePlaybackState
    public let correctionMs: Int64
    public let correctionDurationMs: Int64

    public init(
        positionMs: Int64,
        receivedAtMs: Int64,
        speed: Double,
        durationMs: Int64,
        state: PhonePlaybackState,
        correctionMs: Int64 = 0,
        correctionDurationMs: Int64 = 0
    ) {
        self.positionMs = positionMs
        self.receivedAtMs = receivedAtMs
        self.speed = speed
        self.durationMs = durationMs
        self.state = state
        self.correctionMs = correctionMs
        self.correctionDurationMs = correctionDurationMs
    }

    public func position(atMs nowMs: Int64) -> Int64 {
        let elapsed = max(0, nowMs - receivedAtMs)
        let advanced: Int64
        if state == .playing {
            advanced = Int64((Double(elapsed) * speed).rounded())
        } else {
            advanced = 0
        }
        let correction: Int64
        if correctionDurationMs > 0 {
            let fraction = min(1, Double(elapsed) / Double(correctionDurationMs))
            correction = Int64((Double(correctionMs) * fraction).rounded())
        } else {
            correction = correctionMs
        }
        let raw = max(0, positionMs + advanced + correction)
        return durationMs > 0 ? min(raw, durationMs) : raw
    }
}
