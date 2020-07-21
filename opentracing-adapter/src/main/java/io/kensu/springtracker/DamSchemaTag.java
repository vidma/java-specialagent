package io.kensu.springtracker;

import io.kensu.dam.model.FieldDef;
import io.opentracing.Span;
import io.opentracing.tag.AbstractTag;

import java.util.List;
import java.util.Set;

public class DamSchemaTag extends AbstractTag<Set<FieldDef>> {
    public DamSchemaTag(String key) {
        super(key);
    }

    @Override
    public void set(Span span, Set<FieldDef> tagValue) {
        span.setTag(this, tagValue);
    }
}
