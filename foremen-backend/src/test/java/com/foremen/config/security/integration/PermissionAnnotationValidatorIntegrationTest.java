package com.foremen.config.security.integration;

import com.foremen.config.security.PermissionAnnotationValidator;
import com.foremen.config.security.PermissionOperation;
import com.foremen.config.security.PermissionResolver;
import com.foremen.config.security.PermissionResource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Startup-validation integration tests for {@link PermissionAnnotationValidator} (task 12.2,
 * Requirements 6.1, 6.2, 6.3, 6.5, 13.3).
 *
 * <p>The validator is a {@link org.springframework.beans.factory.SmartInitializingSingleton} that
 * runs once after all singletons are instantiated: it iterates the handler methods registered in
 * {@link RequestMappingHandlerMapping}, classifies each via {@link PermissionResolver}, and throws
 * {@link IllegalStateException} naming the offending controller class and method when any handler is
 * half-annotated (a {@code @PermissionResource} without a matching {@code @PermissionOperation}, or a
 * {@code @PermissionOperation} without a class {@code @PermissionResource}).</p>
 *
 * <p>These tests use a lightweight {@link ApplicationContextRunner} rather than a full
 * {@code @SpringBootTest}: each scenario registers only a {@link RequestMappingHandlerMapping}, the
 * {@link PermissionResolver}, the {@link PermissionAnnotationValidator}, and one deliberately shaped
 * fixture controller. Because the validator throws during {@code afterSingletonsInstantiated()},
 * the failure surfaces as an application-context startup failure — exactly what Requirement 13.3
 * mandates — and the runner captures it without aborting the JVM.</p>
 *
 * <p>The success scenario proves the real, correctly-annotated shape (a class-level
 * {@code @PermissionResource} paired with a method-level {@code @PermissionOperation}) boots without
 * error, mirroring Requirement 6.1 that the real application context starts once no controller is
 * half-annotated.</p>
 */
class PermissionAnnotationValidatorIntegrationTest {

    /**
     * Base runner registering the always-present validation collaborators: an initialized
     * {@link RequestMappingHandlerMapping} (whose {@code getHandlerMethods()} the validator scans),
     * the {@link PermissionResolver}, and the {@link PermissionAnnotationValidator} itself. Each test
     * layers a fixture-controller configuration on top via {@link ApplicationContextRunner#withUserConfiguration}.
     */
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(ValidationInfrastructureConfig.class);

    @Test
    @DisplayName("context starts when the controller is completely annotated (resource + operation) — Req 6.1")
    void contextStartsWhenControllerFullyAnnotated() {
        contextRunner
                .withUserConfiguration(CompleteControllerConfig.class)
                .run(context -> {
                    assertThat(context)
                            .as("a controller with both @PermissionResource and @PermissionOperation "
                                    + "is complete, so startup validation must not fail the context")
                            .hasNotFailed();
                    assertThat(context).hasSingleBean(PermissionAnnotationValidator.class);
                });
    }

    @Test
    @DisplayName("context fails to start when a controller has @PermissionResource but a mapped handler lacks @PermissionOperation — Req 6.2, 6.5, 13.3")
    void contextFailsForResourceWithoutOperation() {
        contextRunner
                .withUserConfiguration(ResourceWithoutOperationControllerConfig.class)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .as("startup must fail via the validator's IllegalStateException")
                            .isInstanceOf(IllegalStateException.class);
                    String message = rootCauseMessage(context.getStartupFailure());
                    assertThat(message)
                            .as("the failure must name the offending controller class")
                            .contains(ResourceWithoutOperationController.class.getName())
                            .as("the failure must name the offending handler method")
                            .contains("uncovered");
                });
    }

    @Test
    @DisplayName("context fails to start when a mapped handler has @PermissionOperation but the controller lacks @PermissionResource — Req 6.3, 6.5, 13.3")
    void contextFailsForOperationWithoutResource() {
        contextRunner
                .withUserConfiguration(OperationWithoutResourceControllerConfig.class)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .as("startup must fail via the validator's IllegalStateException")
                            .isInstanceOf(IllegalStateException.class);
                    String message = rootCauseMessage(context.getStartupFailure());
                    assertThat(message)
                            .as("the failure must name the offending controller class")
                            .contains(OperationWithoutResourceController.class.getName())
                            .as("the failure must name the offending handler method")
                            .contains("orphanOperation");
                });
    }

    private static String rootCauseMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current.getMessage();
    }

    // --- Shared validation infrastructure ---

    /**
     * Registers the validator collaborators. The {@link RequestMappingHandlerMapping} is returned as
     * a plain bean so Spring initializes it (via {@link org.springframework.beans.factory.InitializingBean})
     * against the application context, detecting the fixture {@code @RestController} beans and exposing
     * their handler methods to the validator.
     */
    @Configuration
    static class ValidationInfrastructureConfig {

        @Bean
        RequestMappingHandlerMapping requestMappingHandlerMapping() {
            return new RequestMappingHandlerMapping();
        }

        @Bean
        PermissionResolver permissionResolver() {
            return new PermissionResolver();
        }

        @Bean
        PermissionAnnotationValidator permissionAnnotationValidator(
                RequestMappingHandlerMapping handlerMapping, PermissionResolver resolver) {
            return new PermissionAnnotationValidator(handlerMapping, resolver);
        }
    }

    // --- Fixture controllers + their registering configurations ---

    @Configuration
    static class CompleteControllerConfig {

        @Bean
        CompleteController completeController() {
            return new CompleteController();
        }
    }

    @Configuration
    static class ResourceWithoutOperationControllerConfig {

        @Bean
        ResourceWithoutOperationController resourceWithoutOperationController() {
            return new ResourceWithoutOperationController();
        }
    }

    @Configuration
    static class OperationWithoutResourceControllerConfig {

        @Bean
        OperationWithoutResourceController operationWithoutResourceController() {
            return new OperationWithoutResourceController();
        }
    }

    /**
     * Correctly annotated controller: class carries {@code @PermissionResource} and its single
     * mapped handler carries {@code @PermissionOperation}, so it classifies as COMPLETE.
     */
    @RestController
    @RequestMapping("/it/permission-validator/complete")
    @PermissionResource("FIXTURE")
    static class CompleteController {

        @GetMapping("/read")
        @PermissionOperation("READ")
        public String read() {
            return "ok";
        }
    }

    /**
     * Half-annotated controller: class carries {@code @PermissionResource} but its mapped
     * {@code uncovered} handler lacks both {@code @PermissionOperation} and {@code @RequiresPermission},
     * so it classifies as RESOURCE_WITHOUT_OPERATION and must fail startup naming the class and method.
     */
    @RestController
    @RequestMapping("/it/permission-validator/resource-without-operation")
    @PermissionResource("FIXTURE")
    static class ResourceWithoutOperationController {

        @GetMapping("/uncovered")
        public String uncovered() {
            return "ok";
        }
    }

    /**
     * Half-annotated controller: the mapped {@code orphanOperation} handler carries
     * {@code @PermissionOperation} but the class carries no {@code @PermissionResource} (and the method
     * carries no {@code @RequiresPermission}), so it classifies as OPERATION_WITHOUT_RESOURCE and must
     * fail startup naming the method and its declaring controller class.
     */
    @RestController
    @RequestMapping("/it/permission-validator/operation-without-resource")
    static class OperationWithoutResourceController {

        @GetMapping("/orphan")
        @PermissionOperation("READ")
        public String orphanOperation() {
            return "ok";
        }
    }
}
