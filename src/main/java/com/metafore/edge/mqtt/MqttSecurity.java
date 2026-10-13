package com.metafore.edge.mqtt;

import org.apache.camel.component.paho.PahoComponent;
import org.apache.camel.component.paho.PahoConfiguration;
import org.bouncycastle.asn1.x500.RDN;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x500.style.BCStyle;
import org.bouncycastle.asn1.x500.style.IETFUtils;
import org.bouncycastle.cert.jcajce.JcaX509CertificateHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.cert.X509Certificate;
import java.io.IOException;
import java.util.Map;

/**
 * How the edge signs in to the MQTT broker (v2: never anonymously).
 *
 * <p><b>Certificate</b> — {@code METAFORE_MQTT_TLS_ENABLED=true} with {@code METAFORE_MQTT_TLS_CERT} and
 * {@code METAFORE_MQTT_TLS_KEY} (PEM files, normally mounted at {@code /etc/metafore/mqtt}) and
 * {@code METAFORE_MQTT_TLS_CA_CERT} (the CA that signed the broker's certificate; unset = the JVM's trust store). The
 * broker maps the certificate's common name to the MQTT username its topic rules key on, so the common name must be
 * {@code <TENANT_ID>/<CONTROLLER_ID>}, and the edge sends <b>no username and no password</b>. The pair is read again
 * at every connect ({@link ReloadingSslSocketFactory}), so a renewal needs no restart. The broker URL must be
 * {@code ssl://}.
 *
 * <p><b>Password</b> — without a certificate, {@code METAFORE_MQTT_USERNAME} (exactly
 * {@code <TENANT_ID>/<CONTROLLER_ID>}) and {@code METAFORE_MQTT_PASSWORD} are required. Over {@code ssl://} the same CA
 * variable applies.
 *
 * <p>{@code METAFORE_MQTT_TLS_INSECURE} (the old way to skip checking the broker) is refused: an edge that does not check
 * the broker has no reason to prove who it is. Any other failure to configure is an {@link IllegalStateException} with a
 * message that names the variable and never a secret: the edge does not start.
 */
public final class MqttSecurity {

    private static final Logger LOG = LoggerFactory.getLogger(MqttSecurity.class);

    private final String brokerUrl;
    private final String username;
    private final String password;
    private final ReloadingSslSocketFactory socketFactory;
    private final String certificateName;

    private MqttSecurity(String brokerUrl, String username, String password, ReloadingSslSocketFactory socketFactory,
                         String certificateName) {
        this.brokerUrl = brokerUrl;
        this.username = username;
        this.password = password;
        this.socketFactory = socketFactory;
        this.certificateName = certificateName;
    }

