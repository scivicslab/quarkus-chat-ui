package com.scivicslab.chatui.e2e;

import com.microsoft.playwright.Locator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * E2E tests that verify theme switching functionality.
 */
class ThemeIT extends E2eTestBase {

    @Test
    @DisplayName("Default theme is applied on first load")
    void theme_defaultApplied() {
        page.navigate(baseUrl());
        String dataTheme = (String) page.evaluate(
                "() => document.documentElement.getAttribute('data-theme')"
                        + " || document.body.getAttribute('data-theme')"
                        + " || document.querySelector('[data-theme]')?.getAttribute('data-theme')");
        assertNotNull(dataTheme, "A data-theme attribute should be set on the page");
    }

    @Test
    @DisplayName("Switching theme updates data-theme attribute")
    void theme_switchUpdatesAttribute() {
        page.navigate(baseUrl());
        Locator themeSelect = page.locator("#theme-select");

        // Get available options
        int optionCount = themeSelect.locator("option").count();
        if (optionCount < 2) {
            return; // Cannot test switching with fewer than 2 themes
        }

        // Select the second theme
        String secondThemeValue = themeSelect.locator("option").nth(1).getAttribute("value");
        themeSelect.selectOption(secondThemeValue);

        // Verify data-theme attribute changed
        String currentTheme = (String) page.evaluate(
                "() => document.documentElement.getAttribute('data-theme')"
                        + " || document.body.getAttribute('data-theme')"
                        + " || document.querySelector('[data-theme]')?.getAttribute('data-theme')");
        assertEquals(secondThemeValue, currentTheme,
                "data-theme should match the selected option");
    }

    @Test
    @DisplayName("Theme persists across page reload via localStorage")
    void theme_persistsAcrossReload() {
        page.navigate(baseUrl());
        Locator themeSelect = page.locator("#theme-select");

        int optionCount = themeSelect.locator("option").count();
        if (optionCount < 2) {
            return;
        }

        // Switch to last theme
        String lastThemeValue = themeSelect.locator("option").nth(optionCount - 1).getAttribute("value");
        themeSelect.selectOption(lastThemeValue);

        // Reload page
        page.reload();

        // Verify theme is still applied
        String themeAfterReload = (String) page.evaluate(
                "() => document.documentElement.getAttribute('data-theme')"
                        + " || document.body.getAttribute('data-theme')"
                        + " || document.querySelector('[data-theme]')?.getAttribute('data-theme')");
        assertEquals(lastThemeValue, themeAfterReload,
                "Theme should persist after reload");
    }

    @Test
    @DisplayName("Theme selector reflects the current theme value")
    void theme_selectorReflectsCurrentValue() {
        page.navigate(baseUrl());
        Locator themeSelect = page.locator("#theme-select");

        String selectedValue = themeSelect.inputValue();
        String dataTheme = (String) page.evaluate(
                "() => document.documentElement.getAttribute('data-theme')"
                        + " || document.body.getAttribute('data-theme')"
                        + " || document.querySelector('[data-theme]')?.getAttribute('data-theme')");

        assertEquals(selectedValue, dataTheme,
                "Theme selector value should match the applied data-theme");
    }
}
