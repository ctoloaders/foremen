package com.foremen.qa.steps;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

import com.foremen.qa.fixtures.OtpFixture;
import com.foremen.qa.fixtures.TestUser;
import com.foremen.qa.fixtures.TestUserFixture;
import com.foremen.qa.pages.AppShell;
import com.foremen.qa.pages.EstimateTab;
import com.foremen.qa.pages.LoginPage;
import com.foremen.qa.pages.NotificationBellUi;
import com.foremen.qa.pages.OfferTab;
import com.foremen.qa.pages.OtpLoginPage;
import com.foremen.qa.support.ApiHelper;
import com.foremen.qa.support.DataGen;
import com.foremen.qa.support.Hooks;
import com.foremen.qa.support.NotificationApiHelper;
import com.foremen.qa.support.TestConfig;
import com.foremen.qa.support.World;
import com.microsoft.playwright.Page;

import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;

/**
 * FOR-QA-AUTO-05 detailed steps for the FOR-05-07 offer prepare/approval E2E slice.
 *
 * <p>Drives the full offer chain through the browser: the seeded ADMIN (executor) applies a package
 * and the cheapest materials on the estimate, prepares the offer, and sends it; a run-created ACTIVE
 * CLIENT then signs in through the real {@code /auth/otp} UI (the code is read from {@code otp_tokens}
 * by {@link OtpFixture}, because real email delivery is impossible on the docker stack) and
 * approves / rejects / negotiates. Setup (client user, project with the client as a member, one room)
 * is done over HTTP as the seeded ADMIN via {@link World#api()}, and every created resource is
 * registered for LIFO teardown. Mirrors {@link WorkspaceSteps}' lazy-page-object + {@code adminApi()}
 * conventions; waits live in the page objects, assertions use AssertJ.
 */
public class OfferApprovalSteps {

    /** World key under which the run-created client user is stored. */
    private static final String CLIENT_KEY = "client";

    /** World key under which the run-created manager user is stored. */
    private static final String MANAGER_KEY = "manager";

    /** Frontend auth-store localStorage key holding the current access token (auth-store.ts). */
    private static final String ACCESS_TOKEN_KEY = "foremen-access-token";

    /**
     * The raw i18n key the backend stamps on a manager-proposal notification. The bell renders this
     * via {@code t(notification.type)}, so the UI must show the localized title below, never this key.
     */
    private static final String MANAGER_PROPOSAL_KEY = "notification.offer.manager.proposal";

    /**
     * The RU-localized title of {@code notification.offer.manager.proposal} (QA pins the UI locale to
     * RU). This is the exact text the bell must render for a manager-proposal notification.
     */
    private static final String MANAGER_PROPOSAL_TITLE_RU = "Менеджер прислал предложение";

    private final World world;
    private final TestUserFixture userFixture = new TestUserFixture();
    private final OtpFixture otpFixture = new OtpFixture();

    // Remembered run-created handles for the current scenario.
    private String clientEmail;
    private Long projectId;

    // Number of FINISHING package lines un-chosen (cleared back to Placeholder) via the CellReport
    // eraser in the current scenario (TC-07-07). Used to assert the offer tab later shows at least
    // this many unchosen positions.
    private int unchosenFinishingCount;

    // Placeholder finishing line ids the client fills during TC-07-08, remembered across steps so a
    // later Then can re-open the offer tab and assert each became a chosen product. The "offered
    // package" pick and the "another package" pick target two DISTINCT placeholder lines.
    private Long clientChoiceLineFromOfferedPackage;
    private Long clientChoiceLineFromAnotherPackage;

    // Access tokens captured from the browser localStorage right after each UI sign-in, so the
    // notification Then-steps can read each actor's own (recipient-scoped) inbox over HTTP without a
    // re-login. Set in theClientSignsInViaOtp() / the manager sign-in; read mid-scenario BEFORE any
    // teardown (notifications.recipient_id is ON DELETE CASCADE, so they vanish once a user is torn
    // down — assertions must run while the recipients still exist).
    private String clientAccessToken;
    private String managerAccessToken;

    // Lazily-built page objects over the active page.
    private EstimateTab estimateTab;
    private OfferTab offerTab;
    private OtpLoginPage otpLoginPage;
    private NotificationBellUi notificationBell;

    // Lazily-created notification read client (per acting-user bearer); closed via World teardown.
    private NotificationApiHelper notificationApi;

    /** Cucumber (picocontainer) injects the per-scenario {@link World}. */
    public OfferApprovalSteps(World world) {
        this.world = world;
    }

    // ---- lazily-built page objects ----

    private Page page() {
        return Hooks.currentPage();
    }

    private EstimateTab estimateTab() {
        if (estimateTab == null) {
            estimateTab = new EstimateTab(page());
        }
        return estimateTab;
    }

    private OfferTab offerTab() {
        if (offerTab == null) {
            offerTab = new OfferTab(page());
        }
        return offerTab;
    }

    private OtpLoginPage otpLoginPage() {
        if (otpLoginPage == null) {
            otpLoginPage = new OtpLoginPage(page());
        }
        return otpLoginPage;
    }

    private NotificationBellUi notificationBell() {
        if (notificationBell == null) {
            notificationBell = new NotificationBellUi(page());
        }
        return notificationBell;
    }

