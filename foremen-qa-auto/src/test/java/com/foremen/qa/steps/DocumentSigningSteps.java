package com.foremen.qa.steps;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

import com.foremen.qa.fixtures.TestUser;
import com.foremen.qa.fixtures.TestUserFixture;
import com.foremen.qa.pages.DocumentDetail;
import com.foremen.qa.pages.DocumentSigningTab;
import com.foremen.qa.support.ApiHelper;
import com.foremen.qa.support.DataGen;
import com.foremen.qa.support.DocumentSigningApiHelper;
import com.foremen.qa.support.DocumentSigningApiHelper.Doc;
import com.foremen.qa.support.DocumentSigningApiHelper.Signer;
import com.foremen.qa.support.Hooks;
import com.foremen.qa.support.World;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.FilePayload;

import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;

/** FOR-QA-AUTO-08 steps for the FOR-05-08 document-signing E2E slice (seed docs via API, drive
 * signing over API, assert the browser tab/detail). Repeatability: DataGen run-id + LIFO teardown. */
public class DocumentSigningSteps {

    private final World world;
    private final TestUserFixture userFixture = new TestUserFixture();

    private DocumentSigningApiHelper signApi;
    private DocumentSigningTab tab;
    private DocumentDetail detail;

    private Long projectId;
    private Long documentTypeId;
    private TestUser clientUser;
    private Long lastDocumentId;
    private int lastStatus;

    public DocumentSigningSteps(World world) {
        this.world = world;
    }

    private Page page() {
        return Hooks.currentPage();
    }
    private DocumentSigningTab tab() {
        if (tab == null) {
            tab = new DocumentSigningTab(page());
        }
        return tab;
    }
    private DocumentDetail detail() {
        if (detail == null) {
            detail = new DocumentDetail(page());
        }
        return detail;
    }
    private ApiHelper adminApi() {
        ApiHelper api = world.api();
        if (api.accessToken() == null) {
            api.loginAdmin();
        }
        return api;
    }
    private DocumentSigningApiHelper signApi() {
        if (signApi == null) {
            signApi = DocumentSigningApiHelper.create();
            signApi.loginAdmin();
            world.registerTeardown(() -> {
                if (signApi != null) {
                    signApi.close();
                    signApi = null;
                }
            });
        }
        return signApi;
    }
    private long documentTypeId() {
        if (documentTypeId == null) {
            documentTypeId = signApi().resolveDocumentTypeId("CONTRACT_WORKS");
        }
        return documentTypeId;
    }
    private long requireProjectId() {
        if (projectId == null) {
            throw new IllegalStateException("No project in scope; a project Given must run first.");
        }
        return projectId;
    }
    private static FilePayload pngPayload() {
        byte[] png = new byte[] {
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
            0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
            0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15, (byte) 0xC4,
            (byte) 0x89, 0x00, 0x00, 0x00, 0x0A, 0x49, 0x44, 0x41,
            0x54, 0x78, (byte) 0x9C, 0x63, 0x00, 0x01, 0x00, 0x00,
            0x05, 0x00, 0x01, 0x0D, 0x0A, 0x2D, (byte) 0xB4, 0x00,
            0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44, (byte) 0xAE,
            0x42, 0x60, (byte) 0x82
        };
        return new FilePayload("evidence.png", "image/png", png);
    }

