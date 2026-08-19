package com.foremen.config.mapper;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.Annotation;
import java.lang.classfile.AnnotationElement;
import java.lang.classfile.AnnotationValue;
import java.lang.classfile.attribute.RuntimeInvisibleAnnotationsAttribute;

import static org.assertj.core.api.Assertions.assertThat;

class ForemenMapperConfigTest {

    private static final String MAPPER_CONFIG_DESCRIPTOR = "Lorg/mapstruct/MapperConfig;";

    private ClassModel getClassModel() throws IOException {
        String classResource = ForemenMapperConfig.class.getName().replace('.', '/') + ".class";
        try (InputStream is = ForemenMapperConfig.class.getClassLoader().getResourceAsStream(classResource)) {
            assertThat(is).isNotNull();
            byte[] bytes = is.readAllBytes();
            return ClassFile.of().parse(bytes);
        }
    }

    private Annotation findMapperConfigAnnotation(ClassModel classModel) {
        var invisibleAnnotations = classModel.findAttribute(java.lang.classfile.Attributes.runtimeInvisibleAnnotations());
        assertThat(invisibleAnnotations).isPresent();
        return invisibleAnnotations.get().annotations().stream()
                .filter(a -> a.classSymbol().descriptorString().equals(MAPPER_CONFIG_DESCRIPTOR))
                .findFirst()
                .orElse(null);
    }

    @Test
    void shouldBeAnnotatedWithMapperConfig() throws IOException {
        ClassModel classModel = getClassModel();
        Annotation annotation = findMapperConfigAnnotation(classModel);
        assertThat(annotation).isNotNull();
    }

    @Test
    void shouldUseSpringComponentModel() throws IOException {
        ClassModel classModel = getClassModel();
        Annotation annotation = findMapperConfigAnnotation(classModel);
        assertThat(annotation).isNotNull();

        String componentModel = annotation.elements().stream()
                .filter(e -> e.name().equalsString("componentModel"))
                .map(e -> ((AnnotationValue.OfString) e.value()).stringValue())
                .findFirst()
                .orElse(null);

        assertThat(componentModel).isEqualTo("spring");
    }

    @Test
    void shouldDisableBuilder() throws IOException {
        ClassModel classModel = getClassModel();
        Annotation annotation = findMapperConfigAnnotation(classModel);
        assertThat(annotation).isNotNull();

        // The builder attribute is a nested annotation @Builder(disableBuilder = true)
        AnnotationValue.OfAnnotation builderValue = annotation.elements().stream()
                .filter(e -> e.name().equalsString("builder"))
                .map(e -> (AnnotationValue.OfAnnotation) e.value())
                .findFirst()
                .orElse(null);

        assertThat(builderValue).isNotNull();
        Annotation builderAnnotation = builderValue.annotation();

        Boolean disableBuilder = builderAnnotation.elements().stream()
                .filter(e -> e.name().equalsString("disableBuilder"))
                .map(e -> ((AnnotationValue.OfBoolean) e.value()).booleanValue())
                .findFirst()
                .orElse(null);

        assertThat(disableBuilder).isTrue();
    }

    @Test
    void shouldIgnoreNullValuePropertyMapping() throws IOException {
        ClassModel classModel = getClassModel();
        Annotation annotation = findMapperConfigAnnotation(classModel);
        assertThat(annotation).isNotNull();

        // nullValuePropertyMappingStrategy is an enum value
        AnnotationValue.OfEnum enumValue = annotation.elements().stream()
                .filter(e -> e.name().equalsString("nullValuePropertyMappingStrategy"))
                .map(e -> (AnnotationValue.OfEnum) e.value())
                .findFirst()
                .orElse(null);

        assertThat(enumValue).isNotNull();
        assertThat(enumValue.constantName().equalsString("IGNORE")).isTrue();
    }
}
