package com.scivicslab.coderagent.core.provider;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ProviderCapabilitiesTest {

    @Test
    @DisplayName("DEFAULT has all capabilities disabled")
    void default_allFalse() {
        ProviderCapabilities c = ProviderCapabilities.DEFAULT;
        assertFalse(c.supportsInteractivePrompts());
        assertFalse(c.supportsSessionRestore());
        assertFalse(c.supportsWatchdog());
        assertFalse(c.supportsImages());
        assertFalse(c.supportsUrlFetch());
        assertFalse(c.supportsSlashCommands());
    }

    @Test
    @DisplayName("CLI has interactive prompts, session restore, watchdog, slash commands enabled")
    void cli_cliCapabilitiesEnabled() {
        ProviderCapabilities c = ProviderCapabilities.CLI;
        assertTrue(c.supportsInteractivePrompts());
        assertTrue(c.supportsSessionRestore());
        assertTrue(c.supportsWatchdog());
        assertFalse(c.supportsImages());
        assertFalse(c.supportsUrlFetch());
        assertTrue(c.supportsSlashCommands());
    }

    @Test
    @DisplayName("OPENAI_COMPAT has images and URL fetch enabled, no CLI features")
    void openaiCompat_httpCapabilitiesEnabled() {
        ProviderCapabilities c = ProviderCapabilities.OPENAI_COMPAT;
        assertFalse(c.supportsInteractivePrompts());
        assertFalse(c.supportsSessionRestore());
        assertFalse(c.supportsWatchdog());
        assertTrue(c.supportsImages());
        assertTrue(c.supportsUrlFetch());
        assertFalse(c.supportsSlashCommands());
    }

    @Test
    @DisplayName("custom capabilities record constructed correctly")
    void custom_constructedCorrectly() {
        ProviderCapabilities c = new ProviderCapabilities(true, false, false, true, false, false);
        assertTrue(c.supportsInteractivePrompts());
        assertFalse(c.supportsSessionRestore());
        assertFalse(c.supportsWatchdog());
        assertTrue(c.supportsImages());
        assertFalse(c.supportsUrlFetch());
        assertFalse(c.supportsSlashCommands());
    }
}
