package com.foremen.qa.pages;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.WaitForSelectorState;

/**
 * Page object for the responsive workspace tab host (FOR-05-01), mirroring the real frontend
 * {@code WorkspaceTabs} component.
 *
 * <p>Locators key off the confirmed testids:
 * <ul>
 *   <li>tabs host {@code [data-testid=workspace-tabs]} (attr {@code data-breakpoint}),</li>
 *   <li>strip {@code [data-testid=workspace-tabstrip]} (attr {@code data-variant} =
 *       {@code strip}/{@code segmented}),</li>
 *   <li>a tab button {@code [data-testid=workspace-tab-<key>]} (attr {@code data-active}),</li>
 *   <li>the mobile overflow trigger/menu/items,</li>
 *   <li>the execution-stage grouped design selector trigger/menu/items (the FIX-2 surface).</li>
 * </ul>
 * The tab {@code key}s are: overview, readiness, pricing, rooms, estimate, scheduleDesign, contract,
 * procurement, documents, planActual, payroll, amendments, priceHistory. Holds no assertions.
 */
public final class WorkspaceTabs {

    private final Page page;

    public WorkspaceTabs(Page page) {
        this.page = page;
    }

    // ---- Host + strip ----

    /** The tabs host container ({@code data-breakpoint} attr). */
    public Locator host() {
        return page.locator("[data-testid=workspace-tabs]");
    }

    /** The tab strip element ({@code data-variant} = {@code strip} | {@code segmented}). */
    public Locator strip() {
        return page.locator("[data-testid=workspace-tabstrip]");
    }

    /** The {@code data-variant} of the strip ({@code strip} on desktop/tablet, {@code segmented} on mobile). */
    public String variant() {
        Locator strip = strip();
        return strip.count() == 0 ? null : strip.first().getAttribute("data-variant");
    }

    /** Wait until the tab strip has rendered. */
    public WorkspaceTabs waitUntilRendered() {
        strip().first().waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE));
        return this;
    }

    // ---- Individual tabs ----

    /** The tab button for {@code key} (e.g. {@code overview}, {@code rooms}). */
    public Locator tab(String key) {
        return page.locator("[data-testid=workspace-tab-" + key + "]");
    }

    /** {@code true} when the tab for {@code key} is present and visible in the main strip. */
    public boolean isTabVisible(String key) {
        Locator tab = tab(key);
        return tab.count() > 0 && tab.first().isVisible();
    }

    /** {@code true} when the tab for {@code key} is the active tab ({@code data-active=true}). */
    public boolean isTabActive(String key) {
        Locator tab = tab(key);
        return tab.count() > 0 && "true".equals(tab.first().getAttribute("data-active"));
    }

    /** Click the tab for {@code key} (navigates + writes per-project memory). */
    public void clickTab(String key) {
        tab(key).first().click();
    }

    // ---- Mobile overflow menu ----

    /** The mobile overflow "•••" trigger. */
    public Locator overflowTrigger() {
        return page.locator("[data-testid=workspace-tabs-overflow-trigger]");
    }

    /** The mobile overflow menu (open it via {@link #openOverflow()} first). */
    public Locator overflowMenu() {
        return page.locator("[data-testid=workspace-tabs-overflow-menu]");
    }

    /** Open the mobile overflow menu, waiting for it to appear. */
    public void openOverflow() {
        overflowTrigger().first().click();
        overflowMenu().first().waitFor(
                new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE));
    }

    /** An item inside the mobile overflow menu for {@code key}. */
    public Locator overflowItem(String key) {
        return page.locator("[data-testid=workspace-tab-overflow-" + key + "]");
    }

    // ---- Execution-stage design selector (FIX 2) ----

    /** The grouped design-selector trigger (attr {@code data-active}); execution stage only. */
    public Locator designSelectorTrigger() {
        return page.locator("[data-testid=workspace-design-selector-trigger]");
    }

    /** {@code true} when the design selector is rendered and visible. */
    public boolean isDesignSelectorVisible() {
        Locator trigger = designSelectorTrigger();
        return trigger.count() > 0 && trigger.first().isVisible();
    }

    /** The design-selector dropdown menu (open it via {@link #openDesignSelector()} first). */
    public Locator designSelectorMenu() {
        return page.locator("[data-testid=workspace-design-selector-menu]");
    }

    /** Open the grouped design selector, waiting for its menu to appear. */
    public void openDesignSelector() {
        designSelectorTrigger().first().click();
        designSelectorMenu().first().waitFor(
                new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE));
    }

    /** A design-selector menu item for {@code key} (e.g. {@code rooms}). */
    public Locator designTab(String key) {
        return page.locator("[data-testid=workspace-design-tab-" + key + "]");
    }

    /** Click a design-selector menu item for {@code key} (navigates + writes per-project memory). */
    public void clickDesignTab(String key) {
        designTab(key).first().click();
    }

    /** {@code true} when the design-selector trigger is highlighted active ({@code data-active=true}). */
    public boolean designSelectorActive() {
        Locator trigger = designSelectorTrigger();
        return trigger.count() > 0 && "true".equals(trigger.first().getAttribute("data-active"));
    }
}
