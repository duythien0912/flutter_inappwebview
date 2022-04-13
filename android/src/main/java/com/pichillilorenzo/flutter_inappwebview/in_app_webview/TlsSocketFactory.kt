package com.pichillilorenzo.flutter_inappwebview.in_app_webview

import android.util.Log
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.TlsVersion
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.net.UnknownHostException
import java.security.KeyStore
import javax.net.ssl.*

/**
 * Enables TLSv1.1 and TLSv1.2 when creating SSLSockets.
 * References:
 * https://stackoverflow.com/questions/28943660/how-to-enable-tls-1-2-support-in-an-android-application-running-on-android-4-1
 * https://blog.dev-area.net/2015/08/13/android-4-1-enable-tls-1-1-and-tls-1-2/
 * https://ankushg.com/posts/tls-1.2-on-android/
 * https://github.com/square/okhttp/issues/2372#issuecomment-244807676
 *
 * Android does not support TLS1.1 and TLS1.2 for API 19 or below.
 * @link https://developer.android.com/reference/javax/net/ssl/SSLSocket#protocols
 * @see SSLSocketFactory
 */
class TlsSocketFactory(private val delegate: SSLSocketFactory) : SSLSocketFactory() {

    companion object {
        val ALLOWED_TLS_VERSIONS =
            arrayOf(TlsVersion.TLS_1_1.javaName(), TlsVersion.TLS_1_2.javaName())

        fun createOkhttpClientBuilderWithTlsConfig(): OkHttpClient.Builder {
            return OkHttpClient.Builder().apply {
                val trustManager by lazy {
                    val trustManagerFactory =
                        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
                    trustManagerFactory.init(null as KeyStore?)
                    trustManagerFactory.trustManagers.first { it is X509TrustManager } as X509TrustManager
                }
                try {
                    val sc = SSLContext.getInstance(TlsVersion.TLS_1_1.javaName())
                    sc.init(null, null, null)
                    sslSocketFactory(TlsSocketFactory(sc.socketFactory), trustManager)

                    val cs = ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
                        .tlsVersions(*TlsSocketFactory.ALLOWED_TLS_VERSIONS)
                        .build()

                    val specs = ArrayList<ConnectionSpec>()
                    specs.add(cs)
                    specs.add(ConnectionSpec.COMPATIBLE_TLS)
                    specs.add(ConnectionSpec.CLEARTEXT)

                    connectionSpecs(specs)
                } catch (exc: Exception) {
                    Log.e("OkHttpTLSCompat", "Error while setting TLS 1.1 and 1.2", exc)
                }
            }
        }
    }

    override fun getDefaultCipherSuites(): Array<String> {
        return delegate.defaultCipherSuites
    }

    override fun getSupportedCipherSuites(): Array<String> {
        return delegate.supportedCipherSuites
    }

    @Throws(IOException::class)
    override fun createSocket(s: Socket, host: String, port: Int, autoClose: Boolean): Socket? {
        return patch(delegate.createSocket(s, host, port, autoClose))
    }

    @Throws(IOException::class, UnknownHostException::class)
    override fun createSocket(host: String, port: Int): Socket? {
        return patch(delegate.createSocket(host, port))
    }

    @Throws(IOException::class, UnknownHostException::class)
    override fun createSocket(
        host: String,
        port: Int,
        localHost: InetAddress,
        localPort: Int
    ): Socket? {
        return patch(delegate.createSocket(host, port, localHost, localPort))
    }

    @Throws(IOException::class)
    override fun createSocket(host: InetAddress, port: Int): Socket? {
        return patch(delegate.createSocket(host, port))
    }

    @Throws(IOException::class)
    override fun createSocket(
        address: InetAddress,
        port: Int,
        localAddress: InetAddress,
        localPort: Int
    ): Socket? {
        return patch(delegate.createSocket(address, port, localAddress, localPort))
    }

    private fun patch(s: Socket): Socket {
        if (s is SSLSocket) {
            s.enabledProtocols = ALLOWED_TLS_VERSIONS
        }
        return s
    }

}