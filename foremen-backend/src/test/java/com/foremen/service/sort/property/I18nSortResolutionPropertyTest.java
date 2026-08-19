package com.foremen.service.sort.property;

import com.foremen.dao.ReadOnlyAdminDao;
import com.foremen.mapper.ServiceToDaoMapper;
import com.foremen.service.ReadOnlyAdminService;
import jakarta.persistence.EntityManager;
import net.jqwik.api.*;
import net.jqwik.api.arbitraries.SetArbitrary;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.data.domain.Sort;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property 3: I18n Sort Resolution
 *
 * For any sort property string, for any set of i18n-supported properties, and for any locale:
 * - If the property (or the final segment of a dot-notation property) is in the i18n set
 *   AND locale is Russian → the output property is property + "RU" (or prefix.finalSegment + "RU")
 * - If the property (or the final segment of a dot-notation property) is in the i18n set
 *   AND locale is NOT Russian → the output property is property + "PL" (or prefix.finalSegment + "PL")
 * - If the property (or the final segment) is NOT in the i18n set → the output property equals
 *   the input property unchanged
 * - Sort direction (ASC/DESC) is always preserved regardless of i18n resolution
 *
 * Validates: Requirements 3.6, 10.1, 10.2, 10.3, 10.4, 18.1, 18.2, 18.4
 */
@Tag("Feature: FOR-01-06-crud-service, Property 3: I18n Sort Resolution")
class I18nSortResolutionPropertyTest {

    // --- Minimal test implementation of ReadOnlyAdminService ---

    private static ReadOnlyAdminService<Object, Object, Object, Long> createService(Set<String> i18nProperties) {
        return new ReadOnlyAdminService<>() {
            @Override
            public ServiceToDaoMapper<Object, Object, Object> getMapper() {
                return new ServiceToDaoMapper<>() {
                    @Override
                    public Set<String> getI18nSupportedProperties() {
                        return i18nProperties;
                    }

                    @Override
                    public Object toServiceModel(Object source) { return null; }

                    @Override
                    public Object toServiceExtendedModel(Object source) { return null; }

                    @Override
                    public Object toCreateDaoModel(Object source) { return null; }

                    @Override
                    public void updateFields(Object source, Object target) {}
                };
            }

            @Override
            public ReadOnlyAdminDao<Object, Long> getReadDao() { return null; }

            @Override
            public EntityManager getEntityManager() { return null; }
        };
    }

    // --- Property: Simple i18n property with RU locale gets "RU" suffix ---

    @Property(tries = 100)
    void simpleI18nPropertyWithRuLocaleGetsSuffixRU(
            @ForAll("simplePropertyNames") String property,
            @ForAll("i18nPropertySets") Set<String> extraI18nProps
    ) {
        Set<String> i18nProperties = new HashSet<>(extraI18nProps);
        i18nProperties.add(property);

        var service = createService(i18nProperties);
        Sort sort = Sort.by(Sort.Order.asc(property));

        try {
            LocaleContextHolder.setLocale(Locale.forLanguageTag("ru"));
            Sort result = service.processSort(sort);

            List<Sort.Order> orders = result.toList();
            assertThat(orders).hasSize(1);
            assertThat(orders.getFirst().getProperty()).isEqualTo(property + "RU");
        } finally {
            LocaleContextHolder.resetLocaleContext();
        }
    }

    // --- Property: Simple i18n property with non-RU locale gets "PL" suffix ---

    @Property(tries = 100)
    void simpleI18nPropertyWithNonRuLocaleGetsSuffixPL(
            @ForAll("simplePropertyNames") String property,
            @ForAll("i18nPropertySets") Set<String> extraI18nProps,
            @ForAll("nonRuLocales") Locale locale
    ) {
        Set<String> i18nProperties = new HashSet<>(extraI18nProps);
        i18nProperties.add(property);

        var service = createService(i18nProperties);
        Sort sort = Sort.by(Sort.Order.desc(property));

        try {
            LocaleContextHolder.setLocale(locale);
            Sort result = service.processSort(sort);

            List<Sort.Order> orders = result.toList();
            assertThat(orders).hasSize(1);
            assertThat(orders.getFirst().getProperty()).isEqualTo(property + "PL");
        } finally {
            LocaleContextHolder.resetLocaleContext();
        }
    }

    // --- Property: Non-i18n property remains unchanged ---

    @Property(tries = 100)
    void nonI18nPropertyRemainsUnchanged(
            @ForAll("simplePropertyNames") String property,
            @ForAll("i18nPropertySets") Set<String> i18nProperties,
            @ForAll("allLocales") Locale locale
    ) {
        // Ensure property is NOT in the i18n set
        Set<String> filteredI18n = new HashSet<>(i18nProperties);
        filteredI18n.remove(property);

        var service = createService(filteredI18n);
        Sort sort = Sort.by(Sort.Order.asc(property));

        try {
            LocaleContextHolder.setLocale(locale);
            Sort result = service.processSort(sort);

            List<Sort.Order> orders = result.toList();
            assertThat(orders).hasSize(1);
            assertThat(orders.getFirst().getProperty()).isEqualTo(property);
        } finally {
            LocaleContextHolder.resetLocaleContext();
        }
    }

    // --- Property: Dot-notation resolves i18n on final segment only ---

