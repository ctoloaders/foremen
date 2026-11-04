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
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import com.foremen.config.security.PermissionAnnotationValidator;
import com.foremen.config.security.PermissionResolver;
import com.foremen.config.security.PermissionResource;
import com.foremen.service.bank.cash.CashLedgerService;
import com.foremen.service.bank.ledger.BankLedgerBulkService;
import com.foremen.service.bank.ledger.BankLedgerQueryService;
import com.foremen.service.bank.link.BankLinkService;
import com.foremen.service.permission.ForemenPermissionEvaluator;

/**
 * FOR-07-04 task 7.2 — ABAC wiring of {@link BankTransactionController} (R21.2, R21.4): every handler
 * resolves to {@code BANK_LEDGER} + the operation of the design REST table and the real
 * {@link PermissionAnnotationValidator} accepts the controller (no half-annotation).
 */
class BankTransactionControllerAbacTest {

    private static final String RESOURCE = "BANK_LEDGER";

    private static final Map<String, String> EXPECTED = Map.ofEntries(
            Map.entry("list", "READ"),
            Map.entry("detail", "READ"),
            Map.entry("linkTargets", "READ"),
            Map.entry("linkCandidates", "READ"),
            Map.entry("createCashEntry", "CREATE"),
            Map.entry("updateCashEntry", "UPDATE"),
            Map.entry("deleteCashEntry", "DELETE"),
            Map.entry("createLink", "CREATE"),
            Map.entry("replaceLink", "UPDATE"),
            Map.entry("removeLink", "DELETE"),
            Map.entry("confirmSuggestion", "UPDATE"),
            Map.entry("rejectSuggestion", "UPDATE"),
            Map.entry("confirmAuto", "UPDATE"),
            Map.entry("ignore", "UPDATE"),
            Map.entry("unignore", "UPDATE"),
            Map.entry("createPayout", "CREATE"),
            Map.entry("detect", "UPDATE"),
            Map.entry("bulkConfirm", "UPDATE"));

    private final PermissionResolver resolver = new PermissionResolver();

    @Test
    @DisplayName("class carries @PermissionResource(\"BANK_LEDGER\")")
    void classLevelResource() {
        PermissionResource pr = BankTransactionController.class.getAnnotation(PermissionResource.class);
        assertThat(pr).isNotNull();
        assertThat(pr.value()).isEqualTo(RESOURCE);
    }

    @Test
    @DisplayName("handler → (BANK_LEDGER, op) per the design REST table")
    void operationsPerDesign() {
        BankTransactionController controller = new BankTransactionController(null, null, null, null, null);
        List<Method> handlers = Arrays.stream(BankTransactionController.class.getDeclaredMethods())
                .filter(m -> !m.isSynthetic() && Modifier.isPublic(m.getModifiers()))
                .filter(m -> AnnotatedElementUtils.hasAnnotation(m, RequestMapping.class))
                .toList();
        assertThat(handlers).extracting(Method::getName).containsExactlyInAnyOrderElementsOf(EXPECTED.keySet());
        for (Method m : handlers) {
            HandlerMethod hm = new HandlerMethod(controller, m);
            assertThat(resolver.classifyCompleteness(hm)).as(m.getName())
                    .isEqualTo(PermissionResolver.Completeness.COMPLETE);
            assertThat(resolver.resolve(hm)).as(m.getName())
                    .isEqualTo(new PermissionResolver.ResolvedPair(RESOURCE, EXPECTED.get(m.getName())));
        }
    }

    @Test
    @DisplayName("the real PermissionAnnotationValidator accepts BankTransactionController")
    void validatorAcceptsController() {
        try (GenericApplicationContext ctx = new GenericApplicationContext()) {
            ctx.registerBean(BankTransactionController.class, () -> new BankTransactionController(
                    mock(BankLedgerQueryService.class), mock(BankLedgerBulkService.class),
                    mock(BankLinkService.class), mock(CashLedgerService.class),
                    mock(ForemenPermissionEvaluator.class)));
            ctx.refresh();
            RequestMappingHandlerMapping mapping = new RequestMappingHandlerMapping();
            mapping.setApplicationContext(ctx);
            mapping.afterPropertiesSet();
            assertThat(mapping.getHandlerMethods()).hasSize(EXPECTED.size());
            PermissionAnnotationValidator validator = new PermissionAnnotationValidator(mapping, resolver);
            assertThatCode(validator::afterSingletonsInstantiated).doesNotThrowAnyException();
        }
    }
}
