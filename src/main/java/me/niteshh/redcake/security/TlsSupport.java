package me.niteshh.redcake.security;

import me.niteshh.redcake.config.TlsConfig;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;

/** Builds {@link SSLContext}s from the paths and passwords of a {@link TlsConfig}. */
public final class TlsSupport {

    private TlsSupport() {
    }

    /**
     * @return a context with the configured server key (if any) and trust
     * material (the truststore if given, otherwise the JVM defaults)
     * @throws IllegalStateException if a store cannot be read or is invalid
     */
    public static SSLContext createContext(TlsConfig config) {
        try {
            KeyManagerFactory keyManagers = null;
            if (config.getKeystore() != null) {
                keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                keyManagers.init(
                        load(config.getKeystore(), config.getKeystorePassword()),
                        config.getKeystorePassword());
            }

            TrustManagerFactory trustManagers = TrustManagerFactory.getInstance(
                    TrustManagerFactory.getDefaultAlgorithm());
            trustManagers.init(config.getTruststore() == null
                    ? null // JVM default trust store
                    : load(config.getTruststore(), config.getTruststorePassword()));

            SSLContext context = SSLContext.getInstance("TLS");
            context.init(
                    keyManagers == null ? null : keyManagers.getKeyManagers(),
                    trustManagers.getTrustManagers(),
                    null);
            return context;
        } catch (GeneralSecurityException | IOException e) {
            throw new IllegalStateException("Cannot initialise TLS: " + e.getMessage(), e);
        }
    }

    private static KeyStore load(Path path, char[] password) throws GeneralSecurityException, IOException {
        KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType()); // PKCS12 on modern JDKs
        try (InputStream in = Files.newInputStream(path)) {
            store.load(in, password);
        }
        return store;
    }
}
