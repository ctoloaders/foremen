package com.foremen.service.bank.detect;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.genai.Client;
import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.Part;

import lombok.extern.slf4j.Slf4j;

/**
 * FOR-07-04 (Requirement 15.1–15.3, 15.6, 15.7, 24.3, design §"AI classifier") — the Gemini-backed
 * {@link BankTransactionClassifier}. Active only when {@code foremen.bank-ledger.ai.enabled=true}
 * ({@link BankAiConfiguredCondition}); otherwise {@link DisabledBankTransactionClassifier} is wired.
 *
 * <p>Flow: English instructions as the system instruction, the R15.2 data as a JSON user message
 * (counterparty IBAN masked to its last 4 characters, R24.3) → model → tolerant
 * {@link ClassifierAnswer#parse(String)} → {@link ClassifierAnswerValidator#validate}. Any failure
 * (client construction, provider call, blank / unparsable response, unusable answer) yields
 * {@link Optional#empty()} so the transaction stays {@code UNLINKED} and the sync is not failed
 * (R15.6).
 *
 * <p>The Gen AI client is built lazily on first use (same approach as
 * {@code GeminiMessageTranslationService}): keyed Gemini Developer API when {@code api-key} is set,
 * otherwise the Vertex backend with project / location and ADC or {@code credentials-location}.
 */
@Slf4j
@Service
@Conditional(BankAiConfiguredCondition.class)
public class GeminiBankTransactionClassifier implements BankTransactionClassifier {

    /** System instruction (design §"AI classifier"). */
    static final String INSTRUCTIONS = """
            You classify one company bank transaction of a Polish renovation company.
            Choose exactly one kind allowed for the direction:
             EXPENSE: WORKER_PAYOUT, PROCUREMENT, OTHER_EXPENSE, INTERNAL_TRANSFER
             INCOME:  CLIENT_PAYMENT, OTHER_INCOME, INTERNAL_TRANSFER
            Prefer one of the given candidates when it fits (return its candidateRef).
            For OTHER_EXPENSE pick categoryCode from the categories list; optionally projectId from projects.
            Respond with JSON ONLY, no prose, no markdown fences, in exactly this shape:
            {"kind": string, "candidateRef": string|null, "categoryCode": string|null,
             "projectId": number|null, "confidence": number, "rationale": string}
            confidence is 0..1; rationale <= 200 characters, in Polish.
            The user message contains TRANSACTION, CANDIDATES, CATEGORIES and PROJECTS.""";

    /** The model call seam (stubbed in unit tests). */
    @FunctionalInterface
    interface ModelCall {
        String generate(String model, String systemInstruction, String userMessage);
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private final BankAiProperties properties;
    private final ModelCall modelCall;

    private volatile Client genAiClient;
    private final Object genAiLock = new Object();

    @Autowired
    public GeminiBankTransactionClassifier(BankAiProperties properties) {
        this.properties = properties;
        this.modelCall = this::callGemini;
    }

    /** Test constructor with a stubbed model call. */
    GeminiBankTransactionClassifier(BankAiProperties properties, ModelCall modelCall) {
        this.properties = properties;
        this.modelCall = modelCall;
    }

    @Override
    public Optional<ClassifierAnswer> classify(ClassificationInput input) {
        if (input == null) {
            return Optional.empty();
        }
        try {
            String response = modelCall.generate(resolveModel(), INSTRUCTIONS, buildUserMessage(input));
            if (response == null || response.isBlank()) {
                log.warn("Bank AI classifier returned a blank response");
                return Optional.empty();
            }
            ClassifierAnswer parsed = ClassifierAnswer.parse(response);
            if (parsed == null) {
                log.warn("Bank AI classifier response could not be parsed as JSON");
                return Optional.empty();
            }
            ClassifierAnswer validated = ClassifierAnswerValidator.validate(parsed, input);
            return validated != null && validated.usable() ? Optional.of(validated) : Optional.empty();
        } catch (RuntimeException e) {
            // Never log the input (counterparty data); the message only.
            log.warn("Bank AI classifier call failed; leaving the transaction unlinked: {}", e.getMessage());
            return Optional.empty();
        }
    }

    // --- Prompt -----------------------------------------------------------------------------------

