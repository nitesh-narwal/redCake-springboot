package me.niteshh.redcake.config;

import lombok.Getter;

import java.nio.file.Path;

/** Location of the ACL users file ({@code --acl-file}); {@code null} means no named users. */
@Getter
public final class AclConfig {

    private final Path file;

    public AclConfig(Path file) {
        this.file = file;
    }

    /** @return a configuration without a users file */
    public static AclConfig none() {
        return new AclConfig(null);
    }
}
