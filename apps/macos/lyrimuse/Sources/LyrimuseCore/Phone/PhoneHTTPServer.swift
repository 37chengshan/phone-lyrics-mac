import Foundation
import Network

public final class PhoneHTTPServer: @unchecked Sendable {
    public enum ServerError: Error { case invalidPort }

    private let router: PhoneHTTPRouter
    private let queue = DispatchQueue(label: "me.yudaotor.lyrimuse.phone-http", qos: .userInitiated)
    private var listener: NWListener?
    public private(set) var port: UInt16?
    public var onStateChange: ((NWListener.State) -> Void)?

    public init(router: PhoneHTTPRouter) {
        self.router = router
    }

    public func start(port requestedPort: UInt16 = 8765, discovery: PhoneDiscoveryDescriptor) throws {
        stop()
        guard let nwPort = NWEndpoint.Port(rawValue: requestedPort) else { throw ServerError.invalidPort }
        let listener = try NWListener(using: .tcp, on: nwPort)
        listener.service = discovery.service
        listener.newConnectionHandler = { [weak self] connection in self?.handle(connection) }
        listener.stateUpdateHandler = { [weak self, weak listener] state in
            if case .ready = state { self?.port = listener?.port?.rawValue }
            self?.onStateChange?(state)
        }
        self.listener = listener
        listener.start(queue: queue)
    }

    public func stop() {
        listener?.cancel()
        listener = nil
        port = nil
    }

    private func handle(_ connection: NWConnection) {
        connection.start(queue: queue)
        receive(connection, accumulated: Data())
    }

    private func receive(_ connection: NWConnection, accumulated: Data) {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 16_384) { [weak self] data, _, complete, error in
            guard let self else { connection.cancel(); return }
            var buffer = accumulated
            if let data { buffer.append(data) }
            if buffer.count > 81_920 {
                self.send(.json(status: 413, object: ["error": "payload_too_large"]), on: connection)
                return
            }
            if let request = try? PhoneHTTPRequest.parse(buffer, peer: String(describing: connection.endpoint)) {
                self.send(self.router.route(request), on: connection)
                return
            }
            if complete || error != nil {
                self.send(.json(status: 400, object: ["error": "malformed_request"]), on: connection)
                return
            }
            self.receive(connection, accumulated: buffer)
        }
    }

    private func send(_ response: PhoneHTTPResponse, on connection: NWConnection) {
        connection.send(content: response.serialized(), completion: .contentProcessed { _ in connection.cancel() })
    }
}
