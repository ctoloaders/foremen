package com.foremen.qa.pages;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.options.WaitForSelectorState;

/**
 * Page object for the create/edit user sheet ({@code UserFormSheet}, Requirement 7.3).
 *
 * <p>The real frontend renders this as a right-side {@code Sheet} overlay (a Radix dialog, so it
 * carries {@code role="dialog"}). Its controls map to stable hooks:
 * <ul>
 *   <li>name — {@code #user-name},</li>
 *   <li>email — {@code #user-email},</li>
 *   <li>locale — a {@code Select} whose trigger is {@code #user-locale} (options {@code pl}/{@code ru}),</li>
 *   <li>role — a {@code RoleSelect} combobox (a {@code role=combobox} button opening a popover with a
 *       search box and a list of role buttons),</li>
 *   <li>active — {@code #user-active} checkbox,</li>
 *   <li>submit — the {@code type=submit} button in the sheet footer.</li>
 * </ul>
 * Phone is optional and left blank for the smoke create path. This page object only performs
 * intention-revealing actions; the step holds assertions (e.g. the success toast + list row).
 */
public final class UserFormSheet {

    private final Page page;

    public UserFormSheet(Page page) {
        this.page = page;
    }

    /** The sheet dialog container (Radix dialog role). */
    private Locator dialog() {
        return page.getByRole(AriaRole.DIALOG);
    }

    /** Wait until the sheet dialog and its email field are visible. */
    public UserFormSheet waitUntilOpen() {
        dialog().first().waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE));
        page.locator("#user-email").waitFor(
                new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE));
        return this;
    }

    /** {@code true} when the sheet dialog is currently visible. */
    public boolean isOpen() {
        return dialog().count() > 0 && dialog().first().isVisible();
    }

    // ---- Field actions ----

    public UserFormSheet fillName(String name) {
        page.locator("#user-name").fill(name);
        return this;
    }

    public UserFormSheet fillEmail(String email) {
        page.locator("#user-email").fill(email);
        return this;
    }

    /**
     * Choose the locale via the {@code Select} trigger ({@code #user-locale}). Value is {@code pl} or
     * {@code ru}; the option list is a Radix popover whose items carry the localized label but a
     * stable {@code data-value} — Playwright's role/name matching selects by visible label, so this
     * clicks the option by its language code prefix.
     */
    public UserFormSheet selectLocale(String value) {
        page.locator("#user-locale").click();
        // Radix Select options render as role=option; match the one whose value matches (pl/ru).
        // The visible labels are "Polski (PL)" / "Русский (RU)"; select by the parenthesized code.
        String code = value.equalsIgnoreCase("pl") ? "PL" : "RU";
        page.getByRole(AriaRole.OPTION,
                new Page.GetByRoleOptions().setName(
                        java.util.regex.Pattern.compile(code, java.util.regex.Pattern.CASE_INSENSITIVE)))
                .first().click();
        return this;
    }

    /**
     * The role combobox trigger. The create-user sheet renders TWO {@code role=combobox} buttons —
     * the locale one ({@code #user-locale}, "Wybierz język") and the role one ("Wybierz rolę",
     * {@code aria-haspopup="dialog"}). Target the ROLE combobox by its accessible name so the role
     * popover actually opens.
     */
    private Locator roleCombobox() {
        return page.getByRole(AriaRole.COMBOBOX,
                new Page.GetByRoleOptions().setName(
                        java.util.regex.Pattern.compile("rol", java.util.regex.Pattern.CASE_INSENSITIVE)));
    }

    /** Role option buttons inside the open combobox popover (Radix popper content wrapper). */
    private Locator rolePopoverOptions() {
        return page.locator("[data-radix-popper-content-wrapper] button");
    }

    /**
     * Open the role combobox and select the first available (non-excluded) role. The combobox is a
     * {@code role=combobox} button; opening it renders a popover with a search box and role option
     * buttons. Role display names are localized, so the smoke path selects the first offered role by
     * position rather than matching a localized label. Waits for the option list to render before
     * clicking so the selection is stable.
     */
    public UserFormSheet selectFirstRole() {
        roleCombobox().click();
        Locator options = rolePopoverOptions();
        options.first().waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE));
        options.first().click();
        return this;
    }

    // ---- Submit ----

    /** The footer submit button ({@code type=submit}). */
    public Locator submitButton() {
        return dialog().locator("button[type=submit]");
    }

    /** Submit the form (create the user). Does not wait for the toast; the step asserts that. */
    public void submit() {
        submitButton().first().click();
    }

    /** Wait until the sheet dialog has closed (after a successful create). */
    public void waitUntilClosed() {
        dialog().first().waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.HIDDEN));
    }
}
