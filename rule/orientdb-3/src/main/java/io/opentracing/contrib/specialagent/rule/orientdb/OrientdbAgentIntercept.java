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

package io.opentracing.contrib.specialagent.rule.orientdb;

import com.orientechnologies.orient.core.db.ODatabase;
import com.orientechnologies.orient.core.sql.executor.OResultSet;
import io.opentracing.Span;
import io.opentracing.Tracer;
import io.opentracing.Tracer.SpanBuilder;
import io.opentracing.contrib.specialagent.LocalSpanContext;
import io.opentracing.contrib.specialagent.OpenTracingApiUtil;
import io.opentracing.tag.Tags;
import io.opentracing.util.GlobalTracer;


import java.util.Map;

public class OrientdbAgentIntercept {
  static final String COMPONENT_NAME = "orientdb";

  public static void applyStart(final Object arg0) {
    if (LocalSpanContext.get(COMPONENT_NAME) != null) {
      LocalSpanContext.get(COMPONENT_NAME).increment();
      return;
    }

    final String request = (String) arg0;
    final Tracer tracer = GlobalTracer.get();

    final SpanBuilder spanBuilder = tracer.buildSpan("Query")
            .withTag(Tags.COMPONENT, COMPONENT_NAME)
            .withTag(Tags.SPAN_KIND, Tags.SPAN_KIND_CLIENT)
            .withTag(Tags.DB_TYPE, "orientdb")
            .withTag(Tags.DB_STATEMENT, request);

    final Span span = spanBuilder.start();

    LocalSpanContext.set(COMPONENT_NAME, span, tracer.activateSpan(span));
  }


  /**
   *

   package com.orientechnologies.orient.core.db;
   public interface ODatabase<T> extends OBackupable, Closeable {

   default OResultSet command(String query, Map args) throws OCommandSQLParsingException, OCommandExecutionException {
   throw new UnsupportedOperationException();
   }


   default OResultSet query(String query, Object... args) throws OCommandSQLParsingException, OCommandExecutionException {
   }
   }
   */


  @SuppressWarnings("unchecked")
  public static void applyEnd(final Object thiz, final Object returned, final Throwable thrown) {
    final LocalSpanContext context = LocalSpanContext.get(COMPONENT_NAME);
    if (context == null)
      return;

    if (context.decrementAndGet() != 0)
      return;

    final Span span = context.getSpan();
    context.closeScope();

    span.setTag(Tags.DB_INSTANCE, ((ODatabase<Object>)thiz).getURL());

    if (thrown != null) {
      OpenTracingApiUtil.setErrorTag(span, thrown);
      span.finish();
      return;
    }

    long estimatedRowCount = ((OResultSet)returned).getExactSizeIfKnown();
    if (estimatedRowCount != -1){
      span.setTag("ESTIMATED_ROW_COUNT", estimatedRowCount);
    }
    span.finish();
  }
}
