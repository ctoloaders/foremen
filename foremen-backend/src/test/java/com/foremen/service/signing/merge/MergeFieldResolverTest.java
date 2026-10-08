package com.foremen.service.signing.merge;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.foremen.dao.model.CompanyProfileEntity;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.RoomEntity;
import com.foremen.dao.model.SignableDocumentEntity;
import com.foremen.dao.model.UserEntity;

/**
 * Unit tests for the seven {@link MergeFieldResolver} implementations (FOR-05-08 task 5.4;
 * Requirements 3.3, 3.4).
 *
 * <p>For each resolver this exercises:
 * <ul>
 *   <li>{@link MergeFieldResolver#supports(String)} returns {@code true} for exactly its own tokens
 *       and {@code false} for tokens owned by other resolvers / unknown tokens (R3.3 per-group
 *       ownership);</li>
 *   <li>{@link MergeFieldResolver#resolve(String, MergeContext)} returns the correct value for a
 *       fully-populated {@link MergeContext} (R3.3);</li>
 *   <li>{@code resolve} returns {@code null} (⇒ unresolved placeholder) for a partial/empty context —
 *       a missing project / client / company / representative, or a single-word name whose surname
 *       cannot be derived (R3.4, null→unresolved, no silent empty substitution).</li>
 * </ul>
 *
 * The resolvers are pure stateless {@code @Component}s, so they are instantiated directly with no
 * Spring context.
 */
@DisplayName("MergeFieldResolver implementations")
class MergeFieldResolverTest {

    // --- shared fixtures -----------------------------------------------------

    private static final String UNKNOWN_TOKEN = "NoSuchToken";

    private static SignableDocumentEntity document(Long id, LocalDateTime createdDate) {
        SignableDocumentEntity doc = new SignableDocumentEntity();
        doc.setId(id);
        doc.setCreatedDate(createdDate);
        return doc;
    }

    private static SignableDocumentEntity bareDocument() {
        // A not-yet-persisted document: null id and null created date.
        return new SignableDocumentEntity();
    }

    private static ProjectEntity project() {
        ProjectEntity project = new ProjectEntity();
        project.setStartDate(LocalDate.of(2026, 1, 15));
        project.setEndDate(LocalDate.of(2026, 6, 30));
        project.setArea(new BigDecimal("72.50"));
        project.setFormattedAddress("ul. Marszałkowska 1, 00-001 Warszawa");
        project.setAddress("Marszalkowska 1");
        return project;
    }

    private static UserEntity client() {
        UserEntity user = new UserEntity();
        user.setName("Jan Kowalski");
        user.setEmail("jan.kowalski@example.com");
        user.setPhone("+48 500 100 200");
        return user;
    }

    private static UserEntity representative() {
        UserEntity user = new UserEntity();
        user.setName("Anna Nowak");
        user.setEmail("anna.nowak@foremen.pl");
        user.setPhone("+48 600 300 400");
        return user;
    }

    private static CompanyProfileEntity companyProfile() {
        CompanyProfileEntity company = new CompanyProfileEntity();
        company.setFullName("Foremen Sp. z o.o.");
        company.setRegisteredAddress("ul. Przykładowa 10, 00-002 Warszawa");
        company.setNip("1234567890");
        company.setRegon("987654321");
        company.setRepresentativeName("Piotr Zieliński");
        company.setRepresentativeRole("Prezes Zarządu");
        company.setEmail("biuro@foremen.pl");
        return company;
    }

    private static RoomEntity room(BigDecimal floorArea) {
        RoomEntity room = new RoomEntity();
        room.setFloorArea(floorArea);
        return room;
    }

    /** A fully-populated context with every domain object present. */
    private static MergeContext fullContext() {
        return MergeContext.builder()
                .document(document(42L, LocalDateTime.of(2026, 1, 10, 9, 30)))
                .project(project())
                .clientMembers(List.of(client()))
                .approvedOfferNetTotal("125000.00")
                .rooms(List.of(room(new BigDecimal("30.00")), room(new BigDecimal("42.50"))))
                .representative(representative())
                .companyProfile(companyProfile())
                .build();
    }

