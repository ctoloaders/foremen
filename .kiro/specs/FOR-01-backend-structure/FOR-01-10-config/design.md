# Design Document — FOR-01-10-config

## Architecture Overview

Три конфигурационных класса Spring Boot, каждый в своём подпакете `com.foremen.config.*`, следуя паттерну проекта (один класс — один подпакет):

```
com.foremen.config.security.SecurityConfig   — SecurityFilterChain (permitAll заглушка)
com.foremen.config.cache.CacheConfig         — CaffeineCacheManager с @Value-параметрами
com.foremen.config.web.CorsConfig            — WebMvcConfigurer с CORS-маппингами
```

Все три класса не имеют зависимостей друг от друга и загружаются Spring Boot через component scan.

---

## Components

### 1. SecurityConfig

**Пакет:** `com.foremen.config.security`

```java
package com.foremen.config.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .authorizeHttpRequests(auth -> auth
                        .anyRequest().permitAll()
                )
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                )
                .headers(headers -> headers
                        .frameOptions(frame -> frame.disable())
                )
                .build();
    }
}
```

**Назначение:** Заглушка безопасности для этапа разработки. Открывает все endpoints, отключает CSRF (API stateless), убирает X-Frame-Options (для H2 Console/Swagger UI), устанавливает stateless-сессии.

---

### 2. CacheConfig

**Пакет:** `com.foremen.config.cache`

```java
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
```

**Назначение:** Единый CacheManager для всех `@Cacheable`-методов. Параметры кеша конфигурируемы через `foremen.cache.spec` (Caffeine spec string формат).

---

### 3. CorsConfig

**Пакет:** `com.foremen.config.web`

```java
package com.foremen.config.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

@Configuration
public class CorsConfig implements WebMvcConfigurer {

    @Value("${foremen.cors.allowed-origins:http://localhost:3000}")
    private List<String> allowedOrigins;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOrigins(allowedOrigins.toArray(String[]::new))
                .allowedMethods("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
    }
}
```

**Назначение:** CORS-политика для фронтенда. Origins конфигурируемы через `foremen.cors.allowed-origins` (comma-separated list через Spring `@Value` to `List<String>` binding).

---

## application.yml Additions

Добавить в конец существующего `application.yml`:

```yaml
foremen:
  cache:
    spec: "maximumSize=500,expireAfterWrite=10m"
  cors:
    allowed-origins: "http://localhost:3000"
```

---

## Data Models

Нет новых data models. Все три класса — pure configuration, не оперируют доменными объектами.

---

## Error Handling

| Сценарий | Поведение |
|----------|-----------|
| Невалидный `foremen.cache.spec` | `IllegalArgumentException` при старте приложения (fail-fast) |
| Пустой `foremen.cors.allowed-origins` | Spring Boot не стартует с ошибкой binding |
| SecurityFilterChain conflict | Spring Boot выбирает единственный bean; при появлении второго — `@Order` или `@ConditionalOnMissingBean` в будущем |

---

## Test Strategy

### Unit Tests (JUnit 5 + Spring Boot Test)

1. **SecurityConfigTest** — `@WebMvcTest` срез:
   - GET/POST/PUT/DELETE на произвольный путь без credentials → 200 (не 401/403)
   - POST без CSRF token → не 403
   - Ответ не содержит `X-Frame-Options` header
   - Ответ не содержит `Set-Cookie` (stateless)

2. **CacheConfigTest** — `@SpringBootTest` с `@TestPropertySource`:
   - CacheManager bean создаётся и является `CaffeineCacheManager`
   - При override `foremen.cache.spec=maximumSize=100` — менеджер использует новую спецификацию

3. **CorsConfigTest** — `@WebMvcTest` срез:
   - OPTIONS preflight с Origin: http://localhost:3000 → Access-Control-Allow-Origin present
   - Все 6 методов в Access-Control-Allow-Methods
   - Access-Control-Allow-Credentials: true
   - Access-Control-Max-Age: 3600
   - Запрос с недопустимым origin → нет CORS headers

### Property Tests (jqwik)

Используются для проверки свойств, зависящих от входных данных (пути, методы, origins).

---

## Correctness Properties

*A property is a characteristic or behavior that should hold true across all valid executions of a system — essentially, a formal statement about what the system should do. Properties serve as the bridge between human-readable specifications and machine-verifiable correctness guarantees.*

### Property 1: Security permits all requests without authentication

*For any* valid HTTP path and *for any* HTTP method (GET, POST, PUT, DELETE, PATCH), a request without authentication credentials SHALL receive a response that is not 401 (Unauthorized) or 403 (Forbidden).

**Validates: Requirements 1.3**

### Property 2: Cache spec override from application properties

*For any* valid Caffeine spec string set as the value of `foremen.cache.spec`, the resulting CacheManager SHALL use that spec for cache construction (i.e., changing the property changes the cache behavior).

**Validates: Requirements 2.4, 2.5**

### Property 3: CORS preflight allows configured methods and headers for all paths

*For any* API path matching `/**` and *for any* HTTP method in {GET, POST, PUT, DELETE, PATCH, OPTIONS}, an OPTIONS preflight request from an allowed origin SHALL include that method in the `Access-Control-Allow-Methods` response header and SHALL include `*` (or the requested header) in `Access-Control-Allow-Headers`.

**Validates: Requirements 3.3, 3.4, 3.5**

### Property 4: CORS origin filtering

*For any* origin string, a preflight request SHALL receive `Access-Control-Allow-Origin` header if and only if that origin is contained in the configured `foremen.cors.allowed-origins` list.

**Validates: Requirements 3.8, 3.9**
