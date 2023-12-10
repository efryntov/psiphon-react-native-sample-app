import Foundation
import PsiphonTunnel
import CFNetwork

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
  
  private var onConnectionStateChanged: ((PsiphonConnectionState) -> Void)?
  private var connectionStateListener: ((PsiphonConnectionState) -> Void)?
  
  private let queue = DispatchQueue(label: "PsiphonTunnelDelegateQueue", attributes: .concurrent)
  
  private override init() {
    super.init()
    onConnectionStateChanged = nil
  }
  
  // MARK: - TunneledAppDelegate methods
  
  func getPsiphonConfig() -> Any? {
    guard let config = storedConfig else {
      NSLog("PsiphonTunnelDelegate: Psiphon config not available")
      return nil
    }
    return config
  }
  
  func onConnectionStateChanged( from oldState: PsiphonConnectionState, to newState: PsiphonConnectionState ) {
    queue.async(flags: .barrier) {
      self.currentConnectionState = newState
    }
    onConnectionStateChanged?(newState)
    connectionStateListener?(newState)
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
      self.psiphonTunnel?.stop()
      self.psiphonTunnel = nil
    }
  }
  
  // Method to get the current connection state
  @objc
  func getURLSessionConfiguration() -> URLSessionConfiguration {
    let currentState = queue.sync {
      return self.currentConnectionState
    }
    
    switch currentState {
    case .connected:
      return connectedConfiguration()
    case .disconnected:
      return disconnectedConfiguration()
    default:
      return waitForConnection()
    }
  }
  
  private func connectedConfiguration() -> URLSessionConfiguration {
    let configuration = URLSessionConfiguration.default
    
    // Enable and set the SOCKS proxy values.
    configuration.connectionProxyDictionary = [AnyHashable: Any]()
    configuration.connectionProxyDictionary?[kCFStreamPropertySOCKSProxy as String] = 1
    configuration.connectionProxyDictionary?[kCFStreamPropertySOCKSProxyHost as String] = "127.0.0.1"
    configuration.connectionProxyDictionary?[kCFStreamPropertySOCKSProxyPort as String] = socksProxyPort
    
    return configuration
  }
  
  private func disconnectedConfiguration() -> URLSessionConfiguration {
    let configuration = URLSessionConfiguration.default
    
    // Make sure the SOCKS proxy is disabled
    configuration.connectionProxyDictionary = [AnyHashable: Any]()
    configuration.connectionProxyDictionary?[kCFStreamPropertySOCKSProxy as String] = 0
    
    return configuration
  }
  
  private func waitForConnection() -> URLSessionConfiguration {
    var configuration: URLSessionConfiguration!
    
    let group = DispatchGroup()
    
    group.enter()
    self.onConnectionStateChanged = { [weak self] newState in
      guard let self = self else { return }
      
      if newState == .connected || newState == .disconnected {
        configuration =
        newState == .connected ? self.connectedConfiguration() : self.disconnectedConfiguration()
        group.leave()
      }
    }
    
    group.wait()
    
    return configuration
  }
  
  func startSendingConnectionStateUpdates(listener: ((PsiphonConnectionState) -> Void)?) {
    connectionStateListener = listener
    // Send the current connection state to the listener immediately
    connectionStateListener?(currentConnectionState)
  }
  
  func stopSendingConnectionStateUpdates() {
    connectionStateListener = nil
  }
}
