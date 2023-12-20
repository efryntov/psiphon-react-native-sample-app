package com.psiphondemoapp

import android.app.Application
import com.facebook.react.PackageList
import com.facebook.react.ReactApplication
import com.facebook.react.ReactHost
import com.facebook.react.ReactNativeHost
import com.facebook.react.ReactPackage
import com.facebook.react.defaults.DefaultNewArchitectureEntryPoint.load
import com.facebook.react.defaults.DefaultReactHost.getDefaultReactHost
import com.facebook.react.defaults.DefaultReactNativeHost
import com.facebook.react.flipper.ReactNativeFlipper
import com.facebook.react.modules.network.NetworkingModule
import com.facebook.react.modules.websocket.WebSocketModule
import com.facebook.soloader.SoLoader
import com.psiphon.PsiphonHelper
import com.psiphon.PsiphonPackage

class MainApplication : Application(), ReactApplication {

  override val reactNativeHost: ReactNativeHost =
      object : DefaultReactNativeHost(this) {
        override fun getPackages(): List<ReactPackage> {
          // Packages that cannot be autolinked yet can be added manually here, for example:
          // packages.add(new MyReactNativePackage());

          // Psiphon modification, add PsiphonPackage
          // Note: make sure to add PsiphonPackage import
          val packages = PackageList(this).packages
          packages.add(PsiphonPackage())
          // end Psiphon modification

          return packages
        }

        override fun getJSMainModuleName(): String = "index"

        override fun getUseDeveloperSupport(): Boolean = BuildConfig.DEBUG

        override val isNewArchEnabled: Boolean = BuildConfig.IS_NEW_ARCHITECTURE_ENABLED
        override val isHermesEnabled: Boolean = BuildConfig.IS_HERMES_ENABLED
      }

  override val reactHost: ReactHost
    get() = getDefaultReactHost(this.applicationContext, reactNativeHost)

  override fun onCreate() {
    super.onCreate()
    SoLoader.init(this, false)
    if (BuildConfig.IS_NEW_ARCHITECTURE_ENABLED) {
      // If you opted-in for the New Architecture, we load the native entry point for this app.
      load()
    }
    ReactNativeFlipper.initializeFlipper(this, reactNativeHost.reactInstanceManager)

    // Psiphon modification
    // Note: make sure to add PsiphonHelper import

    // Provide custom http client builder to NetworkingModule and WebSocketModule to enable Psiphon proxying
    val customClientBuilder = PsiphonHelper.getInstance(applicationContext).makePsiphonEnabledOkHttpClientBuilder()

    // Set custom client builder for NetworkingModule and WebSocketModule
    // Note: add NetworkingModule and WebSocketModule imports
    NetworkingModule.setCustomClientBuilder(customClientBuilder);
    WebSocketModule.setCustomClientBuilder(customClientBuilder);

    // end Psiphon modification
  }
}
