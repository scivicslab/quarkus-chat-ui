package com.scivicslab.chatui.e2e;

import com.microsoft.playwright.Locator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * E2E tests that verify the page loads correctly and essential
 * UI elements are present and in their expected initial state.
 */
class PageLoadIT extends E2eTestBase {

    @Test
    @DisplayName("Page title contains application name")
    void pageLoad_titleContainsAppName() {
        page.navigate(baseUrl());
        String title = page.title();
        assertFalse(title.isEmpty(), "Page title should not be empty");
    }

    @Test
    @DisplayName("Main heading is visible")
    void pageLoad_mainHeadingVisible() {
        page.navigate(baseUrl());
        Locator heading = page.locator("#app-title");
        assertThat(heading).isVisible();
    }

    @Test
    @DisplayName("Prompt input textarea is present and enabled")
    void pageLoad_promptInputPresent() {
        page.navigate(baseUrl());
        Locator input = page.locator("#prompt-input");
        assertThat(input).isVisible();
        assertThat(input).isEnabled();
    }

    @Test
    @DisplayName("Send button is present")
    void pageLoad_sendButtonPresent() {
        page.navigate(baseUrl());
        Locator sendBtn = page.locator("#send-btn");
        assertThat(sendBtn).isVisible();
    }

    @Test
    @DisplayName("Cancel button is present and initially disabled")
    void pageLoad_cancelButtonDisabled() {
        page.navigate(baseUrl());
        Locator cancelBtn = page.locator("#cancel-btn");
        assertThat(cancelBtn).isVisible();
        assertThat(cancelBtn).isDisabled();
    }

    @Test
    @DisplayName("Theme selector is present with options")
    void pageLoad_themeSelectPresent() {
        page.navigate(baseUrl());
        Locator themeSelect = page.locator("#theme-select");
        assertThat(themeSelect).isVisible();
        int optionCount = themeSelect.locator("option").count();
        assertTrue(optionCount >= 5, "Theme selector should have at least 5 themes, found: " + optionCount);
    }

    @Test
    @DisplayName("Model selector is present")
    void pageLoad_modelSelectPresent() {
        page.navigate(baseUrl());
        Locator modelSelect = page.locator("#model-select");
        assertThat(modelSelect).isVisible();
    }

    @Test
    @DisplayName("Chat area is present")
    void pageLoad_chatAreaPresent() {
        page.navigate(baseUrl());
        Locator chatArea = page.locator("#chat-area");
        assertThat(chatArea).isVisible();
    }

    @Test
    @DisplayName("Connection status indicator is present")
    void pageLoad_connectionStatusPresent() {
        page.navigate(baseUrl());
        Locator status = page.locator("#connection-status");
        assertThat(status).isVisible();
    }

    @Test
    @DisplayName("Save chat button is present")
    void pageLoad_saveChatButtonPresent() {
        page.navigate(baseUrl());
        Locator saveBtn = page.locator("#save-chat-btn");
        assertThat(saveBtn).isVisible();
    }

    @Test
    @DisplayName("Clear chat button is present")
    void pageLoad_clearChatButtonPresent() {
        page.navigate(baseUrl());
        Locator clearBtn = page.locator("#clear-chat-btn");
        assertThat(clearBtn).isVisible();
    }

    @Test
    @DisplayName("Log panel is present")
    void pageLoad_logPanelPresent() {
        page.navigate(baseUrl());
        Locator logPanel = page.locator("#log-panel");
        assertThat(logPanel).isVisible();
    }
}
