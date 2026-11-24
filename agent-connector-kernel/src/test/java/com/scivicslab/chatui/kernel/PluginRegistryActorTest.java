package com.scivicslab.chatui.kernel;

import com.scivicslab.chatui.core.plugin.PluginRegistry;
import com.scivicslab.chatui.plugin.ChatUiPlugin;
import com.scivicslab.chatui.plugin.ConversationTool;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** What {@link PluginRegistry} promises now that one actor owns it: registrations keep their order, a read follows them. */
class PluginRegistryActorTest {

    private static ConversationTool tool(String name) {
        return new ConversationTool() {
            @Override public String name() { return name; }
            @Override public String description() { return "- " + name; }
            @Override public String execute(String argumentsJson) { return name; }
        };
    }

    @Test
    void registrationsKeepTheirOrderAndAReadAfterThemSeesThem() {
        PluginRegistry registry = new PluginRegistry();
        for (int i = 0; i < 50; i++) registry.register(tool("tool-" + i));
        List<String> names = registry.tools().stream().map(ConversationTool::name).toList();
        assertEquals(50, names.size());
        for (int i = 0; i < 50; i++) assertEquals("tool-" + i, names.get(i));
    }

    @Test
    void aPluginIsRegisteredAsOneStep() {
        PluginRegistry registry = new PluginRegistry();
        registry.register(new ChatUiPlugin() {
            @Override public String id() { return "p"; }
            @Override public List<ConversationTool> tools() { return List.of(tool("a"), tool("b")); }
        }, "test");
        assertEquals(1, registry.plugins().size());
        assertEquals(List.of("a", "b"), registry.plugins().get(0).toolNames());
        assertEquals(List.of("a", "b"), registry.tools().stream().map(ConversationTool::name).toList());
    }

    @Test
    void manyThreadsRegisteringLoseNone() throws Exception {
        PluginRegistry registry = new PluginRegistry();
        ExecutorService threads = Executors.newFixedThreadPool(8);   // test only: callers from outside the actors
        try {
            List<Future<?>> done = new ArrayList<>();
            for (int t = 0; t < 8; t++) {
                int base = t * 100;
                done.add(threads.submit(() -> {
                    for (int i = 0; i < 100; i++) registry.register(tool("t-" + (base + i)));
                    return null;
                }));
            }
            for (Future<?> f : done) f.get(30, TimeUnit.SECONDS);
            assertEquals(800, registry.tools().size());
        } finally {
            threads.shutdownNow();
        }
    }

    @Test
    void anExceptionOfALoadReachesTheCallerAsItself() {
        PluginRegistry registry = new PluginRegistry();
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> registry.loadJars(List.of(Path.of("/nonexistent/plugin.jar"))));
        assertEquals(true, e.getMessage().contains("Plugin jar not found"), e.getMessage());
        assertEquals(0, registry.plugins().size(), "the registry stays usable");
    }
}
