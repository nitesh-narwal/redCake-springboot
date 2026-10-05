package me.niteshh.redcake.config;

import lombok.Getter;

import java.nio.file.Path;

/**
 * TLS settings (all optional).
 *
 * <ul>
 *   <li>{@code keystore} (PKCS12/JKS with the server key and certificate) turns
 *       on TLS for client connections and for replicas connecting to this node.</li>
 *   <li>{@code truststore} lists certificates to trust: the CAs whose client
 *       certificates are accepted (mutual TLS, with {@code clientAuth}), and the
 *       CAs a replica trusts when it connects to its primary over TLS.</li>
 *   <li>{@code replicationTls} makes this node, as a replica, use TLS towards its primary.</li>
 * </ul>
 * Passwords are read from files or environment variables by the command-line
 * parser so they never appear in the process list.
 */
@Getter
public final class TlsConfig {

    private final Path keystore;
    private final char[] keystorePassword;
    private final Path truststore;
    private final char[] truststorePassword;
    private final boolean clientAuth;
    private final boolean replicationTls;

    public TlsConfig(
            Path keystore, char[] keystorePassword,
            Path truststore, char[] truststorePassword,
            boolean clientAuth, boolean replicationTls
    ) {
        if (clientAuth && truststore == null) {
            throw new IllegalArgumentException("--tls-client-auth requires --tls-truststore");
        }
        this.keystore = keystore;
        this.keystorePassword = keystorePassword == null ? new char[0] : keystorePassword;
        this.truststore = truststore;
        this.truststorePassword = truststorePassword == null ? new char[0] : truststorePassword;
        this.clientAuth = clientAuth;
        this.replicationTls = replicationTls;
    }

    /** @return a configuration with TLS off */
    public static TlsConfig disabled() {
        return new TlsConfig(null, null, null, null, false, false);
    }

    /** @return {@code true} if this node accepts TLS connections */
    public boolean isServerTlsEnabled() {
        return keystore != null;
    }
}