    private ApiHelper adminApi() {
        ApiHelper api = world.api();
        if (api.accessToken() == null) {
            api.loginAdmin();
        }
        return api;
    }

    /**
     * The per-acting-user notification read client, created on first use and registered for teardown
     * so its Playwright context is disposed after the scenario (mirrors how {@code World} closes the
     * {@link ApiHelper}).
     */
    private NotificationApiHelper notificationApi() {
        if (notificationApi == null) {
            notificationApi = NotificationApiHelper.create();
            world.registerTeardown(() -> {
                if (notificationApi != null) {
                    notificationApi.close();
                    notificationApi = null;
                }
            });
        }
        return notificationApi;
    }

    /** Read the current browser session's access token from the frontend auth-store localStorage. */
    private String currentBrowserAccessToken() {
        Object value = page().evaluate(
                "() => localStorage.getItem('" + ACCESS_TOKEN_KEY + "')");
        return value == null ? null : value.toString();
    }

    // ================= Setup (Given) =================

    /** Create a run-unique ACTIVE CLIENT user via the DB fixture; store it and register teardown. */
    @Given("an active client user exists")
    public void anActiveClientUserExists() {
        TestUser client = userFixture.createActiveUser("CLIENT");
        world.putTestUser(CLIENT_KEY, client);
        this.clientEmail = client.email();
        // The fixture cleanup removes the user AND its otp_tokens (by email) FK-safely.
        world.registerTeardown(() -> userFixture.deleteUserAndResources(client));
    }

    /**
     * Create a run-unique ACTIVE MANAGER user via the DB fixture; store it and register teardown. A
     * MANAGER is an employee, so it logs in by PASSWORD (both via the UI LoginPage and via
     * {@link ApiHelper#login(String, String)}). This manager becomes the project's MANAGER member so
     * the notification emitter can resolve it as the client→manager recipient.
     */
    @Given("an active manager user exists")
    public void anActiveManagerUserExists() {
        TestUser manager = userFixture.createActiveUser("MANAGER");
        world.putTestUser(MANAGER_KEY, manager);
        world.registerTeardown(() -> userFixture.deleteUserAndResources(manager));
    }

    /**
     * Create a project owning the client as a member plus one simple room, via the seeded-admin API.
     * The CLIENT project-role id is resolved at run time; the project is created with the client in
     * its {@code members[]}, and a minimal room is added. Teardown is registered LIFO (room first,
     * then project) and is idempotent even if the project delete cascades the room.
     */
    @Given("a project with the client and a simple room exists")
    public void aProjectWithTheClientAndASimpleRoomExists() {
        TestUser client = world.testUser(CLIENT_KEY);
        if (client == null) {
            throw new IllegalStateException(
                    "No client user in scope; 'an active client user exists' must run first.");
        }
        createProjectWithMembersAndRoom(List.of(memberFor(client, "CLIENT")));
    }

    /**
     * Create a project owning BOTH the client (as a CLIENT member) and the manager (as a MANAGER
     * member) plus one simple room. The MANAGER member is the delta from
     * {@link #aProjectWithTheClientAndASimpleRoomExists()}: the notification emitter resolves the
     * client→manager recipient as the first project member whose project role is {@code MANAGER}
     * ({@code ProjectMemberDao.findByProjectId}), so without a MANAGER member no
     * {@code notification.offer.discount.request} is ever produced. Shares the project/room creation
     * (and LIFO teardown) with the client-only Given.
     */
    @Given("a project with the client and the manager and a simple room exists")
    public void aProjectWithTheClientAndTheManagerAndASimpleRoomExists() {
        TestUser client = world.testUser(CLIENT_KEY);
        if (client == null) {
            throw new IllegalStateException(
                    "No client user in scope; 'an active client user exists' must run first.");
        }
        TestUser manager = world.testUser(MANAGER_KEY);
        if (manager == null) {
            throw new IllegalStateException(
                    "No manager user in scope; 'an active manager user exists' must run first.");
        }
        createProjectWithMembersAndRoom(List.of(
                memberFor(client, "CLIENT"),
                memberFor(manager, "MANAGER")));
    }

    /**
     * Resolve the project-role id for {@code roleCode} and build the {@code CreateProjectRequest}
     * member entry {@code {userId, projectRoleId}} for {@code user}.
     */
    private Map<String, Object> memberFor(TestUser user, String roleCode) {
        long projectRoleId = adminApi().resolveRoleIdByCode(roleCode);
        Map<String, Object> member = new LinkedHashMap<>();
        member.put("userId", user.id());
        member.put("projectRoleId", projectRoleId);
        return member;
    }

