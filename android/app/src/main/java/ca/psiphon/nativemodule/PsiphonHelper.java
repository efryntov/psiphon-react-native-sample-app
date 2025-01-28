package ca.psiphon.nativemodule;

import android.content.Context;
import android.util.Log;

import com.facebook.react.modules.network.CustomClientBuilder;
import com.jakewharton.rxrelay2.BehaviorRelay;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import ca.psiphon.PsiphonTunnel;
import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.schedulers.Schedulers;

public class PsiphonHelper implements PsiphonTunnel.HostService {
    // Singleton instance
    private static PsiphonHelper instance;

    public static synchronized PsiphonHelper getInstance(Context appContext) {
        if (instance == null) {
            instance = new PsiphonHelper(appContext);
        }
        return instance;
    }

    private PsiphonHelper(Context appContext) {
        this.appContext = appContext;
        psiphonTunnel = PsiphonTunnel.newPsiphonTunnel(this);
    }

    public enum PsiphonState {
        CONNECTING,
        CONNECTED,
        STOPPING,
        STOPPED,
        WAITING_FOR_NETWORK,
    }

    private int httpProxyPort = -1;
    private final PsiphonTunnel psiphonTunnel;
    private final AtomicBoolean isPsiphonStopping = new AtomicBoolean(false);
    private String psiphonConfig;
    private CountDownLatch tunnelCountDownLatch;
    private Thread tunnelThread;

    public Observable<PsiphonState> getConnectionStateObservable() {
        return connectionStateBehaviorRelay
                .distinctUntilChanged()
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread());
    }

    private final BehaviorRelay<PsiphonState> connectionStateBehaviorRelay = BehaviorRelay.createDefault(PsiphonState.STOPPED);
    private final CompositeDisposable compositeDisposable = new CompositeDisposable();

    private final Context appContext;


    @Override
    public String getAppName() {
        return PsiphonNativeModule.NAME;
    }

    @Override
    public Context getContext() {
        return appContext;
    }

    @Override
    public String getPsiphonConfig() {
        if (psiphonConfig == null) {
            throw new IllegalStateException("Psiphon configuration not set.");
        }
        return psiphonConfig;
    }

    // Other PsiphonTunnel.HostService methods

    @Override
    public void onListeningHttpProxyPort(int port) {
        httpProxyPort = port;
    }

    @Override
    public void onListeningSocksProxyPort(int port) {
        // Implement if needed
    }

    @Override
    public void onConnected() {
        connectionStateBehaviorRelay.accept(PsiphonState.CONNECTED);
    }

    @Override
    public void onConnecting() {
        // Do not signal "connecting" if we are stopping
        if (!isPsiphonStopping.get()) {
            connectionStateBehaviorRelay.accept(PsiphonState.CONNECTING);
        }
    }

    @Override
    public void onStartedWaitingForNetworkConnectivity() {
        connectionStateBehaviorRelay.accept(PsiphonState.WAITING_FOR_NETWORK);
    }

    void startPsiphon(String config, PsiphonStartResultCallback resultCallback) {
        psiphonConfig = config;
        if (tunnelThread == null) {
            tunnelCountDownLatch = new CountDownLatch(1);
            tunnelThread = new Thread(() -> {
                try {
                    startPsiphonTunnel();
                    resultCallback.onSuccess();
                } catch (RuntimeException e) {
                    resultCallback.onError(e);
                    psiphonTunnel.stop();
                    connectionStateBehaviorRelay.accept(PsiphonState.STOPPED);
                }
            });
            tunnelThread.start();
        }
    }

    void stopPsiphon() {
        if (tunnelThread != null) {
            connectionStateBehaviorRelay.accept(PsiphonState.STOPPING);
            stopPsiphonTunnel();
        }
    }

    public CustomClientBuilder makePsiphonEnabledOkHttpClientBuilder() {
        return builder -> builder.proxySelector(new ProxySelector() {
            @Override
            public List<Proxy> select(URI uri) {
                // Always bypass proxy for localhost/loopback addresses
                if (isLocalhost(uri.getHost())) {
                    return List.of(Proxy.NO_PROXY);
                }

                return getConnectionStateObservable()
                        .observeOn(Schedulers.io())
                        .switchMap(state -> {
                            switch (state) {
                                case CONNECTED:
                                    return Observable.just(List.of(new Proxy(Proxy.Type.HTTP,
                                            InetSocketAddress.createUnresolved("localhost",
                                                    httpProxyPort))));
                                case STOPPED:
                                    return Observable.just(List.of(Proxy.NO_PROXY));
                                default:
                                    return Observable.empty();
                            }
                        })
                        .firstOrError()
                        .blockingGet();
            }

            private boolean isLocalhost(String host) {
                return host.equalsIgnoreCase("localhost") ||
                        host.equals("127.0.0.1") ||
                        host.equals("::1");
            }

            @Override
            public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
                Log.d(PsiphonNativeModule.NAME, "OkHttp proxy selector: connectFailed: uri: " + uri + " sa: " + sa + " ioe: " + ioe);
            }
        });
    }

    private void startPsiphonTunnel() {
        isPsiphonStopping.set(false);
        connectionStateBehaviorRelay.accept(PsiphonState.CONNECTING);
        try {
            psiphonTunnel.startTunneling("");
            try {
                tunnelCountDownLatch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        } catch (PsiphonTunnel.Exception e) {
            // rethrow as RuntimeException
            throw new RuntimeException(e);
        } finally {
            isPsiphonStopping.set(true);
            psiphonTunnel.stop();
        }
    }

    private void stopPsiphonTunnel() throws RuntimeException {
        if (tunnelCountDownLatch != null) {
            tunnelCountDownLatch.countDown();
        }
        try {
            if (tunnelThread != null) {
                tunnelThread.join();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            tunnelThread = null;
            tunnelCountDownLatch = null;
            connectionStateBehaviorRelay.accept(PsiphonState.STOPPED);
        }
    }
}