    /** The minimum valid context: only the (bare) document is set; every other field is empty/null. */
    private static MergeContext emptyContext() {
        return MergeContext.builder().document(bareDocument()).build();
    }

    // --- DocumentMetaResolver ------------------------------------------------

    @Nested
    @DisplayName("DocumentMetaResolver")
    class DocumentMeta {

        private final DocumentMetaResolver resolver = new DocumentMetaResolver();

        @Test
        @DisplayName("supports its own tokens and no others")
        void supports() {
            assertThat(resolver.supports(DocumentMetaResolver.TOKEN_ID)).isTrue();
            assertThat(resolver.supports(DocumentMetaResolver.TOKEN_CREATE_TIME)).isTrue();
            assertThat(resolver.supports(ClientResolver.TOKEN_CONTACT_NAME)).isFalse();
            assertThat(resolver.supports(UNKNOWN_TOKEN)).isFalse();
        }

        @Test
        @DisplayName("resolves id and creation date from a populated document")
        void resolvesPopulated() {
            MergeContext ctx = fullContext();
            assertThat(resolver.resolve(DocumentMetaResolver.TOKEN_ID, ctx)).isEqualTo("42");
            assertThat(resolver.resolve(DocumentMetaResolver.TOKEN_CREATE_TIME, ctx))
                    .isEqualTo("2026-01-10");
        }

        @Test
        @DisplayName("resolves null for a not-yet-persisted document (no id / no created date)")
        void unresolvedForBareDocument() {
            MergeContext ctx = emptyContext();
            assertThat(resolver.resolve(DocumentMetaResolver.TOKEN_ID, ctx)).isNull();
            assertThat(resolver.resolve(DocumentMetaResolver.TOKEN_CREATE_TIME, ctx)).isNull();
        }
    }

    // --- ClientResolver ------------------------------------------------------

    @Nested
    @DisplayName("ClientResolver")
    class Client {

        private final ClientResolver resolver = new ClientResolver();

        @Test
        @DisplayName("supports its own tokens and no others")
        void supports() {
            assertThat(resolver.supports(ClientResolver.TOKEN_CONTACT_NAME)).isTrue();
            assertThat(resolver.supports(ClientResolver.TOKEN_CONTACT_LAST_NAME)).isTrue();
            assertThat(resolver.supports(ClientResolver.TOKEN_CONTACT_EMAIL)).isTrue();
            assertThat(resolver.supports(ClientResolver.TOKEN_CONTACT_ADDRESS)).isTrue();
            assertThat(resolver.supports(ClientResolver.TOKEN_REQUISITE_PRIMARY_ADDRESS)).isTrue();
            assertThat(resolver.supports(ScheduleResolver.TOKEN_BEGIN_DATE)).isFalse();
            assertThat(resolver.supports(UNKNOWN_TOKEN)).isFalse();
        }

        @Test
        @DisplayName("resolves name, surname, email and address from the primary client")
        void resolvesPopulated() {
            MergeContext ctx = fullContext();
            assertThat(resolver.resolve(ClientResolver.TOKEN_CONTACT_NAME, ctx))
                    .isEqualTo("Jan Kowalski");
            assertThat(resolver.resolve(ClientResolver.TOKEN_CONTACT_LAST_NAME, ctx))
                    .isEqualTo("Kowalski");
            assertThat(resolver.resolve(ClientResolver.TOKEN_CONTACT_EMAIL, ctx))
                    .isEqualTo("jan.kowalski@example.com");
            assertThat(resolver.resolve(ClientResolver.TOKEN_CONTACT_ADDRESS, ctx))
                    .isEqualTo("ul. Marszałkowska 1, 00-001 Warszawa");
            assertThat(resolver.resolve(ClientResolver.TOKEN_REQUISITE_PRIMARY_ADDRESS, ctx))
                    .isEqualTo("ul. Marszałkowska 1, 00-001 Warszawa");
        }

        @Test
        @DisplayName("falls back to the plain project address when no formatted address is set")
        void addressFallsBackToPlain() {
            ProjectEntity project = new ProjectEntity();
            project.setAddress("Marszalkowska 1");
            MergeContext ctx = MergeContext.builder()
                    .document(bareDocument())
                    .project(project)
                    .clientMembers(List.of(client()))
                    .build();
            assertThat(resolver.resolve(ClientResolver.TOKEN_CONTACT_ADDRESS, ctx))
                    .isEqualTo("Marszalkowska 1");
        }

