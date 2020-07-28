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

package io.opentracing.contrib.specialagent.rule.play;

import akka.stream.Materializer;
import akka.util.ByteString;
import io.kensu.json.DamJsonSchemaInferrer;
import io.opentracing.Span;
import io.opentracing.SpanContext;
import io.opentracing.Tracer;
import io.opentracing.Tracer.SpanBuilder;
import io.opentracing.contrib.specialagent.ConcurrentWeakIdentityHashMap;
import io.opentracing.contrib.specialagent.LocalSpanContext;
import io.opentracing.contrib.specialagent.OpenTracingApiUtil;
import io.opentracing.propagation.Format.Builtin;
import io.opentracing.tag.Tags;
import io.opentracing.util.GlobalTracer;
import play.api.http.ContentTypes$;
import play.api.mvc.RequestHeader;
import play.api.mvc.Result;
import play.mvc.Http;
import scala.Function0;
import scala.Function1;
import scala.Option;
import scala.Option$;
import scala.concurrent.Future;
import scala.util.Try;

import java.util.Map;

public class PlayAgentIntercept {
  static final String COMPONENT_NAME = "play";
  static final String HTTP_REQUEST_ID = "http.request_id";

  // FIXME: maybe the span can be closed too early now?
  static final Map<RequestHeader, Span> requestUsages = new ConcurrentWeakIdentityHashMap<>();
  static final Map<RequestHeader, Integer> requestUsagesCounts = new ConcurrentWeakIdentityHashMap<>();


  static final String jsonContentType =  Http.MimeTypes.JSON; // "application/json";

  public static void applyStart(final Object arg0) {
    if (LocalSpanContext.get(COMPONENT_NAME) != null) {
      LocalSpanContext.get(COMPONENT_NAME).increment();
      return;
    }

    // inherited via thread...
    Span maybeThreadActiveSpan = GlobalTracer.get().scopeManager().activeSpan();
    if (maybeThreadActiveSpan != null && maybeThreadActiveSpan.getBaggageItem(HTTP_REQUEST_ID) != null){
      return;
    }

    final RequestHeader request = (RequestHeader)arg0;
    final Tracer tracer = GlobalTracer.get();

    RequestHeader requestId = request; // request.id()
    Span span = null;
    if (requestUsages.containsKey(requestId)){
      span = requestUsages.get(requestId);
      // FIXME: maybe baggage items are thread local without special handling...
      span.setBaggageItem(HTTP_REQUEST_ID, String.valueOf(request.id()));
      synchronized (requestUsagesCounts) {
        requestUsagesCounts.put(requestId, requestUsagesCounts.getOrDefault(requestId, 0) + 1); // global counter among all threads
      }
    } else {
      final SpanBuilder spanBuilder = tracer.buildSpan(request.method())
              .withTag(Tags.COMPONENT, COMPONENT_NAME)
              .withTag(Tags.SPAN_KIND, Tags.SPAN_KIND_SERVER)
              .withTag(Tags.HTTP_METHOD, request.method())
              .withTag(Tags.HTTP_URL, (request.secure() ? "https://" : "http://") + request.host() + request.uri());

      final SpanContext parent = tracer.extract(Builtin.HTTP_HEADERS, new HttpHeadersExtractAdapter(request.headers()));
      if (parent != null)
        spanBuilder.asChildOf(parent);

      span = spanBuilder.start();
      span.setBaggageItem(HTTP_REQUEST_ID, String.valueOf(request.id()));
      requestUsages.put(requestId, span);
      requestUsagesCounts.put(requestId, 1);
    }
    LocalSpanContext.set(COMPONENT_NAME, span, tracer.activateSpan(span));
  }

