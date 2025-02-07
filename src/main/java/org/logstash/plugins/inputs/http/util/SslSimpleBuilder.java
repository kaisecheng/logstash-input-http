package org.logstash.plugins.inputs.http.util;

import io.netty.handler.ssl.ClientAuth;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.crypto.Cipher;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLServerSocketFactory;

public class SslSimpleBuilder implements SslBuilder {

    public enum SslClientVerifyMode {
        NONE(ClientAuth.NONE),
        OPTIONAL(ClientAuth.OPTIONAL),
        REQUIRED(ClientAuth.REQUIRE);

        private final ClientAuth clientAuth;

        SslClientVerifyMode(ClientAuth clientAuth) {
            this.clientAuth = clientAuth;
        }

        public ClientAuth toClientAuth() {
            return clientAuth;
        }
    }

    private final static Logger logger = LogManager.getLogger(SslSimpleBuilder.class);

    public static final Set<String> SUPPORTED_CIPHERS = new HashSet<>(Arrays.asList(
        ((SSLServerSocketFactory) SSLServerSocketFactory.getDefault()).getSupportedCipherSuites()
    ));

    /*
    Ciphers Compatibility List from https://wiki.mozilla.org/Security/Server_Side_TLS
    */
    private final static String[] DEFAULT_CIPHERS;
    static {
        String[] defaultCipherCandidates = new String[] {
            // Modern compatibility
            "TLS_AES_256_GCM_SHA384", // TLS 1.3
            "TLS_AES_128_GCM_SHA256", // TLS 1.3
            "TLS_CHACHA20_POLY1305_SHA256", // TLS 1.3 (since Java 11.0.14)
            // Intermediate compatibility
            "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384",
            "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
            "TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256", // (since Java 11.0.14)
            "TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256", // (since Java 11.0.14)
            "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256",
            "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
            // Backward compatibility
            "TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA384",
            "TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA384",
            "TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA256",
            "TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA256"
        };
        DEFAULT_CIPHERS = Arrays.stream(defaultCipherCandidates).filter(SUPPORTED_CIPHERS::contains).toArray(String[]::new);
    }

    private final static String[] DEFAULT_CIPHERS_LIMITED = new String[] {
            "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384",
            "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
            "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256",
            "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
            "TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA384",
            "TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA384",
            "TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA256",
            "TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA256"
    };

    private String[] protocols = new String[] { "TLSv1.2", "TLSv1.3" };
    private String[] ciphers = getDefaultCiphers();
    private final File sslKeyFile;
    private final File sslCertificateFile;
    private String[] certificateAuthorities;
    private final String passphrase;
    private SslClientVerifyMode verifyMode = SslClientVerifyMode.NONE;

    public SslSimpleBuilder(String sslCertificateFilePath, String sslKeyFilePath, String pass) {
        sslCertificateFile = new File(sslCertificateFilePath);
        if (!sslCertificateFile.canRead()) {
            throw new IllegalArgumentException(
                    String.format("Certificate file cannot be read. Please confirm the user running Logstash has permissions to read: %s", sslCertificateFilePath));
        }

        sslKeyFile = new File(sslKeyFilePath);
        if (!sslKeyFile.canRead()) {
            throw new IllegalArgumentException(
                    String.format("Private key file cannot be read. Please confirm the user running Logstash has permissions to read: %s", sslKeyFilePath));
        }

        passphrase = pass;
    }

    public SslSimpleBuilder setProtocols(String[] protocols) {
        this.protocols = protocols;
        return this;
    }

    public SslSimpleBuilder setCipherSuites(String[] ciphersSuite) throws IllegalArgumentException {
        for (String cipher : ciphersSuite) {
            if (SUPPORTED_CIPHERS.contains(cipher)) {
                logger.debug("{} cipher is supported", cipher);
            } else {
                if (!isUnlimitedJCEAvailable()) {
                    logger.warn("JCE Unlimited Strength Jurisdiction Policy not installed");
                }
                throw new IllegalArgumentException("Cipher `" + cipher + "` is not available");
            }
        }

        ciphers = ciphersSuite;
        return this;
    }

