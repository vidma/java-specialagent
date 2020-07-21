//package io.kensu.springtracker;
//
//import io.opentracing.Scope;
//import io.opentracing.Span;
//import io.opentracing.SpanContext;
//import io.opentracing.Tracer;
//import io.opentracing.contrib.web.servlet.filter.HttpServletRequestExtractAdapter;
//import io.opentracing.contrib.web.servlet.filter.ServletFilterSpanDecorator;
//import io.opentracing.contrib.web.servlet.filter.TracingFilter;
//import io.opentracing.propagation.Format;
//import io.opentracing.tag.Tags;
//import org.springframework.web.util.ContentCachingResponseWrapper;
//
//import javax.servlet.*;
//import javax.servlet.http.HttpServletRequest;
//import javax.servlet.http.HttpServletResponse;
//import java.io.IOException;
//import java.util.ArrayList;
//import java.util.Collections;
//import java.util.List;
//import java.util.regex.Pattern;
//
//public class DAMTracingFilter extends TracingFilter {
//    private final ArrayList<ServletFilterSpanDecorator> spanDecorators;
//
//    /**
//     *
//     * @param tracer tracer
//     * @param spanDecorators decorators
//     * @param skipPattern null or pattern to exclude certain paths from tracing e.g. "/health"
//     */
//    public DAMTracingFilter(Tracer tracer, List<ServletFilterSpanDecorator> spanDecorators, Pattern skipPattern) {
//        this.tracer = tracer;
//        this.spanDecorators = new ArrayList<>(spanDecorators);
//        this.spanDecorators.removeAll(Collections.singleton(null));
//    }
//
//    @Override
//    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain chain)
//            throws IOException, ServletException {
//
//        HttpServletRequest httpRequest = (HttpServletRequest) servletRequest;
//        HttpServletResponse httpResponse = (HttpServletResponse) servletResponse;
//
//        if (!isTraced(httpRequest, httpResponse)) {
//            chain.doFilter(httpRequest, httpResponse);
//            return;
//        }
//
//        //TODO DAM addition => to get content... maybe only when content type is/will be something supported
//        ContentCachingResponseWrapper responseWrapper = new ContentCachingResponseWrapper(httpResponse);
//
//        /**
//         * If request is traced then do not start new span.
//         */
//        if (servletRequest.getAttribute(SERVER_SPAN_CONTEXT) != null) {
//            chain.doFilter(servletRequest, responseWrapper);
//        } else {
//            SpanContext extractedContext = tracer.extract(Format.Builtin.HTTP_HEADERS,
//                    new HttpServletRequestExtractAdapter(httpRequest));
//
//            final Span span = tracer.buildSpan(httpRequest.getMethod())
//                    .asChildOf(extractedContext)
//                    .withTag(Tags.SPAN_KIND.getKey(), Tags.SPAN_KIND_SERVER)
//                    .start();
//
//            httpRequest.setAttribute(SERVER_SPAN_CONTEXT, span.context());
//
//            for (ServletFilterSpanDecorator spanDecorator: spanDecorators) {
//                spanDecorator.onRequest(httpRequest, span);
//            }
//
//            try (Scope scope = tracer.activateSpan(span)) {
//                chain.doFilter(servletRequest, responseWrapper);
//                if (!httpRequest.isAsyncStarted()) {
//                    for (ServletFilterSpanDecorator spanDecorator : spanDecorators) {
//                        spanDecorator.onResponse(httpRequest, responseWrapper, span);
//                    }
//                }
//                // catch all exceptions (e.g. RuntimeException, ServletException...)
//            } catch (Throwable ex) {
//                for (ServletFilterSpanDecorator spanDecorator : spanDecorators) {
//                    spanDecorator.onError(httpRequest, responseWrapper, ex, span);
//                }
//                throw ex;
//            } finally {
//                if (httpRequest.isAsyncStarted()) {
//                    // what if async is already finished? This would not be called
//                    httpRequest.getAsyncContext()
//                            .addListener(new AsyncListener() {
//                                @Override
//                                public void onComplete(AsyncEvent event) throws IOException {
//                                    HttpServletRequest httpRequest = (HttpServletRequest) event.getSuppliedRequest();
//                                    HttpServletResponse httpResponse = (HttpServletResponse) event.getSuppliedResponse();
//                                    for (ServletFilterSpanDecorator spanDecorator: spanDecorators) {
//                                        spanDecorator.onResponse(httpRequest,
//                                                httpResponse,
//                                                span);
//                                    }
//                                    span.finish();
//                                }
//
//                                @Override
//                                public void onTimeout(AsyncEvent event) throws IOException {
//                                    HttpServletRequest httpRequest = (HttpServletRequest) event.getSuppliedRequest();
//                                    HttpServletResponse httpResponse = (HttpServletResponse) event.getSuppliedResponse();
//                                    for (ServletFilterSpanDecorator spanDecorator : spanDecorators) {
//                                        spanDecorator.onTimeout(httpRequest,
//                                                httpResponse,
//                                                event.getAsyncContext().getTimeout(),
//                                                span);
//                                    }
//                                }
//
//                                @Override
//                                public void onError(AsyncEvent event) throws IOException {
//                                    HttpServletRequest httpRequest = (HttpServletRequest) event.getSuppliedRequest();
//                                    HttpServletResponse httpResponse = (HttpServletResponse) event.getSuppliedResponse();
//                                    for (ServletFilterSpanDecorator spanDecorator: spanDecorators) {
//                                        spanDecorator.onError(httpRequest,
//                                                httpResponse,
//                                                event.getThrowable(),
//                                                span);
//                                    }
//                                }
//
//                                @Override
//                                public void onStartAsync(AsyncEvent event) throws IOException {
//                                }
//                            });
//                } else {
//                    // If not async, then need to explicitly finish the span associated with the scope.
//                    // This is necessary, as we don't know whether this request is being handled
//                    // asynchronously until after the scope has already been started.
//                    span.finish();
//                }
//            }
//        }
//    }
//}
