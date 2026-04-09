package com.scivicslab.chatui.multiuser;

import com.scivicslab.chatui.core.provider.LlmProvider;
import com.scivicslab.chatui.core.provider.ProviderCapabilities;
import com.scivicslab.chatui.core.provider.ProviderContext;
import com.scivicslab.chatui.core.rest.ChatEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link MultiUserExtensionImpl#isEnabled()}.
 *
 * <p>Regression guard for the bug where {@code chat-ui.multi-user=true} activated
 * multi-user mode even for CLI providers (claude, codex), which cannot share a
 * session across users. Multi-user mode must only activate for {@code openai-compat}.</p>
 */
class MultiUserExtensionImplTest {

    // ------------------------------------------------------------------ //
    // Helpers                                                              //
    // ------------------------------------------------------------------ //

    /** Creates an {@link MultiUserExtensionImpl} with injected field values via reflection. */
    private MultiUserExtensionImpl buildExt(String providerId, boolean multiUserEnabled)
            throws Exception {
        var ext = new MultiUserExtensionImpl();
        injectField(ext, "provider", stubProvider(providerId));
        injectField(ext, "multiUserEnabled", multiUserEnabled);
        return ext;
    }

    private void injectField(Object target, String fieldName, Object value) throws Exception {
        Field f = MultiUserExtensionImpl.class.getDeclaredField(fieldName);
        f.setAccessible(true);
        f.set(target, value);
    }

    /** Minimal {@link LlmProvider} stub that only implements {@code id()}. */
    private LlmProvider stubProvider(String id) {
        return new LlmProvider() {
            @Override public String id() { return id; }
            @Override public String displayName() { return id; }
            @Override public List<ModelEntry> getAvailableModels() { return List.of(); }
            @Override public String getCurrentModel() { return ""; }
            @Override public void setModel(String model) {}
            @Override public void sendPrompt(String prompt, String model,
                                             Consumer<ChatEvent> emitter, ProviderContext ctx) {}
            @Override public void cancel() {}
        };
    }

    // ------------------------------------------------------------------ //
    // Flag disabled — always false regardless of provider                  //
    // ------------------------------------------------------------------ //

    @Test
    @DisplayName("isEnabled returns false when flag is false and provider is openai-compat")
    void isEnabled_flagFalse_openaiCompat_returnsFalse() throws Exception {
        assertFalse(buildExt("openai-compat", false).isEnabled());
    }

    @Test
    @DisplayName("isEnabled returns false when flag is false and provider is claude")
    void isEnabled_flagFalse_claude_returnsFalse() throws Exception {
        assertFalse(buildExt("claude", false).isEnabled());
    }

    @Test
    @DisplayName("isEnabled returns false when flag is false and provider is codex")
    void isEnabled_flagFalse_codex_returnsFalse() throws Exception {
        assertFalse(buildExt("codex", false).isEnabled());
    }

    // ------------------------------------------------------------------ //
    // Flag enabled — only openai-compat may activate multi-user mode      //
    // ------------------------------------------------------------------ //

    @Test
    @DisplayName("isEnabled returns true when flag is true and provider is openai-compat")
    void isEnabled_flagTrue_openaiCompat_returnsTrue() throws Exception {
        assertTrue(buildExt("openai-compat", true).isEnabled());
    }

    @Test
    @DisplayName("isEnabled returns false for claude even when flag is true (regression guard)")
    void isEnabled_flagTrue_claude_returnsFalse() throws Exception {
        assertFalse(buildExt("claude", true).isEnabled(),
                "chat-ui.multi-user=true must not activate multi-user mode for the claude provider");
    }

    @Test
    @DisplayName("isEnabled returns false for codex even when flag is true (regression guard)")
    void isEnabled_flagTrue_codex_returnsFalse() throws Exception {
        assertFalse(buildExt("codex", true).isEnabled(),
                "chat-ui.multi-user=true must not activate multi-user mode for the codex provider");
    }

    @Test
    @DisplayName("isEnabled returns false for an unknown provider id even when flag is true")
    void isEnabled_flagTrue_unknownProvider_returnsFalse() throws Exception {
        assertFalse(buildExt("unknown-provider", true).isEnabled());
    }
}
