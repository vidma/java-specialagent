package io.kensu.json;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.saasquatch.jsonschemainferrer.JsonSchemaInferrer;
import com.saasquatch.jsonschemainferrer.SpecVersion;
import io.kensu.collector.model.DamSchemaUtils;
import io.kensu.dam.model.FieldDef;
import io.kensu.logging.KensuLogger;

import java.util.*;

public class DamJsonSchemaInferrer {
    public static  String DAM_OUTPUT_SCHEMA_TAG =  "http.output_schema";

    private static final KensuLogger logger = new KensuLogger(DamJsonSchemaInferrer.class);

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

    public String inferSchemaAsJsonString(String json) {
        try {
            final JsonNode cleanedupOutputJson = customJsonTransformer(mapper.readTree(json));
            final JsonNode jsonSchema = inferrer.inferForSample(cleanedupOutputJson);
            return mapper.writeValueAsString(jsonSchema);
        } catch (JsonProcessingException e) {
            logger.error("Error while trying to parse json in inferSchemaAsJsonString", e);
            return null;
        }
    }

    public Set<FieldDef> convertToDamSchemaFromString(String jsonSchema){
        try {
            return convertToDamSchema(mapper.readTree(jsonSchema));
        } catch (JsonProcessingException e) {
            logger.error("Unable to convert json schema for JSON: " + jsonSchema, e);
        }
        return DamSchemaUtils.EMPTY_SCHEMA;
    }

    private Set<FieldDef> convertToDamSchema(final JsonNode jsonSchema){
        Set<FieldDef> damSchema = new HashSet<>();
        final ObjectNode properties = (ObjectNode) (jsonSchema.get("properties"));
        if (properties == null)
            return damSchema;
        Iterator<Map.Entry<String, JsonNode>> fieldsIterator = properties.fields();
        while (fieldsIterator.hasNext()) {
            Map.Entry<String, JsonNode> entry = fieldsIterator.next();
            String fieldName = entry.getKey();
            JsonNode childNode = entry.getValue();
            String fieldType = childNode.get("type").asText("unknown");
            if (!fieldName.startsWith("_links")) {
                damSchema.add(DamSchemaUtils.fieldWithMissingNullable(fieldName, fieldType));
                // flatten the nested fields within objects...
                if (fieldType.equals("object")) {
                    Set<FieldDef> subfields = convertToDamSchema(childNode);
                    for (FieldDef subfield : subfields) {
                        damSchema.add(DamSchemaUtils.fieldWithMissingNullable(fieldName + "." + subfield.getName(), subfield.getFieldType()));
                    }
                }
                if (fieldType.equals("array")) {
                    JsonNode arrayItemsDesc = childNode.get("items");
                    if (arrayItemsDesc != null) {
                        Set<FieldDef> subfields = convertToDamSchema(arrayItemsDesc);
                        for (FieldDef subfield : subfields) {
                            damSchema.add(DamSchemaUtils.fieldWithMissingNullable(fieldName + "[i]." + subfield.getName(), subfield.getFieldType()));
                        }
                    }
                }
            }
        }
        logger.debug("DAM SCHEMA: " + damSchema);
        return damSchema;
    }
}