    /**
     * The user message: exactly the R15.2 fields as JSON. The counterparty IBAN is re-masked to its
     * last 4 characters defensively (R24.3) — a full IBAN never reaches the model.
     */
    static String buildUserMessage(ClassificationInput in) {
        ObjectNode txn = JSON.createObjectNode();
        txn.put("direction", in.direction());
        putAmount(txn, "amount", in.amount());
        txn.put("currency", in.currency());
        putDate(txn, "bookingDate", in.bookingDate());
        txn.put("title", in.title());
        txn.put("counterpartyName", in.counterpartyName());
        txn.put("counterpartyIbanLast4", ClassificationInput.maskIbanLast4(in.counterpartyIbanLast4()));
        txn.put("bankName", in.bankName());

        ArrayNode candidates = JSON.createArrayNode();
        for (ClassificationInput.Candidate c : in.candidates()) {
            ObjectNode n = candidates.addObject();
            n.put("candidateRef", c.candidateRef());
            n.put("kind", c.kind());
            n.put("label", c.label());
            putAmount(n, "amount", c.amount());
            putDate(n, "date", c.date());
        }
        ArrayNode categories = JSON.createArrayNode();
        for (ClassificationInput.Category c : in.categories()) {
            ObjectNode n = categories.addObject();
            n.put("code", c.code());
            n.put("namePl", c.namePl());
            n.put("nameRu", c.nameRu());
        }
        ArrayNode projects = JSON.createArrayNode();
        for (ClassificationInput.Project p : in.projects()) {
            ObjectNode n = projects.addObject();
            if (p.id() == null) {
                n.putNull("id");
            } else {
                n.put("id", p.id());
            }
            n.put("name", p.name());
            n.put("city", p.city());
        }
        try {
            return "TRANSACTION: " + JSON.writeValueAsString(txn) + "\n"
                    + "CANDIDATES: " + JSON.writeValueAsString(candidates) + "\n"
                    + "CATEGORIES: " + JSON.writeValueAsString(categories) + "\n"
                    + "PROJECTS: " + JSON.writeValueAsString(projects);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize classifier input", e);
        }
    }

    private static void putAmount(ObjectNode node, String field, BigDecimal amount) {
        if (amount == null) {
            node.putNull(field);
        } else {
            node.put(field, amount.toPlainString());
        }
    }

    private static void putDate(ObjectNode node, String field, LocalDate date) {
        if (date == null) {
            node.putNull(field);
        } else {
            node.put(field, date.toString());
        }
    }

    // --- LLM call ---------------------------------------------------------------------------------

    private String callGemini(String model, String systemInstruction, String userMessage) {
        GenerateContentConfig config = GenerateContentConfig.builder()
                .systemInstruction(Content.fromParts(Part.fromText(systemInstruction)))
                .responseMimeType("application/json")
                .temperature(0f)
                .build();
        GenerateContentResponse response = genAi().models.generateContent(model, userMessage, config);
        String text = response.text();
        return text == null ? "" : text;
    }

    private String resolveModel() {
        String model = properties.model();
        if (model == null || model.isBlank()) {
            throw new IllegalStateException("foremen.bank-ledger.ai.model is not configured");
        }
        return model.strip();
    }

    // --- Lazy client construction (config only, never hardcoded — R24.6) --------------------------

    private Client genAi() {
        Client local = this.genAiClient;
        if (local == null) {
            synchronized (genAiLock) {
                local = this.genAiClient;
                if (local == null) {
                    local = buildGenAiClient();
                    this.genAiClient = local;
                }
            }
        }
        return local;
    }

    private Client buildGenAiClient() {
        Client.Builder builder = Client.builder();
        String apiKey = properties.apiKey();
        if (apiKey != null && !apiKey.isBlank()) {
            builder.apiKey(apiKey.strip());
        } else {
            builder.vertexAI(true);
            if (properties.project() != null && !properties.project().isBlank()) {
                builder.project(properties.project().strip());
            }
            if (properties.location() != null && !properties.location().isBlank()) {
                builder.location(properties.location().strip());
            }
            GoogleCredentials credentials = resolveCredentials(properties.credentialsLocation());
            if (credentials != null) {
                builder.credentials(credentials);
            }
        }
        return builder.build();
    }

    private GoogleCredentials resolveCredentials(String location) {
        if (location == null || location.isBlank()
                || "application-default".equalsIgnoreCase(location.strip())) {
            return null; // Application Default Credentials
        }
        Resource resource = new DefaultResourceLoader().getResource(location.strip());
        try (InputStream in = resource.getInputStream()) {
            return GoogleCredentials.fromStream(in);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "failed to load Google credentials from '" + location + "': " + e.getMessage(), e);
        }
    }
}
