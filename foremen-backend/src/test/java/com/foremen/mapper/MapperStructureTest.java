package com.foremen.mapper;

import org.junit.jupiter.api.Test;
import org.mapstruct.Mapper;
import org.mapstruct.MapperConfig;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class MapperStructureTest {

    // --- ServiceToDaoMapper structure ---

    @Test
    void serviceToDaoMapper_shouldExtendI18nPropertiesMapper() {
        Class<?>[] interfaces = ServiceToDaoMapper.class.getInterfaces();
        assertThat(interfaces).contains(I18nPropertiesMapper.class);
    }

    @Test
    void serviceToDaoMapper_shouldHaveToCreateDaoModelMethod() throws NoSuchMethodException {
        Method method = ServiceToDaoMapper.class.getDeclaredMethod("toCreateDaoModel", Object.class);
        assertThat(method).isNotNull();
        assertThat(method.getReturnType()).isEqualTo(Object.class);
    }

    @Test
    void serviceToDaoMapper_shouldHaveUpdateFieldsMethod() throws NoSuchMethodException {
        Method method = ServiceToDaoMapper.class.getDeclaredMethod("updateFields", Object.class, Object.class);
        assertThat(method).isNotNull();
        assertThat(method.getReturnType()).isEqualTo(void.class);
    }

    @Test
    void serviceToDaoMapper_shouldHaveProcessI18nEmptyValuesMethod() throws NoSuchMethodException {
        Method method = ServiceToDaoMapper.class.getDeclaredMethod("processI18nEmptyValues", Object.class, Object.class);
        assertThat(method).isNotNull();
        assertThat(method.getReturnType()).isEqualTo(void.class);
    }

    // --- ControllerToServiceMapper structure ---

    @Test
    void controllerToServiceMapper_shouldNotHaveMapperAnnotation() {
        assertThat(ControllerToServiceMapper.class.getAnnotation(Mapper.class)).isNull();
    }

    @Test
    void controllerToServiceMapper_shouldNotHaveMapperConfigAnnotation() {
        assertThat(ControllerToServiceMapper.class.getAnnotation(MapperConfig.class)).isNull();
    }

    @Test
    void controllerToServiceMapper_shouldHaveToServiceExtendedModelMethod() throws NoSuchMethodException {
        Method method = ControllerToServiceMapper.class.getDeclaredMethod("toServiceExtendedModel", Object.class);
        assertThat(method).isNotNull();
        assertThat(method.getReturnType()).isEqualTo(Object.class);
    }

    @Test
    void controllerToServiceMapper_shouldHaveToUpdateServiceExtendedModelMethod() throws NoSuchMethodException {
        Method method = ControllerToServiceMapper.class.getDeclaredMethod("toUpdateServiceExtendedModel", Object.class);
        assertThat(method).isNotNull();
        assertThat(method.getReturnType()).isEqualTo(Object.class);
    }

    @Test
    void controllerToServiceMapper_shouldHaveToCreateResponseMethod() throws NoSuchMethodException {
        Method method = ControllerToServiceMapper.class.getDeclaredMethod("toCreateResponse", Object.class);
        assertThat(method).isNotNull();
        assertThat(method.getReturnType()).isEqualTo(Object.class);
    }

    @Test
    void controllerToServiceMapper_shouldHaveToUpdateResponseMethod() throws NoSuchMethodException {
        Method method = ControllerToServiceMapper.class.getDeclaredMethod("toUpdateResponse", Object.class);
        assertThat(method).isNotNull();
        assertThat(method.getReturnType()).isEqualTo(Object.class);
    }
}
