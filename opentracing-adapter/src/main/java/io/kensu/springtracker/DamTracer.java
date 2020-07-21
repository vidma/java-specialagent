package io.kensu.springtracker;

import io.opentracing.*;
import io.opentracing.propagation.Format;
import io.opentracing.tag.Tag;
import io.opentracing.util.ThreadLocalScopeManager;

public class DamTracer implements Tracer {
    // FIXME: would it work if queries for same endpoint are run from mutliple threads
    private ScopeManager scopeManager = new ThreadLocalScopeManager();

    @Override
    public ScopeManager scopeManager() {
        return scopeManager;
    }

    @Override
    public Span activeSpan() {
        return scopeManager.activeSpan();
    }

    @Override
    public Scope activateSpan(Span span) {
        return scopeManager.activate(span);
    }

    @Override
    public SpanBuilder buildSpan(String operationName) {
        return new DamSpanBuilder(this, operationName);
    }

    @Override
    public <C> void inject(SpanContext spanContext, Format<C> format, C c) {

    }

    @Override
    public <C> SpanContext extract(Format<C> format, C c) {
        return null;
    }

    @Override
    public void close() {

    }

    public static class DamSpanBuilder implements SpanBuilder {
        private final DamTracer damTracer;
        private final String operationName;

        public DamSpanBuilder(DamTracer damTracer, String operationName) {
            this.damTracer = damTracer;
            this.operationName = operationName;
        }

        @Override
        public SpanBuilder asChildOf(SpanContext spanContext) {
            return null;
        }

        @Override
        public SpanBuilder asChildOf(Span span) {
            return null;
        }

        @Override
        public SpanBuilder addReference(String s, SpanContext spanContext) {
            return null;
        }

        @Override
        public SpanBuilder ignoreActiveSpan() {
            return null;
        }

        @Override
        public SpanBuilder withTag(String s, String s1) {
            return null;
        }

        @Override
        public SpanBuilder withTag(String s, boolean b) {
            return null;
        }

        @Override
        public SpanBuilder withTag(String s, Number number) {
            return null;
        }

        @Override
        public <T> SpanBuilder withTag(Tag<T> tag, T t) {
            return null;
        }

        @Override
        public SpanBuilder withStartTimestamp(long l) {
            return null;
        }

        @Override
        public Span startManual() {
            return null;
        }

        @Override
        public Span start() {
            return null;
        }

        @Override
        public Scope startActive(boolean b) {
            return null;
        }
    }
}
