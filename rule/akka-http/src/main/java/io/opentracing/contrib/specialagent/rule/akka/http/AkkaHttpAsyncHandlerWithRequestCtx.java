package io.opentracing.contrib.specialagent.rule.akka.http;

import akka.http.javadsl.Http;
import akka.http.scaladsl.server.RouteResult.Complete;
import akka.http.scaladsl.server.RouteResult;
import akka.http.scaladsl.model.HttpRequest;
import akka.http.scaladsl.model.HttpResponse;
import akka.http.scaladsl.server.RequestContext;
import io.opentracing.tag.Tags;
import scala.Function1;
import scala.compat.java8.functionConverterImpls.FromJavaFunction;
import scala.concurrent.ExecutionContextExecutor;
import scala.concurrent.Future;

public class AkkaHttpAsyncHandlerWithRequestCtx implements scala.Function1<RequestContext,scala.concurrent.Future<RouteResult>> {
    private final scala.Function1<RequestContext,scala.concurrent.Future<RouteResult>> originalRequestHandler;

    public AkkaHttpAsyncHandlerWithRequestCtx(final Function1<RequestContext, Future<RouteResult>> originalRequestHandler) {
        this.originalRequestHandler = originalRequestHandler;
    }

    @Override
    public scala.concurrent.Future<RouteResult> apply(final RequestContext requestContext) {
        HttpRequest request = requestContext.request();
        // FIXME: nasty code... change/refactor how span helpers works...
        Future<RouteResult> originalRouteResult = originalRequestHandler.apply(requestContext);
        ExecutionContextExecutor ec = scala.concurrent.ExecutionContext.global();
        Future<HttpResponse> httpRespFuture = originalRouteResult.map(new FromJavaFunction<>(routeResult -> {
            if (routeResult instanceof Complete) {
                return ((Complete) routeResult).getResponse();
            } else return null;
        }), ec);
        return AbstractAkkaHttpRequestHandler.apply(request, httpRespFuture)
                .flatMap(new FromJavaFunction<>(routeResult -> {
                    return originalRouteResult;
                }), ec);
    }
}
