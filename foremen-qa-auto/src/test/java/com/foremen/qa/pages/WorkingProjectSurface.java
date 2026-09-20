package com.foremen.qa.pages;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;

/**
 * Page object for the always-visible working-project surface (FOR-05-01), which appears in the app
 * shell on every route in two breakpoint-exclusive forms:
 * <ul>
 *   <li><b>desktop / tablet</b> — the {@code WorkingProjectButton} card inside the sidebar
 *       {@code <aside>}, under the Dashboard nav entry;</li>
 *   <li><b>mobile</b> — the {@code WorkingProjectChip} inside the topbar {@code <header>}.</li>
 * </ul>
 *
 * <p>Neither surface carries a testid, so locators are keyed off the RU i18n text and scoped to
 * {@code aside} (desktop) / {@code header} (mobile) so the two are distinguishable at any breakpoint.
 * The three states share text:
 * <ul>
 *   <li>choose affordance — "Выбрать проект" ({@code workspace.chooseProject}),</li>
 *   <li>change affordance — "Сменить проект" ({@code workspace.changeProject}),</li>
 *   <li>working-project caption — "Рабочий проект" ({@code workspace.workingProject}); when a
 *       working project is set the surface shows the project NAME.</li>
 * </ul>
 * Holds no assertions.
 */
public final class WorkingProjectSurface {

    /** RU i18n text of {@code workspace.chooseProject}. */
    public static final String CHOOSE_TEXT = "Выбрать проект";
    /** RU i18n text of {@code workspace.changeProject}. */
    public static final String CHANGE_TEXT = "Сменить проект";
    /** RU i18n text of {@code workspace.workingProject} (caption on the desktop card). */
    public static final String WORKING_PROJECT_TEXT = "Рабочий проект";

    private final Page page;

    public WorkingProjectSurface(Page page) {
        this.page = page;
    }

    // ---- Breakpoint-scoped surfaces ----

    /** The desktop/tablet sidebar surface, scoped to {@code <aside>}. */
    public Locator desktopButton() {
        return page.locator("aside");
    }

    /** The mobile topbar chip surface, scoped to {@code <header>}. */
    public Locator mobileChip() {
        return page.locator("header");
    }

    // ---- Affordances (RU text) ----

    /** The "Выбрать проект" (choose) affordance anywhere in the shell. */
    public Locator chooseAffordance() {
        return page.getByText(CHOOSE_TEXT);
    }

    /** The "Сменить проект" (change) affordance anywhere in the shell. */
    public Locator changeAffordance() {
        return page.getByText(CHANGE_TEXT);
    }

    /** {@code true} when the choose ("Выбрать проект") affordance is visible. */
    public boolean isChooseShown() {
        Locator choose = chooseAffordance();
        return choose.count() > 0 && choose.first().isVisible();
    }

    /** {@code true} when the change ("Сменить проект") affordance is visible. */
    public boolean isChangeShown() {
        Locator change = changeAffordance();
        return change.count() > 0 && change.first().isVisible();
    }

    /** {@code true} when a surface (aside or header) shows the given project name. */
    public boolean showsProjectName(String name) {
        return page.getByText(name).count() > 0 && page.getByText(name).first().isVisible();
    }

    /** {@code true} when the desktop sidebar button ({@code <aside>}) shows the project name. */
    public boolean desktopShowsProjectName(String name) {
        Locator inAside = desktopButton().getByText(name);
        return inAside.count() > 0 && inAside.first().isVisible();
    }

    /** {@code true} when the mobile topbar chip ({@code <header>}) shows the project name. */
    public boolean mobileShowsProjectName(String name) {
        Locator inHeader = mobileChip().getByText(name);
        return inHeader.count() > 0 && inHeader.first().isVisible();
    }

    /** {@code true} when the desktop sidebar shows the "Рабочий проект" caption (working set). */
    public boolean desktopShowsWorkingCaption() {
        Locator caption = desktopButton().getByText(WORKING_PROJECT_TEXT);
        return caption.count() > 0 && caption.first().isVisible();
    }

    // ---- Actions ----

    /** Click the choose ("Выбрать проект") affordance (navigates to {@code /projects}). */
    public void clickChoose() {
        chooseAffordance().first().click();
    }

    /** Click the change ("Сменить проект") affordance (navigates to {@code /projects}). */
    public void clickChange() {
        changeAffordance().first().click();
    }

    /**
     * Click the surface that shows the working project's name to navigate to its workspace. Prefers
     * the desktop sidebar surface ({@code <aside>}), falling back to the mobile chip
     * ({@code <header>}) when the sidebar surface is not present at the current breakpoint.
     */
    public void clickWorkingProject(String name) {
        Locator inAside = desktopButton().getByText(name);
        if (inAside.count() > 0 && inAside.first().isVisible()) {
            inAside.first().click();
            return;
        }
        mobileChip().getByText(name).first().click();
    }
}
