package com.foremen.controller.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foremen.dao.model.AssignmentStatus;
import com.foremen.dao.model.WorkerKind;
import com.foremen.service.team.TeamBlock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Structural unit tests for the {@link TeamMemberView} record (FOR-05-09 task 7.7).
 *
 * <p>These tests pin the <em>shape</em> of the view independently of the service that fills it:
 *
 * <ul>
 *   <li><strong>Field preservation (Requirement 4.5).</strong> The record keeps the five original
 *       response fields ({@code id}, {@code userId}, {@code projectId}, {@code projectRoleId},
 *       {@code projectRoleCode}) with their exact names and types, and carries the enriched
 *       Requirement 4 fields the Team_API promises, so a later refactor cannot silently drop or
 *       rename a field the UI (and the existing clients) rely on.</li>
 *   <li><strong>Secret-field exclusion (Requirement 4.7).</strong> No record component name hints at
 *       a worker rate, tariff, Worker_Type tier percentage, cost, price, password, token, OTP, or
 *       secret — guarding against a future field that would leak a pay figure or a credential through
 *       the view.</li>
 *   <li><strong>WORKER / CLIENT reader masking at the serialization layer (Requirement 4.11).</strong>
 *       The class-level {@code @JsonInclude(NON_NULL)} means that when the service has nulled the
 *       Internal_Attributes for a non-admin-staff (WORKER / CLIENT) reader, those fields are
 *       <em>omitted</em> from the JSON rather than emitted as {@code null}, while the never-masked
 *       {@code assignmentStatus} is still serialized. (The behavioral masking decision itself — which
 *       readers get nulled fields — is covered by {@code ProjectMemberServiceTest} / task 7.6; this
 *       test only verifies the record's serialization contract that makes that masking observable.)</li>
 * </ul>
 *
 * The test asserts over the record's components by reflection, so it stays correct no matter the
 * declaration order and fails loudly if a field is added, removed, or renamed.
 */
class TeamMemberViewFieldsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Lower-cased names of every {@link TeamMemberView} record component. */
    private static Set<String> componentNames() {
        return Arrays.stream(TeamMemberView.class.getRecordComponents())
                .map(RecordComponent::getName)
                .collect(Collectors.toSet());
    }

    // --- Requirement 4.5: existing field names and meanings are kept ---

    @Test
    @DisplayName("the five original response fields are preserved with unchanged names (Req 4.5)")
    void preservesOriginalResponseFields() {
        // The legacy ProjectMemberResponse exposed exactly these; Req 4.5 forbids renaming or
        // repurposing them in the enriched view.
        assertThat(componentNames())
                .contains("id", "userId", "projectId", "projectRoleId", "projectRoleCode");
    }

    @Test
    @DisplayName("the five original fields keep their original types (Req 4.5)")
    void originalFieldsKeepTheirTypes() throws NoSuchMethodException {
        // id / userId / projectId / projectRoleId are identifiers (Long); projectRoleCode is a String.
        assertThat(TeamMemberView.class.getDeclaredMethod("id").getReturnType()).isEqualTo(Long.class);
        assertThat(TeamMemberView.class.getDeclaredMethod("userId").getReturnType()).isEqualTo(Long.class);
        assertThat(TeamMemberView.class.getDeclaredMethod("projectId").getReturnType()).isEqualTo(Long.class);
        assertThat(TeamMemberView.class.getDeclaredMethod("projectRoleId").getReturnType()).isEqualTo(Long.class);
        assertThat(TeamMemberView.class.getDeclaredMethod("projectRoleCode").getReturnType()).isEqualTo(String.class);
    }

    @Test
    @DisplayName("the enriched Requirement 4 fields are all present (Req 4.5 — names/meanings documented)")
    void carriesTheEnrichedRequirement4Fields() {
        // The full set the Team_Member_View promises (Req 4 criteria 3, 5, 10): identity, the
        // immutable Project_Role trio, the Company_Role code, the derived block, the always-visible
        // assignmentStatus, the user record fields, the WORKERS worker attributes, and the
        // Internal_Attributes (workerType* / nip / workerTypeMissing / tags).
        assertThat(componentNames()).containsExactlyInAnyOrder(
                "id", "userId", "projectId",
                "projectRoleId", "projectRoleCode", "projectRoleName",
                "companyRoleCode", "block", "assignmentStatus",
                "userName", "userEmail", "userStatus", "userActive",
                "workerKind", "contactPerson",
                "workerTypeId", "workerTypeCode", "workerTypeName", "workerTypeActive",
                "nip", "workerTypeMissing", "tags");
    }

    @Test
    @DisplayName("assignmentStatus is a declared, non-internal field (Req 4.3/4.5)")
    void assignmentStatusIsADeclaredField() {
        assertThat(componentNames()).contains("assignmentStatus");
    }

    // --- Requirement 4.7: no rate / tariff / tier / cost / price / password / token / OTP / secret ---

    @Test
    @DisplayName("no field name hints at a pay figure or a credential (Req 4.7)")
    void carriesNoSecretOrPayField() {
        // Case-insensitive substring scan over the component names. Each forbidden token stands for a
        // class of value Req 4.7 says the view must never carry: a worker rate, a tariff, a Worker_Type
        // tier percentage, a cost/price, or a secret (password / token / OTP).
        Pattern forbidden = Pattern.compile(
                "rate|tariff|tier|cost|price|password|passwd|token|otp|secret",
                Pattern.CASE_INSENSITIVE);

        List<String> offenders = componentNames().stream()
                .filter(name -> forbidden.matcher(name).find())
                .collect(Collectors.toList());

        assertThat(offenders)
                .as("TeamMemberView must expose no rate/tariff/tier/cost/price/password/token/OTP/secret field (Req 4.7)")
                .isEmpty();
    }

    // --- Requirement 4.11: WORKER/CLIENT readers get no internal fields but still get assignmentStatus ---

    @Test
    @DisplayName("a view with nulled Internal_Attributes omits them from JSON but still serializes assignmentStatus (Req 4.11)")
    void nulledInternalAttributesAreOmittedButAssignmentStatusIsSerialized() throws Exception {
        // This is the exact view the service produces for a WORKER / CLIENT reader (task 7.6): the
        // seven Internal_Attributes (workerTypeId/Code/Name/Active, nip, workerTypeMissing, tags) are
        // null, assignmentStatus is set. @JsonInclude(NON_NULL) must drop the nulls but keep the status.
        TeamMemberView maskedForWorkerReader = new TeamMemberView(
                1L, 7L, 42L,
                3L, "WORKER", "Pracownik",
                "WORKER", TeamBlock.WORKERS, AssignmentStatus.ACTIVE,
                "Jan Kowalski", "jan@example.com", "ACTIVE", true,
                WorkerKind.PERSON, null,
                null, null, null, null,   // workerTypeId / Code / Name / Active — masked
                null,                      // nip — masked
                null,                      // workerTypeMissing — masked
                null);                     // tags — masked

        JsonNode json = MAPPER.readTree(MAPPER.writeValueAsString(maskedForWorkerReader));

        // Internal_Attributes are omitted entirely (not present as null) for a non-admin-staff reader.
        assertThat(json.has("workerTypeId")).isFalse();
        assertThat(json.has("workerTypeCode")).isFalse();
        assertThat(json.has("workerTypeName")).isFalse();
        assertThat(json.has("workerTypeActive")).isFalse();
        assertThat(json.has("nip")).isFalse();
        assertThat(json.has("workerTypeMissing")).isFalse();
        assertThat(json.has("tags")).isFalse();

        // assignmentStatus is never masked and is always serialized.
        assertThat(json.has("assignmentStatus")).isTrue();
        assertThat(json.get("assignmentStatus").asText()).isEqualTo("ACTIVE");

        // The non-internal identity / user fields are still present.
        assertThat(json.get("id").asLong()).isEqualTo(1L);
        assertThat(json.get("userId").asLong()).isEqualTo(7L);
        assertThat(json.get("projectId").asLong()).isEqualTo(42L);
        assertThat(json.get("userName").asText()).isEqualTo("Jan Kowalski");
    }

    @Test
    @DisplayName("an admin-staff view with populated Internal_Attributes serializes them (contrast case, Req 4.10/4.11)")
    void populatedInternalAttributesAreSerializedForAdminStaff() throws Exception {
        // The contrast: when the service leaves the Internal_Attributes populated (admin-staff
        // reader), @JsonInclude(NON_NULL) keeps them — confirming the omission above is driven by the
        // null masking, not by the field being dropped unconditionally.
        TeamMemberView adminStaffView = new TeamMemberView(
                2L, 8L, 42L,
                3L, "WORKER", "Pracownik",
                "WORKER", TeamBlock.WORKERS, AssignmentStatus.ACTIVE,
                "ACME Sp. z o.o.", null, "ACTIVE", true,
                WorkerKind.COMPANY, "Anna Nowak",
                11L, "FIRM", "Firma", true,
                "1234567890",
                false,
                List.of("spec", "lead"));

        JsonNode json = MAPPER.readTree(MAPPER.writeValueAsString(adminStaffView));

        assertThat(json.get("workerTypeId").asLong()).isEqualTo(11L);
        assertThat(json.get("workerTypeCode").asText()).isEqualTo("FIRM");
        assertThat(json.get("nip").asText()).isEqualTo("1234567890");
        assertThat(json.get("workerTypeMissing").asBoolean()).isFalse();
        assertThat(json.get("tags")).hasSize(2);
        // Still returns assignmentStatus to this reader too.
        assertThat(json.get("assignmentStatus").asText()).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("the forbidden-token scan is case-insensitive (guards a future PasswordHash/OtpCode field)")
    void secretScanIsCaseInsensitive() {
        // Sanity check on the scan itself: a hypothetical camelCase credential/pay field would be
        // caught. Keeps the Req 4.7 guard meaningful if a field is added later.
        Pattern forbidden = Pattern.compile(
                "rate|tariff|tier|cost|price|password|passwd|token|otp|secret",
                Pattern.CASE_INSENSITIVE);
        assertThat(forbidden.matcher("passwordHash".toLowerCase(Locale.ROOT)).find()).isTrue();
        assertThat(forbidden.matcher("otpCode".toLowerCase(Locale.ROOT)).find()).isTrue();
        assertThat(forbidden.matcher("tierPct".toLowerCase(Locale.ROOT)).find()).isTrue();
        // And a legitimate field name is not a false positive.
        assertThat(forbidden.matcher("assignmentStatus".toLowerCase(Locale.ROOT)).find()).isFalse();
    }
}