        @Test
        @DisplayName("resolves null for a context with no CLIENT member")
        void unresolvedWithoutClient() {
            MergeContext ctx = emptyContext();
            assertThat(resolver.resolve(ClientResolver.TOKEN_CONTACT_NAME, ctx)).isNull();
            assertThat(resolver.resolve(ClientResolver.TOKEN_CONTACT_LAST_NAME, ctx)).isNull();
            assertThat(resolver.resolve(ClientResolver.TOKEN_CONTACT_EMAIL, ctx)).isNull();
            assertThat(resolver.resolve(ClientResolver.TOKEN_CONTACT_ADDRESS, ctx)).isNull();
            assertThat(resolver.resolve(ClientResolver.TOKEN_REQUISITE_PRIMARY_ADDRESS, ctx))
                    .isNull();
        }

        @Test
        @DisplayName("resolves null last name for a single-word client name")
        void unresolvedLastNameForSingleWordName() {
            UserEntity singleWord = new UserEntity();
            singleWord.setName("Jan");
            singleWord.setEmail("jan@example.com");
            MergeContext ctx = MergeContext.builder()
                    .document(bareDocument())
                    .clientMembers(List.of(singleWord))
                    .build();
            assertThat(resolver.resolve(ClientResolver.TOKEN_CONTACT_NAME, ctx)).isEqualTo("Jan");
            assertThat(resolver.resolve(ClientResolver.TOKEN_CONTACT_LAST_NAME, ctx)).isNull();
        }
    }

    // --- ScheduleResolver ----------------------------------------------------

    @Nested
    @DisplayName("ScheduleResolver")
    class Schedule {

        private final ScheduleResolver resolver = new ScheduleResolver();

        @Test
        @DisplayName("supports its own tokens and no others")
        void supports() {
            assertThat(resolver.supports(ScheduleResolver.TOKEN_BEGIN_DATE)).isTrue();
            assertThat(resolver.supports(ScheduleResolver.TOKEN_CLOSE_DATE)).isTrue();
            assertThat(resolver.supports(OfferTotalsResolver.TOKEN_TOTAL_BEFORE_TAX)).isFalse();
            assertThat(resolver.supports(UNKNOWN_TOKEN)).isFalse();
        }

        @Test
        @DisplayName("resolves start and end dates from a populated project")
        void resolvesPopulated() {
            MergeContext ctx = fullContext();
            assertThat(resolver.resolve(ScheduleResolver.TOKEN_BEGIN_DATE, ctx))
                    .isEqualTo("2026-01-15");
            assertThat(resolver.resolve(ScheduleResolver.TOKEN_CLOSE_DATE, ctx))
                    .isEqualTo("2026-06-30");
        }

        @Test
        @DisplayName("resolves null when the project is absent")
        void unresolvedWithoutProject() {
            MergeContext ctx = emptyContext();
            assertThat(resolver.resolve(ScheduleResolver.TOKEN_BEGIN_DATE, ctx)).isNull();
            assertThat(resolver.resolve(ScheduleResolver.TOKEN_CLOSE_DATE, ctx)).isNull();
        }

        @Test
        @DisplayName("resolves null when the project has no dates set")
        void unresolvedWithoutDates() {
            MergeContext ctx = MergeContext.builder()
                    .document(bareDocument())
                    .project(new ProjectEntity())
                    .build();
            assertThat(resolver.resolve(ScheduleResolver.TOKEN_BEGIN_DATE, ctx)).isNull();
            assertThat(resolver.resolve(ScheduleResolver.TOKEN_CLOSE_DATE, ctx)).isNull();
        }
    }

    // --- OfferTotalsResolver -------------------------------------------------

    @Nested
    @DisplayName("OfferTotalsResolver")
    class OfferTotals {

        private final OfferTotalsResolver resolver = new OfferTotalsResolver();

