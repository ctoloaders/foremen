package com.foremen.controller.bank;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.mock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import com.foremen.config.security.PermissionAnnotationValidator;
import com.foremen.config.security.PermissionResolver;
import com.foremen.config.security.PermissionResource;
import com.foremen.service.bank.account.BankAccountService;
import com.foremen.service.bank.account.SupportedBanksService;
import com.foremen.service.bank.cash.CashLedgerService;
import com.foremen.service.bank.connection.BankConnectionService;
import com.foremen.service.bank.connector.BankFeedConnectorRegistry;
import com.foremen.service.bank.connector.stub.StubDataStore;
import com.foremen.service.bank.link.CounterpartyRuleService;
import com.foremen.service.bank.secret.BankConnectionSecretService;
import com.foremen.service.bank.sync.BankSyncRunQueryService;

/**
 * FOR-07-04 task 7.1 — ABAC wiring of the bank administration controllers (R21.1, R21.3, R21.5):
 * every handler resolves to {@code BANK_CONNECTIONS} + the operation of the design REST table, and
 * the real {@link PermissionAnnotationValidator} accepts all six controllers (no half-annotation), so
 * application startup does not fail on them.
 */
class BankAdminControllersAbacTest {

    private static final String RESOURCE = "BANK_CONNECTIONS";

    private final PermissionResolver resolver = new PermissionResolver();

    private final BankProviderController providerController = new BankProviderController(null, null);
    private final BankConnectionController connectionController = new BankConnectionController(null, null, null, null);
    private final BankAccountController accountController = new BankAccountController(null, null, null);
    private final BankSyncRunController syncRunController = new BankSyncRunController(null);
    private final CounterpartyRuleController ruleController = new CounterpartyRuleController(null);
    private final StubBankFeedController stubController = new StubBankFeedController(null, null);

    @Test
    @DisplayName("every controller carries @PermissionResource(\"BANK_CONNECTIONS\")")
    void classLevelResource() {
        for (Class<?> c : List.of(BankProviderController.class, BankConnectionController.class,
                BankAccountController.class, BankSyncRunController.class, CounterpartyRuleController.class,
                StubBankFeedController.class)) {
            assertThat(c.getAnnotation(PermissionResource.class)).as(c.getSimpleName()).isNotNull();
            assertThat(c.getAnnotation(PermissionResource.class).value()).as(c.getSimpleName()).isEqualTo(RESOURCE);
        }
    }

    @Test
    @DisplayName("handler → (BANK_CONNECTIONS, op) per the design REST table")
    void operationsPerDesign() {
        assertOps(providerController, Map.of("providers", "READ", "banks", "READ"));
        assertOps(connectionController, Map.ofEntries(
                Map.entry("list", "READ"), Map.entry("get", "READ"), Map.entry("create", "CREATE"),
                Map.entry("authorize", "UPDATE"), Map.entry("complete", "UPDATE"),
                Map.entry("reconnect", "UPDATE"), Map.entry("refresh", "UPDATE"), Map.entry("sync", "UPDATE"),
                Map.entry("rename", "UPDATE"), Map.entry("replaceCredentials", "UPDATE"),
                Map.entry("disable", "UPDATE"), Map.entry("delete", "DELETE")));
        assertOps(accountController, Map.ofEntries(
                Map.entry("list", "READ"), Map.entry("get", "READ"), Map.entry("update", "UPDATE"),
                Map.entry("balanceGaps", "READ"), Map.entry("acknowledgeGap", "UPDATE"),
                Map.entry("listCash", "READ"), Map.entry("getCash", "READ"), Map.entry("createCash", "CREATE"),
                Map.entry("updateCash", "UPDATE"), Map.entry("archiveCash", "UPDATE")));
        assertOps(syncRunController, Map.of("list", "READ", "get", "READ"));
        assertOps(ruleController, Map.of("list", "READ", "get", "READ", "create", "CREATE",
                "update", "UPDATE", "delete", "DELETE"));
        assertOps(stubController, Map.of("seed", "UPDATE"));
    }

    @Test
    @DisplayName("the real PermissionAnnotationValidator accepts all bank admin controllers")
    void validatorAcceptsControllers() {
        try (GenericApplicationContext ctx = new GenericApplicationContext()) {
            ctx.registerBean(BankProviderController.class, () -> new BankProviderController(
                    mock(BankFeedConnectorRegistry.class), mock(SupportedBanksService.class)));
            ctx.registerBean(BankConnectionController.class, () -> new BankConnectionController(
                    mock(BankConnectionService.class), mock(BankConnectionSecretService.class),
                    mock(BankAccountService.class), mock(BankFeedConnectorRegistry.class)));
            ctx.registerBean(BankAccountController.class, () -> new BankAccountController(
                    mock(BankAccountService.class), mock(CashLedgerService.class), mock(BankConnectionService.class)));
            ctx.registerBean(BankSyncRunController.class,
                    () -> new BankSyncRunController(mock(BankSyncRunQueryService.class)));
            ctx.registerBean(CounterpartyRuleController.class,
                    () -> new CounterpartyRuleController(mock(CounterpartyRuleService.class)));
            ctx.registerBean(StubBankFeedController.class, () -> new StubBankFeedController(
                    mock(StubDataStore.class), mock(BankConnectionService.class)));
            ctx.refresh();

            RequestMappingHandlerMapping mapping = new RequestMappingHandlerMapping();
            mapping.setApplicationContext(ctx);
            mapping.afterPropertiesSet();

            // 2 + 12 + 10 + 2 + 5 + 1 handlers
            assertThat(mapping.getHandlerMethods()).hasSize(32);
            PermissionAnnotationValidator validator = new PermissionAnnotationValidator(mapping, resolver);
            assertThatCode(validator::afterSingletonsInstantiated).doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("StubBankFeedController exists only with foremen.bank-feed.stub.enabled=true")
    void stubControllerIsConditional() {
        ConditionalOnProperty condition = StubBankFeedController.class.getAnnotation(ConditionalOnProperty.class);
        assertThat(condition).isNotNull();
        assertThat(condition.prefix()).isEqualTo("foremen.bank-feed.stub");
        assertThat(condition.name()).containsExactly("enabled");
        assertThat(condition.havingValue()).isEqualTo("true");
        assertThat(condition.matchIfMissing()).isFalse();
    }

    private void assertOps(Object controller, Map<String, String> expected) {
        List<Method> handlers = Arrays.stream(controller.getClass().getDeclaredMethods())
                .filter(m -> !m.isSynthetic() && Modifier.isPublic(m.getModifiers()))
                .filter(m -> AnnotatedElementUtils.hasAnnotation(m, RequestMapping.class))
                .toList();
        assertThat(handlers).as(controller.getClass().getSimpleName() + " handlers")
                .extracting(Method::getName)
                .containsExactlyInAnyOrderElementsOf(expected.keySet());
        for (Method m : handlers) {
            HandlerMethod hm = new HandlerMethod(controller, m);
            assertThat(resolver.classifyCompleteness(hm)).as(m.getName())
                    .isEqualTo(PermissionResolver.Completeness.COMPLETE);
            assertThat(resolver.resolve(hm)).as(m.getName())
                    .isEqualTo(new PermissionResolver.ResolvedPair(RESOURCE, expected.get(m.getName())));
        }
    }
}