    /**
     * Shared project + single-room creation used by both the client-only and the client+manager
     * Givens. Creates the project with the supplied members, adds one minimal room, stores the
     * project id, and registers idempotent LIFO teardown (room first, then project).
     */
    private void createProjectWithMembersAndRoom(List<Map<String, Object>> members) {
        ApiHelper api = adminApi();

        // Create the project with the given members (CreateProjectRequest.members[] =
        // [{userId, projectRoleId}]).
        String name = DataGen.nextProjectName();
        Map<String, Object> projectBody = new LinkedHashMap<>();
        projectBody.put("name", name);
        projectBody.put("members", members);
        long createdProjectId = api.createProject(projectBody);
        this.projectId = createdProjectId;
        world.registerTeardown(() -> {
            ApiHelper t = world.api();
            t.loginAdmin();
            try {
                t.deleteProject(createdProjectId);
            } catch (RuntimeException ignored) {
                // Best-effort idempotent teardown.
            }
        });

        // Create one minimal room. RoomCreateRequest requires projectId + roomTypeId; a small
        // positive geometry is supplied via manual metric fields so the estimate has a non-zero
        // volume fallback. roomTypeId is the first seeded room type (no fixed seed code assumed).
        // Final body used: {projectId, roomTypeId, label, ceilingHeight=2.5, floorArea=10, wallArea=25}.
        long roomTypeId = api.resolveFirstRowId("/api/room-types");
        Map<String, Object> roomBody = new LinkedHashMap<>();
        roomBody.put("projectId", createdProjectId);
        roomBody.put("roomTypeId", roomTypeId);
        roomBody.put("label", "QA Room " + name);
        roomBody.put("ceilingHeight", 2.5);
        roomBody.put("floorArea", 10);
        roomBody.put("wallArea", 25);
        long createdRoomId = api.createRoom(roomBody);
        world.registerTeardown(() -> {
            ApiHelper t = world.api();
            t.loginAdmin();
            try {
                t.deleteRoom(createdRoomId);
            } catch (RuntimeException ignored) {
                // Best-effort idempotent teardown (project delete may already have cascaded it).
            }
        });
    }

    /**
     * Grant the shared CLIENT system role {@code OFFERS:UPDATE} via the roles admin API (not a seed
     * changeset) so the client can commit a finishing choose-concrete / package selection — the
     * endpoints are {@code @RequiresPermission(OFFERS, UPDATE)} and the CLIENT role ships only
     * {@code OFFERS {READ, APPROVE}}, so without this grant the client gets 403 (Requirement 5.2).
     *
     * <p>The grant is idempotent (GET+merge+PUT preserving every other grant). Because it mutates a
     * SHARED system role for the whole stack, a teardown is registered to REVOKE the same operation
     * again (best-effort), restoring the CLIENT role to its original {@code OFFERS {READ, APPROVE}} so
     * sibling scenarios (which rely on the client having only READ+APPROVE) are unaffected. Granting
     * UPDATE is safe: send/withdraw stay executor-gated (OfferStatusMachine), propose/reject stay
     * role-gated (NegotiationService), and chooseFinishingConcrete remains Placeholder/non-terminal
     * guarded — so UPDATE only unblocks the client's legitimate choose-concrete / select-package.
     *
     * <p>A third grant, {@code OFFER_PACKAGES:READ}, lets the client LOAD the selectable commercial
     * packages ({@code GET /api/offer-packages}, gated resource {@code OFFER_PACKAGES}) so the
     * per-line package Select renders. The CLIENT role does not ship this grant, so without it the
     * fetch 403s, {@code packageOptions} stays empty, the Select never renders, and the "choose from
     * another package" path cannot run. Like the other two it is a read-only reference-catalog grant
     * (safe), per-role, idempotent (GET+merge+PUT), and reverted in teardown.
     */
    @Given("the client role may choose offer materials")
    public void theClientRoleMayChooseOfferMaterials() {
        // (1) OFFERS:UPDATE unblocks the choose-concrete / select-package write endpoints.
        adminApi().grantRoleOperation("CLIENT", "OFFERS", "UPDATE");
        // (2) MATERIALS_FINISHING:READ lets the client LOAD the concrete-product picker options
        // (the AsyncEntitySelect reads GET /api/finishing-materials, gated MATERIALS_FINISHING:READ).
        // Without it the picker renders its no-access state and shows no products to choose — the
        // client could never populate a Placeholder even with UPDATE. Read-only on a reference
        // catalog, so it is safe.
        adminApi().grantRoleOperation("CLIENT", "MATERIALS_FINISHING", "READ");
        // (3) OFFER_PACKAGES:READ lets the client LOAD the selectable commercial packages
        // (GET /api/offer-packages, gated OFFER_PACKAGES:READ) so the per-line package Select renders,
        // enabling the "choose from another package" path. Without it the fetch 403s, packageOptions
        // is empty, and the Select never renders. Read-only on a reference catalog, so it is safe.
        // All three grants are per-role, idempotent (GET+merge+PUT), and reverted in teardown so the
        // shared CLIENT system role is restored to its original matrix.
        adminApi().grantRoleOperation("CLIENT", "OFFER_PACKAGES", "READ");
        world.registerTeardown(() -> {
            ApiHelper t = world.api();
            t.loginAdmin();
            try {
                t.revokeRoleOperation("CLIENT", "MATERIALS_FINISHING", "READ");
            } catch (RuntimeException ignored) {
                // Best-effort idempotent restore of the shared CLIENT role matrix.
            }
            try {
                t.revokeRoleOperation("CLIENT", "OFFER_PACKAGES", "READ");
            } catch (RuntimeException ignored) {
                // Best-effort idempotent restore of the shared CLIENT role matrix.
            }
            try {
                t.revokeRoleOperation("CLIENT", "OFFERS", "UPDATE");
            } catch (RuntimeException ignored) {
                // Best-effort idempotent restore of the shared CLIENT role matrix.
            }
        });
    }

    // ================= Estimate flow (ADMIN in the browser) =================

