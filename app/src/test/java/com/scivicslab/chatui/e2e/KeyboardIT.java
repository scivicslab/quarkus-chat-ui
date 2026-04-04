package com.scivicslab.chatui.e2e;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * E2E tests that verify keyboard interactions in the prompt input.
 *
 * <p>The default keybind mode uses Shift+Enter to submit. These tests
 * verify that basic keyboard input and newline insertion work as
 * expected.</p>
 */
class KeyboardIT extends E2eTestBase {

    @Test
    @DisplayName("Enter key inserts newline in default keybind mode")
    void keyboard_enter_insertsNewline() {
        page.navigate(baseUrl());

        page.waitForFunction(
                "() => document.querySelector('#connection-status').classList.contains('connected')"
                        + " || document.querySelector('#connection-status').textContent.includes('ready')",
                null,
                new Page.WaitForFunctionOptions().setTimeout(10000));

        Locator input = page.locator("#prompt-input");
        input.click();
        input.pressSequentially("line1");
        input.press("Enter");
        input.pressSequentially("line2");

        String value = input.inputValue();
        assertTrue(value.contains("line1") && value.contains("line2"),
                "Input should contain both lines, got: " + value);
    }

    @Test
    @DisplayName("Typing in prompt input does not trigger page navigation")
    void keyboard_typing_noNavigation() {
        page.navigate(baseUrl());
        String originalUrl = page.url();

        Locator input = page.locator("#prompt-input");
        input.fill("test typing does not navigate");

        assertEquals(originalUrl, page.url(), "URL should not change while typing");
    }
}
