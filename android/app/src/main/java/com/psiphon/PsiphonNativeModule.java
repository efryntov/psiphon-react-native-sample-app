package com.psiphon;

import static com.psiphon.PsiphonHelper.PsiphonState;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.module.annotations.ReactModule;
import com.facebook.react.modules.core.DeviceEventManagerModule;

import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.disposables.Disposable;

@ReactModule(name = PsiphonNativeModule.NAME)
public class PsiphonNativeModule extends ReactContextBaseJavaModule {
    public static final String NAME = "PsiphonNativeModule";

    private int listenerCount;
    CompositeDisposable compositeDisposable = new CompositeDisposable();
    private Disposable connectionStateDisposable;

    private final PsiphonHelper psiphonHelper;

    public PsiphonNativeModule(@Nullable ReactApplicationContext context) {
        super(context);
        psiphonHelper = PsiphonHelper.getInstance(getReactApplicationContext().getApplicationContext());
    }

    @NonNull
    @Override
    public String getName() {
        return NAME;
    }

    @ReactMethod
    public synchronized void startPsiphon(String config, Promise promise) {
        try {
            psiphonHelper.startPsiphon(config);
            promise.resolve(null);
        } catch (RuntimeException e) {
            promise.reject(e);
        }
    }

    @ReactMethod
    public synchronized void stopPsiphon() {
        psiphonHelper.stopPsiphon();
    }

    @ReactMethod
    public void addListener(String eventName) {
        if (eventName.equals("PsiphonConnectionState")) {
            // Subscribe to psiphon connection observable and start emitting to React Native

            listenerCount += 1;
            // Once at least one listener is added, subscribe to the connection state relay
            // and start emitting to React Native
            if (connectionStateDisposable == null || connectionStateDisposable.isDisposed()) {
                connectionStateDisposable = psiphonHelper.getConnectionStateObservable()
                        .map((PsiphonState psiphonState) -> switch (psiphonState) {
                            // Map to string for React Native to consume
                            case CONNECTING -> "CONNECTING";
                            case CONNECTED -> "CONNECTED";
                            case STOPPING -> "STOPPING";
                            case STOPPED -> "STOPPED";
                            case WAITING_FOR_NETWORK -> "WAITING_FOR_NETWORK";
                        })
                        .doOnNext(this::emitPsiphonConnectionState)
                        .observeOn(AndroidSchedulers.mainThread())
                        .subscribe();
                compositeDisposable.add(connectionStateDisposable);
            }
        }
    }

    @ReactMethod
    public void removeListeners(Integer count) {
        listenerCount -= count;
        // Once all listeners are removed, dispose of the connection state relay
        if (listenerCount == 0) {
            compositeDisposable.dispose();
        }
    }

    private void emitPsiphonConnectionState(String state) {
        // do nothing if there are no listeners
        if (listenerCount == 0) {
            return;
        }
        WritableMap params = Arguments.createMap();
        params.putString("state", state);
        getReactApplicationContext()
                .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
                .emit("PsiphonConnectionState", params);
    }
}
