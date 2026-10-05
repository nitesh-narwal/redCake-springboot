package me.niteshh.redcake.security;

/**
 * Permission level of an authenticated user.
 *
 * <ul>
 *   <li>{@code ADMIN} - everything, including FLUSHALL, CONFIG, replication control.</li>
 *   <li>{@code READWRITE} - all data commands, no administration.</li>
 *   <li>{@code READONLY} - only commands that do not modify data.</li>
 * </ul>
 */
public enum Role {
    ADMIN,
    READWRITE,
    READONLY;

    /**
     * @param admin whether the command is administrative
     * @param write whether the command modifies data
     * @return whether this role may run such a command
     */
    public boolean allows(boolean admin, boolean write) {
        return switch (this) {
            case ADMIN -> true;
            case READWRITE -> !admin;
            case READONLY -> !admin && !write;
        };
    }

    /** @throws IllegalArgumentException for an unknown role name */
    public static Role parse(String name) {
        try {
            return valueOf(name.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown role '" + name + "' (use admin, readwrite or readonly)");
        }
    }
}
