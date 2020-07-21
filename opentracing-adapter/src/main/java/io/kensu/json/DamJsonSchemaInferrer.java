package io.kensu.json;

import io.kensu.springtracker.DamSchemaTag;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.saasquatch.jsonschemainferrer.JsonSchemaInferrer;
import com.saasquatch.jsonschemainferrer.SpecVersion;
import io.kensu.collector.model.DamSchemaUtils;
import io.kensu.dam.model.FieldDef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public class DamJsonSchemaInferrer {
    public static  DamSchemaTag DAM_OUTPUT_SCHEMA_TAG =  new DamSchemaTag("DamOutputSchema");

    private static final Logger logger = LoggerFactory.getLogger(DamJsonSchemaInferrer.class.getName());

    private static final ObjectMapper mapper = new ObjectMapper();
    private static final JsonSchemaInferrer inferrer = JsonSchemaInferrer.newBuilder()
            .setSpecVersion(SpecVersion.DRAFT_06)
            .build();

    public JsonNode customJsonTransformer(JsonNode json){
        // unnest the _embedded field returning its contents to be used for the schema
        return (!json.has("_embedded")) ? json : json.get("_embedded");
    }

    public Set<FieldDef> inferSchema(String json) {
        try {
            final JsonNode cleanedupOutputJson = customJsonTransformer(mapper.readTree(json));
            final JsonNode jsonSchema = inferrer.inferForSample(cleanedupOutputJson);
            logger.warn(mapper.writeValueAsString(jsonSchema));
            return convertToDamSchema(jsonSchema);
        } catch (JsonProcessingException e) {
            logger.error("Unable to infer schema for JSON: " + json, e);
            return DamSchemaUtils.EMPTY_SCHEMA;
        }
    }

    private Set<FieldDef> convertToDamSchema(final JsonNode jsonSchema){
        final ObjectNode properties = (ObjectNode) (jsonSchema.get("properties"));
        Set<FieldDef> damSchema = new HashSet<>();
        Iterator<Map.Entry<String, JsonNode>> fieldsIterator = properties.fields();
        while (fieldsIterator.hasNext()) {
            Map.Entry<String, JsonNode> entry = fieldsIterator.next();
            String fieldName = entry.getKey();
            String fieldType = entry.getValue().get("type").asText("unknown");
            if (!fieldName.startsWith("_links")) {
                damSchema.add(DamSchemaUtils.fieldWithMissingNullable(fieldName, fieldType));
            }
        }
        return damSchema;
    }
}
