package com.foremen.service.signing.merge;

import java.util.List;

import com.foremen.dao.model.CompanyProfileEntity;
import com.foremen.dao.model.ProjectEntity;
import com.foremen.dao.model.RoomEntity;
import com.foremen.dao.model.SignableDocumentEntity;
import com.foremen.dao.model.UserEntity;

/**
 * FOR-05-08 (Requirements 3.3, 3.8, 14.4; design §Components {@code TemplateMergeEngine +
 * MergeFieldResolver}): the aggregated, read-only domain snapshot a
 * {@link com.foremen.service.signing.merge.TemplateMergeEngine} and its
 * {@link MergeFieldResolver}s read to substitute a template's {@code {Token}} placeholders.
 *
 * <p>It carries the resolved domain objects named in the Requirement 3.3 token map:
 *
 * <ul>
 *   <li>{@link #document} — the {@code SignableDocument} being generated (its id / creation date
 *       drive the {@code DocumentMetaResolver});</li>
 *   <li>{@link #project} — the owning project (dates, address, area → schedule / property);</li>
 *   <li>{@link #clientMembers} — the project {@code CLIENT} member(s), the {@code Zamawiający}
 *       (name / contacts / address → {@code ClientResolver}). The <b>first</b> entry is the primary
 *       client used by single-value client tokens; an empty list ⇒ those tokens are unresolved;</li>
 *   <li>{@link #approvedOfferNetTotal} — the approved offer's net total ({@code OfferTotalsResolver},
 *       {@code {TotalBeforeTax}}), already resolved to the net amount; {@code null} ⇒ unresolved;</li>
 *   <li>{@link #rooms} — the project rooms (usable area → {@code PropertyResolver});</li>
 *   <li>{@link #representative} — the executor representative, the project {@code MANAGER} /
 *       {@code FOREMAN} ({@code RepresentativeResolver}); {@code null} ⇒ unresolved;</li>
 *   <li>{@link #companyProfile} — the executor ({@code Wykonawca}) requisites
 *       ({@code CompanyRequisitesResolver}); {@code null} ⇒ its tokens are unresolved.</li>
 * </ul>
 *
 * <p>The context is a plain carrier assembled by the service layer (task 5.2 / the signing service);
 * it performs no persistence and holds only the already-resolved domain objects. Every field is
 * nullable (or an empty list) so a <b>partial</b> context yields unresolved placeholders rather than
 * a silent empty substitution — the resolvers return {@code null} for any datum they cannot find,
 * which the engine reports as an unresolved placeholder (Requirement 3.4, Property 6).
 *
 * <p>Instances are immutable; use {@link #builder()} to assemble one.
 *
 * @param document              the document being generated; must not be {@code null}
 * @param project              the owning project, or {@code null}
 * @param clientMembers        the project CLIENT members (first = primary); never {@code null}, may be empty
 * @param approvedOfferNetTotal the approved offer net total as a display string, or {@code null}
 * @param rooms                the project rooms; never {@code null}, may be empty
 * @param representative       the executor representative (MANAGER/FOREMAN), or {@code null}
 * @param companyProfile       the executor requisites, or {@code null}
 */
public record MergeContext(
        SignableDocumentEntity document,
        ProjectEntity project,
        List<UserEntity> clientMembers,
        String approvedOfferNetTotal,
        List<RoomEntity> rooms,
        UserEntity representative,
        CompanyProfileEntity companyProfile) {

    public MergeContext {
        if (document == null) {
            throw new IllegalArgumentException("document must not be null");
        }
        // Normalise the collections so resolvers never guard against null lists.
        clientMembers = clientMembers == null ? List.of() : List.copyOf(clientMembers);
        rooms = rooms == null ? List.of() : List.copyOf(rooms);
    }

    /**
     * The primary CLIENT member (the first of {@link #clientMembers}), or {@code null} when the
     * project has no CLIENT member. Single-value client tokens ({@code {ContactName}} etc.) resolve
     * against this member.
     *
     * @return the primary client, or {@code null} if none
     */
    public UserEntity primaryClient() {
        return clientMembers.isEmpty() ? null : clientMembers.get(0);
    }

    /**
     * @return a new {@link Builder} for assembling a {@link MergeContext}
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * A small fluent builder for {@link MergeContext}; all setters are optional except
     * {@link #document(SignableDocumentEntity)} (the only required field).
     */
    public static final class Builder {
        private SignableDocumentEntity document;
        private ProjectEntity project;
        private List<UserEntity> clientMembers = List.of();
        private String approvedOfferNetTotal;
        private List<RoomEntity> rooms = List.of();
        private UserEntity representative;
        private CompanyProfileEntity companyProfile;

        private Builder() {
        }

        public Builder document(SignableDocumentEntity document) {
            this.document = document;
            return this;
        }

        public Builder project(ProjectEntity project) {
            this.project = project;
            return this;
        }

        public Builder clientMembers(List<UserEntity> clientMembers) {
            this.clientMembers = clientMembers == null ? List.of() : clientMembers;
            return this;
        }

        public Builder approvedOfferNetTotal(String approvedOfferNetTotal) {
            this.approvedOfferNetTotal = approvedOfferNetTotal;
            return this;
        }

        public Builder rooms(List<RoomEntity> rooms) {
            this.rooms = rooms == null ? List.of() : rooms;
            return this;
        }

        public Builder representative(UserEntity representative) {
            this.representative = representative;
            return this;
        }

        public Builder companyProfile(CompanyProfileEntity companyProfile) {
            this.companyProfile = companyProfile;
            return this;
        }

        public MergeContext build() {
            return new MergeContext(
                    document,
                    project,
                    clientMembers,
                    approvedOfferNetTotal,
                    rooms,
                    representative,
                    companyProfile);
        }
    }
}
