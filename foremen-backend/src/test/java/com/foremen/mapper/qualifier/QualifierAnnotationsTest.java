package com.foremen.mapper.qualifier;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import static org.assertj.core.api.Assertions.assertThat;

class QualifierAnnotationsTest {

    /**
     * MapStruct's @Qualifier has @Retention(CLASS), so it is not visible via runtime reflection.
     * We verify its presence by checking the class file bytecode contains the descriptor reference.
     */
    @Test
    void toServiceModel_shouldHaveQualifierAnnotation() throws IOException {
        assertClassFileContainsAnnotationDescriptor(ToServiceModel.class, "org/mapstruct/Qualifier");
    }

    @Test
    void toServiceModel_shouldTargetMethod() {
        Target target = ToServiceModel.class.getAnnotation(Target.class);
        assertThat(target).isNotNull();
        assertThat(target.value()).containsExactly(ElementType.METHOD);
    }

    @Test
    void toServiceModel_shouldHaveClassRetention() {
        Retention retention = ToServiceModel.class.getAnnotation(Retention.class);
        assertThat(retention).isNotNull();
        assertThat(retention.value()).isEqualTo(RetentionPolicy.CLASS);
    }

    @Test
    void toExtendedServiceModel_shouldHaveQualifierAnnotation() throws IOException {
        assertClassFileContainsAnnotationDescriptor(ToExtendedServiceModel.class, "org/mapstruct/Qualifier");
    }

    @Test
    void toExtendedServiceModel_shouldTargetMethod() {
        Target target = ToExtendedServiceModel.class.getAnnotation(Target.class);
        assertThat(target).isNotNull();
        assertThat(target.value()).containsExactly(ElementType.METHOD);
    }

    @Test
    void toExtendedServiceModel_shouldHaveClassRetention() {
        Retention retention = ToExtendedServiceModel.class.getAnnotation(Retention.class);
        assertThat(retention).isNotNull();
        assertThat(retention.value()).isEqualTo(RetentionPolicy.CLASS);
    }

    /**
     * Verifies that a class file bytecode contains a reference to the given annotation descriptor.
     * This is necessary for annotations with @Retention(CLASS) which are not available via runtime reflection.
     */
    private void assertClassFileContainsAnnotationDescriptor(Class<?> clazz, String annotationInternalName) throws IOException {
        String classResourcePath = clazz.getName().replace('.', '/') + ".class";
        try (InputStream is = clazz.getClassLoader().getResourceAsStream(classResourcePath)) {
            assertThat(is).as("Class file for %s should be loadable", clazz.getName()).isNotNull();
            byte[] classBytes = is.readAllBytes();
            String classContent = new String(classBytes, java.nio.charset.StandardCharsets.ISO_8859_1);
            assertThat(classContent).as("Class file for %s should contain @Qualifier annotation descriptor", clazz.getName())
                    .contains(annotationInternalName);
        }
    }
}
