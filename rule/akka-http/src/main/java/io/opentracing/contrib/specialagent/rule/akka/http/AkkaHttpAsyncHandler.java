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

import java.util.concurrent.CompletableFuture;

import akka.http.scaladsl.model.HttpRequest;
import akka.http.scaladsl.model.HttpResponse;
import akka.japi.Function;
import io.opentracing.Scope;
import io.opentracing.Span;
import io.opentracing.contrib.specialagent.OpenTracingApiUtil;
import io.opentracing.tag.Tags;
import io.opentracing.util.GlobalTracer;

//import scala.compat.java8.FutureConverters;
import static scala.compat.java8.FutureConverters.*;

public class AkkaHttpAsyncHandler implements scala.Function1<HttpRequest,scala.concurrent.Future<HttpResponse>> {
  private final scala.Function1<HttpRequest,scala.concurrent.Future<HttpResponse>> handler;

  public AkkaHttpAsyncHandler(final scala.Function1<HttpRequest,scala.concurrent.Future<HttpResponse>> handler) {
    this.handler = handler;
  }

  @Override
  public scala.concurrent.Future<HttpResponse> apply(final HttpRequest request) {
    final Span span = buildSpan(request);
    try (final Scope scope = GlobalTracer.get().activateSpan(span)) {
      return toScala(toJava(handler.apply(request)).thenApply(httpResponse -> {
        span.setTag(Tags.HTTP_STATUS, httpResponse.status().intValue());
        span.finish();
        return httpResponse;
      }).exceptionally(throwable -> {
        OpenTracingApiUtil.setErrorTag(span, throwable);
        span.finish();
        return null;
      }));
    }
  }
}