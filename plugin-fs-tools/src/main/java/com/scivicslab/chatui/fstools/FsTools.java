package com.scivicslab.chatui.fstools;

import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * MCP tools for filesystem access. Operates on the machine where chat-ui is running.
 * Access is restricted to directories listed in chat-ui.filesystem.allowed-dirs.
 */
@ApplicationScoped
public class FsTools {

    private static final int DEFAULT_MAX_LENGTH = 20000;
    private static final int DEFAULT_MAX_RESULTS = 50;

    @Inject
    FilesystemGuard guard;

    @Tool(description = "Read the text content of a file. Returns the file contents as a string.")
    String read_file(
            @ToolArg(description = "Absolute path to the file") String path,
            @ToolArg(description = "Maximum characters to return (default 20000)", required = false) Integer max_length) {

        if (path == null || path.isBlank()) return "Error: 'path' is required";
        int limit = (max_length != null && max_length > 0) ? max_length : DEFAULT_MAX_LENGTH;

        Optional<String> err = guard.check(path);
        if (err.isPresent()) return err.get();

        try {
            Path p = Path.of(path);
            if (!Files.isRegularFile(p)) return "Error: not a regular file: " + path;
            String content = Files.readString(p);
            if (content.length() <= limit) return content;
            return content.substring(0, limit) + "\n[truncated — " + content.length() + " chars total]";
        } catch (Exception e) {
            return "Error reading " + path + ": " + e.getMessage();
        }
    }

    @Tool(description = "Write text content to a file. Creates the file if it does not exist, overwrites if it does.")
    String write_file(
            @ToolArg(description = "Absolute path to the file") String path,
            @ToolArg(description = "Text content to write") String content) {

        if (path == null || path.isBlank()) return "Error: 'path' is required";
        if (content == null) content = "";

        Optional<String> err = guard.checkParent(path);
        if (err.isPresent()) return err.get();

        try {
            Path p = Path.of(path);
            Files.createDirectories(p.getParent());
            Files.writeString(p, content);
            return "Written " + content.length() + " chars to " + path;
        } catch (Exception e) {
            return "Error writing " + path + ": " + e.getMessage();
        }
    }

    @Tool(description = "List the contents of a directory. Returns file names, sizes, and types.")
    String list_directory(
            @ToolArg(description = "Absolute path to the directory") String path) {

        if (path == null || path.isBlank()) return "Error: 'path' is required";

        Optional<String> err = guard.check(path);
        if (err.isPresent()) return err.get();

        try {
            Path dir = Path.of(path);
            if (!Files.isDirectory(dir)) return "Error: not a directory: " + path;

            StringBuilder sb = new StringBuilder();
            try (Stream<Path> entries = Files.list(dir).sorted()) {
                entries.forEach(p -> {
                    try {
                        BasicFileAttributes attrs = Files.readAttributes(p, BasicFileAttributes.class);
                        String type = attrs.isDirectory() ? "[dir] " : "[file]";
                        String size = attrs.isDirectory() ? "" : " (" + attrs.size() + " bytes)";
                        sb.append(type).append(" ").append(p.getFileName()).append(size).append("\n");
                    } catch (IOException e) {
                        sb.append("[err]  ").append(p.getFileName()).append("\n");
                    }
                });
            }
            return sb.isEmpty() ? "(empty directory)" : sb.toString().stripTrailing();
        } catch (Exception e) {
            return "Error listing " + path + ": " + e.getMessage();
        }
    }

    @Tool(description = "Search for files matching a glob pattern under a directory. Example: **/*.pdf or *.txt")
    String search_files(
            @ToolArg(description = "Root directory to search") String path,
            @ToolArg(description = "Glob pattern (e.g. **/*.pdf)") String pattern,
            @ToolArg(description = "Maximum results (default 50)", required = false) Integer max_results) {

        if (path == null || path.isBlank()) return "Error: 'path' is required";
        if (pattern == null || pattern.isBlank()) return "Error: 'pattern' is required";
        int limit = (max_results != null && max_results > 0) ? max_results : DEFAULT_MAX_RESULTS;

        Optional<String> err = guard.check(path);
        if (err.isPresent()) return err.get();

        try {
            Path root = Path.of(path);
            PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);

            List<String> results = new ArrayList<>();
            Files.walk(root)
                    .filter(p -> matcher.matches(p.getFileName()) || matcher.matches(root.relativize(p)))
                    .limit(limit)
                    .forEach(p -> results.add(p.toString()));

            if (results.isEmpty()) return "No files found matching: " + pattern;
            return String.join("\n", results);
        } catch (Exception e) {
            return "Error searching " + path + ": " + e.getMessage();
        }
    }

    @Tool(description = "Get metadata about a file or directory: size, type, and modification time.")
    String get_file_info(
            @ToolArg(description = "Absolute path to the file or directory") String path) {

        if (path == null || path.isBlank()) return "Error: 'path' is required";

        Optional<String> err = guard.check(path);
        if (err.isPresent()) return err.get();

        try {
            Path p = Path.of(path);
            BasicFileAttributes attrs = Files.readAttributes(p, BasicFileAttributes.class);
            return "path: " + path + "\n"
                    + "type: " + (attrs.isDirectory() ? "directory" : "file") + "\n"
                    + "size: " + attrs.size() + " bytes\n"
                    + "created: " + Instant.ofEpochMilli(attrs.creationTime().toMillis()) + "\n"
                    + "modified: " + Instant.ofEpochMilli(attrs.lastModifiedTime().toMillis());
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    @Tool(description = "List the directories that the filesystem tools are allowed to access.")
    String list_allowed_directories() {
        List<String> dirs = guard.getAllowedDirs();
        if (dirs.isEmpty() || (dirs.size() == 1 && dirs.get(0).isBlank())) {
            return "No allowed directories configured. Set chat-ui.filesystem.allowed-dirs.";
        }
        return "Allowed directories:\n" + String.join("\n", dirs);
    }
}
