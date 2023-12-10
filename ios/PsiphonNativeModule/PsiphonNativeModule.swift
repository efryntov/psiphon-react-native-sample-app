
import Foundation
import React
import PsiphonTunnel

@objc(PsiphonNativeModule)

class PsiphonNativeModule : RCTEventEmitter {
  private var connectionStateSubscriptions: Int = 0
  private var psiphonTunnelDelegate = PsiphonTunnelDelegate.shared
  
  @objc
  func startPsiphon(_ config: String, resolver: @escaping RCTPromiseResolveBlock, rejecter: @escaping RCTPromiseRejectBlock) {
    return psiphonTunnelDelegate.startPsiphonTunnelAsync(config: config) { success in
      if success {
        resolver(true)
      } else {
        rejecter("PsiphonError", "Failed to start Psiphon", nil)
      }
    }
  }
  
  @objc
  func stopPsiphon() {
    psiphonTunnelDelegate.stopPsiphonTunnelAsync()
  }

  @objc
  override func supportedEvents() -> [String]! {
    return ["PsiphonConnectionState"]
  }
  
  @objc
  override func startObserving() {
    super.startObserving()
    connectionStateSubscriptions += 1
    if connectionStateSubscriptions == 1 {
      let stateToStringMap: [PsiphonConnectionState: String] = [
        .disconnected: "Stopped",
        .connecting: "Connecting",
        .connected: "Connected",
        .waitingForNetwork: "Waiting for network"
      ]
      
      psiphonTunnelDelegate.startSendingConnectionStateUpdates { [weak self] newState in
        if let stateString = stateToStringMap[newState] {
          self?.sendEvent(withName: "PsiphonConnectionState", body: ["state": stateString])
        }
      }
    }
  }
  
  @objc
  override func stopObserving() {
    super.stopObserving()
    connectionStateSubscriptions -= 1
    if connectionStateSubscriptions == 0 {
      psiphonTunnelDelegate.stopSendingConnectionStateUpdates()
    }
  }
  
  @objc
  override static func requiresMainQueueSetup() -> Bool {
    return true
  }
  
}
