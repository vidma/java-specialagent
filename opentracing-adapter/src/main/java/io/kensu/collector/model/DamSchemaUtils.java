package io.kensu.collector.model;

import io.kensu.dam.model.FieldDef;
import io.kensu.utils.ConcurrentHashMultimap;

import java.util.Collections;
import java.util.Set;

public class DamSchemaUtils {
    public static String UNKNOWN_DATATYPE = "unknown";
    public static Boolean UNKNOWN_NULLABLE = true;
    public static FieldDef UNKNOWN_FIELD_DEF = DamSchemaUtils.fieldWithMissingInfo("unknown_field");
    public static Set<FieldDef> EMPTY_SCHEMA = Collections.singleton(UNKNOWN_FIELD_DEF);


    public static ConcurrentHashMultimap<FieldDef> fieldsWithMissingInfoForPath(String path) {
        return new ConcurrentHashMultimap<>(path, UNKNOWN_FIELD_DEF);
    }

    public static FieldDef fieldWithMissingInfo(String name) {
        return new FieldDef()
                .name(name)
                .fieldType(UNKNOWN_DATATYPE)
                .nullable(UNKNOWN_NULLABLE);
    }

    public static FieldDef fieldWithMissingNullable(String name, String dataType) {
        return new FieldDef()
                .name(name)
                .fieldType(dataType)
                .nullable(UNKNOWN_NULLABLE);
    }
}
