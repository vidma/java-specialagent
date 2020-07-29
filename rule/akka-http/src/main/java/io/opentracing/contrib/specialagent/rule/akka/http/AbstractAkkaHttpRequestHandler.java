package io.opentracing.contrib.specialagent.rule.akka.http;

import akka.http.scaladsl.model.HttpRequest;
import akka.http.scaladsl.model.HttpResponse;
import akka.http.scaladsl.server.RouteResult;
import io.opentracing.Scope;
import io.opentracing.Span;
import io.opentracing.contrib.specialagent.OpenTracingApiUtil;
import io.opentracing.tag.Tags;
import io.opentracing.util.GlobalTracer;
import scala.compat.java8.functionConverterImpls.FromJavaFunction;
import scala.concurrent.ExecutionContextExecutor;
import scala.concurrent.Future;

import java.util.function.Function;
import java.util.function.Supplier;

import static io.opentracing.contrib.specialagent.rule.akka.http.AkkaHttpSyncHandler.activateLocalSpanContext;
import static io.opentracing.contrib.specialagent.rule.akka.http.AkkaHttpSyncHandler.buildSpan;

public class AbstractAkkaHttpRequestHandler<R> {
    public scala.concurrent.Future<R> apply(final HttpRequest request,
                                            final Supplier<Future<R>> originalResultFn,
                                            final Function<R, HttpResponse> getHttpResponseFromOrigResultFn) {
        final Span span = buildSpan(request);
        try (final Scope scope = GlobalTracer.get().activateSpan(span)) {
            activateLocalSpanContext(span, scope);
            ExecutionContextExecutor ec = scala.concurrent.ExecutionContext.global();
            return originalResultFn
                    .get()
                    .map(new FromJavaFunction<>(origHttpResult -> {
                        HttpResponse httpResponse = getHttpResponseFromOrigResultFn.apply(origHttpResult);
                        if (httpResponse != null) {
                            span.setTag(Tags.HTTP_STATUS, httpResponse.status().intValue());
                            span.finish();
                        }
                        return origHttpResult;
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
