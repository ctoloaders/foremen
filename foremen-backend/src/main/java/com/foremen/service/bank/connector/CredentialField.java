package com.foremen.service.bank.connector;

/**
 * FOR-07-04 (Requirement 2.2): descriptor of a per-connection credential field a connector needs;
 * the frontend renders the credential form from these.
 *
 * @param name     field name (key in the submitted credentials map)
 * @param labelKey i18n label key
 * @param secret   {@code true} for secret values (masked input, never returned)
 */
public record CredentialField(String name, String labelKey, boolean secret) {
}
