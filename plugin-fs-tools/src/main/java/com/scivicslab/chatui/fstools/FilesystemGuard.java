package com.scivicslab.chatui.fstools;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Validates that requested paths are within the configured allowed directories.
 * Config key: chat-ui.filesystem.allowed-dirs (comma-separated list)
 */
@ApplicationScoped
public class FilesystemGuard {

    @ConfigProperty(name = "chat-ui.filesystem.allowed-dirs")
    Optional<List<String>> allowedDirs;

    public Path validate(String pathStr) throws IOException {
        List<String> dirs = allowedDirs.orElse(List.of());
        if (dirs.isEmpty() || (dirs.size() == 1 && dirs.get(0).isBlank())) {
            throw new SecurityException("No allowed directories configured. "
                    + "Set chat-ui.filesystem.allowed-dirs in application.properties.");
        }
        Path real = Path.of(pathStr).toRealPath();
        for (String dir : dirs) {
            if (dir.isBlank()) continue;
            Path allowedReal = Path.of(dir).toRealPath();
            if (real.startsWith(allowedReal)) return real;
        }
        throw new SecurityException("Access denied: " + pathStr
                + " is outside allowed directories: " + dirs);
    }

    public Optional<String> check(String pathStr) {
        try {
            validate(pathStr);
            return Optional.empty();
        } catch (Exception e) {
            return Optional.of("Error: " + e.getMessage());
        }
    }

    public Optional<String> checkParent(String pathStr) {
        String parent = Path.of(pathStr).toAbsolutePath().getParent().toString();
        return check(parent);
    }

    public List<String> getAllowedDirs() {
        return allowedDirs.orElse(List.of());
    }
}
