package io.opentracing.contrib.specialagent.rule.play;

import io.opentracing.contrib.specialagent.AgentRule;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.utility.JavaModule;
import play.api.mvc.Filter;

import static net.bytebuddy.matcher.ElementMatchers.*;

public class PlayFilterAgentRule extends AgentRule {
  @Override
  public AgentBuilder buildAgentChainedGlobal1(final AgentBuilder builder) {
    return builder
            .type(hasSuperType(named("play.api.mvc.Filter")))
            // override def apply(nextFilter: RequestHeader => Future[Result])(request: RequestHeader): Future[Result]
            .transform(new AgentBuilder.Transformer() {
              @Override
              public DynamicType.Builder<?> transform(final DynamicType.Builder<?> builder, final TypeDescription typeDescription, final ClassLoader classLoader, final JavaModule module) {
                return builder.visit(advice(typeDescription).to(PlayFilterAgentRule.class)
                        .on(named("apply").and(takesArguments(2)).and(takesArgument(1, named("play.api.mvc.RequestHeader")))));
              }});
  }

  @Advice.OnMethodEnter
  public static void enter(final @ClassName String className, final @Advice.Origin String origin, final @Advice.Argument(value = 0) Object arg0, final @Advice.Argument(value = 1) Object arg1) {
    // FIXME: maybe currently this might generate multiple entries for the same request?!
    if (isAllowed(className, origin))
      PlayAgentIntercept.applyStart(arg1);
  }

  @Advice.OnMethodExit(onThrowable = Throwable.class)
  public static void exit(final @ClassName String className, final @Advice.Origin String origin, final @Advice.This Object thiz, final @Advice.Return Object returned, final @Advice.Thrown Throwable thrown) {
    if (isAllowed(className, origin)) {
      // fixme: not sure that's the best exec context to use, but should do
      PlayAgentIntercept.applyEnd(thiz, returned, thrown, ((Filter) thiz).mat().executionContext());
    }
  }
}