    /** As the seeded admin, select the first package, apply it, apply cheapest, and save. */
    @When("the admin applies a package and the cheapest materials")
    public void theAdminAppliesAPackageAndTheCheapestMaterials() {
        EstimateTab tab = estimateTab();
        tab.open(requireProjectId()).waitUntilRendered();
        tab.selectFirstPackage();
        tab.clickApplyPackage();
        tab.clickApplyCheapest();
        tab.clickSave();
        tab.waitUntilSaved();
    }

    /** Assert the estimate readiness (fill indicator) reads 100%. */
    @Then("the estimate readiness is 100%")
    public void theEstimateReadinessIs100() {
        page().waitForCondition(() -> estimateTab().fillPercentText().contains("100"));
        assertThat(estimateTab().fillPercentText())
                .as("the estimate fill indicator should read 100%")
                .contains("100");
    }

    /**
     * As the admin, explicitly open estimate cells and un-choose TWO FINISHING package materials via
     * the CellReport eraser (clearing each back to a Placeholder, which stages an edit), then save.
     * Scans the assigned matrix cells, opening each report and un-choosing concrete finishing lines
     * until a global counter reaches two, then closes the report. Fails with a clear message if fewer
     * than two concrete finishing lines exist across the estimate (apply-cheapest should have made
     * every package finishing line concrete, so two are expected).
     */
    @When("the admin unchooses two finishing materials in the estimate")
    public void theAdminUnchoosesTwoFinishingMaterialsInTheEstimate() {
        final int target = 2;
        EstimateTab tab = estimateTab();
        tab.open(requireProjectId()).waitUntilRendered();

        int unchosen = 0;
        for (String cellTestId : tab.assignedCellTestIds()) {
            if (unchosen >= target) {
                break;
            }
            tab.openCell(cellTestId);
            List<String> finishingLineIds = tab.concreteFinishingUnchooseIdsInOpenReport();
            for (String lineId : finishingLineIds) {
                if (unchosen >= target) {
                    break;
                }
                tab.unchooseLineInOpenReport(lineId);
                unchosen++;
            }
            tab.closeCellReport();
        }

        assertThat(unchosen)
                .as("expected at least %s concrete FINISHING package lines to un-choose after "
                        + "apply-cheapest, but only un-chose %s across all assigned cells",
                        target, unchosen)
                .isGreaterThanOrEqualTo(target);
        this.unchosenFinishingCount = unchosen;

        tab.clickSave();
        tab.waitUntilSaved();
    }

    /**
     * Assert the estimate readiness (fill indicator) dropped below 100% after the finishing lines were
     * un-chosen (two placeholders now count as unfilled). Polls briefly for the recomputed indicator.
     */
    @Then("the estimate readiness is below 100%")
    public void theEstimateReadinessIsBelow100() {
        page().waitForCondition(() -> !estimateTab().fillPercentText().contains("100"));
        assertThat(estimateTab().fillPercentText())
                .as("after un-choosing two finishing materials the readiness should drop below 100%%")
                .doesNotContain("100");
    }

    /**
     * Assert the offer tab shows at least two UNCHOSEN finishing positions (Placeholder badges). The
     * offer tab renders only {@code appliedFromPackage} finishing lines; the two un-chosen package
     * lines surface here as placeholders. Polls until the count settles.
     */
    @Then("the offer shows at least two unchosen finishing positions")
    public void theOfferShowsAtLeastTwoUnchosenFinishingPositions() {
        offerTab().open(requireProjectId()).waitUntilRendered();
        page().waitForCondition(() -> offerTab().placeholderCount() >= 2);
        assertThat(offerTab().placeholderCount())
                .as("the offer tab should show at least two unchosen finishing positions "
                        + "(Placeholder); un-chose %s in the estimate", unchosenFinishingCount)
                .isGreaterThanOrEqualTo(2);
    }

    /**
     * As the admin, prepare the offer from the estimate. The estimate {@code estimate-prepare-offer}
     * action is the real entry point: it calls the prepare endpoint AND navigates to the pricing tab
     * on success (verified in EstimateTab.tsx handlePrepareOffer), so this both creates the offer and
     * lands on the offer surface — unlike a toast-only button.
     */
    @When("the admin prepares the offer")
    public void theAdminPreparesTheOffer() {
        estimateTab().clickPrepareOffer();
        // handlePrepareOffer navigates to /projects/{id}/pricing on success; wait for the offer tab.
        offerTab().waitUntilRendered();
    }

    /** Assert the offer tab (pricing surface) rendered. */
    @Then("the offer surface is rendered")
    public void theOfferSurfaceIsRendered() {
        offerTab().waitUntilRendered();
        assertThat(offerTab().isRendered())
                .as("the offer tab should render on the pricing surface")
                .isTrue();
    }

    // ================= Offer actions — ADMIN (executor) =================

    /** As the admin (executor), send the offer. */
    @When("the admin sends the offer")
    public void theAdminSendsTheOffer() {
        offerTab().open(requireProjectId()).waitUntilRendered();
        offerTab().clickSend();
    }