        @Test
        @DisplayName("supports its own token and no others")
        void supports() {
            assertThat(resolver.supports(OfferTotalsResolver.TOKEN_TOTAL_BEFORE_TAX)).isTrue();
            assertThat(resolver.supports(ScheduleResolver.TOKEN_BEGIN_DATE)).isFalse();
            assertThat(resolver.supports(UNKNOWN_TOKEN)).isFalse();
        }

        @Test
        @DisplayName("resolves the pre-resolved approved offer net total")
        void resolvesPopulated() {
            MergeContext ctx = fullContext();
            assertThat(resolver.resolve(OfferTotalsResolver.TOKEN_TOTAL_BEFORE_TAX, ctx))
                    .isEqualTo("125000.00");
        }

        @Test
        @DisplayName("resolves null when no approved offer total is present")
        void unresolvedWithoutTotal() {
            MergeContext ctx = emptyContext();
            assertThat(resolver.resolve(OfferTotalsResolver.TOKEN_TOTAL_BEFORE_TAX, ctx)).isNull();
        }

        @Test
        @DisplayName("resolves null for a blank total")
        void unresolvedForBlankTotal() {
            MergeContext ctx = MergeContext.builder()
                    .document(bareDocument())
                    .approvedOfferNetTotal("   ")
                    .build();
            assertThat(resolver.resolve(OfferTotalsResolver.TOKEN_TOTAL_BEFORE_TAX, ctx)).isNull();
        }
    }

    // --- CompanyRequisitesResolver -------------------------------------------

    @Nested
    @DisplayName("CompanyRequisitesResolver")
    class CompanyRequisites {

        private final CompanyRequisitesResolver resolver = new CompanyRequisitesResolver();

        @Test
        @DisplayName("supports its own tokens and no others")
        void supports() {
            assertThat(resolver.supports(CompanyRequisitesResolver.TOKEN_NAME)).isTrue();
            assertThat(resolver.supports(CompanyRequisitesResolver.TOKEN_ADDRESS)).isTrue();
            assertThat(resolver.supports(CompanyRequisitesResolver.TOKEN_NIP)).isTrue();
            assertThat(resolver.supports(CompanyRequisitesResolver.TOKEN_REGON)).isTrue();
            assertThat(resolver.supports(CompanyRequisitesResolver.TOKEN_REPRESENTATIVE_NAME))
                    .isTrue();
            assertThat(resolver.supports(CompanyRequisitesResolver.TOKEN_REPRESENTATIVE_ROLE))
                    .isTrue();
            assertThat(resolver.supports(CompanyRequisitesResolver.TOKEN_EMAIL)).isTrue();
            // Not the representative resolver's same-prefix-family tokens, nor unknowns.
            assertThat(resolver.supports(RepresentativeResolver.TOKEN_EMAIL)).isFalse();
            assertThat(resolver.supports(UNKNOWN_TOKEN)).isFalse();
        }

        @Test
        @DisplayName("resolves every requisite from a populated company profile")
        void resolvesPopulated() {
            MergeContext ctx = fullContext();
            assertThat(resolver.resolve(CompanyRequisitesResolver.TOKEN_NAME, ctx))
                    .isEqualTo("Foremen Sp. z o.o.");
            assertThat(resolver.resolve(CompanyRequisitesResolver.TOKEN_ADDRESS, ctx))
                    .isEqualTo("ul. Przykładowa 10, 00-002 Warszawa");
            assertThat(resolver.resolve(CompanyRequisitesResolver.TOKEN_NIP, ctx))
                    .isEqualTo("1234567890");
            assertThat(resolver.resolve(CompanyRequisitesResolver.TOKEN_REGON, ctx))
                    .isEqualTo("987654321");
            assertThat(resolver.resolve(CompanyRequisitesResolver.TOKEN_REPRESENTATIVE_NAME, ctx))
                    .isEqualTo("Piotr Zieliński");
            assertThat(resolver.resolve(CompanyRequisitesResolver.TOKEN_REPRESENTATIVE_ROLE, ctx))
                    .isEqualTo("Prezes Zarządu");
            assertThat(resolver.resolve(CompanyRequisitesResolver.TOKEN_EMAIL, ctx))
                    .isEqualTo("biuro@foremen.pl");
        }

