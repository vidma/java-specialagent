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

package io.opentracing.contrib.specialagent.rule.spring.boot;

import static net.bytebuddy.matcher.ElementMatchers.*;

import java.lang.instrument.Instrumentation;

import io.opentracing.contrib.specialagent.AgentRule;
import io.opentracing.contrib.specialagent.Level;
import io.opentracing.contrib.specialagent.Logger;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.agent.builder.AgentBuilder.Transformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType.Builder;
import net.bytebuddy.utility.JavaModule;

public class SpringBootAgentRule extends AgentRule {
  public static final Logger logger = Logger.getLogger(SpringBootAgentRule.class);
  private static final String[] testClasses = {"org.springframework.boot.loader.Launcher", "org.springframework.boot.SpringApplication"};
  public static boolean initialized;

  @Override
  public boolean isDeferrable(final Instrumentation inst) {
    for (int i = 0; i < testClasses.length; ++i) {
      try {
        Class.forName(testClasses[i], false, ClassLoader.getSystemClassLoader());
        if (logger.isLoggable(Level.FINE))
          logger.fine("\n<<<<<<<<<<<<<<<< Installing SpringBootAgentRule >>>>>>>>>>>>>>>>\n");

        return true;
      }
      catch (final ClassNotFoundException e) {
      }
    }

    return false;
  }

  @Override
  public AgentBuilder buildAgentChainedGlobal1(final AgentBuilder builder) {
    return builder
      .type(hasSuperType(named("org.springframework.boot.StartupInfoLogger")))
      .transform(new Transformer() {
        @Override
        public Builder<?> transform(final Builder<?> builder, final TypeDescription typeDescription, final ClassLoader classLoader, final JavaModule module) {
          return builder.visit(advice(typeDescription).to(SpringBootAgentRule.class).on(named("logStarted")));
        }});
  }

  @Advice.OnMethodEnter
  public static void exit() {
    if (initialized)
      return;

    if (logger.isLoggable(Level.FINE))
      logger.fine("\n<<<<<<<<<<<<<<<< Invoking SpringBootAgentRule >>>>>>>>>>>>>>>>>\n");

    initialized = true;
    AgentRule.$Access.init();
    if (logger.isLoggable(Level.FINE))
      logger.fine("\n>>>>>>>>>>>>>>>>> Invoked SpringBootAgentRule <<<<<<<<<<<<<<<<<\n");
  }
}