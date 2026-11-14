package com.foremen.service.nip;

/**
 * FOR-11-00 (Requirement 6.6) — thrown when the NIP registry cannot be reached or returns an error.
 * The message must never contain the API key.
 */
public class NipLookupUnavailableException extends RuntimeException {

    public NipLookupUnavailableException(String message) {
        super(message);
    }

    public NipLookupUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
