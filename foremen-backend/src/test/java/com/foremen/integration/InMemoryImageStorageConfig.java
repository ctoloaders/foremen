package com.foremen.integration;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.web.multipart.MultipartFile;

import com.foremen.service.image.ImageStorage;

/**
 * FOR-07-04 task 8.1 — an in-memory {@link ImageStorage} for the bank-ledger integration tests, so the
 * procurement upload ({@code PAID_CASH}) and the document detail read (signed image URL) run without a
 * cloud bucket. Only the external object store is replaced; every Foremen bean stays real.
 */
@TestConfiguration(proxyBeanMethods = false)
class InMemoryImageStorageConfig {

    @Bean
    @Primary
    ImageStorage inMemoryImageStorage() {
        return new InMemoryImageStorage();
    }

    static final class InMemoryImageStorage implements ImageStorage {

        private final Map<String, byte[]> objects = new ConcurrentHashMap<>();

        @Override
        public String store(MultipartFile file, String keyNamespace) {
            return storeObject(file, keyNamespace);
        }

        @Override
        public String storeObject(MultipartFile file, String keyNamespace) {
            String key = (keyNamespace == null ? "" : keyNamespace) + UUID.randomUUID();
            try {
                objects.put(key, file.getBytes());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return key;
        }

        @Override
        public String toCdnUrl(String objectKey) {
            return objectKey == null ? null : "https://cdn.test/" + objectKey;
        }

        @Override
        public void deleteIfOrphan(String objectKey) {
            // Test storage: nothing to reclaim.
        }

        @Override
        public boolean isConfigured() {
            return true;
        }

        @Override
        public byte[] readObject(String objectKey) {
            byte[] bytes = objects.get(objectKey);
            if (bytes == null) {
                throw new IllegalArgumentException("No object " + objectKey);
            }
            return bytes;
        }

        @Override
        public String signedDownloadUrl(String objectKey, long ttlSeconds) {
            return objectKey == null ? null : "https://signed.test/" + objectKey;
        }
    }
}
