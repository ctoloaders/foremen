package com.foremen.service.property;

import com.foremen.dao.model.OperationEntity;
import com.foremen.dao.model.ResourceEntity;
import com.foremen.dao.model.RoleEntity;
import com.foremen.service.model.*;
import com.foremen.service.model.mapper.OperationServiceMapper;
import com.foremen.service.model.mapper.ResourceServiceMapper;
import com.foremen.service.model.mapper.RoleServiceMapper;
import net.jqwik.api.*;
import org.mapstruct.factory.Mappers;
import org.springframework.context.i18n.LocaleContextHolder;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag("Feature: FOR-02-03-abac-entities, Property 1: I18n Locale Resolution")
class I18nLocaleResolutionPropertyTest {

    private final ResourceServiceMapper resourceMapper = Mappers.getMapper(ResourceServiceMapper.class);
    private final OperationServiceMapper operationMapper = Mappers.getMapper(OperationServiceMapper.class);
    private final RoleServiceMapper roleMapper = Mappers.getMapper(RoleServiceMapper.class);

    @Property(tries = 100)
    void resourceNameResolvesToCorrectLocale(
            @ForAll("nonBlankStrings") String nameRU,
            @ForAll("nonBlankStrings") String namePL,
            @ForAll("nonBlankStrings") String descRU,
            @ForAll("nonBlankStrings") String descPL,
            @ForAll("locales") Locale locale) {

        ResourceEntity entity = new ResourceEntity();
        entity.setCode("TEST");
        entity.setNameRU(nameRU);
        entity.setNamePL(namePL);
        entity.setDescriptionRU(descRU);
        entity.setDescriptionPL(descPL);

        LocaleContextHolder.setLocale(locale);
        try {
            ResourceServiceModel model = resourceMapper.toServiceModel(entity);
            if ("ru".equalsIgnoreCase(locale.getLanguage())) {
                assertEquals(nameRU, model.getName());
                assertEquals(descRU, model.getDescription());
            } else {
                assertEquals(namePL, model.getName());
                assertEquals(descPL, model.getDescription());
            }
        } finally {
            LocaleContextHolder.resetLocaleContext();
        }
    }

    @Property(tries = 100)
    void operationNameResolvesToCorrectLocale(
            @ForAll("nonBlankStrings") String nameRU,
            @ForAll("nonBlankStrings") String namePL,
            @ForAll("locales") Locale locale) {

        OperationEntity entity = new OperationEntity();
        entity.setCode("TEST");
        entity.setNameRU(nameRU);
        entity.setNamePL(namePL);

        LocaleContextHolder.setLocale(locale);
        try {
            OperationServiceModel model = operationMapper.toServiceModel(entity);
            if ("ru".equalsIgnoreCase(locale.getLanguage())) {
                assertEquals(nameRU, model.getName());
            } else {
                assertEquals(namePL, model.getName());
            }
        } finally {
            LocaleContextHolder.resetLocaleContext();
        }
    }

    @Property(tries = 100)
    void roleNameResolvesToCorrectLocale(
            @ForAll("nonBlankStrings") String nameRU,
            @ForAll("nonBlankStrings") String namePL,
            @ForAll("nonBlankStrings") String descRU,
            @ForAll("nonBlankStrings") String descPL,
            @ForAll("locales") Locale locale) {

        RoleEntity entity = new RoleEntity();
        entity.setCode("TEST");
        entity.setNameRU(nameRU);
        entity.setNamePL(namePL);
        entity.setDescriptionRU(descRU);
        entity.setDescriptionPL(descPL);
        entity.setSystem(false);

        LocaleContextHolder.setLocale(locale);
        try {
            RoleServiceModel model = roleMapper.toServiceModel(entity);
            if ("ru".equalsIgnoreCase(locale.getLanguage())) {
                assertEquals(nameRU, model.getName());
                assertEquals(descRU, model.getDescription());
            } else {
                assertEquals(namePL, model.getName());
                assertEquals(descPL, model.getDescription());
            }
        } finally {
            LocaleContextHolder.resetLocaleContext();
        }
    }

    @Provide
    Arbitrary<String> nonBlankStrings() {
        return Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(50);
    }

    @Provide
    Arbitrary<Locale> locales() {
        return Arbitraries.of(Locale.of("ru"), Locale.of("pl"), Locale.ENGLISH, Locale.FRENCH);
    }
}
