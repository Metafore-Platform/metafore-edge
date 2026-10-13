package com.metafore.edge.mqtt;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/** The factory that signs the edge in to the broker's certificate listener, against a real TLS server that requires a client certificate. */
class ReloadingSslSocketFactoryTest {

    @TempDir Path dir;
    TestPki pki;
    Path ca;
    SSLServerSocket server;
    final List<X509Certificate> seen = new CopyOnWriteArrayList<>();

    @BeforeEach
    void startBroker() throws Exception {
        pki = new TestPki("test-mqtt-ca");
        ca = pki.writeCa(dir, "ca.crt");
        KeyPair serverKey = TestPki.ec();
        KeyStore keys = KeyStore.getInstance("PKCS12");
        keys.load(null, null);
        keys.setKeyEntry("server", serverKey.getPrivate(), new char[0],
            new java.security.cert.Certificate[] {pki.leaf("emqx", serverKey, "localhost")});
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keys, new char[0]);
        KeyStore trust = KeyStore.getInstance("PKCS12");
        trust.load(null, null);
        trust.setCertificateEntry("ca", pki.caCert);
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trust);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
        server = (SSLServerSocket) context.getServerSocketFactory().createServerSocket(0, 5, InetAddress.getLoopbackAddress());
        server.setNeedClientAuth(true);
        Thread t = new Thread(() -> {
            while (!server.isClosed()) {
                try (SSLSocket s = (SSLSocket) server.accept()) {
                    s.startHandshake();
                    seen.add((X509Certificate) s.getSession().getPeerCertificates()[0]);
                    s.getOutputStream().write(1);
                } catch (Exception ignored) {
                    // a client the broker refuses (wrong CA, no certificate): nothing to record
                }
            }
        }, "test-broker");
        t.setDaemon(true);
        t.start();
    }

    @AfterEach
    void stopBroker() throws Exception {
        server.close();
    }

    private void connect(ReloadingSslSocketFactory factory) throws Exception {
        try (SSLSocket s = (SSLSocket) factory.createSocket()) {
            s.connect(new java.net.InetSocketAddress(InetAddress.getLoopbackAddress(), server.getLocalPort()), 3000);
            s.startHandshake();
            assertEquals(1, s.getInputStream().read());
            assertTrue(List.of("TLSv1.3", "TLSv1.2").containsAll(List.of(s.getEnabledProtocols())), "only TLS 1.3 and 1.2");
        }
    }

    private static String cn(X509Certificate c) {
        return c.getSubjectX500Principal().getName();
    }

    @Test
    void theEdgePresentsItsCertificateToTheBroker() throws Exception {
        Path[] pair = pki.writePair(dir, "edge", "acme/acme-managed", TestPki.ec(), false);
        connect(new ReloadingSslSocketFactory(ca, pair[0], pair[1]));
        assertEquals(1, seen.size());
        assertEquals("CN=acme/acme-managed", cn(seen.get(0)));
    }

    @Test
    void aRenewedPairOnDiskIsUsedAtTheNextConnectWithoutANewFactory() throws Exception {
        Path[] pair = pki.writePair(dir, "edge", "acme/acme-managed", TestPki.ec(), false);
        ReloadingSslSocketFactory factory = new ReloadingSslSocketFactory(ca, pair[0], pair[1]);
        connect(factory);
        KeyPair renewed = TestPki.ec();
        TestPki.write(pair[0], TestPki.pem(pki.leaf("acme/acme-managed", renewed)));    // cert-manager rewrites the Secret
        TestPki.write(pair[1], TestPki.pkcs8(renewed));
        connect(factory);
        assertEquals(2, seen.size());
        assertNotEquals(seen.get(0).getSerialNumber(), seen.get(1).getSerialNumber(), "the second connect used the renewed certificate");
    }

    @Test
    void aPairCaughtMidRotationKeepsTheLastOneThatLoaded() throws Exception {
        Path[] pair = pki.writePair(dir, "edge", "acme/acme-managed", TestPki.ec(), false);
        ReloadingSslSocketFactory factory = new ReloadingSslSocketFactory(ca, pair[0], pair[1]);
        connect(factory);
        TestPki.write(pair[0], "-----BEGIN CERTIFICATE-----\ntruncat");
        Files.delete(pair[1]);
        connect(factory);                                   // does not throw: the last good pair signs in
        assertEquals(seen.get(0).getSerialNumber(), seen.get(1).getSerialNumber());
    }

    @Test
    void everyPemFormOfTheKeyLoads() throws Exception {
        KeyPair ec = TestPki.ec();
        for (boolean sec1 : new boolean[] {false, true}) {
            Path[] pair = pki.writePair(dir, "k" + sec1, "acme/acme-managed", ec, sec1);
            connect(new ReloadingSslSocketFactory(ca, pair[0], pair[1]));
        }
        java.security.KeyPairGenerator rsaGen = java.security.KeyPairGenerator.getInstance("RSA");
        rsaGen.initialize(2048);
        KeyPair rsa = rsaGen.generateKeyPair();
        Path cert = dir.resolve("rsa.crt");
        Path key = dir.resolve("rsa.key");
        TestPki.write(cert, TestPki.pem(new TestPkiRsa(pki).leaf("acme/acme-managed", rsa)));
        TestPki.write(key, TestPki.pem(rsa));               // PKCS#1: -----BEGIN RSA PRIVATE KEY-----
        assertTrue(Files.readString(key).contains("BEGIN RSA PRIVATE KEY"));
        connect(new ReloadingSslSocketFactory(ca, cert, key));
        assertEquals(3, seen.size());
    }

    @Test
    void aBrokerNotSignedByTheConfiguredCaIsRefused() throws Exception {
        TestPki other = new TestPki("another-ca");
        Path otherCa = other.writeCa(dir, "other-ca.crt");
        Path[] pair = pki.writePair(dir, "edge", "acme/acme-managed", TestPki.ec(), false);
        ReloadingSslSocketFactory factory = new ReloadingSslSocketFactory(otherCa, pair[0], pair[1]);
        assertThrows(SSLHandshakeException.class, () -> connect(factory));
    }

    @Test
    void aClientCertificateTheBrokerDoesNotTrustIsRefusedByTheBroker() throws Exception {
        TestPki other = new TestPki("another-ca");
        Path[] pair = other.writePair(dir, "stranger", "acme/acme-managed", TestPki.ec(), false);
        ReloadingSslSocketFactory factory = new ReloadingSslSocketFactory(ca, pair[0], pair[1]);
        assertThrows(java.io.IOException.class, () -> connect(factory));
        assertEquals(0, seen.size());
    }

    @Test
    void anUnusablePairStopsTheEdgeAtStartWithoutLeakingTheFileContents() throws Exception {
        Path garbage = dir.resolve("garbage.key");
        TestPki.write(garbage, "s3cret-key-material");
        Path[] pair = pki.writePair(dir, "edge", "acme/acme-managed", TestPki.ec(), false);
        IllegalStateException e = assertThrows(IllegalStateException.class,
            () -> new ReloadingSslSocketFactory(ca, pair[0], garbage));
        assertFalse(String.valueOf(e.getMessage()).contains("s3cret-key-material"));
        assertThrows(IllegalStateException.class, () -> new ReloadingSslSocketFactory(ca, dir.resolve("missing.crt"), pair[1]));
    }
}
