import CFNetwork
import Foundation
import PsiphonTunnel

class PsiphonTunnelDelegate: NSObject, TunneledAppDelegate {
    // Singleton instance
    @objc
    static let shared: PsiphonTunnelDelegate = {
        let instance = PsiphonTunnelDelegate()
        return instance
    }()

    private var psiphonTunnel: PsiphonTunnel?
    private var storedConfig: String?
    private var httpProxyPort: Int = 0
    private var socksProxyPort: Int = 0

    private var currentConnectionState: PsiphonConnectionState = .disconnected
    private let stateChangeSemaphore = DispatchSemaphore(value: 0)

    private var cachedURLSessionConfiguration: URLSessionConfiguration?

    private var connectionStateListener: ((PsiphonConnectionState) -> Void)?

    private let queue = DispatchQueue(label: "PsiphonTunnelDelegateQueue", attributes: .concurrent)

    override private init() {
        super.init()
    }

    private weak var bridge: RCTBridge?

    @objc
    func setBridge(_ bridge: RCTBridge) {
        self.bridge = bridge
    }

    // MARK: - TunneledAppDelegate methods

    func getPsiphonConfig() -> Any? {
        guard let config = storedConfig else {
            NSLog("PsiphonTunnelDelegate: Psiphon config not available")
            return nil
        }
        return config
    }

    func onConnectionStateChanged(from _: PsiphonConnectionState, to newState: PsiphonConnectionState) {
        queue.async(flags: .barrier) {
            self.currentConnectionState = newState
            self.connectionStateListener?(newState)

            // Reset cached config
            self.cachedURLSessionConfiguration = nil

            // Reset handler on every state change
            if let bridge = self.bridge {
                if let handler = bridge.module(for: RCTHTTPRequestHandler.self) as? RCTHTTPRequestHandler {
                    handler.setValue(nil, forKey: "_session")
                    handler.setValue(nil, forKey: "_delegates")
                }
            }

            self.stateChangeSemaphore.signal()
        }
    }

    func onListeningHttpProxyPort(_ port: Int) {
        queue.async(flags: .barrier) {
            self.httpProxyPort = port
        }
    }

    func onListeningSocksProxyPort(_ port: Int) {
        queue.async(flags: .barrier) {
            self.socksProxyPort = port
        }
    }

    // MARK: - Custom methods

    // Method to start PsiphonTunnel asynchronously
    func startPsiphonTunnelAsync(config: String, completion: @escaping (Bool) -> Void) {
        queue.async {
            self.cachedURLSessionConfiguration = nil
            // Store the provided configuration string
            self.storedConfig = config

            self.psiphonTunnel = PsiphonTunnel.newPsiphonTunnel(self)
            let startedSuccessfully = self.psiphonTunnel?.start(true) ?? false
            if startedSuccessfully {
                // Tunnel started successfully
                completion(true)
            } else {
                // Failed to start the tunnel
                self.psiphonTunnel = nil
                completion(false)
            }
        }
    }

    // Method to stop PsiphonTunnel asynchronously
    func stopPsiphonTunnelAsync() {
        queue.async {
            self.cachedURLSessionConfiguration = nil
            self.psiphonTunnel?.stop()
            self.psiphonTunnel = nil
        }
    }

    // Method to start sending connection state updates
    func startSendingConnectionStateUpdates(listener: ((PsiphonConnectionState) -> Void)?) {
        queue.async(flags: .barrier) {
            self.connectionStateListener = listener
            // Send the current connection state to the listener immediately
            listener?(self.currentConnectionState)
        }
    }

    // Method to stop sending connection state updates
    func stopSendingConnectionStateUpdates() {
        queue.async(flags: .barrier) {
            self.connectionStateListener = nil
        }
    }

    // Method to get the URLSessionConfiguration to be used for making network requests
    // This method will block until the configuration becomes available which will happen
    // when the tunnel reaches the connected or disconnected state.
    @objc
    func getURLSessionConfiguration() -> URLSessionConfiguration? {
        if let cachedConfig = cachedURLSessionConfiguration {
            return cachedConfig
        }

        var config: URLSessionConfiguration?
        queue.sync {
            if currentConnectionState == .connected || currentConnectionState == .disconnected {
                cachedURLSessionConfiguration = createURLSessionConfiguration(for: currentConnectionState)
                config = cachedURLSessionConfiguration
            }
        }

        if config == nil {
            stateChangeSemaphore.wait()
            return getURLSessionConfiguration() // Recursive call to check again after a state change.
        }

        return config
    }

    // Method to create a URLSessionConfiguration based on the current connection state
    private func createURLSessionConfiguration(for state: PsiphonConnectionState) -> URLSessionConfiguration? {
        let config = URLSessionConfiguration.default
        config.httpShouldSetCookies = true
        config.httpCookieAcceptPolicy = .always
        config.httpCookieStorage = HTTPCookieStorage.shared
        config.connectionProxyDictionary = [AnyHashable: Any]()

        switch state {
        case .connected:
            config.connectionProxyDictionary?[kCFStreamPropertySOCKSProxy as String] = 1
            config.connectionProxyDictionary?[kCFStreamPropertySOCKSProxyHost as String] = "127.0.0.1"
            config.connectionProxyDictionary?[kCFStreamPropertySOCKSProxyPort as String] = socksProxyPort
        case .disconnected:
            config.connectionProxyDictionary?[kCFStreamPropertySOCKSProxy as String] = 0
        default:
            return nil
        }

        return config
    }
}