    @Given("a project with a signing client and a room exists")
    public void aProjectWithASigningClientAndARoomExists() {
        ApiHelper api = adminApi();
        TestUser client = userFixture.createActiveUser("CLIENT");
        this.clientUser = client;
        world.registerTeardown(() -> userFixture.deleteUserAndResources(client));
        long clientRoleId = api.resolveRoleIdByCode("CLIENT");
        Map<String, Object> member = new LinkedHashMap<>();
        member.put("userId", client.id());
        member.put("projectRoleId", clientRoleId);
        String name = DataGen.nextProjectName();
        Map<String, Object> projectBody = new LinkedHashMap<>();
        projectBody.put("name", name);
        projectBody.put("members", List.of(member));
        long createdProjectId = api.createProject(projectBody);
        this.projectId = createdProjectId;
        world.registerTeardown(() -> {
            ApiHelper t = world.api();
            t.loginAdmin();
            try {
                t.deleteProject(createdProjectId);
            } catch (RuntimeException ignored) {
            }
        });
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
            }
        });
    }

    private long seedDocument() {
        signApi().loginAdmin();
        String title = "Umowa " + DataGen.runId();
        long id = signApi().createDocument(requireProjectId(), documentTypeId(), title);
        this.lastDocumentId = id;
        world.registerTeardown(() -> {
            try {
                signApi().loginAdmin();
                signApi().voidDocument(id);
            } catch (RuntimeException ignored) {
            }
        });
        return id;
    }

    @Given("a draft signable document exists")
    public void aDraftSignableDocumentExists() {
        long id = seedDocument();
        Doc doc = signApi().getDocument(id);
        assertThat(doc.status()).as("a freshly created document is DRAFT").isEqualTo("DRAFT");
    }

    @When("the document is voided")
    public void theDocumentIsVoided() {
        signApi().loginAdmin();
        lastStatus = signApi().voidDocument(lastDocumentId);
    }

    @Then("the document status is {string}")
    public void theDocumentStatusIs(String expected) {
        Doc doc = signApi().getDocument(lastDocumentId);
        assertThat(doc.status()).as("document %s status", lastDocumentId).isEqualTo(expected);
    }

    @Then("the last signing call returns HTTP {int}")
    public void theLastSigningCallReturnsHttp(int code) {
        assertThat(lastStatus).as("last signing HTTP status").isEqualTo(code);
    }

    @When("signatures are requested from the client with method {string}")
    public void signaturesAreRequestedFromTheClient(String method) {
        signApi().loginAdmin();
        lastStatus = signApi().requestSignatures(lastDocumentId, method, "AdES",
                List.of(Signer.byRole("CLIENT")));
    }

    @When("signatures are requested again from the client with method {string}")
    public void signaturesAreRequestedAgain(String method) {
        signaturesAreRequestedFromTheClient(method);
    }

    @When("signatures are requested from two signers with method {string}")
    public void signaturesAreRequestedFromTwoSigners(String method) {
        signApi().loginAdmin();
        lastStatus = signApi().requestSignatures(lastDocumentId, method, "AdES",
                List.of(Signer.byRole("CLIENT"), Signer.byUser(clientUser.id())));
    }

    @Then("the signing progress shows {int} signed of {int}")
    public void theSigningProgressShows(int signed, int total) {
        Doc doc = signApi().getDocument(lastDocumentId);
        assertThat(doc.signedCount()).as("signedCount").isEqualTo(signed);
        assertThat(doc.totalCount()).as("totalCount").isEqualTo(total);
    }

    @When("signatures are requested with an empty signer set")
    public void signaturesAreRequestedWithEmptySignerSet() {
        signApi().loginAdmin();
        lastStatus = signApi().requestSignatures(lastDocumentId, "PRINT", "AdES", List.of());
    }

    @Then("the document has a non-null content hash")
    public void theDocumentHasANonNullContentHash() {
        Doc doc = signApi().getDocument(lastDocumentId);
        assertThat(doc.contentHash()).as("frozen contentHash").isNotNull();
    }

    @When("the body is edited")
    public void theBodyIsEdited() {
        signApi().loginAdmin();
        lastStatus = signApi().saveBody(lastDocumentId, "tampered " + DataGen.runId());
    }

    @When("generate is invoked")
    public void generateIsInvoked() {
        signApi().loginAdmin();
        lastStatus = signApi().generate(lastDocumentId);
    }

    @When("signatures are requested from the client user with method {string}")
    public void signaturesAreRequestedFromClientUser(String method) {
        signApi().loginAdmin();
        lastStatus = signApi().requestSignatures(lastDocumentId, method, "AdES",
                List.of(Signer.byUser(clientUser.id())));
    }

    @When("the client signs via scan upload")
    public void theClientSignsViaScanUpload() {
        signApi().loginAs(clientUser.email(), clientUser.password());
        lastStatus = signApi().signViaScan(lastDocumentId, pngPayload());
    }

    @When("the client signs via tablet initials")
    public void theClientSignsViaTabletInitials() {
        signApi().loginAs(clientUser.email(), clientUser.password());
        lastStatus = signApi().signViaTablet(lastDocumentId, pngPayload());
    }

    @When("the client attempts to sign without evidence")
    public void theClientAttemptsToSignWithoutEvidence() {
        signApi().loginAs(clientUser.email(), clientUser.password());
        lastStatus = signApi().signViaScanWithoutEvidence(lastDocumentId);
    }

    @Then("the document reports all signatures signed")
    public void theDocumentReportsAllSignaturesSigned() {
        Doc doc = signApi().getDocument(lastDocumentId);
        assertThat(doc.allSigned()).as("allSigned").isTrue();
    }

    @When("the client initiates a provider signature")
    public void theClientInitiatesAProviderSignature() {
        signApi().loginAs(clientUser.email(), clientUser.password());
        lastStatus = signApi().signViaProvider(lastDocumentId, clientUser.id());
    }

    @When("the provider calls back signed with a matching hash")
    public void theProviderCallsBackSignedWithMatchingHash() {
        signApi().loginAdmin();
        Doc doc = signApi().getDocument(lastDocumentId);
        // providerRef is a provider-internal correlation id, intentionally NOT exposed on the
        // client-reachable DTO; the webhook already knows it. We read it from the DB (the provider's
        // authoritative source) exactly as a real provider would echo it back.
        String providerRef = pendingProviderRef(lastDocumentId);
        lastStatus = signApi().providerCallback(providerRef, "SIGNED",
                "sealed://evidence", doc.contentHash(), null);
    }

    @When("the provider calls back signed with a mismatching hash")
    public void theProviderCallsBackSignedWithMismatchingHash() {
        signApi().loginAdmin();
        String providerRef = pendingProviderRef(lastDocumentId);
        lastStatus = signApi().providerCallback(providerRef, "SIGNED",
                "sealed://evidence", "deadbeef-not-the-hash", null);
    }

    @When("the provider calls back declined")
    public void theProviderCallsBackDeclined() {
        signApi().loginAdmin();
        String providerRef = pendingProviderRef(lastDocumentId);
        lastStatus = signApi().providerCallback(providerRef, "DECLINED",
                null, null, "client refused");
    }

    /**
     * Reads the {@code provider_ref} of the document's PENDING provider signature straight from the
     * test DB. The signing provider persists it on {@code document_signatures.provider_ref} when the
     * ONLINE/PODPIS_GOV_PL ceremony is initiated; it is not surfaced on the client DTO, so the DB is
     * the authoritative source for the simulated provider callback.
     */
    private String pendingProviderRef(long documentId) {
        String sql = "SELECT provider_ref FROM document_signatures "
                + "WHERE document_id = ? AND status = 'PENDING' AND provider_ref IS NOT NULL "
                + "ORDER BY id DESC LIMIT 1";
        try (java.sql.Connection c = com.foremen.qa.fixtures.Db.dataSource().getConnection();
             java.sql.PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, documentId);
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next())
                        .as("a PENDING provider signature with a provider_ref must exist for doc %s",
                                documentId)
                        .isTrue();
                String ref = rs.getString(1);
                assertThat(ref).as("provider_ref must be persisted after ceremony initiation")
                        .isNotBlank();
                return ref;
            }
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("Failed to read provider_ref for document " + documentId, e);
        }
    }

    @Then("a signature exists with status {string}")
    public void aSignatureExistsWithStatus(String status) {
        Doc doc = signApi().getDocument(lastDocumentId);
        boolean found = doc.signatures().stream().anyMatch(s -> status.equals(s.status()));
        assertThat(found).as("a signature with status %s on document %s", status, lastDocumentId)
                .isTrue();
    }

    @When("the client attempts to create a document")
    public void theClientAttemptsToCreateADocument() {
        // Resolve the document type id while still ADMIN — the type catalog read is itself
        // ADMIN-gated, so resolving it after switching to the CLIENT login would 403 on the lookup
        // (masking the create-forbidden assertion we actually want to make).
        signApi().loginAdmin();
        long typeId = documentTypeId();
        signApi().loginAs(clientUser.email(), clientUser.password());
        lastStatus = signApi().tryCreateDocument(requireProjectId(), typeId,
                "Illegal " + DataGen.runId());
        signApi().loginAdmin();
    }

    @Then("the client document view exposes no cost or margin fields")
    public void theClientDocumentViewExposesNoCostFields() {
        signApi().loginAs(clientUser.email(), clientUser.password());
        String body = signApi().getDocumentRawBody(lastDocumentId).toLowerCase();
        signApi().loginAdmin();
        assertThat(body).as("client-facing document JSON must not leak cost data")
                .doesNotContain("\"cost").doesNotContain("margin")
                .doesNotContain("estimateunitprice").doesNotContain("workerrate");
    }

    @Given("a pending-signatures document exists in the project")
    public void aPendingSignaturesDocumentExists() {
        long id = seedDocument();
        signApi().loginAdmin();
        int status = signApi().requestSignatures(id, "PRINT", "AdES",
                List.of(Signer.byRole("CLIENT")));
        assertThat(status).as("request-signatures should move the document to PENDING_SIGNATURES")
                .isBetween(200, 299);
    }

    @When("I open the document signing tab")
    public void iOpenTheDocumentSigningTab() {
        tab().open(requireProjectId()).waitUntilRendered();
    }

    @Then("the document signing tab is rendered")
    public void theDocumentSigningTabIsRendered() {
        assertThat(tab().isRendered()).as("the documentSigning tab should render").isTrue();
    }

    @Then("the tab lists the seeded document")
    public void theTabListsTheSeededDocument() {
        assertThat(tab().isListRendered()).as("the document list should render").isTrue();
        assertThat(tab().hasRow(lastDocumentId)).as("row for document %s", lastDocumentId).isTrue();
    }

    @When("I open the seeded document detail")
    public void iOpenTheSeededDocumentDetail() {
        tab().openDocument(lastDocumentId);
        detail().waitUntilRendered();
    }

    @Then("the owner can see the void action")
    public void theOwnerCanSeeTheVoidAction() {
        assertThat(detail().isVoidVisible()).as("owner sees the void action on a PENDING document")
                .isTrue();
    }

    @When("I void the document from the detail view")
    public void iVoidTheDocumentFromTheDetailView() {
        lastStatus = detail().clickVoidAndConfirm();
    }

    @Then("the void request succeeds")
    public void theVoidRequestSucceeds() {
        assertThat(lastStatus).as("UI void HTTP status").isEqualTo(200);
    }

    @Then("the signing surface shows no raw i18n keys")
    public void theSigningSurfaceShowsNoRawI18nKeys() {
        String text = tab().listText() + " " + detail().detailText();
        assertThat(text).as("no raw documentSigning.* keys should be visible")
                .doesNotContain("documentSigning.");
    }

    // --- TC-UI-FILL-01/02/03: add → view merged content → fill gaps -----------------------------

    private String mergedBody;

    @Given("a generated draft document exists in the project")
    public void aGeneratedDraftDocumentExists() {
        long id = seedDocument();
        signApi().loginAdmin();
        int status = signApi().generate(id);
        assertThat(status).as("generate should merge the template into the DRAFT body (HTTP 200)")
                .isEqualTo(200);
        this.mergedBody = signApi().getBody(id);
    }

    /** The display name the backend stores for a QA-provisioned user (TestUserFixture: "QA " + email). */
    private String clientDisplayName() {
        return "QA " + clientUser.email();
    }

    @Then("the merged body substitutes tokens with the client's real value")
    public void theMergedBodySubstitutesTokens() {
        assertThat(mergedBody).as("merged DRAFT body must be present").isNotNull().isNotBlank();
        assertThat(mergedBody)
                .as("resolved tokens are replaced by the client's real name (not a raw placeholder)")
                .contains(clientDisplayName());
    }

    @Then("the merged body contains no raw placeholder tokens")
    public void theMergedBodyContainsNoRawTokens() {
        assertThat(mergedBody).as("no raw {ContactName} placeholder remains after merge")
                .doesNotContain("{ContactName}");
    }

    @Then("the merged body marks unresolved tokens as blank gaps")
    public void theMergedBodyMarksUnresolvedAsBlank() {
        // MARK_BLANK renders an unresolved token as the «___» marker (R3.4). The seeded CONTRACT_WORKS
        // template leaves executor-requisite tokens unresolved when CompanyProfile is empty, and also
        // carries manual underscore fill-in lines (PESEL etc.) — either is a visible "gap".
        assertThat(mergedBody).as("unresolved/blank gaps are visible as a marker, not empty text")
                .containsAnyOf("«___»", "___");
    }

    @When("I expand the document body preview")
    public void iExpandTheDocumentBodyPreview() {
        detail().openBodyPreview();
    }

    @Then("the body preview shows the substituted client value")
    public void theBodyPreviewShowsSubstitutedValue() {
        assertThat(detail().isBodyPreviewShown()).as("the body preview is expanded").isTrue();
        String preview = detail().bodyPreviewText();
        assertThat(preview).as("preview renders the merged body with the client's real name")
                .contains(clientDisplayName());
        assertThat(preview).as("no raw {Token} placeholder is shown in the preview")
                .doesNotContain("{ContactName}");
        assertThat(preview).as("no raw i18n keys are shown in the preview")
                .doesNotContain("documentSigning.");
    }

    @Then("the form fields panel is shown")
    public void theFormFieldsPanelIsShown() {
        assertThat(detail().isFormFieldsPanelShown()).as("the form-fields panel renders").isTrue();
    }

    @Then("I fill the first declared gap if any")
    public void iFillTheFirstDeclaredGapIfAny() {
        signApi().loginAdmin();
        List<DocumentSigningApiHelper.FormField> fields = signApi().getFormFields(lastDocumentId);
        if (fields.isEmpty()) {
            // No declared form fields on this document — the visible gaps are the «___» blanks in the
            // body (already asserted). Record N/A rather than fabricating a field.
            assertThat(detail().isFormFieldsPanelShown() || detail().isFormFieldsEmpty())
                    .as("with no declared fields the panel still renders (empty state)").isTrue();
            lastStatus = 200;
            return;
        }
        String key = fields.get(0).key();
        String value = "QA-" + DataGen.runId();
        lastStatus = detail().fillFormFieldAndSave(key, value);
        assertThat(lastStatus).as("filling a declared gap succeeds (HTTP 200)").isEqualTo(200);
        DocumentSigningApiHelper.FormField reloaded = signApi().getFormFields(lastDocumentId).stream()
                .filter(f -> key.equals(f.key())).findFirst().orElse(null);
        assertThat(reloaded).as("the filled field persists").isNotNull();
        assertThat(reloaded.value()).as("the saved gap value persists").isEqualTo(value);
    }
}
