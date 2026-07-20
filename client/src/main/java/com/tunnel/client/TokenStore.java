package com.tunnel.client;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Properties;
import java.util.Set;

/**
 * Reads/writes the bearer token at {@code ~/.tunnel/config} with {@code 0600}
 * permissions (PRD-v2.md §7, §9.2.2; §9.3 secrets hygiene). The token is a
 * secret — the file is owner-only and the token is never logged.
 */
public class TokenStore {

    private static final Path CONFIG_DIR = Paths.get(System.getProperty("user.home"), ".tunnel");
    private static final Path CONFIG_FILE = CONFIG_DIR.resolve("config");

    /** Persist the token (and metadata) with restrictive file permissions. */
    public void save(String token, String owner, String coordinator) throws IOException {
        Properties props = new Properties();
        props.setProperty("token", token);
        props.setProperty("owner", owner == null ? "" : owner);
        props.setProperty("coordinator", coordinator == null ? "" : coordinator);

        boolean posix = Files.getFileStore(CONFIG_DIR.getParent()).supportsFileAttributeView("posix");
        if (Files.notExists(CONFIG_DIR)) {
            Files.createDirectories(CONFIG_DIR);
        }
        if (posix) {
            Files.setPosixFilePermissions(CONFIG_DIR,
                    PosixFilePermissions.fromString("rwx------"));
        }
        if (Files.notExists(CONFIG_FILE)) {
            Files.createFile(CONFIG_FILE);
        }
        if (posix) {
            Set<PosixFilePermission> perms = PosixFilePermissions.fromString("rw-------");
            Files.setPosixFilePermissions(CONFIG_FILE, perms);
        }
        try (var out = Files.newOutputStream(CONFIG_FILE)) {
            props.store(out, "tunnel credentials — keep secret");
        }
        if (posix) {
            // Re-assert 0600 in case store() recreated with a umask default.
            Files.setPosixFilePermissions(CONFIG_FILE, PosixFilePermissions.fromString("rw-------"));
        }
    }

    public String token() {
        return load().getProperty("token");
    }

    public String owner() {
        String owner = load().getProperty("owner");
        return owner == null || owner.isBlank() ? null : owner;
    }

    public boolean exists() {
        return Files.exists(CONFIG_FILE);
    }

    public Path path() {
        return CONFIG_FILE;
    }

    private Properties load() {
        Properties props = new Properties();
        if (Files.exists(CONFIG_FILE)) {
            try (var in = Files.newInputStream(CONFIG_FILE)) {
                props.load(in);
            } catch (IOException e) {
                // treated as no credentials
            }
        }
        return props;
    }
}