    /**
     * As the admin, propose a percent discount on the first open client request. Re-logs-in as the
     * seeded admin in the same page first (the client OTP login overwrote the session identity), then
     * opens the offer surface and submits the manager proposal.
     */
    @When("the admin proposes a discount of {int} percent")
    public void theAdminProposesADiscountOfPercent(int percent) {
        signInAsAdmin();
        offerTab().open(requireProjectId()).waitUntilRendered();
        int status = offerTab().managerProposeFirstOpenRound("PERCENT", String.valueOf(percent));
        assertThat(status)
                .as("the manager propose must be accepted (HTTP 2xx) so a MANAGER_PROPOSAL round is "
                        + "created for the client to respond to; got %s", status)
                .isBetween(200, 299);
    }

    /** Re-login as the seeded admin via the password form in the current page. */
    @When("the admin signs in")
    public void theAdminSignsIn() {
        signInAsAdmin();
    }

    /** Re-login as the run-created MANAGER via the password form in the current page. */
    @When("the manager signs in")
    public void theManagerSignsIn() {
        signInAsManager();
    }

    /**
     * As the MANAGER (a project member with the MANAGER project role), propose a percent discount on
     * the first open client request. The manager signs in by password first (the client OTP login
     * overwrote the session identity), then opens the offer surface and submits the manager proposal.
     * Driving the proposal as the real MANAGER (not the global ADMIN) is what makes the backend emit
     * the {@code notification.offer.manager.proposal} to the requesting client.
     */
    @When("the manager proposes a discount of {int} percent")
    public void theManagerProposesADiscountOfPercent(int percent) {
        signInAsManager();
        offerTab().open(requireProjectId()).waitUntilRendered();
        int status = offerTab().managerProposeFirstOpenRound("PERCENT", String.valueOf(percent));
        assertThat(status)
                .as("the manager propose must be accepted (HTTP 2xx) so a MANAGER_PROPOSAL round is "
                        + "created for the client to respond to; got %s", status)
                .isBetween(200, 299);
    }

    // ================= Offer actions — CLIENT (OTP) =================

    /**
     * Sign in as the client through the real {@code /auth/otp} UI. The request is async, so the code
     * is polled from {@code otp_tokens} (a few short retries) until present, then entered; the step
     * waits for navigation away from {@code /auth/otp} (successful auto-login). This overwrites the
     * auth tokens in the same page, switching the session identity to the client.
     */
    @When("the client signs in via OTP")
    public void theClientSignsInViaOtp() {
        String email = requireClientEmail();
        OtpLoginPage otp = otpLoginPage();
        otp.open();
        otp.requestCodeFor(email);
        String code = waitForOtpCode(email);
        assertThat(code)
                .as("an OTP code should be persisted in otp_tokens for %s", email)
                .isNotNull();
        otp.enterCode(code);
        // Successful verify auto-logs-in and redirects away from the OTP route.
        page().waitForURL(u -> !u.contains(OtpLoginPage.ROUTE));
        new AppShell(page()).waitUntilRendered();
        // Capture the client's access token now (recipient-scoped notification reads use it later,
        // and the token is overwritten the moment another identity signs in this same page).
        this.clientAccessToken = currentBrowserAccessToken();
    }

    /** As the client, request a discount on the first material line with a justification. */
    @When("the client requests a discount on the first material line")
    public void theClientRequestsADiscountOnTheFirstMaterialLine() {
        offerTab().open(requireProjectId()).waitUntilRendered();
        int status = offerTab().openDiscountRequestForFirstLine("QA discount please");
        assertThat(status)
                .as("the client open-discount-request must be accepted (HTTP 2xx) so an open "
                        + "DISCOUNT_REQUEST round is created; got %s", status)
                .isBetween(200, 299);
    }

    /** As the client, approve the offer. */
    @When("the client approves the offer")
    public void theClientApprovesTheOffer() {
        offerTab().open(requireProjectId()).waitUntilRendered();
        int status = offerTab().clickApprove();
        assertThat(status)
                .as("the client approve must be accepted (HTTP 2xx); got %s", status)
                .isBetween(200, 299);
    }

    /** As the client, reject the offer. */
    @When("the client rejects the offer")
    public void theClientRejectsTheOffer() {
        offerTab().open(requireProjectId()).waitUntilRendered();
        int status = offerTab().clickReject();
        assertThat(status)
                .as("the client reject must be accepted (HTTP 2xx); got %s", status)
                .isBetween(200, 299);
    }

    /**
     * As the client, pick a concrete product for the FIRST Placeholder finishing line from the
     * offer's currently-offered package and save. Asserts the {@code choose-concrete} POST returned
     * HTTP 2xx — the core proof the client (now holding OFFERS:UPDATE) is no longer 403. The chosen
     * line id is remembered so a later Then can assert it became a chosen product.
     */
    @When("the client chooses a product for a placeholder from the offered package")
    public void theClientChoosesAProductForAPlaceholderFromTheOfferedPackage() {
        offerTab().open(requireProjectId()).waitUntilRendered();
        Long lineId = offerTab().firstPlaceholderLineId();
        assertThat(lineId)
                .as("the offer tab should show at least one unchosen finishing position (Placeholder) "
                        + "for the client to fill; none found")
                .isNotNull();
        int status = offerTab().pickFirstProductAndSave(lineId);
        assertThat(status)
                .as("the client choose-concrete from the offered package must be accepted (HTTP 2xx), "
                        + "NOT 403 — this proves the OFFERS:UPDATE grant unblocked the client; got %s",
                        status)
                .isBetween(200, 299);
        this.clientChoiceLineFromOfferedPackage = lineId;
    }

