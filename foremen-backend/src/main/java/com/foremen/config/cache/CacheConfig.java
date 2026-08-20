package com.foremen.config.cache;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.CaffeineSpec;

@Configuration
@EnableCaching
public class CacheConfig {

    @Value("${foremen.cache.spec:maximumSize=500,expireAfterWrite=10m}")
    private String cacheSpec;

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager();
        manager.setCaffeine(Caffeine.from(CaffeineSpec.parse(cacheSpec)));
        return manager;
    }
}
