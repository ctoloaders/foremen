package com.foremen.service.bank.secret;

/**
 * Raised when a stored bank secret cannot be decrypted: tampering, wrong key, unknown key version
 * or a malformed payload. Never carries secret values.
 */
public class BankSecretCipherException extends RuntimeException {

    public BankSecretCipherException(String message) {
        super(message);
    }

    public BankSecretCipherException(String message, Throwable cause) {
        super(message, cause);
    }
}
