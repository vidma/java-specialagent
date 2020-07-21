/* Copyright 2019 The OpenTracing Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.opentracing.contrib.specialagent.rule.akka.http;

import static io.opentracing.contrib.specialagent.rule.akka.http.AkkaHttpSyncHandler.*;

import akka.http.scaladsl.model.HttpRequest;
import akka.http.scaladsl.model.HttpResponse;
import io.opentracing.Scope;
import io.opentracing.Span;
import io.opentracing.contrib.specialagent.LocalSpanContext;
import io.opentracing.contrib.specialagent.OpenTracingApiUtil;
import io.opentracing.tag.Tags;
import io.opentracing.util.GlobalTracer;

//import scala.compat.java8.FutureConverters;
import scala.compat.java8.functionConverterImpls.FromJavaFunction;
import scala.concurrent.ExecutionContextExecutor;


public class AkkaHttpAsyncHandler implements scala.Function1<HttpRequest,scala.concurrent.Future<HttpResponse>> {
  private final scala.Function1<HttpRequest,scala.concurrent.Future<HttpResponse>> originalRequestHandler;

  public AkkaHttpAsyncHandler(final scala.Function1<HttpRequest,scala.concurrent.Future<HttpResponse>> originalRequestHandler) {
    this.originalRequestHandler = originalRequestHandler;
  }

  private final static String COMPONENT_NAME = AkkaAgentIntercept.COMPONENT_NAME_SERVER;

  @Override
  public scala.concurrent.Future<HttpResponse> apply(final HttpRequest request) {
      final Span span = buildSpan(request);
      try (final Scope scope = GlobalTracer.get().activateSpan(span)) {
          activateLocalSpanContext(span, scope);
          // FIXME: what is right EC here?
          ExecutionContextExecutor ec = scala.concurrent.ExecutionContext.global();
          return originalRequestHandler
                  .apply(request)
                  .map(new FromJavaFunction<>(httpResponse -> {
                      span.setTag(Tags.HTTP_STATUS, httpResponse.status().intValue());
                      span.finish();
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