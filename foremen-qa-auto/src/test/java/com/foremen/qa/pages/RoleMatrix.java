package com.foremen.qa.pages;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.WaitForSelectorState;

/**
 * Page object for the access/permission matrix on the Roles page ({@code RoleMatrix},
 * Requirement 7.2).
 *
 * <p>The frontend {@code PermissionMatrix} renders a grid inside a horizontally-scrollable
 * container: a {@code <table>} whose header row's first {@code <th>} is the fixed literal
 * {@code "Role"} followed by one {@code <th>} per resource, and whose body rows start with a role
 * name and then a cell per resource. Each resource cell holds toggleable operation controls
 * ({@code OperationCell}) rendered as buttons with a locale-independent {@code aria-label} of the
 * form {@code "<OP> active|inactive"} and an {@code aria-pressed} state (e.g. {@code "READ active"}).
 *
 * <p>These operation-cell buttons are the durable signal that the resource × operation matrix has
 * rendered, so this page object keys its readiness/visibility off them rather than off localized
 * text.
 */
public final class RoleMatrix {

    private final Page page;

    public RoleMatrix(Page page) {
        this.page = page;
    }

    /** The fixed {@code "Role"} header cell that anchors the matrix table. */
    private Locator roleHeaderCell() {
        return page.locator("th", new Page.LocatorOptions().setHasText("Role"));
    }

    /** All operation-toggle controls in the matrix (buttons carrying an {@code aria-pressed} state). */
    public Locator operationControls() {
        return page.locator("table button[aria-pressed]");
    }

    /** Wait until the matrix grid has rendered (its "Role" header and operation controls appear). */
    public RoleMatrix waitUntilVisible() {
        roleHeaderCell().first().waitFor(
                new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE));
        operationControls().first().waitFor(
                new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE));
        return this;
    }

    /**
     * {@code true} when the access matrix (resource × operation controls) is visible: the fixed
     * "Role" header column plus at least one operation-toggle control are present.
     */
    public boolean isVisible() {
        return roleHeaderCell().count() > 0
                && operationControls().count() > 0
                && operationControls().first().isVisible();
    }

    /** The number of resource columns (header cells beyond the leading "Role" column). */
    public int resourceColumnCount() {
        int total = page.locator("table thead th").count();
        return Math.max(0, total - 1);
    }

    /** The number of role rows rendered in the matrix body. */
    public int roleRowCount() {
        return page.locator("table tbody tr").count();
    }
}
