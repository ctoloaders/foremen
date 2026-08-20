# Requirements Document

## Introduction

Конфигурационные классы Spring Boot для проекта Foremen: SecurityConfig (заглушка с открытым доступом для этапа разработки), CacheConfig (Caffeine-based кеширование) и CorsConfig (CORS-политика для фронтенд-приложения). Все три конфигурации располагаются в соответствующих подпакетах `com.foremen.config.*` и следуют паттернам, установленным существующими конфигурациями проекта.

## Glossary

- **SecurityConfig**: Spring Security конфигурационный класс в пакете `com.foremen.config.security`, определяющий SecurityFilterChain для HTTP-авторизации
- **CacheConfig**: Конфигурационный класс кеширования в пакете `com.foremen.config.cache`, определяющий CaffeineCacheManager
- **CorsConfig**: Конфигурационный класс CORS-политики в пакете `com.foremen.config.web`, реализующий WebMvcConfigurer
- **SecurityFilterChain**: Spring Security bean, определяющий цепочку фильтров безопасности для HTTP-запросов
- **CaffeineCacheManager**: Spring Cache manager на базе библиотеки Caffeine для in-memory кеширования
- **Application_Properties**: Настройки приложения, определённые в `application.yml` и доступные через `@Value` или `@ConfigurationProperties`
- **Foremen_Backend**: Серверная часть приложения Foremen на Spring Boot 4.0.0, Java 25

## Requirements

### Requirement 1: SecurityConfig — базовая конфигурация безопасности

**User Story:** Как разработчик, я хочу иметь конфигурацию Spring Security с открытым доступом ко всем endpoint'ам, чтобы на этапе разработки не получать 401/403 ошибки до реализации полноценной аутентификации (FOR-03).

#### Acceptance Criteria

1. THE SecurityConfig SHALL быть аннотирован `@Configuration` и `@EnableWebSecurity` и располагаться в пакете `com.foremen.config.security`
2. THE SecurityConfig SHALL определять bean типа `SecurityFilterChain`, принимающий `HttpSecurity` как параметр
3. THE SecurityFilterChain SHALL разрешать доступ ко всем HTTP-запросам без аутентификации (permitAll)
4. THE SecurityFilterChain SHALL отключать CSRF-защиту
5. THE SecurityFilterChain SHALL устанавливать политику управления сессиями в STATELESS
6. THE SecurityFilterChain SHALL отключать frameOptions в заголовках ответа

### Requirement 2: CacheConfig — конфигурация Caffeine-кеширования

**User Story:** Как разработчик, я хочу иметь настроенный CacheManager на базе Caffeine с параметрами по умолчанию и возможностью переопределения через application properties, чтобы сервисы могли использовать аннотацию `@Cacheable` без дополнительной конфигурации.

#### Acceptance Criteria

1. THE CacheConfig SHALL быть аннотирован `@Configuration` и `@EnableCaching` и располагаться в пакете `com.foremen.config.cache`
2. THE CacheConfig SHALL определять bean типа `CaffeineCacheManager`
3. THE CaffeineCacheManager SHALL использовать спецификацию кеша по умолчанию: maximumSize=500, expireAfterWrite=10m
4. WHEN свойство `foremen.cache.spec` задано в Application_Properties, THE CacheConfig SHALL использовать значение этого свойства вместо спецификации по умолчанию
5. THE CacheConfig SHALL читать значение спецификации кеша из свойства `foremen.cache.spec` с помощью механизма `@Value` с указанием значения по умолчанию

### Requirement 3: CorsConfig — конфигурация CORS-политики

**User Story:** Как фронтенд-разработчик, я хочу иметь настроенную CORS-политику, разрешающую запросы с фронтенд-приложения, чтобы браузер не блокировал API-запросы из-за ограничений same-origin policy.

#### Acceptance Criteria

1. THE CorsConfig SHALL быть аннотирован `@Configuration` и располагаться в пакете `com.foremen.config.web`
2. THE CorsConfig SHALL реализовывать интерфейс `WebMvcConfigurer`
3. THE CorsConfig SHALL переопределять метод `addCorsMappings` и применять CORS-правила ко всем путям (`/**`)
4. THE CorsConfig SHALL разрешать HTTP-методы: GET, POST, PUT, DELETE, PATCH, OPTIONS
5. THE CorsConfig SHALL разрешать все заголовки в запросах (`*`)
6. THE CorsConfig SHALL разрешать передачу credentials (cookies, authorization headers)
7. THE CorsConfig SHALL устанавливать max-age preflight-кеша в 3600 секунд
8. WHEN свойство `foremen.cors.allowed-origins` задано в Application_Properties, THE CorsConfig SHALL использовать его значение как список разрешённых origins
9. IF свойство `foremen.cors.allowed-origins` не задано в Application_Properties, THEN THE CorsConfig SHALL использовать `http://localhost:3000` как значение по умолчанию

### Requirement 4: Интеграция с application.yml

**User Story:** Как DevOps-инженер, я хочу управлять параметрами кеширования и CORS через application.yml/переменные окружения, чтобы менять конфигурацию без перекомпиляции приложения.

#### Acceptance Criteria

1. THE Foremen_Backend SHALL содержать свойство `foremen.cache.spec` в application.yml со значением по умолчанию `maximumSize=500,expireAfterWrite=10m`
2. THE Foremen_Backend SHALL содержать свойство `foremen.cors.allowed-origins` в application.yml со значением по умолчанию `http://localhost:3000`
