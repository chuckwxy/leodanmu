package com.github.catvod.spider;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

public class TLSSocketFactory extends SSLSocketFactory {

    private final SSLSocketFactory delegate;

    public TLSSocketFactory() throws KeyManagementException, NoSuchAlgorithmException {
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, null, null);
        delegate = context.getSocketFactory();
    }

    @Override
    public String[] getDefaultCipherSuites() {
        return delegate.getDefaultCipherSuites();
    }

    @Override
    public String[] getSupportedCipherSuites() {
        return delegate.getSupportedCipherSuites();
    }

    @Override
    public Socket createSocket(Socket s, String host, int port, boolean autoClose) throws IOException {
        return enableTLSOnSocket(delegate.createSocket(s, host, port, autoClose));
    }

    @Override
    public Socket createSocket(String host, int port) throws IOException, UnknownHostException {
        return enableTLSOnSocket(delegate.createSocket(host, port));
    }

    @Override
    public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException, UnknownHostException {
        return enableTLSOnSocket(delegate.createSocket(host, port, localHost, localPort));
    }

    @Override
    public Socket createSocket(InetAddress host, int port) throws IOException {
        return enableTLSOnSocket(delegate.createSocket(host, port));
    }

    @Override
    public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort) throws IOException {
        return enableTLSOnSocket(delegate.createSocket(address, port, localAddress, localPort));
    }

    private Socket enableTLSOnSocket(Socket socket) {
        if (socket instanceof SSLSocket) {
            SSLSocket sslSocket = (SSLSocket) socket;
            try {
                String[] preferred = new String[]{"TLSv1.3", "TLSv1.2", "TLSv1.1", "TLSv1", "SSLv3"};
                List<String> supported = Arrays.asList(sslSocket.getSupportedProtocols());
                List<String> enabled = new ArrayList<>();
                for (String protocol : preferred) {
                    if (supported.contains(protocol)) {
                        enabled.add(protocol);
                    }
                }
                if (!enabled.isEmpty()) {
                    sslSocket.setEnabledProtocols(enabled.toArray(new String[0]));
                    Leodanmu.log("TLS启用协议=" + enabled);
                } else {
                    Leodanmu.log("TLS未找到首选协议，沿用系统默认协议=" + Arrays.asList(sslSocket.getEnabledProtocols()));
                }
            } catch (Exception e) {
                Leodanmu.log("TLS协议设置失败，沿用系统默认: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }
        return socket;
    }
}