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

import java.util.function.Function;
import java.util.function.Supplier;

public class AkkaHttpAsyncHandlerWithRequestCtx implements scala.Function1<RequestContext,scala.concurrent.Future<RouteResult>> {
    private final scala.Function1<RequestContext,scala.concurrent.Future<RouteResult>> originalRequestHandler;

    public AkkaHttpAsyncHandlerWithRequestCtx(final Function1<RequestContext, Future<RouteResult>> originalRequestHandler) {
        this.originalRequestHandler = originalRequestHandler;
    }

    @Override
    public scala.concurrent.Future<RouteResult> apply(final RequestContext requestContext) {
        HttpRequest request = requestContext.request();
        Supplier<Future<RouteResult>> originalRouteResultFn = () -> originalRequestHandler.apply(requestContext);
        Function<RouteResult, HttpResponse> getHttpResponseFromOrigResultFn = routeResult -> {
            if (routeResult instanceof Complete) {
                return ((Complete) routeResult).getResponse();
            } else return null;
        };
        return new AbstractAkkaHttpRequestHandler().apply(request, originalRouteResultFn, getHttpResponseFromOrigResultFn);
    }
}
