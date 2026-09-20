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
 * Property-based tests for {@link GcsImageStorage} URL resolution and key namespacing
 * (FOR-04-17, Property 3 — "Image CDN URL is a null-safe round-trip of the stored object key").
 *
 * <p>{@link GcsImageStorage#toCdnUrl(String)} is a pure, read-time function
 * ({@code cdnBase + "/" + objectKey}, null key → null URL) and is exercised directly over generated
 * object keys and CDN bases. {@link GcsImageStorage#store} is exercised with a mocked
 * {@link Storage} client (so no real upload happens) to assert that the returned object key begins
 * with the requested per-entity-kind namespace prefix. The GCS {@code Storage} client, which the
 * production code normally builds in a {@code @PostConstruct}, is injected into the private
 * {@code storage} field via {@link ReflectionTestUtils} — the least-invasive way to supply a mock
 * without refactoring the production class.
 *
 * <p>Feature: FOR-04-17-construction-materials, Property 3
 *
 * <p><b>Validates: Requirements 4.7, 7.3, 7.4, 7.5, 8.4</b>
 */
@Tag("Feature: FOR-04-17-construction-materials, Property 3: Image CDN URL is a null-safe round-trip of the stored object key")
class ImageUrlResolutionPropertyTest {

    /** Allowed image content types → the canonical file extension the store path produces. */
    private static final String[][] CONTENT_TYPES = {
            {"image/png", "png"},
            {"image/jpeg", "jpg"},
            {"image/webp", "webp"},
    };

    /** Per-entity-kind key namespaces used by callers (Requirement 7.5, 8.4). */
    private static final String[] NAMESPACES = {"producers", "construction-materials"};

    // ------------------------------------------------------------------------------------------
    // Property 3a: toCdnUrl is a round-trip of the object key against the configured CDN base.
    // Validates: Requirements 4.7, 7.3, 7.4
    // ------------------------------------------------------------------------------------------

    @Property(tries = 200)
    @Tag("Feature: FOR-04-17-construction-materials, Property 3: Image CDN URL is a null-safe round-trip of the stored object key")
    void toCdnUrlIsRoundTripOfObjectKey(@ForAll("cdnBases") String cdnBase,
                                        @ForAll("objectKeys") String objectKey) {
        GcsImageStorage storage = storageWith(cdnBase, Mockito.mock(Storage.class));

        String url = storage.toCdnUrl(objectKey);

        assertThat(url).isEqualTo(cdnBase + "/" + objectKey);
    }

    // ------------------------------------------------------------------------------------------
    // Property 3b: a null object key resolves to a null URL (null-safe).
    // Validates: Requirement 7.4
    // ------------------------------------------------------------------------------------------

    @Property(tries = 200)
    @Tag("Feature: FOR-04-17-construction-materials, Property 3: Image CDN URL is a null-safe round-trip of the stored object key")
    void nullObjectKeyResolvesToNullUrl(@ForAll("cdnBases") String cdnBase) {
        GcsImageStorage storage = storageWith(cdnBase, Mockito.mock(Storage.class));

        assertThat(storage.toCdnUrl(null)).isNull();
    }

    // ------------------------------------------------------------------------------------------
    // Property 3c: store() returns an object key beginning with the requested namespace prefix,
    //              and the resolved URL is a round-trip of that returned key (mocked bucket — no
    //              real upload happens).
    // Validates: Requirements 7.3, 7.5, 8.4
    // ------------------------------------------------------------------------------------------

    @Property(tries = 200)
    @Tag("Feature: FOR-04-17-construction-materials, Property 3: Image CDN URL is a null-safe round-trip of the stored object key")
    void storedKeyBeginsWithNamespaceAndRoundTripsThroughCdn(
            @ForAll("cdnBases") String cdnBase,
            @ForAll("namespaces") String namespace,
            @ForAll("contentTypeIndexes") int contentTypeIndex,
            @ForAll @WithNull String bucketIgnored) {
        String contentType = CONTENT_TYPES[contentTypeIndex][0];
        String extension = CONTENT_TYPES[contentTypeIndex][1];

        Storage gcs = Mockito.mock(Storage.class);
        GcsImageStorage storage = storageWith(cdnBase, gcs);

        MockMultipartFile file = new MockMultipartFile(
                "file", "upload." + extension, contentType,
                "not-a-real-image".getBytes(StandardCharsets.UTF_8));

        String objectKey = storage.store(file, namespace);

        // Requirement 7.5/8.4: key is namespaced per entity kind: "{namespace}/{uuid}.{ext}".
        assertThat(objectKey)
                .startsWith(namespace + "/")
                .endsWith("." + extension);
        // The uuid segment is non-empty between the prefix and the extension.
        assertThat(objectKey.length()).isGreaterThan(namespace.length() + 1 + extension.length() + 1);

        // No real upload: the mock's create(...) was invoked with the namespaced blob, not the disk.
        Mockito.verify(gcs).create(Mockito.any(BlobInfo.class), Mockito.any(byte[].class));

        // Requirement 7.3/7.4: the resolved CDN URL is a round-trip of the returned object key.
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
        Arbitrary<String> hosts = Arbitraries.of(
                "https://cdn.example.com",
                "https://cdn.example.com/images",
                "http://localhost:8080/media",
                "https://storage.googleapis.com/foremen-bucket");
        return hosts;
    }

    /** Non-blank object keys spanning both namespaces plus arbitrary bucket-relative keys. */
    @Provide
    Arbitrary<String> objectKeys() {
        Arbitrary<String> namespaced = Arbitraries.of(NAMESPACES)
                .flatMap(ns -> Arbitraries.strings()
                        .withCharRange('a', 'z')
                        .numeric()
                        .ofMinLength(1).ofMaxLength(24)
                        .map(name -> ns + "/" + name + ".png"));
        Arbitrary<String> freeform = Arbitraries.strings()
                .withCharRange('a', 'z')
                .numeric()
                .withChars('/', '-', '.', '_')
                .ofMinLength(1).ofMaxLength(48);
        return Arbitraries.oneOf(namespaced, freeform);
    }

    @Provide
    Arbitrary<String> namespaces() {
        return Arbitraries.of(NAMESPACES);
    }

    @Provide
    Arbitrary<Integer> contentTypeIndexes() {
        return Arbitraries.integers().between(0, CONTENT_TYPES.length - 1);
    }
}
