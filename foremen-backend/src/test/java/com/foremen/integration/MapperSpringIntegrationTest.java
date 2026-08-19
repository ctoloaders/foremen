package com.foremen.integration;

import com.foremen.controller.model.mapper.TestEntityControllerMapper;
import com.foremen.service.model.mapper.TestEntityServiceMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test verifying that MapStruct-generated mapper implementations
 * are registered as Spring-managed beans in the application context.
 *
 * Uses a minimal context with only the generated mapper Impl classes to avoid
 * loading the full application context (which requires a datasource).
 *
 * Validates: Requirements 7.4, 7.5, 8.7
 */
@SpringBootTest(
        classes = {
                com.foremen.service.model.mapper.TestEntityServiceMapperImpl.class,
                com.foremen.controller.model.mapper.TestEntityControllerMapperImpl.class
        }
)
class MapperSpringIntegrationTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void testEntityServiceMapperIsSpringBean() {
        TestEntityServiceMapper mapper = applicationContext.getBean(TestEntityServiceMapper.class);
        assertNotNull(mapper, "TestEntityServiceMapper should be available as a Spring bean");
    }

    @Test
    void testEntityControllerMapperIsSpringBean() {
        TestEntityControllerMapper mapper = applicationContext.getBean(TestEntityControllerMapper.class);
        assertNotNull(mapper, "TestEntityControllerMapper should be available as a Spring bean");
    }

    @Test
    void testEntityServiceMapperIsComponent() {
        Object bean = applicationContext.getBean(TestEntityServiceMapper.class);
        Class<?> implClass = bean.getClass();
        assertTrue(
                implClass.isAnnotationPresent(org.springframework.stereotype.Component.class),
                "Generated TestEntityServiceMapperImpl should be annotated with @Component"
        );
    }

    @Test
    void testEntityControllerMapperIsComponent() {
        Object bean = applicationContext.getBean(TestEntityControllerMapper.class);
        Class<?> implClass = bean.getClass();
        assertTrue(
                implClass.isAnnotationPresent(org.springframework.stereotype.Component.class),
                "Generated TestEntityControllerMapperImpl should be annotated with @Component"
        );
    }
}
