package com.foremen.service;

import com.foremen.controller.model.WorkerRecordRequest;
import com.foremen.controller.model.WorkerRecordResponse;
import com.foremen.dao.RoleDao;
import com.foremen.dao.model.ProjectMemberEntity;
import com.foremen.dao.model.RoleEntity;
import com.foremen.dao.model.UserEntity;
import com.foremen.dao.model.WorkerKind;
import com.foremen.exception.ForemenApiException;
import com.foremen.exception.FieldValidationException;
import com.foremen.service.permission.ForemenPermissionEvaluator;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * FOR-05-09 (task 14.2, Requirement 13) — the {@code Worker_Record_Flow} behind
 * {@code POST /api/users/worker}: creates one <b>uninvited</b> WORKER user (no password, cannot
 * authenticate, {@code active = true}) plus one WORKER {@code Project_Member} (Assignment_Status
 * {@code ACTIVE}, the submitted Worker_Type or an Uncategorized_Worker, normalized tags) as a single
 * atomic unit, sending <b>no</b> email (D7, D-new).
 *
 * <p>This service <em>mirrors</em> {@link ClientRegistrationService} rather than generalizing it, so
 * the FOR-03-05 client contract stays unchanged: one {@code @Transactional} method, the role fixed
 * to {@code WORKER} server-side, the user built through {@link UserService#createWorkerRecord} (the
 * uninvited, no-email create path), and the membership assigned through
 * {@link ProjectMemberService#assign}. A failure in any step rolls the whole unit back, leaving no
 * user, no membership, and no audit row (Requirement 13 criterion 12).
 *
 * <p><b>Dual-permission guard (D7).</b> Mirroring {@link WorkerInvitationService}, the
 * {@code POST /api/users/worker} handler declares the {@code PROJECTS/EDIT} half with
 * {@code @RequiresPermission} (the FOR-03-05 pattern, whitelisted for {@code UserController}),
 * enforced by the {@code PermissionInterceptor} before the controller body; this service asserts the
 * {@code PROJECT_MEMBERS/CREATE} half via {@link ForemenPermissionEvaluator} (ADMIN bypass). The full
 * guard is therefore {@code PROJECT_MEMBERS} CREATE <b>and</b> {@code PROJECTS} EDIT (Requirement 13
 * criterion 10). A caller lacking either operation receives 403 {@code error.access.denied}.
 *
 * <p><b>Canonical record-flow order (Requirement 13 criterion 11).</b> The first failing check wins
 * and no user / membership / email is created on any failure:
 * <ol>
 *   <li>step 1 (401) + step 2 (403 {@code PROJECTS} EDIT) — upstream interceptor;</li>
 *   <li>step 2 (403 {@code PROJECT_MEMBERS} CREATE) — {@link #assertProjectMembersCreate()};</li>
 *   <li>step 3 (400 field validation of Worker_Kind / names / contact person / email / phone / NIP /
 *       a non-integer Worker_Type id / tags, all offending fields reported together) —
 *       {@link #validateFields(WorkerRecordRequest)};</li>
 *   <li>step 4 (404 project existence / access) + step 5 (409 Locked_Status) —
 *       {@link ProjectMemberService#assertProjectAccessibleAndEditable(Long)};</li>
 *   <li>step 6 (409 duplicate email, only when an email is supplied) —
 *       {@link UserService#createWorkerRecord};</li>
 *   <li>step 8 (400 {@code error.project.member.worker.type.invalid} for a nonexistent / inactive
 *       Worker_Type) — the {@link ProjectMemberService#assign} team-composition step.</li>
 * </ol>
 * The project checks (steps 4–5) run <em>before</em> the user is created so a non-accessible or
 * locked project is reported before a would-be duplicate email, and the Worker_Type existence check
 * (step 8) is left to {@code assign}, so it is reported last (Requirement 13 criteria 6, 11).
 */
@Service
@RequiredArgsConstructor
@Transactional
public class WorkerRecordService {

    private static final String WORKER_ROLE_CODE = "WORKER";
    private static final String ROLE_PREFIX = "ROLE_";

    /** Max lengths (Requirement 13 criterion 2). */
    private static final int MAX_NAME = 255;
    private static final int MAX_CONTACT_PERSON = 255;
    private static final int MAX_EMAIL = 254;

    /** Minimal RFC-5322-ish syntactic email check (one '@', non-empty local + domain, a dot in the domain). */
    private static final java.util.regex.Pattern EMAIL_PATTERN =
            java.util.regex.Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private final RoleDao roleDao;
    private final UserService userService;
    private final ProjectMemberService projectMemberService;
    private final ForemenPermissionEvaluator permissionEvaluator;

    /**
     * Creates the uninvited WORKER user and its WORKER membership as one atomic unit, in the
     * canonical record-flow order (Requirement 13 criteria 1–12).
     *
     * @param request the add-worker payload
     * @return the new user id, stored email (empty when none), project id, and membership id
     * @throws ForemenApiException the first tripped check (403 / 400 / 404 / 409 as described above)
     */
    public WorkerRecordResponse register(WorkerRecordRequest request) {
        // Step 2 (second half) — PROJECT_MEMBERS CREATE, in addition to the handler's PROJECTS EDIT.
        assertProjectMembersCreate();

        // Step 3 — field validation; all offending fields reported together (Req 13.4).
        ValidatedWorker worker = validateFields(request);

        // Step 4 + 5 — project existence / access, then Locked_Status, BEFORE the user is created so a
        // non-accessible / locked project is reported before a would-be duplicate email (Req 13.9, 13.8, 13.11).
        projectMemberService.assertProjectAccessibleAndEditable(request.projectId());

        // The role is fixed to WORKER server-side (Req 13.1); any role in the request is ignored.
        RoleEntity workerRole = roleDao.findByCode(WORKER_ROLE_CODE)
                .orElseThrow(() -> new ForemenApiException(
                        HttpStatus.INTERNAL_SERVER_ERROR, "error.role.worker.missing"));

        // Step 6 — create the uninvited WORKER user (no password, no email); a supplied duplicate
        // email surfaces 409 error.user.email.already.exists here (Req 13.1, 13.7).
        UserEntity user = userService.createWorkerRecord(
                worker.name(), worker.email(), worker.phone(), workerRole,
                worker.workerKind(), worker.contactPerson(), worker.nip());

        // Step 8 — assign the WORKER membership (ACTIVE, submitted worker type or Uncategorized,
        // normalized tags). The assign re-runs steps 4/5 harmlessly and owns the Worker_Type
        // existence/active check (400 error.project.member.worker.type.invalid, Req 13.6, 14.3).
        ProjectMemberEntity membership = projectMemberService.assign(
                user.getId(), request.projectId(), null, worker.workerTypeId(), worker.tags());

        // Req 13.1 — respond with the new identity; stored email is empty (not null) when none.
        String storedEmail = user.getEmail() == null ? "" : user.getEmail();
        return new WorkerRecordResponse(user.getId(), storedEmail, request.projectId(), membership.getId());
    }

    // --- Step 2: PROJECT_MEMBERS CREATE assertion (the second half of the dual-permission guard) ---

    /**
     * Asserts the caller holds {@code PROJECT_MEMBERS} CREATE (the handler's
     * {@code @RequiresPermission(PROJECTS, EDIT)} already enforced the {@code PROJECTS} EDIT half
     * upstream). The caller's role code is read from the {@code ROLE_<code>} authority, exactly as the
     * {@code PermissionInterceptor} does, and checked through {@link ForemenPermissionEvaluator}
     * (ADMIN bypass included). A caller lacking the grant — or with no authenticated principal — is
     * rejected with 403 {@code error.access.denied} (Requirement 13 criterion 10). Mirrors
     * {@link WorkerInvitationService#invite(Long)}.
     */
    private void assertProjectMembersCreate() {
        String roleCode = currentRoleCode();
        if (roleCode == null || !permissionEvaluator.isAllowed(roleCode, "PROJECT_MEMBERS", "CREATE")) {
            throw new ForemenApiException(HttpStatus.FORBIDDEN, "error.access.denied");
        }
    }

    private static String currentRoleCode() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return null;
        }
        for (GrantedAuthority ga : auth.getAuthorities()) {
            String authority = ga.getAuthority();
            if (authority != null && authority.startsWith(ROLE_PREFIX)) {
                return authority.substring(ROLE_PREFIX.length());
            }
        }
        return null;
    }

    // --- Step 3: field validation (all offending fields reported together, Req 13.2–13.5) ---

    /**
     * Validates every field of the add-worker payload in the record-flow order, collecting all
     * offending fields into one {@link FieldValidationException} (400) when any fail (Requirement 13
     * criterion 4). Fields hidden by the selected Worker_Kind are ignored without validation
     * (Requirement 13 criterion 3). On success, returns the trimmed / normalized values the create +
     * assign path consumes.
     *
     * @throws FieldValidationException 400 listing every offending field (with the field-specific
     *         codes of criteria 5 for NIP and {@code error.project.member.tag.invalid} for tags)
     * @throws ForemenApiException the tag-list rejection re-raised as a field error
     */
    private ValidatedWorker validateFields(WorkerRecordRequest request) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();

        // Worker_Kind — exactly PERSON or COMPANY, case-sensitive (Req 13.2, 13.4).
        WorkerKind kind = null;
        String rawKind = request.workerKind();
        if ("PERSON".equals(rawKind)) {
            kind = WorkerKind.PERSON;
        } else if ("COMPANY".equals(rawKind)) {
            kind = WorkerKind.COMPANY;
        } else {
            fieldErrors.put("workerKind", "error.worker.kind.invalid");
        }

        // Name — the person or company name, 1..255 after trimming (Req 13.2, 13.4).
        String name = trimToNull(request.name());
        if (name == null || name.length() > MAX_NAME) {
            fieldErrors.put("name", "error.worker.name.invalid");
        }

        // Contact person — COMPANY only, optional, 1..255 after trimming (Req 13.2–13.4). Ignored
        // (not validated, not stored) for a PERSON (Req 13.3).
        String contactPerson = null;
        if (kind == WorkerKind.COMPANY) {
            contactPerson = trimToNull(request.contactPerson());
            if (contactPerson != null && contactPerson.length() > MAX_CONTACT_PERSON) {
                fieldErrors.put("contactPerson", "error.worker.contact.person.invalid");
            }
        }

        // NIP — COMPANY only, optional, normalized + checksum-validated (Req 13.5). Ignored for a
        // PERSON (Req 13.3).
        String nip = null;
        if (kind == WorkerKind.COMPANY) {
            String rawNip = trimToNull(request.nip());
            if (rawNip != null) {
                if (NipValidator.isValidNip(rawNip)) {
                    nip = NipValidator.normalize(rawNip);
                } else {
                    fieldErrors.put("nip", NipValidator.NIP_INVALID_MESSAGE);
                }
            }
        }

        // Email — optional; 1..254 after trimming and syntactically valid (Req 13.2, 13.4).
        String email = trimToNull(request.email());
        if (email != null && (email.length() > MAX_EMAIL || !EMAIL_PATTERN.matcher(email).matches())) {
            fieldErrors.put("email", "error.worker.email.invalid");
        }

        // Phone — optional; the FOR-05-09 phone rule (Req 13.2, 13.4).
        String phone = trimToNull(request.phone());
        if (phone != null && !isValidPhone(phone)) {
            fieldErrors.put("phone", "error.worker.phone.invalid");
        }

        // Worker_Type id — optional; a positive integer id at this step (Req 13.6 item: a non-positive
        // integer is rejected here with worker.type.invalid; existence / activity is checked later at
        // step 8 by the assign). A null id = an Uncategorized_Worker.
        Long workerTypeId = request.workerTypeId();
        if (workerTypeId != null && workerTypeId <= 0) {
            fieldErrors.put("workerTypeId", "error.project.member.worker.type.invalid");
        }

        // Tags — optional; normalized per Requirement 15 criterion 3. A violation becomes the
        // tag field error (Req 13.4 maps it to error.project.member.tag.invalid).
        List<String> tags = null;
        try {
            tags = com.foremen.util.TagNormalizer.normalize(request.tags());
        } catch (ForemenApiException e) {
            fieldErrors.put("tags", e.getMessageCode());
        }

        if (!fieldErrors.isEmpty()) {
            throw new FieldValidationException(fieldErrors);
        }

        return new ValidatedWorker(kind, name, email, phone, contactPerson, nip, workerTypeId, tags);
    }

    /**
     * The FOR-05-09 phone rule (Requirement 13 criterion 2): after trimming, 1..50 characters made
     * only of digits, spaces and {@code + - ( )}, with {@code +} allowed only as the first character,
     * and 7..15 digits total.
     */
    private static boolean isValidPhone(String phone) {
        if (phone.isEmpty() || phone.length() > 50) {
            return false;
        }
        int digits = 0;
        for (int i = 0; i < phone.length(); i++) {
            char c = phone.charAt(i);
            if (c >= '0' && c <= '9') {
                digits++;
            } else if (c == ' ' || c == '-' || c == '(' || c == ')') {
                // allowed separators
            } else if (c == '+') {
                if (i != 0) {
                    return false; // '+' only as the first character
                }
            } else {
                return false; // any other character is invalid
            }
        }
        return digits >= 7 && digits <= 15;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** The validated, trimmed / normalized worker-record values consumed by the create + assign path. */
    private record ValidatedWorker(
            WorkerKind workerKind,
            String name,
            String email,
            String phone,
            String contactPerson,
            String nip,
            Long workerTypeId,
            List<String> tags) {
    }
}
