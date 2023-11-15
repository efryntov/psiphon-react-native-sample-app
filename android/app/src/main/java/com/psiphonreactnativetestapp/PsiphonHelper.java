package com.psiphonreactnativetestapp;

import android.content.Context;
import android.net.Uri;

import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
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
import io.reactivex.schedulers.Schedulers;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class PsiphonHelper implements PsiphonTunnel.HostService {
    private static final int TIMEOUT_SECONDS = 30;
    // Singleton instance
    private static PsiphonHelper instance;

    public static synchronized PsiphonHelper getInstance(ReactApplicationContext context) {
        if (instance == null) {
            instance = new PsiphonHelper(context);
        }
        return instance;
    }

    private PsiphonHelper(ReactApplicationContext context) {
        this.context = context;
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

    private final ReactApplicationContext context;


    public void startPsiphon() {
        if (tunnelThread == null) {
            tunnelCountDownLatch = new CountDownLatch(1);
            tunnelThread = new Thread(this::startPsiphonTunnel);
            tunnelThread.setUncaughtExceptionHandler((thread, throwable) -> {
                // rethrow as RuntimeException to be caught by the main thread
                throw new RuntimeException(throwable);
            });
            tunnelThread.start();
        }
    }

    public void stopPsiphon() {
        if (tunnelThread != null) {
            connectionStateBehaviorRelay.accept(PsiphonState.STOPPING);
            stopPsiphonTunnel();
        }
    }

    @Override
    public String getAppName() {
        return PsiphonNativeModule.NAME;
    }

    @Override
    public Context getContext() {
        return context;
    }

    @Override
    public String getPsiphonConfig() {
        try {
            JSONObject config = new JSONObject(
                    readInputStreamToString(
                            context.getResources().openRawResource(R.raw.psiphon_config)));

            return config.toString();

        } catch (IOException | JSONException e) {
            throw new RuntimeException(e);
        }
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
            // rethrow as RuntimeException to be caught by the calling thread exception handler
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
                            return io.reactivex.Observable.empty();
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

    private static class ReqParams {
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

    private Observable<String> fetchObservable(ReqParams reqParams) {
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
                        emitter.setDisposable(io.reactivex.disposables.Disposables.fromAction(call::cancel));
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
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int length;
        while ((length = inputStream.read(buffer)) != -1) {
            result.write(buffer, 0, length);
        }
        return result.toString(StandardCharsets.UTF_8.name());
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