        @Test
        @DisplayName("resolves null for every token when the company profile is absent")
        void unresolvedWithoutCompany() {
            MergeContext ctx = emptyContext();
            assertThat(resolver.resolve(CompanyRequisitesResolver.TOKEN_NAME, ctx)).isNull();
            assertThat(resolver.resolve(CompanyRequisitesResolver.TOKEN_ADDRESS, ctx)).isNull();
            assertThat(resolver.resolve(CompanyRequisitesResolver.TOKEN_NIP, ctx)).isNull();
            assertThat(resolver.resolve(CompanyRequisitesResolver.TOKEN_REGON, ctx)).isNull();
            assertThat(resolver.resolve(CompanyRequisitesResolver.TOKEN_REPRESENTATIVE_NAME, ctx))
                    .isNull();
            assertThat(resolver.resolve(CompanyRequisitesResolver.TOKEN_REPRESENTATIVE_ROLE, ctx))
                    .isNull();
            assertThat(resolver.resolve(CompanyRequisitesResolver.TOKEN_EMAIL, ctx)).isNull();
        }

        @Test
        @DisplayName("resolves null for optional requisites that are left blank")
        void unresolvedForBlankOptionalFields() {
            CompanyProfileEntity partial = new CompanyProfileEntity();
            partial.setFullName("Foremen Sp. z o.o.");
            partial.setRegisteredAddress("ul. Przykładowa 10");
            partial.setNip("1234567890");
            // regon / representative name+role / email left null.
            MergeContext ctx = MergeContext.builder()
                    .document(bareDocument())
                    .companyProfile(partial)
                    .build();
            assertThat(resolver.resolve(CompanyRequisitesResolver.TOKEN_NAME, ctx))
                    .isEqualTo("Foremen Sp. z o.o.");
            assertThat(resolver.resolve(CompanyRequisitesResolver.TOKEN_REGON, ctx)).isNull();
            assertThat(resolver.resolve(CompanyRequisitesResolver.TOKEN_REPRESENTATIVE_NAME, ctx))
                    .isNull();
            assertThat(resolver.resolve(CompanyRequisitesResolver.TOKEN_EMAIL, ctx)).isNull();
        }
    }

    // --- PropertyResolver ----------------------------------------------------

    @Nested
    @DisplayName("PropertyResolver")
    class Property {

        private final PropertyResolver resolver = new PropertyResolver();

        @Test
        @DisplayName("supports its own tokens and no others")
        void supports() {
            assertThat(resolver.supports(PropertyResolver.TOKEN_WORKS_ADDRESS)).isTrue();
            assertThat(resolver.supports(PropertyResolver.TOKEN_USABLE_AREA)).isTrue();
            assertThat(resolver.supports(ClientResolver.TOKEN_CONTACT_ADDRESS)).isFalse();
            assertThat(resolver.supports(UNKNOWN_TOKEN)).isFalse();
        }

        @Test
        @DisplayName("resolves works address and uses project.area for usable area")
        void resolvesPopulated() {
            MergeContext ctx = fullContext();
            assertThat(resolver.resolve(PropertyResolver.TOKEN_WORKS_ADDRESS, ctx))
                    .isEqualTo("ul. Marszałkowska 1, 00-001 Warszawa");
            assertThat(resolver.resolve(PropertyResolver.TOKEN_USABLE_AREA, ctx))
                    .isEqualTo("72.50");
        }

        @Test
        @DisplayName("falls back to the summed room floor areas when project.area is absent")
        void usableAreaFallsBackToRooms() {
            ProjectEntity project = new ProjectEntity();
            project.setAddress("Marszalkowska 1");
            // No area on the project → fall back to rooms (30.00 + 42.50 = 72.50).
            MergeContext ctx = MergeContext.builder()
                    .document(bareDocument())
                    .project(project)
                    .rooms(List.of(room(new BigDecimal("30.00")), room(new BigDecimal("42.50"))))
                    .build();
            assertThat(resolver.resolve(PropertyResolver.TOKEN_USABLE_AREA, ctx))
                    .isEqualTo("72.50");
        }

