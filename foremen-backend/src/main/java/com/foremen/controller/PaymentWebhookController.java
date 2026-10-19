package com.foremen.controller;

import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.foremen.exception.ForemenApiException;
import com.foremen.service.payment.PaymentTransactionService;
import com.foremen.service.payment.provider.OnlinePaymentProvider;
import com.foremen.service.payment.provider.OnlineWebhookEvent;
import com.foremen.service.payment.provider.RawWebhook;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;

/**
 * FOR-06-07 (Requirements 7.3, 7.5; design §Backend controllers {@code PaymentWebhookController}):
 * the public przelewy24 (online-payment) webhook that receives the asynchronous outcome of an
 * online payment and advances the matching {@code PaymentTransaction} by {@code externalRef}.
 *
 * <h2>Intentionally unguarded (per {@code entity-creation-rules})</h2>
 * This is an <b>unauthenticated external provider webhook</b>, exactly like {@link AuthController}
 * and {@link SignatureCallbackController}: it carries <b>none</b> of the three permission
 * annotations ({@code @PermissionResource} / {@code @PermissionOperation} /
 * {@code @RequiresPermission}), so {@code PermissionAnnotationValidator} treats it as deliberately
 * unguarded rather than half-annotated. It is NOT ABAC-protected because the caller is the
 * przelewy24 provider (or, under the {@code NoOpOnlinePaymentProvider}, nothing at all), not a
 * logged-in Foremen principal — there is no project role to resolve. The path
 * {@code /api/payments/webhook/**} is admitted to the public allowlist in {@code SecurityConfig} so
 * an unauthenticated provider request reaches this controller instead of being rejected with 401 by
 * the {@code anyRequest().authenticated()} catch-all.
 *
 * <p><b>Authenticity is enforced by the provider signature</b> rather than by the security layer
 * (Requirement 7.5): the raw request body bytes + headers are wrapped into a {@link RawWebhook} and
 * handed to {@link OnlinePaymentProvider#verifySignature(RawWebhook)}; an unverified call is
 * rejected with {@code 401 error.payment.webhook.unverified}. Under the
 * {@code NoOpOnlinePaymentProvider} {@code verifySignature} returns {@code false}, so no live
 * webhook is ever accepted while credentials are absent. Once verified, the payload is parsed into
 * an {@link OnlineWebhookEvent} (CONFIRMED / FAILED / REFUNDED by {@code externalRef}) and delegated
 * to {@link PaymentTransactionService#onOnlineWebhook(OnlineWebhookEvent)} (Requirement 7.3). This
 * controller is a thin delegator: it maps the HTTP concern only and holds no business logic.
 */
@RestController
@RequestMapping("/api/payments/webhook")
@RequiredArgsConstructor
public class PaymentWebhookController {

    private final OnlinePaymentProvider onlinePaymentProvider;
    private final PaymentTransactionService paymentTransactionService;

    /**
     * Receives a przelewy24 online-payment webhook, verifies its provider signature, parses it into
     * an {@link OnlineWebhookEvent}, and delegates to
     * {@link PaymentTransactionService#onOnlineWebhook(OnlineWebhookEvent)} (R7.3, R7.5).
     *
     * @param request the raw inbound HTTP request; its body bytes + headers form the
     *                {@link RawWebhook} the provider verifies and parses
     * @return HTTP 200 once the (verified) event has been processed
     */
    @PostMapping("/online")
    public ResponseEntity<Void> online(HttpServletRequest request) {
        RawWebhook raw = toRawWebhook(request);
        // R7.5: reject unverified calls; the No-Op provider verifies nothing, so no live webhook is
        // accepted while credentials are absent.
        if (!onlinePaymentProvider.verifySignature(raw)) {
            throw new ForemenApiException(HttpStatus.UNAUTHORIZED, "error.payment.webhook.unverified");
        }
        OnlineWebhookEvent event = onlinePaymentProvider.parse(raw);
        paymentTransactionService.onOnlineWebhook(event);
        return ResponseEntity.ok().build();
    }

    /**
     * Builds a {@link RawWebhook} from the raw request body bytes (exactly as received, for
     * signature verification) and a case-insensitive snapshot of the request headers.
     */
    private RawWebhook toRawWebhook(HttpServletRequest request) {
        byte[] body = readBody(request);
        Map<String, String> headers = readHeaders(request);
        return new RawWebhook(body, headers);
    }

    private byte[] readBody(HttpServletRequest request) {
        try {
            byte[] body = request.getInputStream().readAllBytes();
            return body != null ? body : new byte[0];
        } catch (IOException ex) {
            throw new ForemenApiException(HttpStatus.UNAUTHORIZED, "error.payment.webhook.unverified");
        }
    }

    private Map<String, String> readHeaders(HttpServletRequest request) {
        Map<String, String> headers = new LinkedHashMap<>();
        Enumeration<String> names = request.getHeaderNames();
        if (names == null) {
            return headers;
        }
        for (String name : Collections.list(names)) {
            headers.put(name, request.getHeader(name));
        }
        return headers;
    }
}
