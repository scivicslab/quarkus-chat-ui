package com.scivicslab.chatui.core.iolog;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * After the shutdown the I/O log behaves as logging being off, and says nothing about it: {@code store()} is {@code null}, a
 * write is dropped without a log line, a lookup finds nothing, the owner is closed. Readers that open a connection of their
 * own flush first, so they see what was told before them.
 */
class IoLogAfterShutdownTest {

    @Test
    void afterTheShutdownTheLogIsOffAndQuiet(@TempDir Path dir) throws Exception {
        java.util.List<LogRecord> warnings = new java.util.concurrent.CopyOnWriteArrayList<>();
        Handler spy = new Handler() {
            @Override public void publish(LogRecord r) { if (r.getLevel().intValue() >= Level.WARNING.intValue()) warnings.add(r); }
            @Override public void flush() { }
            @Override public void close() { }
        };
        Logger.getLogger("").addHandler(spy);
        try {
            IoLogStore log = new IoLogStore();
            log.useDatabaseAt(dir.resolve("off").toString());
            long sid = log.ensureSession("project1/chat-01");
            log.record(sid, "node", "label", "kept");
            log.shutdown();

            assertNull(log.store(), "the closed store is not handed out");
            log.record(sid, "node", "label", "too late");
            log.flush();
            assertEquals(-1, log.ensureSession("project1/chat-01"));
            assertTrue(log.resumableConversationSquads().isEmpty());
            assertTrue(warnings.stream().noneMatch(r -> String.valueOf(r.getMessage()).contains("I/O log")),
                    "no warning per dropped write: " + warnings.stream().map(LogRecord::getMessage).toList());
        } finally {
            Logger.getLogger("").removeHandler(spy);
        }
    }

    @Test
    void aReaderWithItsOwnConnectionSeesTheWritesToldBeforeIt(@TempDir Path dir) throws Exception {
        IoLogStore log = new IoLogStore();
        log.useDatabaseAt(dir.resolve("search").toString());
        try {
            for (int round = 0; round < 30; round++) {
                long sid = log.ensureSession("project1/chat-0" + (round % 5 + 1));
                log.record(sid, "project1/chat-01.chat", "turn" + round + "/step1/llm", "REQUEST:\n{}\nRESPONSE:\nneedle" + round);
                // IoLogSearch reads through a connection of its own; it flushes first, so the write told just above is there.
                IoLogSearch search = new IoLogSearch();
                search.ioLog = log;
                var result = search.search("needle" + round, 5);
                assertEquals("", result.error());
                assertTrue(result.hits().size() >= 1, "round " + round + ": the write told just before the search is not found");
            }
        } finally {
            log.close();
        }
    }
}
