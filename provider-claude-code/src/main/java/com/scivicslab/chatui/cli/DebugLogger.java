package com.scivicslab.chatui.cli;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Debug logger that writes to /tmp/chat-ui-debug.log
 */
public class DebugLogger {

    private static final Path LOG_FILE = Path.of("/tmp/chat-ui-debug.log");
    private static final DateTimeFormatter FORMATTER =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault());
    private static final boolean ENABLED = true; // Always enabled for debugging

    public static void log(String message) {
        if (!ENABLED) return;

        try (PrintWriter writer = new PrintWriter(new FileWriter(LOG_FILE.toFile(), true))) {
            String timestamp = FORMATTER.format(Instant.now());
            writer.println(timestamp + " " + message);
        } catch (IOException e) {
            // Ignore logging errors
        }
    }

    public static void logEvent(String phase, String type, String content) {
        if (!ENABLED) return;

        String preview = content != null
            ? (content.length() > 100 ? content.substring(0, 100) + "..." : content)
            : "(null)";
        log(String.format("[%s] type=%s content=%s", phase, type, preview));
    }

    public static void clear() {
        if (!ENABLED) return;

        try {
            Files.deleteIfExists(LOG_FILE);
            log("=== DEBUG LOG STARTED ===");
        } catch (IOException e) {
            // Ignore
        }
    }
}
