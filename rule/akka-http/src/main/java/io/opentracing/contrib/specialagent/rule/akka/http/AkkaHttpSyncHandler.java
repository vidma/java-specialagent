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

import static io.opentracing.contrib.specialagent.rule.akka.http.AkkaAgentIntercept.*;

import akka.http.scaladsl.model.HttpRequest;
import akka.http.scaladsl.model.HttpResponse;
import io.opentracing.References;
import io.opentracing.Scope;
import io.opentracing.Span;
import io.opentracing.SpanContext;
import io.opentracing.Tracer.SpanBuilder;
import io.opentracing.contrib.specialagent.OpenTracingApiUtil;
import io.opentracing.propagation.Format.Builtin;
import io.opentracing.tag.Tags;
import io.opentracing.util.GlobalTracer;

public class AkkaHttpSyncHandler implements scala.Function1<HttpRequest,HttpResponse> {
  private final scala.Function1<HttpRequest,HttpResponse> handler;

  public AkkaHttpSyncHandler(final scala.Function1<HttpRequest,HttpResponse> handler) {
    this.handler = handler;
  }

  @Override
  public HttpResponse apply(final HttpRequest request) {
    final Span span = buildSpan(request);
    try (final Scope scope = GlobalTracer.get().activateSpan(span)) {
      final HttpResponse response = handler.apply(request);
      span.setTag(Tags.HTTP_STATUS, response.status().intValue());
      return response;
    }
    catch (final Exception e) {
      OpenTracingApiUtil.setErrorTag(span, e);
      throw e;
    }
    finally {
      span.finish();
    }
  }

  static Span buildSpan(final HttpRequest request) {
    final SpanBuilder spanBuilder = GlobalTracer.get().buildSpan(request.method().value())
      .withTag(Tags.COMPONENT, COMPONENT_NAME_SERVER)
      .withTag(Tags.HTTP_URL, request.getUri().toString())
      .withTag(Tags.SPAN_KIND, Tags.SPAN_KIND_SERVER);

    final SpanContext context = GlobalTracer.get().extract(Builtin.HTTP_HEADERS, new HttpHeadersExtractAdapter(request));
    if (context != null)
      spanBuilder.addReference(References.FOLLOWS_FROM, context);

    return spanBuilder.start();
  }
}