    /** The offered-package placeholder the client filled is now a chosen product. */
    @Then("that placeholder becomes a chosen product")
    public void thatPlaceholderBecomesAChosenProduct() {
        long lineId = requireLine(clientChoiceLineFromOfferedPackage, "offered package");
        offerTab().open(requireProjectId()).waitUntilRendered();
        page().waitForCondition(() -> offerTab().lineIsChosen(lineId));
        assertThat(offerTab().lineIsChosen(lineId))
                .as("the finishing line %s the client filled from the offered package should now "
                        + "render as a chosen product (finishing-chosen-%s)", lineId, lineId)
                .isTrue();
    }

    /**
     * As the client, pick a concrete product for ANOTHER Placeholder finishing line, first switching
     * that line's per-line package to a DIFFERENT package (the second option in the per-line Select),
     * then saving. Targets a placeholder line distinct from the offered-package one. Asserts the
     * {@code choose-concrete} POST returned HTTP 2xx — proving the client can also pick from a
     * different package. Falls back to the first placeholder if only one exists.
     */
    @When("the client chooses a product for a placeholder from another package")
    public void theClientChoosesAProductForAPlaceholderFromAnotherPackage() {
        offerTab().open(requireProjectId()).waitUntilRendered();
        Long lineId = pickSecondPlaceholderLineId();
        assertThat(lineId)
                .as("the offer tab should show a placeholder line for the 'another package' choice")
                .isNotNull();
        // Switch this line's per-line package to a different package (second option); best-effort —
        // if no alternate package exists the picker is still driven and the 2xx assertion carries the
        // proof the client can choose.
        offerTab().selectLinePackageByIndex(lineId, 1);
        int status = offerTab().pickFirstProductAndSave(lineId);
        assertThat(status)
                .as("the client choose-concrete from another package must be accepted (HTTP 2xx), "
                        + "NOT 403; got %s", status)
                .isBetween(200, 299);
        this.clientChoiceLineFromAnotherPackage = lineId;
    }

    /** The another-package placeholder the client filled is now a chosen product too. */
    @Then("that placeholder also becomes a chosen product")
    public void thatPlaceholderAlsoBecomesAChosenProduct() {
        long lineId = requireLine(clientChoiceLineFromAnotherPackage, "another package");
        offerTab().open(requireProjectId()).waitUntilRendered();
        page().waitForCondition(() -> offerTab().lineIsChosen(lineId));
        assertThat(offerTab().lineIsChosen(lineId))
                .as("the finishing line %s the client filled from another package should now render "
                        + "as a chosen product (finishing-chosen-%s)", lineId, lineId)
                .isTrue();
    }

    /**
     * Resolve a placeholder line id for the "another package" pick that is DISTINCT from the
     * offered-package line the client already filled, falling back to the first remaining placeholder
     * (or the first placeholder overall if only one exists).
     */
    private Long pickSecondPlaceholderLineId() {
        for (Long id : offerTab().placeholderLineIds()) {
            if (!id.equals(clientChoiceLineFromOfferedPackage)) {
                return id;
            }
        }
        return offerTab().firstPlaceholderLineId();
    }

    private long requireLine(Long lineId, String which) {
        if (lineId == null) {
            throw new IllegalStateException(
                    "No " + which + " placeholder line in scope; the matching 'the client chooses a "
                            + "product ...' step must run first.");
        }
        return lineId;
    }

    /** As the client, accept the manager's proposal on the first open round. */
    @When("the client accepts the managers proposal")
    public void theClientAcceptsTheManagersProposal() {
        offerTab().open(requireProjectId()).waitUntilRendered();
        offerTab().clientAcceptFirstOpenRound();
    }

    /** As the client, decline the manager's proposal on the first open round. */
    @When("the client declines the managers proposal")
    public void theClientDeclinesTheManagersProposal() {
        offerTab().open(requireProjectId()).waitUntilRendered();
        offerTab().clientDeclineFirstOpenRound();
    }

    // ================= Assertions (Then) =================

    /** The offer reached a terminal state (approved) — the terminal hint is shown. */
    @Then("the offer is approved")
    public void theOfferIsApproved() {
        // Re-open the tab so a fresh by-project read carries the now-terminal status; the in-place
        // cache invalidation can lag behind the assertion otherwise.
        offerTab().open(requireProjectId()).waitUntilRendered();
        assertThat(offerTab().isTerminalHintShown())
                .as("an approved offer should render the terminal-state hint")
                .isTrue();
    }

    /** The offer reached a terminal state (rejected) — the terminal hint is shown. */
    @Then("the offer is rejected")
    public void theOfferIsRejected() {
        // Re-open the tab so a fresh by-project read carries the now-terminal status (see above).
        offerTab().open(requireProjectId()).waitUntilRendered();
        assertThat(offerTab().isTerminalHintShown())
                .as("a rejected offer should render the terminal-state hint")
                .isTrue();
    }

