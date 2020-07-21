package io.kensu.jdbc.parser;

import io.kensu.dam.model.FieldDef;
import io.kensu.utils.ConcurrentHashMultimap;

public class ReferencedSchemaFieldsInfo {
    public final ConcurrentHashMultimap<FieldDef> data, control, schema;
    public final String lineageOperation;

    public ReferencedSchemaFieldsInfo(String lineageOperation, ConcurrentHashMultimap<FieldDef> data, ConcurrentHashMultimap<FieldDef> control){
        this.lineageOperation = lineageOperation;
        this.data = data;
        this.control = control;
        this.schema = new ConcurrentHashMultimap<FieldDef>();
        this.schema.putAll(data);
        this.schema.putAll(control);
    }
}
