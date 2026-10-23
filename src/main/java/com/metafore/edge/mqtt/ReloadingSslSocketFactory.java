package com.metafore.edge.mqtt;

import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * The edge's TLS client socket factory for the broker's certificate listener.
 *
 * <p>The client certificate and key are read from their PEM files <b>every time a socket is created</b> — paho creates
 * one for the first connect and for each reconnect — so a certificate the platform has renewed on disk (cert-manager
 * rewrites the mounted Secret before the old one expires, with no pod restart) is used from the next handshake on. A
 * pair that cannot be read just then (a rotation caught mid-write) keeps the last pair that loaded: the broker refuses
 * it only once it has expired. The first read happens in the constructor and fails loudly: an edge with no usable
 * certificate does not start. Nothing read from the files is ever logged or put in an exception message.
 *
 * <p>The CA that signed the broker's certificate comes from {@code caCert} (every certificate in the file is trusted);
 * unset, the JVM's default trust store is used. With no certificate and key (both null) it only checks the broker (a
 * password sign-in over ssl:// with a private CA). Only TLS 1.3 and 1.2 are enabled.
 */
public final class ReloadingSslSocketFactory extends SSLSocketFactory {

    private static final Logger LOG = LoggerFactory.getLogger(ReloadingSslSocketFactory.class);
    private static final String[] PROTOCOLS = {"TLSv1.3", "TLSv1.2"};

    private final Path caCert;
    private final Path cert;
    private final Path key;
    private volatile SSLSocketFactory current;

    public ReloadingSslSocketFactory(Path caCert, Path cert, Path key) {
        this.caCert = caCert;
        this.cert = cert;
        this.key = key;
        try {
            this.current = build().getSocketFactory();
        } catch (IOException | GeneralSecurityException e) {
            throw new IllegalStateException("MQTT client certificate unusable: " + e.getClass().getSimpleName()
                + " reading " + cert + " / " + key + (caCert == null ? "" : " / " + caCert), e);
        }
    }

    /** The leaf certificate of the pair as it is on disk now (for the startup identity check). */
    public static X509Certificate leaf(Path certFile) throws IOException, GeneralSecurityException {
        List<X509Certificate> chain = certificates(certFile);
        if (chain.isEmpty()) {
            throw new GeneralSecurityException("no certificate in " + certFile);
        }
        return chain.get(0);
    }

    // ── SSLSocketFactory ────────────────────────────────────────────────────

    private SSLSocketFactory fresh() {
        try {
            SSLSocketFactory next = build().getSocketFactory();
            current = next;
            return next;
        } catch (IOException | GeneralSecurityException e) {
            LOG.warn("MQTT client certificate could not be re-read ({}); keeping the last pair that loaded",
                e.getClass().getSimpleName());
            return current;
        }
    }

    private static Socket secure(Socket socket) {
        if (socket instanceof SSLSocket ssl) {
            ssl.setEnabledProtocols(PROTOCOLS);
        }
        return socket;
    }

    @Override public Socket createSocket() throws IOException { return secure(fresh().createSocket()); }

    @Override public Socket createSocket(Socket s, String host, int port, boolean autoClose) throws IOException {
        return secure(fresh().createSocket(s, host, port, autoClose));
    }

    @Override public Socket createSocket(String host, int port) throws IOException {
        return secure(fresh().createSocket(host, port));
    }

    @Override public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
        return secure(fresh().createSocket(host, port, localHost, localPort));
    }

    @Override public Socket createSocket(InetAddress host, int port) throws IOException {
        return secure(fresh().createSocket(host, port));
    }

    @Override public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort)
        throws IOException {
        return secure(fresh().createSocket(address, port, localAddress, localPort));
    }

    @Override public String[] getDefaultCipherSuites() { return current.getDefaultCipherSuites(); }

    @Override public String[] getSupportedCipherSuites() { return current.getSupportedCipherSuites(); }

    // ── reading the files ───────────────────────────────────────────────────

    private SSLContext build() throws IOException, GeneralSecurityException {
        KeyManager[] keyManagers = null;
        if (cert != null) {
            List<X509Certificate> chain = certificates(cert);
            if (chain.isEmpty()) {
                throw new GeneralSecurityException("no certificate in the client certificate file");
            }
            PrivateKey privateKey = privateKey(key);
            KeyStore keys = KeyStore.getInstance("PKCS12");
            keys.load(null, null);
            keys.setKeyEntry("client", privateKey, new char[0], chain.toArray(new Certificate[0]));
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(keys, new char[0]);
            keyManagers = kmf.getKeyManagers();
        }

        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        if (caCert == null) {
            tmf.init((KeyStore) null);
        } else {
            List<X509Certificate> cas = certificates(caCert);
            if (cas.isEmpty()) {
                throw new GeneralSecurityException("no certificate in the CA file");
            }
            KeyStore trust = KeyStore.getInstance("PKCS12");
            trust.load(null, null);
            for (int i = 0; i < cas.size(); i++) {
                trust.setCertificateEntry("ca-" + i, cas.get(i));
            }
            tmf.init(trust);
        }
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keyManagers, tmf.getTrustManagers(), null);
        return context;
    }

    private static List<X509Certificate> certificates(Path file) throws IOException, GeneralSecurityException {
        try (InputStream in = Files.newInputStream(file)) {
            Collection<? extends Certificate> all = CertificateFactory.getInstance("X.509").generateCertificates(in);
            List<X509Certificate> out = new ArrayList<>();
            for (Certificate c : all) {
                out.add((X509Certificate) c);
            }
            return out;
        }
    }

    /** A PEM private key in PKCS#8, SEC1 ({@code EC PRIVATE KEY}) or PKCS#1 ({@code RSA PRIVATE KEY}) form. */
    static PrivateKey privateKey(Path file) throws IOException, GeneralSecurityException {
        JcaPEMKeyConverter converter = new JcaPEMKeyConverter();
        try (Reader reader = Files.newBufferedReader(file); PEMParser parser = new PEMParser(reader)) {
            Object parsed = parser.readObject();
            if (parsed instanceof PEMKeyPair pair) {
                // the private half only: a SEC1 / PKCS#1 file need not carry the public key (openssl can leave it out)
                return converter.getPrivateKey(pair.getPrivateKeyInfo());
            }
            if (parsed instanceof PrivateKeyInfo info) {
                return converter.getPrivateKey(info);
            }
            throw new GeneralSecurityException("the client key file holds no unencrypted PEM private key");
        }
    }
}
