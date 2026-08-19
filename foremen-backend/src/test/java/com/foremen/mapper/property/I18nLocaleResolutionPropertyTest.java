package com.foremen.mapper.property;

import com.foremen.mapper.fixture.TestDaoModel;
import com.foremen.mapper.fixture.TestServiceExtendedModel;
import com.foremen.mapper.fixture.TestServiceModel;
import com.foremen.service.model.mapper.TestEntityServiceMapper;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.WithNull;
import org.mapstruct.factory.Mappers;
import org.springframework.context.i18n.LocaleContextHolder;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property 5: I18n Locale Resolution (toServiceModel only)
 *
 * For any DaoModel source and for any locale:
 * - If the locale language is "ru", calling toServiceModel(dao) SHALL produce a ServiceModel
 *   where name == dao.nameRU and description == dao.descriptionRU.
 * - If the locale language is anything other than "ru", calling toServiceModel(dao) SHALL produce
 *   a ServiceModel where name == dao.namePL and description == dao.descriptionPL.
 * - If the locale-specific field value is null, the corresponding base field on the ServiceModel SHALL be null.
 *
 * Additionally: toServiceExtendedModel(dao) does NOT resolve locale fields — result.nameRU == dao.nameRU
 * and result.namePL == dao.namePL regardless of locale.
 *
 * Validates: Requirements 4.2, 4.4, 4.5, 4.6, 4.10, 8.4, 8.5
 */
class I18nLocaleResolutionPropertyTest {

    private final TestEntityServiceMapper mapper = Mappers.getMapper(TestEntityServiceMapper.class);

    @Property(tries = 100)
    void ruLocaleResolvesRuFields(
            @ForAll @WithNull(0.3) String nameRU,
            @ForAll @WithNull(0.3) String namePL,
            @ForAll @WithNull(0.3) String descriptionRU,
            @ForAll @WithNull(0.3) String descriptionPL,
            @ForAll Long id,
            @ForAll @WithNull(0.3) String code,
            @ForAll @WithNull(0.3) Integer quantity
    ) {
        TestDaoModel dao = new TestDaoModel();
        dao.setId(id);
        dao.setNameRU(nameRU);
        dao.setNamePL(namePL);
        dao.setDescriptionRU(descriptionRU);
        dao.setDescriptionPL(descriptionPL);
        dao.setCode(code);
        dao.setQuantity(quantity);

        try {
            LocaleContextHolder.setLocale(Locale.forLanguageTag("ru"));

            TestServiceModel result = mapper.toServiceModel(dao);

            assertThat(result.getName()).isEqualTo(dao.getNameRU());
            assertThat(result.getDescription()).isEqualTo(dao.getDescriptionRU());
            assertThat(result.getCode()).isEqualTo(dao.getCode());
            assertThat(result.getQuantity()).isEqualTo(dao.getQuantity());
            assertThat(result.getId()).isEqualTo(dao.getId());
        } finally {
            LocaleContextHolder.resetLocaleContext();
        }
    }

    @Property(tries = 100)
    void nonRuLocaleResolvesPlFields(
            @ForAll @WithNull(0.3) String nameRU,
            @ForAll @WithNull(0.3) String namePL,
            @ForAll @WithNull(0.3) String descriptionRU,
            @ForAll @WithNull(0.3) String descriptionPL,
            @ForAll Long id,
            @ForAll @WithNull(0.3) String code,
            @ForAll @WithNull(0.3) Integer quantity,
            @ForAll("nonRuLocales") Locale locale
    ) {
        TestDaoModel dao = new TestDaoModel();
        dao.setId(id);
        dao.setNameRU(nameRU);
        dao.setNamePL(namePL);
        dao.setDescriptionRU(descriptionRU);
        dao.setDescriptionPL(descriptionPL);
        dao.setCode(code);
        dao.setQuantity(quantity);

        try {
            LocaleContextHolder.setLocale(locale);

            TestServiceModel result = mapper.toServiceModel(dao);

            assertThat(result.getName()).isEqualTo(dao.getNamePL());
            assertThat(result.getDescription()).isEqualTo(dao.getDescriptionPL());
            assertThat(result.getCode()).isEqualTo(dao.getCode());
            assertThat(result.getQuantity()).isEqualTo(dao.getQuantity());
            assertThat(result.getId()).isEqualTo(dao.getId());
        } finally {
            LocaleContextHolder.resetLocaleContext();
        }
    }

    @Property(tries = 100)
    void toServiceExtendedModelDoesNotResolveLocale(
            @ForAll @WithNull(0.3) String nameRU,
            @ForAll @WithNull(0.3) String namePL,
            @ForAll @WithNull(0.3) String descriptionRU,
            @ForAll @WithNull(0.3) String descriptionPL,
            @ForAll Long id,
            @ForAll @WithNull(0.3) String code,
            @ForAll @WithNull(0.3) Integer quantity,
            @ForAll("allLocales") Locale locale
    ) {
        TestDaoModel dao = new TestDaoModel();
        dao.setId(id);
        dao.setNameRU(nameRU);
        dao.setNamePL(namePL);
        dao.setDescriptionRU(descriptionRU);
        dao.setDescriptionPL(descriptionPL);
        dao.setCode(code);
        dao.setQuantity(quantity);

        try {
            LocaleContextHolder.setLocale(locale);

            TestServiceExtendedModel result = mapper.toServiceExtendedModel(dao);

            // Direct field copy — no locale resolution
            assertThat(result.getNameRU()).isEqualTo(dao.getNameRU());
            assertThat(result.getNamePL()).isEqualTo(dao.getNamePL());
            assertThat(result.getDescriptionRU()).isEqualTo(dao.getDescriptionRU());
            assertThat(result.getDescriptionPL()).isEqualTo(dao.getDescriptionPL());
            assertThat(result.getCode()).isEqualTo(dao.getCode());
            assertThat(result.getQuantity()).isEqualTo(dao.getQuantity());
            assertThat(result.getId()).isEqualTo(dao.getId());
        } finally {
            LocaleContextHolder.resetLocaleContext();
        }
    }

    @Provide
    Arbitrary<Locale> nonRuLocales() {
        return Arbitraries.of(
                Locale.forLanguageTag("pl"),
                Locale.forLanguageTag("en"),
                Locale.forLanguageTag("fr"),
                Locale.forLanguageTag("de"),
                Locale.forLanguageTag("es"),
                Locale.forLanguageTag("uk"),
                Locale.forLanguageTag("ja")
        );
    }

    @Provide
    Arbitrary<Locale> allLocales() {
        return Arbitraries.of(
                Locale.forLanguageTag("ru"),
                Locale.forLanguageTag("pl"),
                Locale.forLanguageTag("en"),
                Locale.forLanguageTag("fr"),
                Locale.forLanguageTag("de"),
                Locale.forLanguageTag("es")
        );
    }
}