    public static MqttSecurity fromEnv(Map<String, String> env, String brokerUrl, String tenantId, String controllerId) {
        String want = tenantId + "/" + controllerId;
        boolean enabled = "true".equalsIgnoreCase(trim(env.get("METAFORE_MQTT_TLS_ENABLED")));
        String certPath = trim(env.get("METAFORE_MQTT_TLS_CERT"));
        String keyPath = trim(env.get("METAFORE_MQTT_TLS_KEY"));
        String caPath = trim(env.get("METAFORE_MQTT_TLS_CA_CERT"));
        String username = trim(env.get("METAFORE_MQTT_USERNAME"));
        String password = env.get("METAFORE_MQTT_PASSWORD") == null ? "" : env.get("METAFORE_MQTT_PASSWORD");
        boolean tlsUrl = brokerUrl != null && (brokerUrl.startsWith("ssl://") || brokerUrl.startsWith("tls://"));

        if ("true".equalsIgnoreCase(trim(env.get("METAFORE_MQTT_TLS_INSECURE")))) {
            throw new IllegalStateException("METAFORE_MQTT_TLS_INSECURE is refused: an edge that does not check the "
                + "broker's certificate has no reason to prove who it is. Set METAFORE_MQTT_TLS_CA_CERT instead.");
        }
        if (!certPath.isEmpty() || !keyPath.isEmpty()) {
            if (certPath.isEmpty() || keyPath.isEmpty()) {
                throw new IllegalStateException("METAFORE_MQTT_TLS_CERT and METAFORE_MQTT_TLS_KEY go together: set both or neither");
            }
            if (!enabled) {
                throw new IllegalStateException("METAFORE_MQTT_TLS_CERT is set but METAFORE_MQTT_TLS_ENABLED is not true");
            }
            if (!tlsUrl) {
                throw new IllegalStateException("a client certificate needs an ssl:// BROKER_URL, got " + scheme(brokerUrl));
            }
            Path cert = existing("METAFORE_MQTT_TLS_CERT", certPath);
            Path key = existing("METAFORE_MQTT_TLS_KEY", keyPath);
            Path ca = caPath.isEmpty() ? null : existing("METAFORE_MQTT_TLS_CA_CERT", caPath);
            String commonName = commonName(cert);
            if (!want.equals(commonName)) {
                throw new IllegalStateException("the client certificate's common name is \"" + commonName + "\", it must be \""
                    + want + "\" (the broker reads it as the username its topic rules key on)");
            }
            ReloadingSslSocketFactory factory = new ReloadingSslSocketFactory(ca, cert, key);
            LOG.info("MQTT sign-in: client certificate CN={} from {} (read again at every connect), no username", commonName, cert);
            return new MqttSecurity(brokerUrl, null, null, factory, commonName);
        }
        if (enabled && !tlsUrl) {
            throw new IllegalStateException("METAFORE_MQTT_TLS_ENABLED is true but BROKER_URL is " + scheme(brokerUrl) + ", not ssl://");
        }
        if (username.isEmpty() || password.isEmpty()) {
            throw new IllegalStateException("the edge never connects anonymously: set METAFORE_MQTT_TLS_CERT and "
                + "METAFORE_MQTT_TLS_KEY (certificate sign-in), or METAFORE_MQTT_USERNAME and METAFORE_MQTT_PASSWORD");
        }
        if (!want.equals(username)) {
            throw new IllegalStateException("METAFORE_MQTT_USERNAME must be \"" + want + "\": the broker lets an edge reach only "
                + "the topics its username names");
        }
        // a password sign-in over ssl:// checks the broker against METAFORE_MQTT_TLS_CA_CERT (or the JVM's trust store)
        ReloadingSslSocketFactory factory = tlsUrl && !caPath.isEmpty()
            ? new ReloadingSslSocketFactory(existing("METAFORE_MQTT_TLS_CA_CERT", caPath), null, null) : null;
        LOG.info("MQTT sign-in: username {} and password", username);
        return new MqttSecurity(brokerUrl, username, password, factory, null);
    }

    /** The Camel {@code paho} component every route's endpoint inherits its connection settings from. */
    public PahoComponent component() {
        PahoConfiguration config = new PahoConfiguration();
        config.setBrokerUrl(brokerUrl);
        config.setAutomaticReconnect(true);
        if (socketFactory != null) {
            config.setSocketFactory(socketFactory);
        }
        if (username != null) {
            config.setUserName(username);
            config.setPassword(password);
        }
        PahoComponent component = new PahoComponent();
        component.setConfiguration(config);
        return component;
    }

    /** True when the edge signs in with its certificate (no username is sent). */
    public boolean certificateMode() { return certificateName != null; }

    /** The common name of the certificate in use, or null in password mode. */
    public String certificateName() { return certificateName; }

    // ── helpers ──

    private static String commonName(Path certFile) {
        try {
            X509Certificate leaf = ReloadingSslSocketFactory.leaf(certFile);
            X500Name name = new JcaX509CertificateHolder(leaf).getSubject();
            RDN[] cns = name.getRDNs(BCStyle.CN);
            return cns.length == 0 ? "" : IETFUtils.valueToString(cns[0].getFirst().getValue());
        } catch (IOException | GeneralSecurityException e) {
            throw new IllegalStateException("METAFORE_MQTT_TLS_CERT is not a readable certificate (" + e.getClass().getSimpleName() + ")", e);
        }
    }

    private static Path existing(String variable, String path) {
        Path p = Path.of(path);
        if (!Files.isReadable(p)) {
            throw new IllegalStateException(variable + " " + path + " is not a readable file");
        }
        return p;
    }

    private static String trim(String value) { return value == null ? "" : value.trim(); }

    private static String scheme(String url) {
        int i = url == null ? -1 : url.indexOf("://");
        return i < 0 ? "no scheme" : url.substring(0, i + 3);
    }
}