    /** A manager proposal is open for the client to respond to. */
    @Then("the negotiation shows an open managers proposal")
    public void theNegotiationShowsAnOpenManagersProposal() {
        // The preceding client OTP sign-in lands on the dashboard, so open the offer tab first (as the
        // client accept/decline steps do) before asserting the open-proposal respond control is shown.
        offerTab().open(requireProjectId()).waitUntilRendered();
        page().waitForCondition(() -> offerTab().hasOpenClientRespondRound());
        assertThat(offerTab().hasOpenClientRespondRound())
                .as("the client should see an open manager-proposal round to respond to")
                .isTrue();
    }

    /** After the client accepts, that proposal round is no longer open (resolved). */
    @Then("the managers proposal is no longer open")
    public void theManagersProposalIsNoLongerOpen() {
        page().waitForCondition(() -> !offerTab().hasOpenClientRespondRound());
        assertThat(offerTab().hasOpenClientRespondRound())
                .as("after the client responds, no open manager-proposal round should remain")
                .isFalse();
    }

    /** After the client declines, the offer stays negotiable (not terminal). */
    @Then("the offer is still negotiable")
    public void theOfferIsStillNegotiable() {
        assertThat(offerTab().terminalHint().count())
                .as("a declined proposal must not drive the offer to a terminal state")
                .isZero();
    }

    /**
     * The MANAGER received a client discount-request notification. The emitter runs best-effort
     * AFTER_COMMIT (async relative to the client's HTTP submit), so the manager's inbox is polled a
     * few times. Asserts the type list contains {@code notification.offer.discount.request} and the
     * unread count is at least one. Reads over HTTP as the MANAGER (its captured bearer).
     */
    @Then("the manager has a client discount-request notification")
    public void theManagerHasAClientDiscountRequestNotification() {
        String token = requireManagerToken();
        List<String> types = pollForType(token, "notification.offer.discount.request");
        assertThat(types)
                .as("the project MANAGER should receive a client discount-request notification "
                        + "(notification.offer.discount.request); observed types: %s", types)
                .contains("notification.offer.discount.request");
        assertThat(notificationApi().unreadCount(token))
                .as("the MANAGER should have at least one unread notification")
                .isGreaterThanOrEqualTo(1);
    }

    /**
     * The CLIENT received a manager-proposal notification. Polled for the same AFTER_COMMIT/async
     * reason. Asserts the type list contains {@code notification.offer.manager.proposal} and the
     * unread count is at least one. Reads over HTTP as the CLIENT (its captured bearer).
     */
    @Then("the client has a manager-proposal notification")
    public void theClientHasAManagerProposalNotification() {
        String token = requireClientToken();
        List<String> types = pollForType(token, "notification.offer.manager.proposal");
        assertThat(types)
                .as("the requesting CLIENT should receive a manager-proposal notification "
                        + "(notification.offer.manager.proposal); observed types: %s", types)
                .contains("notification.offer.manager.proposal");
        assertThat(notificationApi().unreadCount(token))
                .as("the CLIENT should have at least one unread notification")
                .isGreaterThanOrEqualTo(1);
    }

    // ================= Notification bell (CLIENT, UI) =================

    /**
     * As the client, open the global top-bar notification bell. The client lands on the dashboard
     * after the OTP sign-in, and the bell is rendered in the TopBar on every authenticated surface,
     * so it can be opened from there before following the deep link.
     */
    @When("the client opens the notification bell")
    public void theClientOpensTheNotificationBell() {
        notificationBell().open();
    }

    /**
     * The bell shows the manager-proposal notification: a row whose title is the RU-localized string
     * of {@code notification.offer.manager.proposal} ("Менеджер прислал предложение") — NOT the raw
     * i18n key — and the unread badge count is at least one. This is the UI-side i18n raw-key guard
     * (issue 4): no visible bell title may be a raw {@code notification.*} key.
     */
    @Then("the client sees the manager proposal notification")
    public void theClientSeesTheManagerProposalNotification() {
        NotificationBellUi bell = notificationBell();
        // The bell list is populated from the same async-emitted notification; give it a brief poll.
        page().waitForCondition(() -> bell.hasType(MANAGER_PROPOSAL_TITLE_RU));
        assertThat(bell.typesShown())
                .as("the bell should show the manager-proposal notification title; shown: %s",
                        bell.typesShown())
                .contains(MANAGER_PROPOSAL_TITLE_RU);
        // i18n raw-key guard: the bell must render the localized title, never the raw key.
        assertThat(bell.typesShown())
                .as("the bell must not show the raw i18n key for the manager proposal")
                .doesNotContain(MANAGER_PROPOSAL_KEY);
        assertThat(bell.hasRawKeyTitle())
                .as("no visible bell notification title may be a raw 'notification.*' i18n key; "
                        + "shown: %s", bell.typesShown())
                .isFalse();
        assertThat(bell.unreadBadgeCount())
                .as("the client should have at least one unread notification on the bell badge")
                .isGreaterThanOrEqualTo(1);
    }

    /**
     * As the client, follow the manager-proposal notification's deep link and confirm the browser
     * navigated to the offer/pricing tab and the offer surface rendered. The deep link is the
     * clickable row target; the frontend navigates to the offer pricing route (R13.10).
     */
    @When("the client follows the notification deep link")
    public void theClientFollowsTheNotificationDeepLink() {
        notificationBell().clickDeepLinkForType(MANAGER_PROPOSAL_TITLE_RU);
        // The deep link routes to the project's pricing (offer) tab; wait for the URL + rendered tab.
        page().waitForURL(u -> u.contains("/pricing"));
        offerTab().waitUntilRendered();
        assertThat(page().url())
                .as("following the manager-proposal deep link should navigate to the offer/pricing tab")
                .contains("/pricing");
        assertThat(offerTab().isRendered())
                .as("the offer tab should render after following the notification deep link")
                .isTrue();
    }

