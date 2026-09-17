import Foundation
import Network

public struct PhoneDiscoveryDescriptor: Equatable, Sendable {
    public let serviceName: String
    public let stableMacId: String
    public let pairingEnabled: Bool

    public init(serviceName: String, stableMacId: String, pairingEnabled: Bool) {
        self.serviceName = serviceName
        self.stableMacId = stableMacId
        self.pairingEnabled = pairingEnabled
    }

    public var service: NWListener.Service {
        return NWListener.Service(
            name: serviceName,
            type: "_phonelyrics._tcp",
            domain: nil,
            txtRecord: NWTXTRecord([
                "v": "1",
                "id": stableMacId,
                "pairing": pairingEnabled ? "1" : "0",
            ]))
    }
}
