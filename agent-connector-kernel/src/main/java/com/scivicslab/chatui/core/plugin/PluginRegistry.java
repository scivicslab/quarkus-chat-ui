package com.scivicslab.chatui.core.plugin;

import com.scivicslab.chatui.plugin.ChatUiPlugin;
import com.scivicslab.chatui.plugin.ConversationTool;
import com.scivicslab.chatui.plugin.LlmProviderFactory;
import com.scivicslab.chatui.plugin.ProviderChoice;
import com.scivicslab.pojoactor.core.ActorRef;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.function.Function;

/**
 * What this instance can offer a conversation beyond its built-in tools: the provider kinds and
 * the tools, built once at start-up and never changed ({@code ProviderAndToolPlugins_260912_oo01}).
 *
 * <p>The body registers its own {@code openai-compat} factory first. Then each jar named in
 * {@code chat-ui.plugins} is put on a class loader whose parent is the body's, and the
 * {@link ChatUiPlugin} implementations that {@link java.util.ServiceLoader} finds there add their factories
 * and tools. A jar that cannot be loaded stops start-up: an instance asked for a plugin must not
 * come up silently without it.</p>
 *
 * <p>Nothing is added after start-up. Which kinds and tools exist is therefore decided by the
 * command line, which is what makes "no way out to the network" a property of the process rather
 * than of a setting.</p>
 *
 * <p>The state is one owner's, an actor that runs {@link PluginBook}; this class holds no lock and no state of its own,
 * and each method is a message to the owner, answered after every registration made before it.</p>
 */
public class PluginRegistry {

    /** One loaded plugin, as reported by {@code GET /api/plugins}. */
    public record LoadedPlugin(String id, String source, List<String> providerKinds, List<String> toolNames) {}

    private final ActorRef<PluginBook> owner = new ActorRef<>("plugin-registry", new PluginBook());

    private <R> R ask(Function<PluginBook, R> call) {
        try {
            return owner.ask(call).join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException cause) throw cause;
            throw e;
        }
    }

    /** Registers one provider kind. A later registration of the same kind replaces the earlier. */
    public void register(LlmProviderFactory factory) {
        ask(b -> { b.register(factory); return null; });
    }

    /** Registers one tool. A later registration of the same name replaces the earlier. */
    public void register(ConversationTool tool) {
        ask(b -> { b.register(tool); return null; });
    }

    /**
     * Registers everything one plugin provides.
     *
     * @param plugin the plugin
     * @param source where it came from (a jar path, or {@code built-in})
     */
    public void register(ChatUiPlugin plugin, String source) {
        ask(b -> { b.register(plugin, source); return null; });
    }

    /**
     * Loads every plugin found in the given jars. Each jar gets its own class loader, parented to
     * this class's, so a plugin sees the body's {@code LlmProvider} and never a copy of it.
     *
     * @param jars paths of plugin jars
     * @throws IllegalStateException when a jar is missing, unreadable, or holds no plugin
     */
    public void loadJars(List<Path> jars) {
        ask(b -> { b.loadJars(jars); return null; });
    }

    /** @return the factory of the given kind, if registered */
    public Optional<LlmProviderFactory> factory(String kind) {
        return ask(b -> b.factory(kind));
    }

    /** @return the registered kinds, in registration order */
    public List<String> kinds() {
        return ask(PluginBook::kinds);
    }

    /** @return every dropdown entry, in registration order of kinds */
    public List<ProviderChoice> choices() {
        return ask(PluginBook::choices);
    }

    /** @return the registered tools, in registration order */
    public List<ConversationTool> tools() {
        return ask(PluginBook::tools);
    }

    /** @return the loaded plugins, in load order */
    public List<LoadedPlugin> plugins() {
        return ask(PluginBook::plugins);
    }

    /** @return what {@code GET /api/plugins} returns */
    public Map<String, Object> describe() {
        return ask(PluginBook::describe);
    }
}
