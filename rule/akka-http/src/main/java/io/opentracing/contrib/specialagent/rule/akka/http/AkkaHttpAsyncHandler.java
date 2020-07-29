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

import akka.http.scaladsl.model.HttpRequest;
import akka.http.scaladsl.model.HttpResponse;
import scala.concurrent.Future;

import java.util.function.Supplier;


public class AkkaHttpAsyncHandler implements scala.Function1<HttpRequest,scala.concurrent.Future<HttpResponse>> {
  private final scala.Function1<HttpRequest,scala.concurrent.Future<HttpResponse>> originalRequestHandler;

  public AkkaHttpAsyncHandler(final scala.Function1<HttpRequest,scala.concurrent.Future<HttpResponse>> originalRequestHandler) {
    this.originalRequestHandler = originalRequestHandler;
  }

  @Override
  public scala.concurrent.Future<HttpResponse> apply(final HttpRequest request) {
    Supplier<Future<HttpResponse>> httpResponseFuture1 = () -> originalRequestHandler.apply(request);

    return new AbstractAkkaHttpRequestHandler().apply(
            request,
            () -> originalRequestHandler.apply(request),
            r -> r);
  }
}