    @Property(tries = 100)
    void nestedPathResolvesI18nOnFinalSegmentWithRuLocale(
            @ForAll("simplePropertyNames") String prefix,
            @ForAll("simplePropertyNames") String finalSegment,
            @ForAll("i18nPropertySets") Set<String> extraI18nProps
    ) {
        // Ensure final segment is in i18n properties
        Set<String> i18nProperties = new HashSet<>(extraI18nProps);
        i18nProperties.add(finalSegment);

        var service = createService(i18nProperties);
        String nestedProperty = prefix + "." + finalSegment;
        Sort sort = Sort.by(Sort.Order.asc(nestedProperty));

        try {
            LocaleContextHolder.setLocale(Locale.forLanguageTag("ru"));
            Sort result = service.processSort(sort);

            List<Sort.Order> orders = result.toList();
            assertThat(orders).hasSize(1);
            assertThat(orders.getFirst().getProperty()).isEqualTo(prefix + "." + finalSegment + "RU");
        } finally {
            LocaleContextHolder.resetLocaleContext();
        }
    }

    @Property(tries = 100)
    void nestedPathResolvesI18nOnFinalSegmentWithNonRuLocale(
            @ForAll("simplePropertyNames") String prefix,
            @ForAll("simplePropertyNames") String finalSegment,
            @ForAll("i18nPropertySets") Set<String> extraI18nProps,
            @ForAll("nonRuLocales") Locale locale
    ) {
        Set<String> i18nProperties = new HashSet<>(extraI18nProps);
        i18nProperties.add(finalSegment);

        var service = createService(i18nProperties);
        String nestedProperty = prefix + "." + finalSegment;
        Sort sort = Sort.by(Sort.Order.desc(nestedProperty));

        try {
            LocaleContextHolder.setLocale(locale);
            Sort result = service.processSort(sort);

            List<Sort.Order> orders = result.toList();
            assertThat(orders).hasSize(1);
            assertThat(orders.getFirst().getProperty()).isEqualTo(prefix + "." + finalSegment + "PL");
        } finally {
            LocaleContextHolder.resetLocaleContext();
        }
    }

    // --- Property: Nested path with non-i18n final segment remains unchanged ---

    @Property(tries = 100)
    void nestedPathWithNonI18nFinalSegmentUnchanged(
            @ForAll("simplePropertyNames") String prefix,
            @ForAll("simplePropertyNames") String finalSegment,
            @ForAll("i18nPropertySets") Set<String> i18nProperties,
            @ForAll("allLocales") Locale locale
    ) {
        // Ensure final segment is NOT in i18n properties
        Set<String> filteredI18n = new HashSet<>(i18nProperties);
        filteredI18n.remove(finalSegment);

        var service = createService(filteredI18n);
        String nestedProperty = prefix + "." + finalSegment;
        Sort sort = Sort.by(Sort.Order.asc(nestedProperty));

        try {
            LocaleContextHolder.setLocale(locale);
            Sort result = service.processSort(sort);

            List<Sort.Order> orders = result.toList();
            assertThat(orders).hasSize(1);
            assertThat(orders.getFirst().getProperty()).isEqualTo(nestedProperty);
        } finally {
            LocaleContextHolder.resetLocaleContext();
        }
    }

    // --- Property: Sort direction is always preserved ---

    @Property(tries = 100)
    void sortDirectionAlwaysPreserved(
            @ForAll("simplePropertyNames") String property,
            @ForAll("i18nPropertySets") Set<String> i18nProperties,
            @ForAll("allLocales") Locale locale,
            @ForAll Sort.Direction direction
    ) {
        var service = createService(i18nProperties);
        Sort sort = Sort.by(new Sort.Order(direction, property));

        try {
            LocaleContextHolder.setLocale(locale);
            Sort result = service.processSort(sort);

            List<Sort.Order> orders = result.toList();
            assertThat(orders).hasSize(1);
            assertThat(orders.getFirst().getDirection()).isEqualTo(direction);
        } finally {
            LocaleContextHolder.resetLocaleContext();
        }
    }

    // --- Property: Deep nested path resolves i18n on final segment ---

    @Property(tries = 100)
    void deepNestedPathResolvesI18nOnFinalSegment(
            @ForAll("simplePropertyNames") String seg1,
            @ForAll("simplePropertyNames") String seg2,
            @ForAll("simplePropertyNames") String finalSegment,
            @ForAll("i18nPropertySets") Set<String> extraI18nProps,
            @ForAll("allLocales") Locale locale
    ) {
        Set<String> i18nProperties = new HashSet<>(extraI18nProps);
        i18nProperties.add(finalSegment);

        var service = createService(i18nProperties);
        String deepNestedProperty = seg1 + "." + seg2 + "." + finalSegment;
        Sort sort = Sort.by(Sort.Order.asc(deepNestedProperty));

        String expectedSuffix = "ru".equalsIgnoreCase(locale.getLanguage()) ? "RU" : "PL";

        try {
            LocaleContextHolder.setLocale(locale);
            Sort result = service.processSort(sort);

            List<Sort.Order> orders = result.toList();
            assertThat(orders).hasSize(1);
            assertThat(orders.getFirst().getProperty())
                    .isEqualTo(seg1 + "." + seg2 + "." + finalSegment + expectedSuffix);
        } finally {
            LocaleContextHolder.resetLocaleContext();
        }
    }

    // --- Providers ---

    @Provide
    Arbitrary<String> simplePropertyNames() {
        return Arbitraries.strings()
                .withCharRange('a', 'z')
                .ofMinLength(2)
                .ofMaxLength(12);
    }

    @Provide
    SetArbitrary<String> i18nPropertySets() {
        return Arbitraries.strings()
                .withCharRange('a', 'z')
                .ofMinLength(2)
                .ofMaxLength(10)
                .set()
                .ofMinSize(0)
                .ofMaxSize(5);
    }

    @Provide
    Arbitrary<Locale> nonRuLocales() {
        return Arbitraries.of(
                Locale.forLanguageTag("pl"),
                Locale.forLanguageTag("en"),
                Locale.forLanguageTag("fr"),
                Locale.forLanguageTag("de"),
                Locale.forLanguageTag("es"),
                Locale.forLanguageTag("uk")
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
