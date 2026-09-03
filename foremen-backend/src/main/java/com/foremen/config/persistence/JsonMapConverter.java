package com.foremen.config.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.postgresql.util.PGobject;

import java.sql.SQLException;
import java.util.Map;

/**
 * JPA AttributeConverter for Map&lt;String, Object&gt; ↔ PostgreSQL JSONB.
 * Handles serialization/deserialization using Jackson.
 *
 * <p>convertToDatabaseColumn returns a {@link PGobject} typed as {@code jsonb} so the JDBC
 * driver sends the value with the correct PostgreSQL type. Returning a plain String would cause
 * PostgreSQL to reject the value with a type-mismatch error against the {@code jsonb} column.
 */
@Converter(autoApply = false)
public class JsonMapConverter implements AttributeConverter<Map<String, Object>, Object> {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE_REF = new TypeReference<>() {};

    @Override
    public Object convertToDatabaseColumn(Map<String, Object> attribute) {
        if (attribute == null) {
            return null;
        }
        try {
            String json = MAPPER.writeValueAsString(attribute);
            PGobject pgObject = new PGobject();
            pgObject.setType("jsonb");
            pgObject.setValue(json);
            return pgObject;
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Cannot serialize display_preferences to JSON", e);
        } catch (SQLException e) {
            throw new IllegalArgumentException("Cannot wrap display_preferences as jsonb", e);
        }
    }

    @Override
    public Map<String, Object> convertToEntityAttribute(Object dbData) {
        if (dbData == null) {
            return null;
        }
        String json;
        if (dbData instanceof PGobject pgObject) {
            json = pgObject.getValue();
        } else {
            json = dbData.toString();
        }
        if (json == null) {
            return null;
        }
        try {
            return MAPPER.readValue(json, MAP_TYPE_REF);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Cannot deserialize display_preferences from JSON", e);
        }
    }
}
