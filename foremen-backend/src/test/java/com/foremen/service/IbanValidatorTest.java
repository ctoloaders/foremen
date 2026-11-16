package com.foremen.service;

import com.foremen.exception.ForemenApiException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Unit tests for {@link IbanValidator} (FOR-11-06, Requirement 4.1). */
class IbanValidatorTest {

    @Test
    void normalizesSpacesAndCase() {
        assertThat(IbanValidator.normalize(" pl61 1090 1014 0000 0712 1981 2874 "))
                .isEqualTo("PL61109010140000071219812874");
    }

    @Test
    void acceptsKnownValidIbans() {
        assertThat(IbanValidator.isValid("DE89370400440532013000")).isTrue();
        assertThat(IbanValidator.isValid("GB82WEST12345698765432")).isTrue();
        assertThat(IbanValidator.isValid("FR1420041010050500013M02606")).isTrue();
    }

    @Test
    void polishNrbWithoutPrefixGetsPlPrefix() {
        assertThat(IbanValidator.normalize("61 1090 1014 0000 0712 1981 2874"))
                .isEqualTo("PL61109010140000071219812874");
    }

    @Test
    void rejectsBadChecksum() {
        assertThat(IbanValidator.isValid("PL62109010140000071219812874")).isFalse();
        assertThat(IbanValidator.isValid("62109010140000071219812874")).isFalse();
    }

    @Test
    void rejectsWrongCountryLength() {
        assertThat(IbanValidator.isValid("DE8937040044053201300")).isFalse();
        assertThat(IbanValidator.isValid("DE893704004405320130000")).isFalse();
    }

    @Test
    void rejectsMalformedAndNull() {
        assertThat(IbanValidator.isValid(null)).isFalse();
        assertThat(IbanValidator.isValid("")).isFalse();
        assertThat(IbanValidator.isValid("1234")).isFalse();
        assertThat(IbanValidator.isValid("PL6110901014000007121981287!")).isFalse();
    }

    @Test
    void normalizeThrows400WithMessageCode() {
        assertThatThrownBy(() -> IbanValidator.normalize("PL00"))
                .isInstanceOf(ForemenApiException.class)
                .hasMessageContaining(IbanValidator.IBAN_INVALID_MESSAGE);
        assertThatThrownBy(() -> IbanValidator.normalize(null)).isInstanceOf(ForemenApiException.class);
    }
}
