package com.foremen.service.image;

import com.foremen.config.image.ImageStorageProperties;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;
import net.jqwik.api.constraints.WithNull;
import org.mockito.Mockito;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based test for finishing-material photo CDN URL resolution reuse
 * (FOR-04-18, Property 3 — "Photo CDN URL is a null-safe round-trip of the stored object key").
 *
 * <p>FOR-04-18 does NOT reimplement image storage; the finishing-material photo consumes the shared
 * FOR-04-17 {@link GcsImageStorage} seam verbatim. This test therefore exercises that same shared
 * service exactly as {@code ImageUrlResolutionPropertyTest} does, but pins the behaviour the
 * finishing-material vertical relies on:
 * <ul>
 *   <li>{@link GcsImageStorage#toCdnUrl(String)} is a pure read-time round-trip of the stored
 *       object key against the configured CDN base ({@code cdnBase + "/" + objectKey}).</li>
 *   <li>a null object key resolves to a null URL (null-safe).</li>
 *   <li>an object key produced by a finishing-material upload (entity kind / namespace
 *       {@code finishing-materials}) begins with the {@code finishing-materials/} namespace
 *       prefix.</li>
 * </ul>
 *
 * <p>The GCS {@link Storage} client the production code normally builds in a {@code @PostConstruct}
 * is injected into the private {@code storage} field via {@link ReflectionTestUtils} so no real
 * upload happens (the bucket is mocked).
 *
 * <p>Feature: FOR-04-18-finishing-materials, Property 3
 *
 * <p><b>Validates: Requirements 2.8, 4.2, 4.3, 4.4</b>
 */
@Tag("Feature: FOR-04-18-finishing-materials, Property 3: Photo CDN URL is a null-safe round-trip of the stored object key")
class FinishingMaterialPhotoUrlResolutionPropertyTest {

    /** Allowed image content types → the canonical file extension the store path produces. */
    private static final String[][] CONTENT_TYPES = {
            {"image/png", "png"},
            {"image/jpeg", "jpg"},
            {"image/webp", "webp"},
    };

    /** The entity-kind namespace finishing-material uploads pass to the shared endpoint. */
    private static final String FINISHING_NAMESPACE = "finishing-materials";

    // ------------------------------------------------------------------------------------------
    // Property 3a: toCdnUrl is a round-trip of the object key against the configured CDN base.
    // Validates: Requirements 4.4, 2.8
    // ------------------------------------------------------------------------------------------

    @Property(tries = 200)
    @Tag("Feature: FOR-04-18-finishing-materials, Property 3: Photo CDN URL is a null-safe round-trip of the stored object key")
    void toCdnUrlIsRoundTripOfObjectKey(@ForAll("cdnBases") String cdnBase,
                                        @ForAll("objectKeys") String objectKey) {
        GcsImageStorage storage = storageWith(cdnBase, Mockito.mock(Storage.class));

        String url = storage.toCdnUrl(objectKey);

        assertThat(url).isEqualTo(cdnBase + "/" + objectKey);
    }

    // ------------------------------------------------------------------------------------------
    // Property 3b: a null object key resolves to a null URL (null-safe).
    // Validates: Requirement 4.4
    // ------------------------------------------------------------------------------------------

    @Property(tries = 200)
    @Tag("Feature: FOR-04-18-finishing-materials, Property 3: Photo CDN URL is a null-safe round-trip of the stored object key")
    void nullObjectKeyResolvesToNullUrl(@ForAll("cdnBases") String cdnBase) {
        GcsImageStorage storage = storageWith(cdnBase, Mockito.mock(Storage.class));

        assertThat(storage.toCdnUrl(null)).isNull();
    }

    // ------------------------------------------------------------------------------------------
    // Property 3c: a finishing-material upload object key begins with the "finishing-materials/"
    //              namespace prefix, and the resolved URL is a round-trip of that returned key
    //              (mocked bucket — no real upload happens).
    // Validates: Requirements 4.2, 4.3, 4.4
    // ------------------------------------------------------------------------------------------

    @Property(tries = 200)
    @Tag("Feature: FOR-04-18-finishing-materials, Property 3: Photo CDN URL is a null-safe round-trip of the stored object key")
    void finishingUploadKeyBeginsWithNamespaceAndRoundTripsThroughCdn(
            @ForAll("cdnBases") String cdnBase,
            @ForAll("contentTypeIndexes") int contentTypeIndex,
            @ForAll @WithNull String bucketIgnored) {
        String contentType = CONTENT_TYPES[contentTypeIndex][0];
        String extension = CONTENT_TYPES[contentTypeIndex][1];

        Storage gcs = Mockito.mock(Storage.class);
        GcsImageStorage storage = storageWith(cdnBase, gcs);

        MockMultipartFile file = new MockMultipartFile(
                "file", "upload." + extension, contentType,
                "not-a-real-image".getBytes(StandardCharsets.UTF_8));

        String objectKey = storage.store(file, FINISHING_NAMESPACE);

        // Requirement 4.2/4.3: the finishing-material upload key is namespaced under
        // "finishing-materials/{uuid}.{ext}" and it is a bucket object key, not a full URL.
        assertThat(objectKey)
                .startsWith(FINISHING_NAMESPACE + "/")
                .endsWith("." + extension)
                .doesNotContain("://");
        // The uuid segment is non-empty between the prefix and the extension.
        assertThat(objectKey.length())
                .isGreaterThan(FINISHING_NAMESPACE.length() + 1 + extension.length() + 1);

        // No real upload: the mock's create(...) was invoked with the namespaced blob, not the disk.
        Mockito.verify(gcs).create(Mockito.any(BlobInfo.class), Mockito.any(byte[].class));

        // Requirement 4.4: the resolved CDN URL is a round-trip of the returned object key.
        assertThat(storage.toCdnUrl(objectKey)).isEqualTo(cdnBase + "/" + objectKey);
    }

    // ------------------------------------------------------------------------------------------
    // Fixture — a GcsImageStorage whose private storage field is the supplied mock.
    // ------------------------------------------------------------------------------------------

    private GcsImageStorage storageWith(String cdnBase, Storage gcs) {
        ImageStorageProperties properties = new ImageStorageProperties(
                "test-bucket", cdnBase, null, "5MB", null);
        // referenceLookup is unused by toCdnUrl/store; a mock keeps construction total.
        ImageReferenceLookup referenceLookup = Mockito.mock(ImageReferenceLookup.class);
        GcsImageStorage storage = new GcsImageStorage(properties, referenceLookup);
        // Inject the mocked GCS client the production code would otherwise build in @PostConstruct.
        ReflectionTestUtils.setField(storage, "storage", gcs);
        return storage;
    }

    // ------------------------------------------------------------------------------------------
    // Generators
    // ------------------------------------------------------------------------------------------

    /** CDN base URLs without a trailing slash (the resolver adds the single separator). */
    @Provide
    Arbitrary<String> cdnBases() {
        return Arbitraries.of(
                "https://cdn.example.com",
                "https://cdn.example.com/images",
                "http://localhost:8080/media",
                "https://storage.googleapis.com/foremen-bucket");
    }

    /**
     * Non-blank object keys: finishing-material namespaced keys plus arbitrary bucket-relative keys,
     * so the round-trip holds for any stored key regardless of shape.
     */
    @Provide
    Arbitrary<String> objectKeys() {
        Arbitrary<String> finishing = Arbitraries.strings()
                .withCharRange('a', 'z')
                .numeric()
                .ofMinLength(1).ofMaxLength(24)
                .map(name -> FINISHING_NAMESPACE + "/" + name + ".png");
        Arbitrary<String> freeform = Arbitraries.strings()
                .withCharRange('a', 'z')
                .numeric()
                .withChars('/', '-', '.', '_')
                .ofMinLength(1).ofMaxLength(48);
        return Arbitraries.oneOf(finishing, freeform);
    }

    @Provide
    Arbitrary<Integer> contentTypeIndexes() {
        return Arbitraries.integers().between(0, CONTENT_TYPES.length - 1);
    }
}
