package com.metafore.edge.mqtt;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import java.io.IOException;
import java.io.StringWriter;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.concurrent.atomic.AtomicLong;

/** Throw-away certificates for the tests: a CA, leaf certificates it signs, and the PEM forms the platform writes. */
final class TestPki {

    private static final AtomicLong SERIAL = new AtomicLong(1000);

    final KeyPair caKey;
    final X509Certificate caCert;

    TestPki(String caName) throws Exception {
        caKey = ec();
        caCert = sign(new X500Name("CN=" + caName), caKey, caKey.getPublic(), new X500Name("CN=" + caName), true, null);
    }

    static KeyPair ec() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
        gen.initialize(new ECGenParameterSpec("secp256r1"));
        return gen.generateKeyPair();
    }

    /** A leaf certificate for {@code cn}, optionally with DNS names (a server). */
    X509Certificate leaf(String cn, KeyPair key, String... dnsNames) throws Exception {
        GeneralNames sans = null;
        if (dnsNames.length > 0) {
            GeneralName[] names = new GeneralName[dnsNames.length];
            for (int i = 0; i < dnsNames.length; i++) {
                names[i] = new GeneralName(GeneralName.dNSName, dnsNames[i]);
            }
            sans = new GeneralNames(names);
        }
        return sign(new X500Name("CN=" + cn), caKey, key.getPublic(), new X500Name(caCert.getSubjectX500Principal().getName()),
            false, sans);
    }

    private static X509Certificate sign(X500Name subject, KeyPair signer, java.security.PublicKey subjectKey, X500Name issuer,
                                        boolean ca, GeneralNames sans) throws Exception {
        Date from = Date.from(Instant.now().minusSeconds(3600));
        Date to = Date.from(Instant.now().plusSeconds(86_400));
        X509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
            issuer, BigInteger.valueOf(SERIAL.incrementAndGet()), from, to, subject, subjectKey);
        builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(ca));
        if (sans != null) {
            builder.addExtension(Extension.subjectAlternativeName, false, sans);
        }
        return new JcaX509CertificateConverter().getCertificate(
            builder.build(new JcaContentSignerBuilder("SHA256withECDSA").build(signer.getPrivate())));
    }

    // ── PEM ──

    static String pem(Object o) throws IOException {
        StringWriter out = new StringWriter();
        try (JcaPEMWriter w = new JcaPEMWriter(out)) {
            w.writeObject(o);
        }
        return out.toString();
    }

    /** PKCS#8: {@code -----BEGIN PRIVATE KEY-----} (what core writes for a provisioned edge). */
    static String pkcs8(KeyPair key) {
        String body = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(key.getPrivate().getEncoded());
        return "-----BEGIN PRIVATE KEY-----\n" + body + "\n-----END PRIVATE KEY-----\n";
    }

    /**
     * SEC1: {@code -----BEGIN EC PRIVATE KEY-----}, as Go's x509.MarshalECPrivateKey writes it for cert-manager and as
     * {@code openssl ecparam -genkey} does: the key, the named curve and the public point.
     */
    static String sec1(KeyPair key) throws IOException {
        java.security.interfaces.ECPrivateKey priv = (java.security.interfaces.ECPrivateKey) key.getPrivate();
        java.security.interfaces.ECPublicKey pub = (java.security.interfaces.ECPublicKey) key.getPublic();
        byte[] point = new byte[65];
        point[0] = 4;
        copy(pub.getW().getAffineX(), point, 1);
        copy(pub.getW().getAffineY(), point, 33);
        byte[] der = new org.bouncycastle.asn1.sec.ECPrivateKey(
            256, priv.getS(), new org.bouncycastle.asn1.DERBitString(point),
            org.bouncycastle.asn1.x9.X9ObjectIdentifiers.prime256v1).getEncoded();
        StringWriter out = new StringWriter();
        try (org.bouncycastle.util.io.pem.PemWriter w = new org.bouncycastle.util.io.pem.PemWriter(out)) {
            w.writeObject(new org.bouncycastle.util.io.pem.PemObject("EC PRIVATE KEY", der));
        }
        return out.toString();
    }

    private static void copy(BigInteger n, byte[] to, int offset) {
        byte[] raw = n.toByteArray();
        int len = Math.min(raw.length, 32);
        System.arraycopy(raw, raw.length - len, to, offset + 32 - len, len);
    }

    static void write(Path file, String text) throws IOException {
        Files.writeString(file, text);
    }

    /** Writes cert and key files and returns [cert, key]. */
    Path[] writePair(Path dir, String prefix, String cn, KeyPair key, boolean sec1Key) throws Exception {
        Path cert = dir.resolve(prefix + ".crt");
        Path k = dir.resolve(prefix + ".key");
        write(cert, pem(leaf(cn, key)));
        write(k, sec1Key ? sec1(key) : pkcs8(key));
        return new Path[] {cert, k};
    }

    Path writeCa(Path dir, String name) throws IOException {
        Path ca = dir.resolve(name);
        write(ca, pem(caCert));
        return ca;
    }
}
