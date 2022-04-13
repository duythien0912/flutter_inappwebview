package com.pichillilorenzo.flutter_inappwebview.in_app_webview;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.UnknownHostException;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

import okhttp3.TlsVersion;

public class TlsSocketFactory extends SSLSocketFactory {

    final private SSLSocketFactory delegate;
    final static String[] ALLOWED_TLS_VERSIONS = new String[]{TlsVersion.TLS_1_1.javaName(), TlsVersion.TLS_1_2.javaName()};

    TlsSocketFactory(SSLSocketFactory _delegate) {
        this.delegate = _delegate;
    }

    @Override
    public String[] getDefaultCipherSuites() {
        return this.delegate.getDefaultCipherSuites();
    }

    @Override
    public String[] getSupportedCipherSuites() {
        return this.delegate.getSupportedCipherSuites();
    }

    @Override
    public Socket createSocket(Socket socket, String s, int i, boolean b) throws IOException {
        return this.patch(this.delegate.createSocket(socket, s, i, b));
    }

    @Override
    public Socket createSocket(String s, int i) throws IOException, UnknownHostException {
        return this.patch(this.delegate.createSocket(s, i));
    }

    @Override
    public Socket createSocket(String s, int i, InetAddress inetAddress, int i1) throws IOException, UnknownHostException {
        return this.patch(this.delegate.createSocket(s, i, inetAddress, i1));
    }

    @Override
    public Socket createSocket(InetAddress inetAddress, int i) throws IOException {
        return this.patch(this.delegate.createSocket(inetAddress, i));
    }

    @Override
    public Socket createSocket(InetAddress inetAddress, int i, InetAddress inetAddress1, int i1) throws IOException {
        return this.patch(this.delegate.createSocket(inetAddress, i, inetAddress1, i1));
    }

    private Socket patch(Socket s) {
        if (s instanceof SSLSocket) {
            ((SSLSocket) s).setEnabledProtocols(ALLOWED_TLS_VERSIONS);
        }
        return s;
    }

}