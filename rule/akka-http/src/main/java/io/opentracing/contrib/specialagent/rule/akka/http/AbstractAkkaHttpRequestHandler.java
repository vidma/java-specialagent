package io.opentracing.contrib.specialagent.rule.akka.http;

import akka.http.scaladsl.model.HttpRequest;
import akka.http.scaladsl.model.HttpResponse;
import io.opentracing.Scope;
import io.opentracing.Span;
import io.opentracing.contrib.specialagent.OpenTracingApiUtil;
import io.opentracing.tag.Tags;
import io.opentracing.util.GlobalTracer;
import scala.compat.java8.functionConverterImpls.FromJavaFunction;
import scala.concurrent.ExecutionContextExecutor;
import scala.concurrent.Future;

import static io.opentracing.contrib.specialagent.rule.akka.http.AkkaHttpSyncHandler.activateLocalSpanContext;
import static io.opentracing.contrib.specialagent.rule.akka.http.AkkaHttpSyncHandler.buildSpan;

public class AbstractAkkaHttpRequestHandler {
    public static scala.concurrent.Future<HttpResponse> apply(final HttpRequest request, final Future<HttpResponse> originalResponse) {
        final Span span = buildSpan(request);
        try (final Scope scope = GlobalTracer.get().activateSpan(span)) {
            activateLocalSpanContext(span, scope);
            // FIXME: what is right EC here?
            ExecutionContextExecutor ec = scala.concurrent.ExecutionContext.global();
            return originalResponse
                    .map(new FromJavaFunction<>(httpResponse -> {
                        if (httpResponse != null) {
                            span.setTag(Tags.HTTP_STATUS, httpResponse.status().intValue());
                            span.finish();
                        }
                        return httpResponse;
                    }), ec)
                    .transform(new FromJavaFunction<>(tryResponse -> {
                        if (tryResponse.isFailure()) {
                            OpenTracingApiUtil.setErrorTag(span, tryResponse.failed().get());
                            span.finish();
                        }
                        return tryResponse;
                    }), ec);
        }
    }
}
