package me.niteshh.redcake.security;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Named users with roles, loaded from {@code --acl-file}.
 *
 * <p>File format (one user per line, {@code #} starts a comment):
 * <pre>
 * user alice admin     5e884898da28047151d0e56f8dc6292773603d0d6aabbdd62a11ef721d1542d8
 * user bob   readonly  &lt;sha256-hex-of-password&gt;
 * </pre>
 * Only SHA-256 digests are stored; create one with
 * {@code echo -n 'secret' | java -jar redcake.jar --hash-password}.
 * Digests are compared in constant time. The implicit {@code default} user
 * (the API key, admin role) is handled by {@code RedCakeAuthConfig}.
 */
@Component
public class AclRegistry {

    private record User(Role role, byte[] passwordDigest) {
    }

    private final Map<String, User> users;

    /** Spring constructor; the file path comes from {@link me.niteshh.redcake.config.AclConfig}. */
    @Autowired
    public AclRegistry(me.niteshh.redcake.config.AclConfig config) {
        this(config.getFile() == null ? Map.<String, User>of() : load(config.getFile()));
    }

    private AclRegistry(Map<String, User> users) {
        this.users = users;
    }

    /** @return a registry without users (ACL disabled) */
    public static AclRegistry empty() {
        return new AclRegistry(Map.of());
    }

    /** @return {@code true} if at least one named user exists, which makes authentication mandatory */
    public boolean isEnabled() {
        return !users.isEmpty();
    }

    /**
     * @param password the password as received on the wire (byte-string form)
     * @return the user's role, or {@code null} for unknown user / wrong password
     */
    public Role authenticate(String username, String password) {
        User user = users.get(username);
        // Always hash, so unknown users cost the same time as wrong passwords.
        byte[] candidate = sha256(password.getBytes(StandardCharsets.ISO_8859_1));
        if (user == null) {
            return null;
        }
        return MessageDigest.isEqual(user.passwordDigest(), candidate) ? user.role() : null;
    }

    /** @return {@code "name role"} lines for {@code ACL LIST} */
    public List<String> describe() {
        return users.entrySet().stream()
                .map(e -> e.getKey() + " " + e.getValue().role().name().toLowerCase())
                .toList();
    }

    /** @return the user names */
    public List<String> usernames() {
        return List.copyOf(users.keySet());
    }

    /** @return the lower-case hex SHA-256 of {@code passwordBytes} (for {@code --hash-password}) */
    public static String hash(byte[] passwordBytes) {
        return HexFormat.of().formatHex(sha256(passwordBytes));
    }

    private static Map<String, User> load(Path file) {
        Map<String, User> loaded = new LinkedHashMap<>();
        try {
            int lineNumber = 0;
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                lineNumber++;
                String text = line.strip();
                if (text.isEmpty() || text.startsWith("#")) {
                    continue;
                }
                String[] words = text.split("\\s+");
                if (words.length != 4 || !"user".equals(words[0])) {
                    throw new IllegalArgumentException(
                            file + ":" + lineNumber + ": expected 'user <name> <role> <sha256-hex>'");
                }
                if ("default".equals(words[1])) {
                    throw new IllegalArgumentException(
                            file + ":" + lineNumber + ": 'default' is reserved for the API key");
                }
                byte[] digest;
                try {
                    digest = HexFormat.of().parseHex(words[3]);
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException(file + ":" + lineNumber + ": password is not valid hex");
                }
                if (digest.length != 32) {
                    throw new IllegalArgumentException(
                            file + ":" + lineNumber + ": password must be a SHA-256 digest (64 hex chars)");
                }
                Role role;
                try {
                    role = Role.parse(words[2]);
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException(file + ":" + lineNumber + ": " + e.getMessage());
                }
                if (loaded.put(words[1], new User(role, digest)) != null) {
                    throw new IllegalArgumentException(file + ":" + lineNumber + ": duplicate user " + words[1]);
                }
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot read --acl-file " + file + ": " + e.getMessage(), e);
        }
        return java.util.Collections.unmodifiableMap(loaded); // keeps file order (ACL LIST)
    }

    private static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JVM spec", e);
        }
    }
}
