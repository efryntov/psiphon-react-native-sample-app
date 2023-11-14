package com.psiphonreactnativetestapp;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.modules.core.DeviceEventManagerModule;
import com.jakewharton.rxrelay2.BehaviorRelay;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import ca.psiphon.PsiphonTunnel;
import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.disposables.Disposable;
import io.reactivex.disposables.Disposables;
import io.reactivex.schedulers.Schedulers;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class PsiphonNativeModule extends ReactContextBaseJavaModule implements PsiphonTunnel.HostService {
    private static final int TIMEOUT_SECONDS = 30;

    enum PsiphonState {
        CONNECTING,
        CONNECTED,
        STOPPING,
        STOPPED,
        WAITING_FOR_NETWORK,
    }

    private int socksProxyPort = -1;
    private int httpProxyPort = -1;
    PsiphonTunnel psiphonTunnel;
    private int listenerCount;
    private final AtomicBoolean isPsiphonStopping = new AtomicBoolean(false);
    private CountDownLatch tunnelCountDownLatch;
    private Thread tunnelThread;
    private final BehaviorRelay<PsiphonState> connectionStateBehaviorRelay = BehaviorRelay.createDefault(PsiphonState.STOPPED);
    CompositeDisposable compositeDisposable = new CompositeDisposable();
    private Disposable connectionStateDisposable;

    public PsiphonNativeModule(@Nullable ReactApplicationContext context) {
        super(context);
        psiphonTunnel = PsiphonTunnel.newPsiphonTunnel(this);
    }

    @NonNull
    @Override
    public String getName() {
        return "PsiphonNativeModule";
    }

    @Override
    public String getAppName() {
        return "PsiphonReactNativeTestApp";
    }

    @Override
    public Context getContext() {
        return this.getReactApplicationContext().getApplicationContext();
    }

    @Override
    public String getPsiphonConfig() {
        try {
            JSONObject config = new JSONObject(
                    readInputStreamToString(
                            getContext().getResources().openRawResource(R.raw.psiphon_config)));

            return config.toString();

        } catch (IOException | JSONException e) {
            emitEvent("PsiphonError", e.toString());
        }
        return "";
    }

    @Override
    public void onListeningHttpProxyPort(int port) {
        httpProxyPort = port;
    }

    @Override
    public void onListeningSocksProxyPort(int port) {
        socksProxyPort = port;
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

    @ReactMethod
    public synchronized void startPsiphon() {
        if (tunnelThread != null) {
            return;
        }
        tunnelCountDownLatch = new CountDownLatch(1);
        tunnelThread = new Thread(this::runPsiphonTunnel);
        tunnelThread.start();
    }

    @ReactMethod
    public synchronized void stopPsiphon() {
        if (tunnelThread != null) {
            connectionStateBehaviorRelay.accept(PsiphonState.STOPPING);
            stopPsiphonTunnelThread();
        }
    }

    @ReactMethod
    public void fetch(String method, String url, String body, boolean usePsiphon, Promise promise) {
        ReqParams.Builder builder = new ReqParams.Builder()
                .setMethod(method)
                .setUri(Uri.parse(url))
                .setBody(body);
        Observable<String> observable;
        if (usePsiphon) {
            observable = connectionStateBehaviorRelay
                    .switchMap(psiphonState -> {
                        if (psiphonState == PsiphonState.CONNECTED) {
                            builder.setHttpProxyPort(httpProxyPort);
                            return fetchObservable(builder.build());
                        } else {
                            return Observable.empty();
                        }
                    });
        } else {
            observable = fetchObservable(builder.build());
        }

        compositeDisposable.add(observable
                .firstOrError()
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .doOnSuccess(promise::resolve)
                .doOnError(promise::reject)
                .onErrorReturnItem("")
                .subscribe());
    }


    @ReactMethod
    public void addListener(String eventName) {
        // Subscribe to psiphon connection relay and start emitting to React Native

        listenerCount += 1;
        // Once at least one listener is added, subscribe to the connection state relay
        // and start emitting to React Native
        if (connectionStateDisposable == null || connectionStateDisposable.isDisposed()) {
            connectionStateDisposable = connectionStateBehaviorRelay
                    .doOnNext(psiphonState -> emitEvent("PsiphonConnectionState", psiphonState.toString()))
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe();
            compositeDisposable.add(connectionStateDisposable);
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

    private void runPsiphonTunnel() {
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
            emitEvent("PsiphonError", e.toString());
        } finally {
            isPsiphonStopping.set(true);
            psiphonTunnel.stop();
        }
    }

    private void stopPsiphonTunnelThread() {
        if (tunnelCountDownLatch != null) {
            tunnelCountDownLatch.countDown();
        }
        try {
            tunnelThread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            tunnelThread = null;
            tunnelCountDownLatch = null;
            connectionStateBehaviorRelay.accept(PsiphonState.STOPPED);
        }
    }


    private void emitEvent(String eventName, String eventValue) {
        // do nothing if there are no listeners
        if (listenerCount == 0) {
            return;
        }
        WritableMap params = Arguments.createMap();
        params.putString(eventName, eventValue);
        getReactApplicationContext()
                .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
                .emit("PsiphonEvent", params);
    }

    public static class ReqParams {
        String method;
        Uri uri;
        String body;
        int httpProxyPort;

        private ReqParams(String method, Uri uri, String body, int httpProxyPort) {
            this.method = method;
            this.uri = uri;
            this.body = body;
            this.httpProxyPort = httpProxyPort;
        }

        static class Builder {
            private String method;
            private Uri uri;
            private String body;
            private int httpProxyPort;

            Builder setMethod(String method) {
                // GET by default
                if (method == null) {
                    this.method = "GET";
                } else {
                    this.method = method;
                }
                return this;
            }

            Builder setUri(Uri uri) {
                this.uri = uri;
                return this;
            }

            Builder setBody(String body) {
                this.body = body;
                return this;
            }

            Builder setHttpProxyPort(int httpProxyPort) {
                this.httpProxyPort = httpProxyPort;
                return this;
            }

            ReqParams build() {
                return new ReqParams(method, uri, body, httpProxyPort);
            }
        }
    }

    public Observable<String> fetchObservable(ReqParams reqParams) {
        OkHttpClient.Builder okHttpClientBuilder = new OkHttpClient.Builder()
                .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        return Observable.<String>create(emitter -> {
                    Request.Builder reqBuilder = new Request.Builder();
                    reqBuilder.url(reqParams.uri.toString());
                    if (reqParams.method.equalsIgnoreCase("GET")) {
                        reqBuilder.get();
                    } else if (reqParams.method.equalsIgnoreCase("POST")) {
                        reqBuilder.post(RequestBody.create(reqParams.body, null));
                    } else if (reqParams.method.equalsIgnoreCase("PUT")) {
                        reqBuilder.put(RequestBody.create(new byte[0], null));
                    } else if (reqParams.method.equalsIgnoreCase("HEAD")) {
                        reqBuilder.head();
                    }
                    Request request = reqBuilder.build();

                    if (reqParams.httpProxyPort > 0) {
                        okHttpClientBuilder.proxy(new Proxy(Proxy.Type.HTTP, new InetSocketAddress("localhost", reqParams.httpProxyPort)));
                    }

                    Response response = null;
                    try {
                        final Call call;
                        call = okHttpClientBuilder.build().newCall(request);
                        emitter.setDisposable(Disposables.fromAction(call::cancel));
                        response = call.execute();
                        if (response.isSuccessful()) {
                            if (!emitter.isDisposed()) {
                                final String responseString;
                                if (response.body() != null) {
                                    responseString = response.body().string();
                                } else {
                                    responseString = "";
                                }
                                emitter.onNext(responseString);
                            }
                        } else {
                            if (!emitter.isDisposed()) {
                                final RuntimeException e;
                                e = new RuntimeException("Bad response code from upstream: " +
                                        response.code());
                                emitter.onError(e);
                            }
                        }
                    } catch (IOException e) {
                        if (!emitter.isDisposed()) {
                            emitter.onError(new RuntimeException(e));
                        }
                    } finally {
                        if (response != null && response.body() != null) {
                            response.body().close();
                        }
                    }
                })
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread());
    }


    private static String readInputStreamToString(InputStream inputStream) throws IOException {
        return new String(readInputStreamToBytes(inputStream), StandardCharsets.UTF_8);
    }

    private static byte[] readInputStreamToBytes(InputStream inputStream) throws IOException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        int readCount;
        byte[] buffer = new byte[16384];
        while ((readCount = inputStream.read(buffer, 0, buffer.length)) != -1) {
            outputStream.write(buffer, 0, readCount);
        }
        outputStream.flush();
        inputStream.close();
        return outputStream.toByteArray();
    }
}
