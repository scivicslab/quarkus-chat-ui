package com.scivicslab.chatui.core.remote;

import com.scivicslab.chatui.openaicompat.DisconnectDetectingServer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A refresh of the peer list blocks on the workspace for up to 3 s per request; while it does, a read of the lists, and a
 * lookup of the remote path (made on every ask_chat), answer at once.
 */
class RefreshDoesNotBlockReadsTest {

    private static long millis(long from) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - from);
    }

    @Test
    void readsOfTheListsAnswerWhileARefreshWaitsForTheWorkspace() throws Exception {
        try (DisconnectDetectingServer server = new DisconnectDetectingServer(DisconnectDetectingServer.Mode.HOLD)) {
            String url = server.url();
            WorkspaceNodeDiscovery discovery = new WorkspaceNodeDiscovery(
                    new com.scivicslab.pojoactor.distributed.NodeInfo("me", "127.0.0.1", 1), url);
            CompletableFuture<Boolean> refresh = CompletableFuture.supplyAsync(discovery::refresh,
                    command -> Thread.ofVirtual().start(command));
            server.requestSeen().get(10, TimeUnit.SECONDS);
            long start = System.nanoTime();
            discovery.peers();
            discovery.toolNodes();
            discovery.getAllNodes();
            assertTrue(millis(start) < 500, "the reads waited " + millis(start) + " ms for the refresh");
            assertTrue(!refresh.isDone(), "the refresh was still waiting for the workspace");
            refresh.get(15, TimeUnit.SECONDS);
        }
    }

    @Test
    void theLookupOfTheRemotePathAnswersWhileARefreshWaitsForTheWorkspace(@TempDir Path dir) throws Exception {
        int port;
        try (ServerSocket free = new ServerSocket(0)) {
            port = free.getLocalPort();
        }
        com.scivicslab.turingworkflow.workflow.IIActorSystem local = new com.scivicslab.turingworkflow.workflow.IIActorSystem("refresh-read");
        try (DisconnectDetectingServer server = new DisconnectDetectingServer(DisconnectDetectingServer.Mode.HOLD)) {
            String url = server.url();
            RemoteNodes remote = RemoteNodes.start(local, "", url, "me", port, dir.resolve("ids.txt"));
            assertNotNull(remote);
            try {
                int before = server.requestLines().size();   // the start's own read of the lists
                CompletableFuture<Void> refresh = CompletableFuture.runAsync(remote::refreshPeers,
                        command -> Thread.ofVirtual().start(command));
                long until = System.currentTimeMillis() + 10_000;
                while (server.requestLines().size() <= before && System.currentTimeMillis() < until) Thread.sleep(10);
                assertTrue(server.requestLines().size() > before, "the refresh asked the workspace");
                long start = System.nanoTime();
                assertNotNull(RemoteNodes.of(local));
                assertTrue(millis(start) < 500, "the lookup waited " + millis(start) + " ms for the refresh");
                refresh.get(15, TimeUnit.SECONDS);
            } finally {
                remote.close();
            }
        } finally {
            local.terminate();
        }
    }
}
