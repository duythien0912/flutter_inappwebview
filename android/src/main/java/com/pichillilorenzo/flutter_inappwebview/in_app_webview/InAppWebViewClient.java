package com.pichillilorenzo.flutter_inappwebview.in_app_webview;

import android.annotation.TargetApi;
import android.graphics.Bitmap;
import android.net.http.SslError;
import android.os.Build;
import android.os.Message;
import android.util.Log;
import android.view.KeyEvent;
import android.webkit.ClientCertRequest;
import android.webkit.CookieManager;
import android.webkit.CookieSyncManager;
import android.webkit.HttpAuthHandler;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.SafeBrowsingResponse;
import android.webkit.SslErrorHandler;
import android.webkit.ValueCallback;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import com.pichillilorenzo.flutter_inappwebview.Util;
import com.pichillilorenzo.flutter_inappwebview.credential_database.CredentialDatabase;
import com.pichillilorenzo.flutter_inappwebview.in_app_browser.InAppBrowserDelegate;
import com.pichillilorenzo.flutter_inappwebview.plugin_scripts_js.JavaScriptBridgeJS;
import com.pichillilorenzo.flutter_inappwebview.types.ClientCertChallenge;
import com.pichillilorenzo.flutter_inappwebview.types.HttpAuthenticationChallenge;
import com.pichillilorenzo.flutter_inappwebview.types.NavigationAction;
import com.pichillilorenzo.flutter_inappwebview.types.NavigationActionPolicy;
import com.pichillilorenzo.flutter_inappwebview.types.ServerTrustChallenge;
import com.pichillilorenzo.flutter_inappwebview.types.URLCredential;
import com.pichillilorenzo.flutter_inappwebview.types.URLProtectionSpace;
import com.pichillilorenzo.flutter_inappwebview.types.URLRequest;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

import io.flutter.plugin.common.MethodChannel;
import okhttp3.ConnectionSpec;
import okhttp3.Headers;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.TlsVersion;

public class InAppWebViewClient extends WebViewClient {

    protected static final String LOG_TAG = "IAWebViewClient";
    private InAppBrowserDelegate inAppBrowserDelegate;
    private final MethodChannel channel;
    private static int previousAuthRequestFailureCount = 0;
    private static List<URLCredential> credentialsProposed = null;
    protected static OkHttpClient httpClient;

    public InAppWebViewClient(MethodChannel channel, InAppBrowserDelegate inAppBrowserDelegate) {
        super();

        this.channel = channel;
        this.inAppBrowserDelegate = inAppBrowserDelegate;

        httpClient = new OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build();
//        try {
//            httpClient = InAppWebViewClient.createOkhttpClientBuilderWithTlsConfig()
//                    .followRedirects(false)
//                    .followSslRedirects(false)
//                    .build();
//        } catch (Exception e) {
//            e.printStackTrace();
//        }
    }

    static private OkHttpClient.Builder createOkhttpClientBuilderWithTlsConfig() throws Exception {
        OkHttpClient.Builder builder = new OkHttpClient.Builder();

        TrustManagerFactory trustManager = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManager.init((KeyStore) null);

        X509TrustManager x509manager = (X509TrustManager) trustManager.getTrustManagers()[0];
        SSLContext tls11 = SSLContext.getInstance(TlsVersion.TLS_1_1.javaName());
        tls11.init(null, null, null);

        builder.sslSocketFactory(new TlsSocketFactory(tls11.getSocketFactory()), x509manager);

        ConnectionSpec cs = new ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS).tlsVersions(TlsSocketFactory.ALLOWED_TLS_VERSIONS).build();
        List<ConnectionSpec> specs = new ArrayList();
        specs.add(cs);
        specs.add(ConnectionSpec.COMPATIBLE_TLS);
        specs.add(ConnectionSpec.CLEARTEXT);

