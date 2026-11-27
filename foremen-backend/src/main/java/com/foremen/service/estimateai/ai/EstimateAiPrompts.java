package com.foremen.service.estimateai.ai;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * FOR-05-04 (Requirements 7.1, 7.2) — loader of the versioned system prompts kept as classpath
 * resources. The version is read from the first line {@code <!-- prompt-version: X -->} and is
 * recorded on the job ({@code result.model.promptVersion}).
 */
public final class EstimateAiPrompts {

    public static final String SYSTEM_PROMPT_RESOURCE = "estimate-ai/system-prompt.md";
    public static final String CHAT_SYSTEM_PROMPT_RESOURCE = "estimate-ai/chat-system-prompt.md";

    private static final Pattern VERSION = Pattern.compile("^<!--\\s*prompt-version:\\s*([^\\s>]+)\\s*-->");

    private EstimateAiPrompts() {
    }

    /** A loaded prompt: the {@code text} without the version marker line and its {@code version}. */
    public record Prompt(String version, String text) {
    }

    public static Prompt system() {
        return load(SYSTEM_PROMPT_RESOURCE);
    }

    public static Prompt chatSystem() {
        return load(CHAT_SYSTEM_PROMPT_RESOURCE);
    }

    static Prompt load(String resource) {
        try (InputStream in = EstimateAiPrompts.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Missing classpath resource " + resource);
            }
            String raw = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            Matcher m = VERSION.matcher(raw);
            if (!m.find()) {
                throw new IllegalStateException("Resource " + resource + " has no prompt-version marker");
            }
            return new Prompt(m.group(1), raw.substring(m.end()).strip());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