    // ================= Internals =================

    private void signInAsAdmin() {
        Page page = page();
        // The client OTP login left a valid session in localStorage. If we navigate straight to
        // /login the frontend auth guard redirects an already-authenticated browser away from the
        // login route, so #login-email never renders and LoginPage.open() times out. Clear the two
        // auth-token keys (the exact keys the frontend auth store reads/writes —
        // foremen-access-token / foremen-refresh-token) at the app origin first so the guard lets the
        // login form render, then sign in as the admin.
        clearClientSession(page);
        LoginPage loginPage = new LoginPage(page);
        loginPage.open();
        loginPage.login(TestConfig.adminEmail(), TestConfig.adminPassword());
        page.waitForURL(url -> !url.contains(LoginPage.ROUTE));
        new AppShell(page).waitUntilRendered();
    }

    /**
     * Sign in as the run-created MANAGER via the password form, clearing the prior (client/admin)
     * session first exactly like {@link #signInAsAdmin()} so the auth guard renders the login form.
     * Captures the manager's access token afterward for the recipient-scoped notification read.
     */
    private void signInAsManager() {
        TestUser manager = world.testUser(MANAGER_KEY);
        if (manager == null) {
            throw new IllegalStateException(
                    "No manager user in scope; 'an active manager user exists' must run first.");
        }
        Page page = page();
        clearClientSession(page);
        LoginPage loginPage = new LoginPage(page);
        loginPage.open();
        loginPage.login(manager.email(), manager.password());
        page.waitForURL(url -> !url.contains(LoginPage.ROUTE));
        new AppShell(page).waitUntilRendered();
        this.managerAccessToken = currentBrowserAccessToken();
    }

    /**
     * Poll the acting user's notification types a few times (short waits) for {@code expectedType},
     * because the emitter runs best-effort AFTER_COMMIT (asynchronous to the triggering HTTP call).
     * Returns the latest type list observed (so the caller can assert against it either way); gives
     * up after a bounded window (~5s).
     */
    private List<String> pollForType(String bearerToken, String expectedType) {
        List<String> types = List.of();
        for (int attempt = 0; attempt < 20; attempt++) {
            types = notificationApi().listTypes(bearerToken);
            if (types.contains(expectedType)) {
                return types;
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return types;
    }

    /**
     * The MANAGER's bearer token for a recipient-scoped notification read. Prefers the token captured
     * from the browser when the manager has already UI-signed-in; otherwise (the discount-request
     * assertion runs BEFORE the manager ever signs in) obtains one via a fresh API password login as
     * the manager — a MANAGER is an employee, so password login works for both the UI and the API.
     * The token is cached so a later manager sign-in simply overwrites it.
     */
    private String requireManagerToken() {
        if (managerAccessToken != null && !managerAccessToken.isBlank()) {
            return managerAccessToken;
        }
        TestUser manager = world.testUser(MANAGER_KEY);
        if (manager == null) {
            throw new IllegalStateException(
                    "No manager user in scope; 'an active manager user exists' must run first.");
        }
        // Dedicated helper so this login never disturbs the admin bearer stored on world.api().
        ApiHelper managerApi = ApiHelper.create();
        world.registerTeardown(managerApi::close);
        this.managerAccessToken = managerApi.login(manager.email(), manager.password());
        return managerAccessToken;
    }

    private String requireClientToken() {
        if (clientAccessToken == null || clientAccessToken.isBlank()) {
            throw new IllegalStateException(
                    "No client access token captured; 'the client signs in via OTP' must run first.");
        }
        return clientAccessToken;
    }

    /**
     * Clear the persisted auth tokens so the next {@code /login} navigation renders the sign-in form
     * instead of being bounced by the auth guard. Navigates to the frontend origin first so the
     * {@code localStorage} of the app's origin is in scope, then removes only the token keys.
     */
    private void clearClientSession(Page page) {
        page.navigate(TestConfig.frontendUrl() + "/login");
        page.evaluate("() => { try {"
                + " localStorage.removeItem('foremen-access-token');"
                + " localStorage.removeItem('foremen-refresh-token');"
                + " } catch (e) {} }");
    }

    /**
     * Poll {@code otp_tokens} for the freshest live code, retrying a few times with short sleeps
     * because {@code POST /api/auth/otp/request} persists the row asynchronously relative to the UI
     * submit. Returns {@code null} if no code appears within the bounded window.
     */
    private String waitForOtpCode(String email) {
        for (int attempt = 0; attempt < 20; attempt++) {
            String code = otpFixture.latestCode(email);
            if (code != null) {
                return code;
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return otpFixture.latestCode(email);
    }

    private long requireProjectId() {
        if (projectId == null) {
            throw new IllegalStateException(
                    "No project in scope; 'a project with the client and a simple room exists' must run first.");
        }
        return projectId;
    }

    private String requireClientEmail() {
        if (clientEmail == null) {
            throw new IllegalStateException(
                    "No client email in scope; 'an active client user exists' must run first.");
        }
        return clientEmail;
    }
}
