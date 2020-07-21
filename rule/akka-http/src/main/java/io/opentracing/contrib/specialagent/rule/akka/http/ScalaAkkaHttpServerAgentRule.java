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

import io.opentracing.contrib.specialagent.AgentRule;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.agent.builder.AgentBuilder.Transformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType.Builder;
import net.bytebuddy.implementation.bytecode.assign.Assigner.Typing;
import net.bytebuddy.utility.JavaModule;

import static net.bytebuddy.matcher.ElementMatchers.*;


/**
 *
 *
 object Http extends ExtensionId[HttpExt] with ExtensionIdProvider {


 * Convenience method which starts a new HTTP server at the given endpoint and uses the given `handler`
 * [[akka.stream.scaladsl.Flow]] for processing all incoming connections.
 *
 * The number of concurrently accepted connections can be configured by overriding
 * the `akka.http.server.max-connections` setting. Please see the documentation in the reference.conf for more
 * information about what kind of guarantees to expect.
 *
 * To configure additional settings for a server started using this method,
 * use the `akka.http.server` config section or pass in a [[akka.http.scaladsl.settings.ServerSettings]] explicitly.

  def bindAndHandleSync(
          handler:   HttpRequest => HttpResponse,
interface: String, port: Int = DefaultPortForProtocol,
        connectionContext: ConnectionContext = defaultServerHttpContext,
        settings:          ServerSettings    = ServerSettings(system),
        log:               LoggingAdapter    = system.log)(implicit fm: Materializer): Future[ServerBinding] =
        bindAndHandle(Flow[HttpRequest].map(handler), interface, port, connectionContext, settings, log)

        /*
         * Convenience method which starts a new HTTP server at the given endpoint and uses the given `handler`
         * [[akka.stream.scaladsl.Flow]] for processing all incoming connections.
         *
         * The number of concurrently accepted connections can be configured by overriding
         * the `akka.http.server.max-connections` setting. Please see the documentation in the reference.conf for more
         * information about what kind of guarantees to expect.
         *
         * To configure additional settings for a server started using this method,
         * use the `akka.http.server` config section or pass in a [[akka.http.scaladsl.settings.ServerSettings]] explicitly.
         *
         * Parameter `parallelism` specifies how many requests are attempted to be handled concurrently per connection. In HTTP/1
         * this makes only sense if HTTP pipelining is enabled (which is not recommended). The default value of `0` means that
         * the value is taken from the `akka.http.server.pipelining-limit` setting from the configuration. In HTTP/2,
         * the default value is taken from `akka.http.server.http2.max-concurrent-streams`.
         *
         * Any other value for `parallelism` overrides the setting.

        def bindAndHandleAsync(
        handler:   HttpRequest => Future[HttpResponse],
interface: String, port: Int = DefaultPortForProtocol,
        connectionContext: ConnectionContext = defaultServerHttpContext,
        settings:          ServerSettings    = ServerSettings(system),
        parallelism:       Int               = 0,
        log:               LoggingAdapter    = system.log)(implicit fm: Materializer): Future[ServerBinding] = {

        }
 */
public class ScalaAkkaHttpServerAgentRule extends AgentRule {
  @Override
  public AgentBuilder buildAgentChainedGlobal1(final AgentBuilder builder) {
    return builder
      .type(named("akka.http.scaladsl.HttpExt"))
      .transform(new Transformer() {
        @Override
        public Builder<?> transform(final Builder<?> builder, final TypeDescription typeDescription, final ClassLoader classLoader, final JavaModule module) {
            //   public static void handle(scala.Function1<HttpRequest, Future[HttpResponse]>);
          return builder.visit(advice(typeDescription).to(SyncHandler.class).on(named("bindAndHandleSync").and(takesArgument(0, named("scala.Function1")))));
        }})
      .transform(new Transformer() {
        @Override
        public Builder<?> transform(final Builder<?> builder, final TypeDescription typeDescription, final ClassLoader classLoader, final JavaModule module) {
          return builder.visit(advice(typeDescription).to(AsyncHandler.class).on(named("bindAndHandleAsync").and(takesArgument(0, named("scala.Function1")))));
        }});
  }

  public static class SyncHandler {
    @Advice.OnMethodEnter
    public static void enter(final @ClassName String className, final @Advice.Origin String origin, @Advice.Argument(value = 0, readOnly = false, typing = Typing.DYNAMIC) Object arg0) {
      if (isAllowed(className, origin))
        arg0 = AkkaAgentIntercept.bindAndHandleSync(arg0);
    }
  }

  public static class AsyncHandler {
    @Advice.OnMethodEnter
    public static void enter(final @ClassName String className, final @Advice.Origin String origin, @Advice.Argument(value = 0, readOnly = false, typing = Typing.DYNAMIC) Object arg0) {
      if (isAllowed(className, origin))
        arg0 = AkkaAgentIntercept.bindAndHandleAsync(arg0);
    }
  }
}