        builder.connectionSpecs(specs);
        return builder;
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    @Override
    public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
        InAppWebView webView = (InAppWebView) view;
        if (webView.options.useShouldOverrideUrlLoading) {
            boolean isRedirect = false;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                isRedirect = request.isRedirect();
            }
            onShouldOverrideUrlLoading(
                    webView,
                    request.getUrl().toString(),
                    request.getMethod(),
                    request.getRequestHeaders(),
                    request.isForMainFrame(),
                    request.hasGesture(),
                    isRedirect);
            if (webView.regexToCancelSubFramesLoadingCompiled != null) {
                if (request.isForMainFrame())
                    return true;
                else {
                    Matcher m = webView.regexToCancelSubFramesLoadingCompiled.matcher(request.getUrl().toString());
                    return m.matches();
                }
            } else {
                // There isn't any way to load an URL for a frame that is not the main frame,
                // so if the request is not for the main frame, the navigation is allowed.
                return request.isForMainFrame();
            }
        }
        return false;
    }

    @Override
    public boolean shouldOverrideUrlLoading(WebView webView, String url) {
        InAppWebView inAppWebView = (InAppWebView) webView;
        if (inAppWebView.options.useShouldOverrideUrlLoading) {
            onShouldOverrideUrlLoading(inAppWebView, url, "GET", null, true, false, false);
            return true;
        }
        return false;
    }

    private void allowShouldOverrideUrlLoading(WebView webView, String url, Map<String, String> headers, boolean isForMainFrame) {
        if (isForMainFrame) {
            // There isn't any way to load an URL for a frame that is not the main frame,
            // so call this only on main frame.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)
                webView.loadUrl(url, headers);
            else
                webView.loadUrl(url);
        }
    }

    public void onShouldOverrideUrlLoading(final InAppWebView webView, final String url, final String method, final Map<String, String> headers,
                                           final boolean isForMainFrame, boolean hasGesture, boolean isRedirect) {
        URLRequest request = new URLRequest(url, method, null, headers);
        NavigationAction navigationAction = new NavigationAction(
                request,
                isForMainFrame,
                hasGesture,
                isRedirect
        );

        channel.invokeMethod("shouldOverrideUrlLoading", navigationAction.toMap(), new MethodChannel.Result() {
            @Override
            public void success(Object response) {
                if (response != null) {
                    Map<String, Object> responseMap = (Map<String, Object>) response;
                    Integer action = (Integer) responseMap.get("action");
                    action = action != null ? action : NavigationActionPolicy.CANCEL.rawValue();

                    NavigationActionPolicy navigationActionPolicy = NavigationActionPolicy.fromValue(action);
                    if (navigationActionPolicy != null) {
                        switch (navigationActionPolicy) {
                            case ALLOW:
                                allowShouldOverrideUrlLoading(webView, url, headers, isForMainFrame);
                                return;
                            case CANCEL:
                            default:
                                return;
                        }
                    }
                    return;
                }
                allowShouldOverrideUrlLoading(webView, url, headers, isForMainFrame);
            }

            @Override
            public void error(String errorCode, @Nullable String errorMessage, @Nullable Object errorDetails) {
                Log.e(LOG_TAG, errorCode + ", " + ((errorMessage != null) ? errorMessage : ""));
                allowShouldOverrideUrlLoading(webView, url, headers, isForMainFrame);
            }

            @Override
            public void notImplemented() {
                allowShouldOverrideUrlLoading(webView, url, headers, isForMainFrame);
            }
        });
    }

    public void loadCustomJavaScriptOnPageStarted(WebView view) {
        InAppWebView webView = (InAppWebView) view;

        String source = webView.userContentController.generateWrappedCodeForDocumentStart();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            webView.evaluateJavascript(source, (ValueCallback<String>) null);
        } else {
            webView.loadUrl("javascript:" + source.replaceAll("[\r\n]+", ""));
        }
    }

    public void loadCustomJavaScriptOnPageFinished(WebView view) {
        InAppWebView webView = (InAppWebView) view;

        String source = webView.userContentController.generateWrappedCodeForDocumentEnd();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            webView.evaluateJavascript(source, (ValueCallback<String>) null);
        } else {
            webView.loadUrl("javascript:" + source.replaceAll("[\r\n]+", ""));
        }
    }

    @Override
    public void onPageStarted(WebView view, String url, Bitmap favicon) {
        final InAppWebView webView = (InAppWebView) view;
        webView.isLoading = true;
        webView.disposeWebMessageChannels();
        webView.userContentController.resetContentWorlds();
        loadCustomJavaScriptOnPageStarted(webView);

        super.onPageStarted(view, url, favicon);

        if (inAppBrowserDelegate != null) {
            inAppBrowserDelegate.didStartNavigation(url);
        }

        Map<String, Object> obj = new HashMap<>();
        obj.put("url", url);
        channel.invokeMethod("onLoadStart", obj);
    }


    public void onPageFinished(WebView view, String url) {
        final InAppWebView webView = (InAppWebView) view;
        webView.isLoading = false;
        loadCustomJavaScriptOnPageFinished(webView);
        previousAuthRequestFailureCount = 0;
        credentialsProposed = null;

        super.onPageFinished(view, url);

        if (inAppBrowserDelegate != null) {
            inAppBrowserDelegate.didFinishNavigation(url);
        }

        // WebView not storing cookies reliable to local device storage
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            CookieManager.getInstance().flush();
        } else {
            CookieSyncManager.getInstance().sync();
        }

        String js = JavaScriptBridgeJS.PLATFORM_READY_JS_SOURCE;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            webView.evaluateJavascript(js, (ValueCallback<String>) null);
        } else {
            webView.loadUrl("javascript:" + js.replaceAll("[\r\n]+", ""));
        }

        Map<String, Object> obj = new HashMap<>();
        obj.put("url", url);
        channel.invokeMethod("onLoadStop", obj);
    }

    @Override
    public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
        super.doUpdateVisitedHistory(view, url, isReload);

        url = view.getUrl();

        if (inAppBrowserDelegate != null) {
            inAppBrowserDelegate.didUpdateVisitedHistory(url);
        }

        Map<String, Object> obj = new HashMap<>();
        // url argument sometimes doesn't contain the new changed URL, so we get it again from the webview.
        obj.put("url", url);
        obj.put("androidIsReload", isReload);
        channel.invokeMethod("onUpdateVisitedHistory", obj);
    }

    @RequiresApi(api = Build.VERSION_CODES.M)
    @Override
    public void onReceivedError(WebView view, @NonNull WebResourceRequest request, @NonNull WebResourceError error) {
//    final InAppWebView webView = (InAppWebView) view;
//
//    if (request.isForMainFrame()) {
//      if (webView.options.disableDefaultErrorPage) {
//        webView.stopLoading();
//        webView.loadUrl("about:blank");
//      }
//
//      webView.isLoading = false;
//      previousAuthRequestFailureCount = 0;
//      credentialsProposed = null;
//
//      if (inAppBrowserDelegate != null) {
//        inAppBrowserDelegate.didFailNavigation(request.getUrl().toString(), error.getErrorCode(), error.getDescription().toString());
//      }
//    }
//
//    Map<String, Object> obj = new HashMap<>();
//    obj.put("url", request.getUrl().toString());
//    obj.put("code", error.getErrorCode());
//    obj.put("message", error.getDescription());
//    channel.invokeMethod("onLoadError", obj);

        super.onReceivedError(view, request, error);
    }

    @Override
    public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
        final InAppWebView webView = (InAppWebView) view;

        if (webView.options.disableDefaultErrorPage) {
            webView.stopLoading();
            webView.loadUrl("about:blank");
        }

        webView.isLoading = false;
        previousAuthRequestFailureCount = 0;
        credentialsProposed = null;

        if (inAppBrowserDelegate != null) {
            inAppBrowserDelegate.didFailNavigation(failingUrl, errorCode, description);
        }

        Map<String, Object> obj = new HashMap<>();
        obj.put("url", failingUrl);
        obj.put("code", errorCode);
        obj.put("message", description);
        channel.invokeMethod("onLoadError", obj);

        super.onReceivedError(view, errorCode, description, failingUrl);
    }

    @RequiresApi(api = Build.VERSION_CODES.M)
    @Override
    public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse errorResponse) {
        super.onReceivedHttpError(view, request, errorResponse);
        if (request.isForMainFrame()) {
            Map<String, Object> obj = new HashMap<>();
            obj.put("url", request.getUrl().toString());
            obj.put("statusCode", errorResponse.getStatusCode());
            obj.put("description", errorResponse.getReasonPhrase());
            channel.invokeMethod("onLoadHttpError", obj);
        }
    }

    @Override
    public void onReceivedHttpAuthRequest(final WebView view, final HttpAuthHandler handler, final String host, final String realm) {

        URI uri;
        try {
            uri = new URI(view.getUrl());
        } catch (URISyntaxException e) {
            e.printStackTrace();

            credentialsProposed = null;
            previousAuthRequestFailureCount = 0;

            handler.cancel();
            return;
        }

        final String protocol = uri.getScheme();
        final int port = uri.getPort();

        previousAuthRequestFailureCount++;

        Map<String, Object> obj = new HashMap<>();
        obj.put("host", host);
        obj.put("protocol", protocol);
        obj.put("realm", realm);
        obj.put("port", port);
        obj.put("previousFailureCount", previousAuthRequestFailureCount);

        if (credentialsProposed == null)
            credentialsProposed = CredentialDatabase.getInstance(view.getContext()).getHttpAuthCredentials(host, protocol, realm, port);

        URLCredential credentialProposed = null;
        if (credentialsProposed != null && credentialsProposed.size() > 0) {
            credentialProposed = credentialsProposed.get(0);
        }

        URLProtectionSpace protectionSpace = new URLProtectionSpace(host, protocol, realm, port, view.getCertificate(), null);
        HttpAuthenticationChallenge challenge = new HttpAuthenticationChallenge(protectionSpace, previousAuthRequestFailureCount, credentialProposed);

        channel.invokeMethod("onReceivedHttpAuthRequest", challenge.toMap(), new MethodChannel.Result() {
            @Override
            public void success(Object response) {
                if (response != null) {
                    Map<String, Object> responseMap = (Map<String, Object>) response;
                    Integer action = (Integer) responseMap.get("action");
                    if (action != null) {
                        switch (action) {
                            case 1:
                                String username = (String) responseMap.get("username");
                                String password = (String) responseMap.get("password");
                                Boolean permanentPersistence = (Boolean) responseMap.get("permanentPersistence");
                                if (permanentPersistence != null && permanentPersistence) {
                                    CredentialDatabase.getInstance(view.getContext()).setHttpAuthCredential(host, protocol, realm, port, username, password);
                                }
                                handler.proceed(username, password);
                                return;
                            case 2:
                                if (credentialsProposed.size() > 0) {
                                    URLCredential credential = credentialsProposed.remove(0);
                                    handler.proceed(credential.getUsername(), credential.getPassword());
                                } else {
                                    handler.cancel();
                                }
                                // used custom CredentialDatabase!
                                // handler.useHttpAuthUsernamePassword();
                                return;
                            case 0:
                            default:
                                credentialsProposed = null;
                                previousAuthRequestFailureCount = 0;
                                handler.cancel();
                                return;
                        }
                    }
                }

                InAppWebViewClient.super.onReceivedHttpAuthRequest(view, handler, host, realm);
            }

            @Override
            public void error(String errorCode, @Nullable String errorMessage, @Nullable Object errorDetails) {
                Log.e(LOG_TAG, errorCode + ", " + ((errorMessage != null) ? errorMessage : ""));
            }

            @Override
            public void notImplemented() {
                InAppWebViewClient.super.onReceivedHttpAuthRequest(view, handler, host, realm);
            }
        });
    }

    @Override
    public void onReceivedSslError(final WebView view, final SslErrorHandler handler, final SslError sslError) {
        URI uri;
        try {
            uri = new URI(sslError.getUrl());
        } catch (URISyntaxException e) {
            e.printStackTrace();
            handler.cancel();
            return;
        }

        final String host = uri.getHost();
        final String protocol = uri.getScheme();
        final String realm = null;
        final int port = uri.getPort();

        URLProtectionSpace protectionSpace = new URLProtectionSpace(host, protocol, realm, port, sslError.getCertificate(), sslError);
        ServerTrustChallenge challenge = new ServerTrustChallenge(protectionSpace);

        channel.invokeMethod("onReceivedServerTrustAuthRequest", challenge.toMap(), new MethodChannel.Result() {
            @Override
            public void success(Object response) {
                if (response != null) {
                    Map<String, Object> responseMap = (Map<String, Object>) response;
                    Integer action = (Integer) responseMap.get("action");
                    if (action != null) {
                        switch (action) {
                            case 1:
                                handler.proceed();
                                return;
                            case 0:
                            default:
                                handler.cancel();
                                return;
                        }
                    }
                }

                InAppWebViewClient.super.onReceivedSslError(view, handler, sslError);
            }

            @Override
            public void error(String errorCode, @Nullable String errorMessage, @Nullable Object errorDetails) {
                Log.e(LOG_TAG, errorCode + ", " + ((errorMessage != null) ? errorMessage : ""));
            }

            @Override
            public void notImplemented() {
                InAppWebViewClient.super.onReceivedSslError(view, handler, sslError);
            }
        });
    }

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    @Override
    public void onReceivedClientCertRequest(final WebView view, final ClientCertRequest request) {

        InAppWebView webView = (InAppWebView) view;

        URI uri;
        try {
            uri = new URI(view.getUrl());
        } catch (URISyntaxException e) {
            e.printStackTrace();
            request.cancel();
            return;
        }

        final String host = request.getHost();
        final String protocol = uri.getScheme();
        final String realm = null;
        final int port = request.getPort();

        URLProtectionSpace protectionSpace = new URLProtectionSpace(host, protocol, realm, port, view.getCertificate(), null);
        ClientCertChallenge challenge = new ClientCertChallenge(protectionSpace, request.getPrincipals(), request.getKeyTypes());

        channel.invokeMethod("onReceivedClientCertRequest", challenge.toMap(), new MethodChannel.Result() {
            @Override
            public void success(Object response) {
                if (response != null) {
                    Map<String, Object> responseMap = (Map<String, Object>) response;
                    Integer action = (Integer) responseMap.get("action");
                    if (action != null) {
                        switch (action) {
                            case 1: {
                                InAppWebView webView = (InAppWebView) view;
                                String certificatePath = (String) responseMap.get("certificatePath");
                                String certificatePassword = (String) responseMap.get("certificatePassword");
                                String androidKeyStoreType = (String) responseMap.get("androidKeyStoreType");
                                Util.PrivateKeyAndCertificates privateKeyAndCertificates = Util.loadPrivateKeyAndCertificate(webView.plugin, certificatePath, certificatePassword, androidKeyStoreType);
                                request.proceed(privateKeyAndCertificates.privateKey, privateKeyAndCertificates.certificates);
                            }
                            return;
                            case 2:
                                request.ignore();
                                return;
                            case 0:
                            default:
                                request.cancel();
                                return;
                        }
                    }
                }

                InAppWebViewClient.super.onReceivedClientCertRequest(view, request);
            }

            @Override
            public void error(String errorCode, @Nullable String errorMessage, @Nullable Object errorDetails) {
                Log.e(LOG_TAG, errorCode + ", " + ((errorMessage != null) ? errorMessage : ""));
            }

            @Override
            public void notImplemented() {
                InAppWebViewClient.super.onReceivedClientCertRequest(view, request);
            }
        });
    }

    @Override
    public void onScaleChanged(WebView view, float oldScale, float newScale) {
        super.onScaleChanged(view, oldScale, newScale);
        final InAppWebView webView = (InAppWebView) view;
        webView.zoomScale = newScale / Util.getPixelDensity(webView.getContext());

        Map<String, Object> obj = new HashMap<>();
        obj.put("oldScale", oldScale);
        obj.put("newScale", newScale);
        channel.invokeMethod("onZoomScaleChanged", obj);
    }

    @RequiresApi(api = Build.VERSION_CODES.O_MR1)
    @Override
    public void onSafeBrowsingHit(final WebView view, final WebResourceRequest request, final int threatType, final SafeBrowsingResponse callback) {
        Map<String, Object> obj = new HashMap<>();
        obj.put("url", request.getUrl().toString());
        obj.put("threatType", threatType);

        channel.invokeMethod("onSafeBrowsingHit", obj, new MethodChannel.Result() {
            @Override
            public void success(Object response) {
                if (response != null) {
                    Map<String, Object> responseMap = (Map<String, Object>) response;
                    Boolean report = (Boolean) responseMap.get("report");
                    Integer action = (Integer) responseMap.get("action");

                    report = report != null ? report : true;

                    if (action != null) {
                        switch (action) {
                            case 0:
                                callback.backToSafety(report);
                                return;
                            case 1:
                                callback.proceed(report);
                                return;
                            case 2:
                            default:
                                callback.showInterstitial(report);
                                return;
                        }
                    }
                }

                InAppWebViewClient.super.onSafeBrowsingHit(view, request, threatType, callback);
            }

            @Override
            public void error(String errorCode, @Nullable String errorMessage, @Nullable Object errorDetails) {
                Log.e(LOG_TAG, errorCode + ", " + ((errorMessage != null) ? errorMessage : ""));
            }

            @Override
            public void notImplemented() {
                InAppWebViewClient.super.onSafeBrowsingHit(view, request, threatType, callback);
            }
        });
    }

    @Override
    public WebResourceResponse shouldInterceptRequest(WebView view, final String url) {

        final InAppWebView webView = (InAppWebView) view;

        if (webView.options.useShouldInterceptRequest) {
            WebResourceResponse onShouldInterceptResponse = onShouldInterceptRequest(url);
            return onShouldInterceptResponse;
        }

        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException uriExpection) {
            String[] urlSplitted = url.split(":");
            String scheme = urlSplitted[0];
            try {
                URL tempUrl = new URL(url.replace(scheme, "https"));
                uri = new URI(scheme, tempUrl.getUserInfo(), tempUrl.getHost(), tempUrl.getPort(), tempUrl.getPath(), tempUrl.getQuery(), tempUrl.getRef());
            } catch (Exception e) {
                e.printStackTrace();
                return null;
            }
        }

        String scheme = uri.getScheme();

        if (webView.options.resourceCustomSchemes != null && webView.options.resourceCustomSchemes.contains(scheme)) {
            final Map<String, Object> obj = new HashMap<>();
            obj.put("url", url);

            Util.WaitFlutterResult flutterResult;
            try {
                flutterResult = Util.invokeMethodAndWait(channel, "onLoadResourceCustomScheme", obj);
            } catch (InterruptedException e) {
                e.printStackTrace();
                return null;
            }

            if (flutterResult.error != null) {
                Log.e(LOG_TAG, flutterResult.error);
            } else if (flutterResult.result != null) {
                Map<String, Object> res = (Map<String, Object>) flutterResult.result;
                WebResourceResponse response = null;
                try {
                    response = webView.contentBlockerHandler.checkUrl(webView, url, res.get("contentType").toString());
                } catch (Exception e) {
                    e.printStackTrace();
                }
                if (response != null)
                    return response;
                byte[] data = (byte[]) res.get("data");
                return new WebResourceResponse(res.get("contentType").toString(), res.get("contentEncoding").toString(), new ByteArrayInputStream(data));
            }
        }

        WebResourceResponse response = null;
        if (webView.contentBlockerHandler.getRuleList().size() > 0) {
            try {
                response = webView.contentBlockerHandler.checkUrl(webView, url);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        return response;
    }

    @Override
    public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
        final InAppWebView webView = (InAppWebView) view;

        String url = request.getUrl().toString();

        if (webView.options.useShouldInterceptRequest) {
            return onShouldInterceptRequest(request);
        }

        return shouldInterceptRequest(view, url);
    }

    public WebResourceResponse onShouldInterceptRequest(Object request) {
        String url = request instanceof String ? (String) request : null;
        String method = "GET";
        Map<String, String> headers = null;
        boolean isForMainFrame = true;

        if (request instanceof WebResourceRequest) {
            WebResourceRequest webResourceRequest = (WebResourceRequest) request;
            url = webResourceRequest.getUrl().toString();
            method = webResourceRequest.getMethod();
            headers = webResourceRequest.getRequestHeaders();
            isForMainFrame = webResourceRequest.isForMainFrame();
        }

        // Only for BitizenWallet :: Start
        if (url == null) return null;
        if (headers == null) {
            headers = new HashMap<>();
        }
        if (!headers.containsKey("Cookie")) {
            final String cookie = CookieManager.getInstance().getCookie(url);
            if (cookie != null && cookie.trim().length() > 0) {
                headers.put("Cookie", cookie);
            }
        }
        if (!isForMainFrame || !method.equals("GET")) return null;
        Util.WaitFlutterResult flutterResult;
        Request req = new Request.Builder()
                .method(method, null)
                .url(url)
                .headers(Headers.of(headers))
                .build();
        try {
            Response response = httpClient.newCall(req).execute();

            // https://developer.android.com/reference/android/webkit/WebResourceResponse 3xx is not supported
            if (response.isRedirect()) return null;
            if (response.body() == null) return null;

            String rawContentType = response.header("content-type") == null ? response.header("Content-Type") : response.header("content-type");
            String contentTypeString = "";
            String charsetString = StandardCharsets.UTF_8.toString();
            if (rawContentType != null) {
                String[] splitContentType = rawContentType.split(";");
                if (splitContentType.length > 0) {
                    contentTypeString = splitContentType[0];
                }
                if (splitContentType.length > 1) {
                    String[] splitContentEncoding = splitContentType[1].split("=");
                    if (splitContentEncoding.length > 1) {
                        charsetString = splitContentEncoding[1];
                    }
                }
            }
            if (contentTypeString.isEmpty()) {
                MediaType contentType = response.body().contentType();
                if (contentType != null) {
                    contentTypeString = contentType.type() + "/" + contentType.subtype();
                    Charset charset = contentType.charset();
                    if (charset != null) {
                        contentTypeString = charset.toString();
                    }
                }
            }

            Map<String, String> respHeaders = new HashMap<>();
            for (Map.Entry<String, List<String>> entry : response.headers().toMultimap().entrySet()) {
                respHeaders.put(entry.getKey(), String.join("; ", entry.getValue()));
            }
            final Map<String, Object> obj = new HashMap<>();
            obj.put("contentType", contentTypeString);
            obj.put("contentEncoding", charsetString);
            obj.put("data", response.body().bytes());
            if (!respHeaders.isEmpty()) {
                obj.put("headers", respHeaders);
            }
            obj.put("url", url);
            if (!response.isSuccessful()) {
                obj.put("statusCode", response.code());
                if (!response.message().equals("")) {
                    obj.put("reasonPhrase", response.message());
                } else {
                    obj.put("reasonPhrase", "HTTP " + response.code());
                }
            }
            flutterResult = Util.invokeMethodAndWait(channel, "shouldInterceptResponse", obj);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
        // Only for BitizenWallet :: End


//        final Map<String, Object> obj = new HashMap<>();
//        obj.put("url", url);
//        obj.put("method", method);
//        obj.put("headers", headers);
//        obj.put("isForMainFrame", isForMainFrame);
//        obj.put("hasGesture", hasGesture);
//        obj.put("isRedirect", isRedirect);

//        Util.WaitFlutterResult flutterResult;
//        try {
//            flutterResult = Util.invokeMethodAndWait(channel, "shouldInterceptRequest", obj);
//        } catch (InterruptedException e) {
//            e.printStackTrace();
//            return null;
//        }

        if (flutterResult.error != null) {
            Log.e(LOG_TAG, flutterResult.error);
        } else if (flutterResult.result != null) {
            Map<String, Object> res = (Map<String, Object>) flutterResult.result;
            String contentType = (String) res.get("contentType");
            String contentEncoding = (String) res.get("contentEncoding");
            byte[] data = (byte[]) res.get("data");

            Map<String, String> responseHeaders = null;
            if (res.get("headers") != null) {
                responseHeaders = (Map<String, String>) res.get("headers");
            }
            Integer statusCode = null;
            if (res.get("statusCode") != null) {
                statusCode = (Integer) res.get("statusCode");
            }
            String reasonPhrase = null;
            if (res.get("reasonPhrase") != null) {
                reasonPhrase = (String) res.get("reasonPhrase");
            }

            ByteArrayInputStream inputStream = (data != null) ? new ByteArrayInputStream(data) : null;

            if (responseHeaders == null && statusCode == null && reasonPhrase == null) {
                return new WebResourceResponse(contentType, contentEncoding, inputStream);
            } else {
                return new WebResourceResponse(contentType, contentEncoding, statusCode, reasonPhrase, responseHeaders, inputStream);
            }
        }

        return null;
    }

    @Override
    public void onFormResubmission(final WebView view, final Message dontResend, final Message resend) {
        Map<String, Object> obj = new HashMap<>();
        obj.put("url", view.getUrl());

        channel.invokeMethod("onFormResubmission", obj, new MethodChannel.Result() {

            @Override
            public void success(@Nullable Object response) {
                if (response != null) {
                    Map<String, Object> responseMap = (Map<String, Object>) response;
                    Integer action = (Integer) responseMap.get("action");
                    if (action != null) {
                        switch (action) {
                            case 0:
                                resend.sendToTarget();
                                return;
                            case 1:
                            default:
                                dontResend.sendToTarget();
                                return;
                        }
                    }
                }

                InAppWebViewClient.super.onFormResubmission(view, dontResend, resend);
            }

            @Override
            public void error(String errorCode, @Nullable String errorMessage, @Nullable Object errorDetails) {
                Log.e(LOG_TAG, "ERROR: " + errorCode + " " + errorMessage);
            }

            @Override
            public void notImplemented() {
                InAppWebViewClient.super.onFormResubmission(view, dontResend, resend);
            }
        });
    }

    @Override
    public void onPageCommitVisible(WebView view, String url) {
        super.onPageCommitVisible(view, url);
        Map<String, Object> obj = new HashMap<>();
        obj.put("url", url);
        channel.invokeMethod("onPageCommitVisible", obj);
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    @Override
    public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
        final InAppWebView webView = (InAppWebView) view;

        if (webView.options.useOnRenderProcessGone) {
            Boolean didCrash = detail.didCrash();
            Integer rendererPriorityAtExit = detail.rendererPriorityAtExit();

            Map<String, Object> obj = new HashMap<>();
            obj.put("didCrash", didCrash);
            obj.put("rendererPriorityAtExit", rendererPriorityAtExit);

            channel.invokeMethod("onRenderProcessGone", obj);

            return true;
        }

        return super.onRenderProcessGone(view, detail);
    }

    @Override
    public void onReceivedLoginRequest(WebView view, String realm, String account, String args) {
        Map<String, Object> obj = new HashMap<>();
        obj.put("realm", realm);
        obj.put("account", account);
        obj.put("args", args);

        channel.invokeMethod("onReceivedLoginRequest", obj);
    }

    @Override
    public void onUnhandledKeyEvent(WebView view, KeyEvent event) {

    }

    public void dispose() {
        if (inAppBrowserDelegate != null) {
            inAppBrowserDelegate = null;
        }
    }
}
