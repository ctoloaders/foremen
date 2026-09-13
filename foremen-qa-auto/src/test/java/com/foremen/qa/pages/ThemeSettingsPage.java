package com.foremen.qa.pages;

import com.foremen.qa.support.TestConfig;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.options.WaitForSelectorState;

/**
 * Page object for the Appearance/theme settings page ({@code /settings/appearance},
 * Requirement 7.4).
 *
 * <p>The frontend {@code SettingsAppearancePage} composes a {@code ThemeModeSelector} rendered as a
 * {@code role="radiogroup"} with three {@code role="radio"} options — <b>dark</b>, <b>light</b>,
 * <b>system</b> — in that DOM order. Selecting a mode calls {@code theme-store.setThemeMode}, which
 * <em>immediately</em> persists the whole preferences object to {@code localStorage} under the key
 * {@code foremen-theme-preferences} and applies the resolved mode to {@code <html>} (adding/removing
 * the {@code dark} class). On reload, {@code useThemeApplicator} re-reads the store (hydrated from
 * that same {@code localStorage} key) and re-applies the class — which is exactly the persistence
 * this page object verifies.
 *
 * <p>Because the mode radios are ordered dark/light/system and the active one carries
 * {@code aria-checked="true"}, the page object selects and reads modes by that stable order + ARIA
 * state rather than by localized labels. To keep the toggle deterministic (independent of the OS
 * {@code prefers-color-scheme}), scenarios toggle between the explicit {@code dark} and {@code light}
 * modes, whose effect on the {@code dark} class is unambiguous.
 */
public final class ThemeSettingsPage {

    /** Appearance settings route (centralized here per the design). */
    public static final String ROUTE = "/settings/appearance";

    /** localStorage key the theme store persists preferences under. */
    public static final String STORAGE_KEY = "foremen-theme-preferences";

    /** DOM order of the theme-mode radios in {@code ThemeModeSelector}. */
    public static final String MODE_DARK = "dark";
    public static final String MODE_LIGHT = "light";
    public static final String MODE_SYSTEM = "system";

    private final Page page;

    public ThemeSettingsPage(Page page) {
        this.page = page;
    }

    /** Absolute URL of the appearance route against the configured frontend base URL. */
    public static String url() {
        return TestConfig.frontendUrl() + ROUTE;
    }

    /** Navigate to {@code /settings/appearance} and wait for the theme-mode radiogroup to render. */
    public ThemeSettingsPage open() {
        page.navigate(url());
        modeRadios().first().waitFor(
                new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE));
        return this;
    }

    /** {@code true} when the browser is on the appearance route. */
    public boolean isOpen() {
        return page.url().contains(ROUTE);
    }

    /**
     * The three theme-mode radios in DOM order: dark (0), light (1), system (2). The page also renders
     * color-scheme and font-size radiogroups, so a bare {@code getByRole(RADIO)} would match 16 radios
     * — scope to the "Tryb motywu" radiogroup to robustly target exactly the 3 mode radios.
     */
    private Locator modeRadios() {
        return page.getByRole(AriaRole.RADIOGROUP,
                        new Page.GetByRoleOptions().setName(
                                java.util.regex.Pattern.compile("Tryb motywu", java.util.regex.Pattern.CASE_INSENSITIVE)))
                .getByRole(AriaRole.RADIO);
    }

    /**
     * The "Zapisz preferencje" (Save preferences) button that commits the durable preference to the
     * backend. It is {@code type=button} and disabled until there are unsaved changes.
     */
    private Locator saveButton() {
        return page.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName(
                        java.util.regex.Pattern.compile("Zapisz preferencje", java.util.regex.Pattern.CASE_INSENSITIVE)));
    }

    /**
     * Commit the currently-previewed preferences by clicking "Zapisz preferencje" (waiting until it is
     * enabled) and wait for the save to settle (the button disables again once there are no unsaved
     * changes). Without this the un-saved localStorage preview is overwritten by the last SAVED value
     * on reload.
     */
    public ThemeSettingsPage savePreferences() {
        Locator save = saveButton();
        save.waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE));
        // The button is disabled until there are unsaved changes; wait for it to become enabled.
        page.waitForCondition(save::isEnabled);
        save.click();
        // Save settles when the button disables again (no more unsaved changes).
        page.waitForCondition(() -> !save.isEnabled());
        return this;
    }

    private int indexOf(String mode) {
        return switch (mode.toLowerCase()) {
            case MODE_DARK -> 0;
            case MODE_LIGHT -> 1;
            case MODE_SYSTEM -> 2;
            default -> throw new IllegalArgumentException("Unknown theme mode: " + mode);
        };
    }

    /** Select the given theme mode ({@code dark}/{@code light}/{@code system}). */
    public ThemeSettingsPage selectMode(String mode) {
        modeRadios().nth(indexOf(mode)).click();
        return this;
    }

    /** {@code true} when the given theme mode is the currently-checked radio. */
    public boolean isModeSelected(String mode) {
        String checked = modeRadios().nth(indexOf(mode)).getAttribute("aria-checked");
        return "true".equals(checked);
    }

    /** The currently-checked theme mode, or {@code null} if none is checked. */
    public String currentMode() {
        for (String mode : new String[] {MODE_DARK, MODE_LIGHT, MODE_SYSTEM}) {
            if (isModeSelected(mode)) {
                return mode;
            }
        }
        return null;
    }

    // ---- Persistence signals ----

    /** {@code true} when {@code <html>} currently carries the {@code dark} class. */
    public boolean isDarkClassApplied() {
        Object v = page.evaluate("() => document.documentElement.classList.contains('dark')");
        return Boolean.TRUE.equals(v);
    }

    /** The persisted {@code themeMode} value read from {@code localStorage}, or {@code null}. */
    public String persistedThemeMode() {
        Object v = page.evaluate(
                "key => { const raw = window.localStorage.getItem(key); "
                        + "if (!raw) return null; try { return JSON.parse(raw).themeMode ?? null; } "
                        + "catch (e) { return null; } }",
                STORAGE_KEY);
        return v == null ? null : v.toString();
    }

    /** Reload the current page (to verify the theme survives a full reload). */
    public ThemeSettingsPage reload() {
        page.reload();
        modeRadios().first().waitFor(
                new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE));
        return this;
    }
}