    public SslSimpleBuilder setClientAuthentication(SslClientVerifyMode verifyMode, String[] certificateAuthorities) {
        if (isClientAuthenticationEnabled(verifyMode) && (certificateAuthorities == null || certificateAuthorities.length < 1)) {
            throw new IllegalArgumentException("Certificate authorities are required to enable client authentication");
        }

        this.verifyMode = verifyMode;
        this.certificateAuthorities = certificateAuthorities;
        return this;
    }

    private boolean isClientAuthenticationEnabled(final SslClientVerifyMode mode) {
        return mode == SslClientVerifyMode.OPTIONAL || mode == SslClientVerifyMode.REQUIRED;
    }

    public boolean isClientAuthenticationRequired() {
        return verifyMode == SslClientVerifyMode.REQUIRED;
    }

    public SslContext build() throws Exception {
        if (logger.isDebugEnabled()) {
            logger.debug("Available ciphers: {}", SUPPORTED_CIPHERS);
            logger.debug("Ciphers: {}", Arrays.toString(ciphers));
        }

        SslContextBuilder builder = SslContextBuilder
                .forServer(sslCertificateFile, sslKeyFile, passphrase)
                .ciphers(Arrays.asList(ciphers))
                .protocols(protocols);

        if (isClientAuthenticationEnabled(verifyMode)) {
            if (logger.isDebugEnabled()) {
                logger.debug("Certificate Authorities: {}", Arrays.toString(certificateAuthorities));
            }

            builder.clientAuth(verifyMode.toClientAuth())
                    .trustManager(loadCertificateCollection(certificateAuthorities));
        }

        return doBuild(builder);
    }

    // NOTE: copy-pasta from input-beats
    static SslContext doBuild(final SslContextBuilder builder) throws Exception {
        try {
            return builder.build();
        } catch (SSLException e) {
            logger.debug("Failed to initialize SSL", e);
            // unwrap generic wrapped exception from Netty's JdkSsl{Client|Server}Context
            if ("failed to initialize the server-side SSL context".equals(e.getMessage()) ||
                "failed to initialize the client-side SSL context".equals(e.getMessage())) {
                // Netty catches Exception and simply wraps: throw new SSLException("...", e);
                if (e.getCause() instanceof Exception) throw (Exception) e.getCause();
            }
            throw e;
        } catch (Exception e) {
            logger.debug("Failed to initialize SSL", e);
            throw e;
        }
    }

    private X509Certificate[] loadCertificateCollection(String[] certificates) throws IOException, CertificateException {
        logger.debug("Load certificates collection");
        CertificateFactory certificateFactory = CertificateFactory.getInstance("X.509");

        final List<X509Certificate> collections = new ArrayList<>();

        for (String certificate : certificates) {
            logger.debug("Loading certificates from file {}", certificate);

            try (InputStream in = new FileInputStream(certificate)) {
                List<X509Certificate> certificatesChains = (List<X509Certificate>) certificateFactory.generateCertificates(in);
                collections.addAll(certificatesChains);
            }
        }
        return collections.toArray(new X509Certificate[collections.size()]);
    }

    public static String[] getDefaultCiphers() {
        if (isUnlimitedJCEAvailable()){
            return DEFAULT_CIPHERS;
        } else {
            logger.warn("JCE Unlimited Strength Jurisdiction Policy not installed - max key length is 128 bits");
            return DEFAULT_CIPHERS_LIMITED;
        }
    }


    public static boolean isUnlimitedJCEAvailable(){
        try {
            return (Cipher.getMaxAllowedKeyLength("AES") > 128);
        } catch (NoSuchAlgorithmException e) {
            logger.warn("AES not available", e);
            return false;
        }
    }

    String[] getProtocols() {
        return protocols != null ? protocols.clone() : null;
    }

    String[] getCertificateAuthorities() {
        return certificateAuthorities != null ? certificateAuthorities.clone() : null;
    }

    String[] getCiphers() {
        return ciphers != null ? ciphers.clone() : null;
    }

    SslClientVerifyMode getVerifyMode() {
        return verifyMode;
    }
}
