package com.scivicslab.chatui.core.iolog;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins what {@link IoLogStore} promises now that its state has one owner, an actor, per way of sending to it:
 * {@code tell} (a write: told in order, nobody waits), {@code ask} (a question or a session opening: answered after every
 * write told before it, one at a time), a flush (an {@code ask} that returns when earlier writes are in the database), and
 * a close (an {@code ask} behind the pending writes).
 */
class IoLogStoreActorTest {

    private static IoLogStore open(Path dir, String name) {
        IoLogStore log = new IoLogStore();
        log.useDatabaseAt(dir.resolve(name).toString());
        return log;
    }

    /** Reads the messages of a session, in the order they were written, through a connection of its own. */
    private static List<String> messages(IoLogStore log, long session) throws Exception {
        List<String> out = new ArrayList<>();
        try (Connection c = DriverManager.getConnection(log.jdbcUrl());
             Statement s = c.createStatement();
             ResultSet r = s.executeQuery("SELECT message FROM logs WHERE session_id = " + session + " ORDER BY id")) {
            while (r.next()) out.add(r.getString(1));
        }
        return out;
    }

    @Test
    void writesAreWrittenInTheOrderTheyWereTold(@TempDir Path dir) throws Exception {
        IoLogStore log = open(dir, "order");
        try {
            long sid = log.ensureSession("project1/chat-01");
            for (int i = 0; i < 300; i++) log.record(sid, "node", "label", "entry-" + i);
            log.flush();
            List<String> seen = messages(log, sid);
            assertEquals(300, seen.size());
            for (int i = 0; i < 300; i++) assertEquals("entry-" + i, seen.get(i));
        } finally {
            log.close();
        }
    }

    @Test
    void aFlushReturnsOnlyAfterEveryEarlierWriteIsInTheDatabase(@TempDir Path dir) throws Exception {
        IoLogStore log = open(dir, "flush");
        try {
            long sid = log.ensureSession("project1/chat-01");
            for (int round = 1; round <= 5; round++) {
                for (int i = 0; i < 200; i++) log.record(sid, "node", "label", "r" + round + "-" + i);
                log.flush();
                // Read at once, from a connection that is not behind the mailbox: all of the round must be there.
                assertEquals(round * 200, messages(log, sid).size(), "after flush " + round);
            }
        } finally {
            log.close();
        }
    }

    @Test
    void aCloseAfterWritesLosesNoneOfThem(@TempDir Path dir) throws Exception {
        IoLogStore first = open(dir, "close");
        long sid = first.ensureSession("project1/chat-01");
        for (int i = 0; i < 500; i++) first.record(sid, "node", "label", "kept-" + i);
        first.close();   // asked behind the pending writes; nothing is flushed by hand

        IoLogStore second = open(dir, "close");
        try {
            assertEquals(500, messages(second, sid).size(), "every write told before the close is in the file");
        } finally {
            second.close();
        }
    }

    @Test
    void whatAskedAfterAWriteSeesThatWrite(@TempDir Path dir) throws Exception {
        IoLogStore log = open(dir, "ask");
        try {
            long sid = log.ensureSession("project1/chat-01");
            log.record(sid, "node", "label", "just-told");
            // store() is an ask to the owner and the store's read goes through the store's actor: both come after the tell.
            var entries = log.store().getLogsByNode(sid, "node");
            assertEquals(1, entries.size());
        } finally {
            log.close();
        }
    }

    @Test
    void manyThreadsOpeningTheSameSessionGetOneSession(@TempDir Path dir) throws Exception {
        IoLogStore log = open(dir, "once");
        ExecutorService threads = Executors.newFixedThreadPool(8);   // test only: callers from outside the actors
        try {
            Set<Long> ids = ConcurrentHashMap.newKeySet();
            CountDownLatch go = new CountDownLatch(1);
            List<java.util.concurrent.Future<?>> done = new ArrayList<>();
            for (int t = 0; t < 8; t++) {
                done.add(threads.submit(() -> {
                    go.await();
                    for (int i = 0; i < 20; i++) ids.add(log.ensureSession("project1/chat-01"));
                    return null;
                }));
            }
            go.countDown();
            for (var f : done) f.get(30, TimeUnit.SECONDS);
            assertEquals(1, ids.size(), "one message at a time: the session is opened once, not once per caller");
            assertEquals(1, log.store().listSessions(10).size());
        } finally {
            threads.shutdownNow();
            log.close();
        }
    }

    @Test
    void afterTheShutdownALateWriteIsHarmlessAndALookupFindsNothing(@TempDir Path dir) throws Exception {
        IoLogStore log = open(dir, "shutdown");
        long sid = log.ensureSession("project1/chat-01");
        log.shutdown();   // the store closes behind the pending writes, and the owner with it
        log.record(sid, "node", "label", "too late");   // dropped, nothing throws
        log.flush();
        assertEquals(-1, log.sessionIdOf("project1/chat-01"));
    }

    @Test
    void afterTheCloseALogBehavesAsIfLoggingWereOff(@TempDir Path dir) throws Exception {
        IoLogStore log = open(dir, "closed");
        long sid = log.ensureSession("project1/chat-01");
        log.close();
        log.record(sid, "node", "label", "too late");   // nobody is told, nothing throws
        log.flush();
        assertEquals(-1, log.ensureSession("project1/chat-01"));
        assertNull(log.store());
        assertTrue(log.resumableConversationSquads().isEmpty());
        log.close();   // a second close is harmless
    }
}
