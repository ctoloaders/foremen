package com.foremen.config.cache;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = CacheConfig.class)
@DisplayName("CacheConfig")
class CacheConfigTest {

    @Autowired
    private CacheManager cacheManager;

    @Test
    @DisplayName("CacheManager bean is created and is instance of CaffeineCacheManager")
    void cacheManagerBeanIsCaffeineCacheManager() {
        assertThat(cacheManager).isNotNull();
        assertThat(cacheManager).isInstanceOf(CaffeineCacheManager.class);
    }

    @Nested
    @SpringBootTest(classes = CacheConfig.class)
    @TestPropertySource(properties = "foremen.cache.spec=maximumSize=100")
    @DisplayName("with overridden cache spec property")
    class WithOverriddenCacheSpec {

        @Autowired
        private CacheManager cacheManager;

        @Test
        @DisplayName("CacheManager uses overridden spec without error")
        void cacheManagerUsesOverriddenSpec() {
            assertThat(cacheManager).isNotNull();
            assertThat(cacheManager).isInstanceOf(CaffeineCacheManager.class);
        }
    }
}
