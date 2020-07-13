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

import static net.bytebuddy.matcher.ElementMatchers.*;

import io.opentracing.contrib.specialagent.AgentRule;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.agent.builder.AgentBuilder.Transformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType.Builder;
import net.bytebuddy.utility.JavaModule;

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

public class OrientdbAgentRule extends AgentRule {
  @Override
  public AgentBuilder buildAgentChainedGlobal1(final AgentBuilder builder) {
    return builder
      .type(hasSuperType(named("com.orientechnologies.orient.core.db.ODatabase")))
      .transform(new Transformer() {
        @Override
        public Builder<?> transform(final Builder<?> builder, final TypeDescription typeDescription, final ClassLoader classLoader, final JavaModule module) {
          return builder.visit(advice(typeDescription)
                  .to(OrientdbAgentRule.class)
                  .on(named("command").and(takesArgument(0, named("java.lang.String")))));
        }});
  }

  @Advice.OnMethodEnter
  public static void enter(final @ClassName String className, final @Advice.Origin String origin, final @Advice.Argument(value = 0) Object arg0) {
    if (isAllowed(className, origin))
      OrientdbAgentIntercept.applyStart(arg0);
  }

  @Advice.OnMethodExit(onThrowable = Throwable.class)
  public static void exit(final @ClassName String className, final @Advice.Origin String origin, final @Advice.This Object thiz, final @Advice.Return Object returned, final @Advice.Thrown Throwable thrown) {
    if (isAllowed(className, origin))
      OrientdbAgentIntercept.applyEnd(thiz, returned, thrown);
  }
}