        @Test
        @DisplayName("resolves null works address when the project is absent")
        void unresolvedWorksAddressWithoutProject() {
            MergeContext ctx = emptyContext();
            assertThat(resolver.resolve(PropertyResolver.TOKEN_WORKS_ADDRESS, ctx)).isNull();
        }

        @Test
        @DisplayName("resolves null usable area with no project area and no room floor areas")
        void unresolvedUsableAreaWithoutAreaOrRooms() {
            MergeContext ctx = emptyContext();
            assertThat(resolver.resolve(PropertyResolver.TOKEN_USABLE_AREA, ctx)).isNull();

            // A project with no area and rooms that carry no floor area → still unresolved.
            MergeContext roomsWithoutArea = MergeContext.builder()
                    .document(bareDocument())
                    .project(new ProjectEntity())
                    .rooms(List.of(room(null)))
                    .build();
            assertThat(resolver.resolve(PropertyResolver.TOKEN_USABLE_AREA, roomsWithoutArea))
                    .isNull();
        }
    }

    // --- RepresentativeResolver ----------------------------------------------

    @Nested
    @DisplayName("RepresentativeResolver")
    class Representative {

        private final RepresentativeResolver resolver = new RepresentativeResolver();

        @Test
        @DisplayName("supports its own tokens and no others")
        void supports() {
            assertThat(resolver.supports(RepresentativeResolver.TOKEN_ASSIGNED_NAME)).isTrue();
            assertThat(resolver.supports(RepresentativeResolver.TOKEN_ASSIGNED_LAST_NAME)).isTrue();
            assertThat(resolver.supports(RepresentativeResolver.TOKEN_EMAIL)).isTrue();
            assertThat(resolver.supports(RepresentativeResolver.TOKEN_PHONE)).isTrue();
            // Not the company resolver's same-prefix-family email token, nor unknowns.
            assertThat(resolver.supports(CompanyRequisitesResolver.TOKEN_EMAIL)).isFalse();
            assertThat(resolver.supports(UNKNOWN_TOKEN)).isFalse();
        }

        @Test
        @DisplayName("resolves name, surname, email and phone from the representative")
        void resolvesPopulated() {
            MergeContext ctx = fullContext();
            assertThat(resolver.resolve(RepresentativeResolver.TOKEN_ASSIGNED_NAME, ctx))
                    .isEqualTo("Anna Nowak");
            assertThat(resolver.resolve(RepresentativeResolver.TOKEN_ASSIGNED_LAST_NAME, ctx))
                    .isEqualTo("Nowak");
            assertThat(resolver.resolve(RepresentativeResolver.TOKEN_EMAIL, ctx))
                    .isEqualTo("anna.nowak@foremen.pl");
            assertThat(resolver.resolve(RepresentativeResolver.TOKEN_PHONE, ctx))
                    .isEqualTo("+48 600 300 400");
        }

        @Test
        @DisplayName("resolves null for every token when the representative is absent")
        void unresolvedWithoutRepresentative() {
            MergeContext ctx = emptyContext();
            assertThat(resolver.resolve(RepresentativeResolver.TOKEN_ASSIGNED_NAME, ctx)).isNull();
            assertThat(resolver.resolve(RepresentativeResolver.TOKEN_ASSIGNED_LAST_NAME, ctx))
                    .isNull();
            assertThat(resolver.resolve(RepresentativeResolver.TOKEN_EMAIL, ctx)).isNull();
            assertThat(resolver.resolve(RepresentativeResolver.TOKEN_PHONE, ctx)).isNull();
        }

        @Test
        @DisplayName("resolves null last name for a single-word representative name")
        void unresolvedLastNameForSingleWordName() {
            UserEntity singleWord = new UserEntity();
            singleWord.setName("Anna");
            singleWord.setEmail("anna@foremen.pl");
            MergeContext ctx = MergeContext.builder()
                    .document(bareDocument())
                    .representative(singleWord)
                    .build();
            assertThat(resolver.resolve(RepresentativeResolver.TOKEN_ASSIGNED_NAME, ctx))
                    .isEqualTo("Anna");
            assertThat(resolver.resolve(RepresentativeResolver.TOKEN_ASSIGNED_LAST_NAME, ctx))
                    .isNull();
        }
    }
}