  @SuppressWarnings("unchecked")
  public static void applyEnd(final Object thiz, final Object returned, final Throwable thrown, final Object matObj) {
    final LocalSpanContext context = LocalSpanContext.get(COMPONENT_NAME);
    final akka.stream.Materializer mat = (akka.stream.Materializer) matObj;

    if (context == null)
      return;

    // FIXME: or simply check if doesn't have a parent.. but then need good thread context inheritance
    // fixme: can it get negative in some cases?
    if (context.decrementAndGet() != 0)
      return;

    final Span span = context.getSpan();

    // check if all filters/processors for this request are done already, if not we should still wait for lasy one
    // FIXME: no direct access to Request object from here so it's rather ineffocient in case of many requests at the same time...? but we want to keep weakref as well...
    final String requestId = span.getBaggageItem(HTTP_REQUEST_ID);
    final Integer globalCount;
    if (requestId == null){
      // fixme: what if getBaggageItem returns null?! when?
      globalCount = 0;
      System.out.println("WARNING: unable to retrieve HTTP_REQUEST_ID from span.getBaggageItem");
    } else {
      globalCount = requestUsagesCounts.keySet().stream()
              .filter(request -> {
                final Long reqID = request.id();
                return String.valueOf(reqID).equals(requestId);
              })
              .map(request -> {
                        synchronized (requestUsagesCounts) {
                          Integer newValue = requestUsagesCounts.computeIfPresent(request, (key, value) -> value - 1);  // fixme: in concurrent conflict might result in minus large number
                          if (newValue == null)
                            System.out.println("WARNING: requestUsagesCounts newValue == null (probably processing was fully done)");
                          return (newValue == null) ? 0 : newValue;
                        }
              }).findFirst()
              .orElseGet(() -> {
                System.out.println("WARNING: requestUsagesCounts didn't contain requestID == " + requestId);
                return 0;
              });
      System.out.println("INFO: retrieved new globalCount=" + ((globalCount == null) ? "null" : globalCount.toString()));
    }

    // FIXME: issue is that span is not closed... (or closed too early otherwise in other cases)

    if (globalCount > 0)
      return;

    // FIXME: maybe we still need this to release memory? but maybe we shouldn't call close scope multiple times if it wasn't thread-local!? seems like scope is thread local and can be closed...
    context.closeScope();

    if (thrown != null) {
      OpenTracingApiUtil.setErrorTag(span, thrown);
      span.finish(); // not sure if always correct if not fully done yet
      return;
    }

    ((Future<Result>)returned).onComplete(new Function1<Try<Result>,Object>() {
      @Override
      public Object apply(final Try<Result> response) {
        if (response.isFailure()) {
          OpenTracingApiUtil.setErrorTag(span, response.failed().get());
          span.finish();
        }
        else {
          span.setTag(Tags.HTTP_STATUS, response.get().header().status());
          String contentType = getResponseContentType(response);
          System.out.println("Play response content type=" + contentType + " filter class: " + thiz.getClass().getName());
          if (contentType.contains(jsonContentType)){
            captureHttpResultSchemaAndFinishSpanMat(response.get(), mat, span);
            return null;
          } else {
            span.finish();
          }
        }

        return null;
      }
    }, mat.executionContext());
  }

  protected static String getResponseContentType(Try<Result> response) {
    return response.get().body().contentType().getOrElse(new Function0<String>() {
            @Override
            public String apply() {
              return "";
            }
          });
  }

  protected static void captureHttpResultSchemaAndFinishSpanMat(Result response, Materializer mat, Span span) {
    System.out.println("Trying to get json schema...");
    response.body().consumeData(mat).onComplete(new Function1<Try<ByteString>, Object>() {
      @Override
      public Object apply(final Try<ByteString> result) {
        if (result.isSuccess()){
          String defaultCharset = "UTF-8"; // FIXME !!!!
          String jsonStr = result.get().decodeString(defaultCharset);
          String jsonSchema = new DamJsonSchemaInferrer().inferSchemaAsJsonString(jsonStr);
          if (jsonSchema != null){
            span.setTag(DamJsonSchemaInferrer.DAM_OUTPUT_SCHEMA_TAG, jsonSchema);
          }
        }
        span.finish();
        return null;
      }
    }, mat.executionContext());
  }
}
