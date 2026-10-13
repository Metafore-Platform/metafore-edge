package com.metafore.edge.mqtt;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Date;

/** An RSA leaf signed by the same CA, for the PKCS#1 key form. */
final class TestPkiRsa {
    private final TestPki pki;

    TestPkiRsa(TestPki pki) { this.pki = pki; }

    X509Certificate leaf(String cn, KeyPair rsa) throws Exception {
        return new JcaX509CertificateConverter().getCertificate(new JcaX509v3CertificateBuilder(
            new X500Name(pki.caCert.getSubjectX500Principal().getName()), BigInteger.valueOf(System.nanoTime()),
            Date.from(Instant.now().minusSeconds(3600)), Date.from(Instant.now().plusSeconds(86_400)),
            new X500Name("CN=" + cn), rsa.getPublic())
            .build(new JcaContentSignerBuilder("SHA256withECDSA").build(pki.caKey.getPrivate())));
    }
}
