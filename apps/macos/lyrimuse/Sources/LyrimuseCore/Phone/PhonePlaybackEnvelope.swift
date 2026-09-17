import Foundation

public enum PhonePlaybackEvent: String, Codable, CaseIterable, Sendable {
    case trackChanged
    case play
    case pause
    case seek
    case stop
    case heartbeat
}

public enum PhonePlaybackState: String, Codable, CaseIterable, Sendable {
    case playing
    case paused
    case stopped
}

public struct PhoneTrack: Codable, Equatable, Sendable {
    public let trackId: String
    public let title: String
    public let artist: String
    public let album: String
    public let durationMs: Int64

    public init(trackId: String, title: String, artist: String, album: String, durationMs: Int64) {
        self.trackId = trackId
        self.title = title
        self.artist = artist
        self.album = album
        self.durationMs = durationMs
    }
}

public struct PhonePlayback: Codable, Equatable, Sendable {
    public let state: PhonePlaybackState
    public let positionMs: Int64
    public let speed: Double
    public let capturedAtMonotonicMs: Int64

    public init(state: PhonePlaybackState, positionMs: Int64, speed: Double, capturedAtMonotonicMs: Int64) {
        self.state = state
        self.positionMs = positionMs
        self.speed = speed
        self.capturedAtMonotonicMs = capturedAtMonotonicMs
    }
}

public struct PhonePlaybackSourceIdentity: Codable, Equatable, Sendable {
    public let packageName: String
    public let deviceId: String

    public init(packageName: String, deviceId: String) {
        self.packageName = packageName
        self.deviceId = deviceId
    }
}

public enum PhoneProtocolError: Error, Equatable, Sendable {
    case malformedJSON
    case unknownFields
    case unsupportedVersion(Int)
    case invalidIdentifier
    case invalidSequence
    case invalidTrack
    case invalidPlayback
    case invalidSource
}

public struct PhonePlaybackEnvelope: Codable, Equatable, Sendable {
    public let protocolVersion: Int
    public let sessionId: String
    public let sequence: Int64
    public let event: PhonePlaybackEvent
    public let track: PhoneTrack
    public let playback: PhonePlayback
    public let source: PhonePlaybackSourceIdentity

    public init(
        protocolVersion: Int = 1,
        sessionId: String,
        sequence: Int64,
        event: PhonePlaybackEvent,
        track: PhoneTrack,
        playback: PhonePlayback,
        source: PhonePlaybackSourceIdentity
    ) {
        self.protocolVersion = protocolVersion
        self.sessionId = sessionId
        self.sequence = sequence
        self.event = event
        self.track = track
        self.playback = playback
        self.source = source
    }

    public static func decodeAndValidate(_ data: Data) throws -> Self {
        guard let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw PhoneProtocolError.malformedJSON
        }
        guard hasExactKeys(object, expected: [
            "protocolVersion", "sessionId", "sequence", "event", "track", "playback", "source",
        ]),
        let trackObject = object["track"] as? [String: Any],
        hasExactKeys(trackObject, expected: ["trackId", "title", "artist", "album", "durationMs"]),
        let playbackObject = object["playback"] as? [String: Any],
        hasExactKeys(playbackObject, expected: ["state", "positionMs", "speed", "capturedAtMonotonicMs"]),
        let sourceObject = object["source"] as? [String: Any],
        hasExactKeys(sourceObject, expected: ["packageName", "deviceId"])
        else { throw PhoneProtocolError.unknownFields }

        let value: Self
        do {
            value = try JSONDecoder().decode(Self.self, from: data)
        } catch {
            throw PhoneProtocolError.malformedJSON
        }
        try value.validate()
        return value
    }

    public func validate() throws {
        guard protocolVersion == 1 else { throw PhoneProtocolError.unsupportedVersion(protocolVersion) }
        guard !sessionId.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              sessionId.utf8.count <= 128
        else { throw PhoneProtocolError.invalidIdentifier }
        guard sequence > 0 else { throw PhoneProtocolError.invalidSequence }
        guard !track.trackId.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              !track.title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              track.trackId.utf8.count <= 512,
              track.title.utf8.count <= 1024,
              track.artist.utf8.count <= 1024,
              track.album.utf8.count <= 1024,
              (0 ... 86_400_000).contains(track.durationMs)
        else { throw PhoneProtocolError.invalidTrack }
        guard (0 ... 86_400_000).contains(playback.positionMs),
              (0 ... 4).contains(playback.speed), playback.speed.isFinite,
              playback.capturedAtMonotonicMs >= 0
        else { throw PhoneProtocolError.invalidPlayback }
        guard source.packageName.hasPrefix("com.tencent.qqmusic"),
              !source.deviceId.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              source.deviceId.utf8.count <= 256
        else { throw PhoneProtocolError.invalidSource }
    }

    private static func hasExactKeys(_ object: [String: Any], expected: Set<String>) -> Bool {
        Set(object.keys) == expected
    }
}
