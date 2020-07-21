/* Copyright 2020 The OpenTracing Authors
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

package io.opentracing.contrib.specialagent;

import static io.opentracing.contrib.specialagent.Constants.*;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;

import io.kensu.springtracker.DamTracer;
import io.kensu.springtracker.DamTracerReporter;
import io.kensu.springtracker.SimpleSpringtUrlsTransformer;
import io.opentracing.ScopeManager;
import io.opentracing.Tracer;
import io.opentracing.contrib.reporter.TracerR;
import io.opentracing.mock.MockTracer;
import io.opentracing.mock.ProxyMockTracer;
import io.opentracing.util.GlobalTracer;
import io.opentracing.util.ThreadLocalScopeManager;
import org.slf4j.LoggerFactory;

public class OpenTracingAdapter extends Adapter {
  private static final Logger logger = Logger.getLogger(OpenTracingAdapter.class);
  private static final String TRACER_FACTORY = "META-INF/services/io.opentracing.contrib.tracerresolver.TracerFactory";

  private boolean loaded;
  private Tracer deferredTracer;

  private static final Object tracerMutex = new Object();
  private Tracer tracer;

  /**
   * Returns the OpenTracing {@link Tracer} to be used for the duration of the
   * test process. The {@link Tracer} is initialized on first invocation to this
   * method in a synchronized, thread-safe manner. If the {@code "-javaagent"}
   * argument is not specified for the current process, this function will
   * return {@code null}.
   *
   * @return The OpenTracing {@link Tracer} to be used for the duration of the
   *         test process, or {@code null} if the {@code "-javaagent"} argument
   *         is not specified for the current process.
   */
  @Override
  public Tracer getAgentRunnerTracer() {
    if (tracer != null)
      return tracer;

    synchronized (tracerMutex) {
      if (tracer != null)
        return tracer;

      final MockTracer tracer;
      if (GlobalTracer.isRegistered()) {
        final Tracer registered = TestUtil.getGlobalTracer();
        if (deferredTracer == null) {
          tracer = registered instanceof MockTracer ? (MockTracer)registered : new ProxyMockTracer(registered);
        }
        else if (registered instanceof MockTracer) {
          tracer = new ProxyMockTracer((MockTracer)registered, deferredTracer);
        }
        else {
          throw new IllegalStateException("There is already a registered global Tracer.");
        }

        TestUtil.setGlobalTracer(tracer);
      }
      else {
        tracer = deferredTracer != null ? new ProxyMockTracer(deferredTracer) : new MockTracer();
        if (!GlobalTracer.registerIfAbsent(tracer))
          throw new IllegalStateException("There is already a registered global Tracer.");
      }

      if (logger.isLoggable(Level.FINER)) {
        logger.finer("Registering tracer for AgentRunner: " + tracer);
        logger.finer("  Tracer ClassLoader: " + tracer.getClass().getClassLoader());
        logger.finer("  Tracer Location: " + ClassLoader.getSystemClassLoader().getResource(AssembleUtil.classNameToResource(tracer.getClass())));
        logger.finer("  GlobalTracer ClassLoader: " + GlobalTracer.class.getClassLoader());
        logger.finer("  GlobalTracer Location: " + ClassLoader.getSystemClassLoader().getResource(AssembleUtil.classNameToResource(GlobalTracer.class)));
      }

      return this.tracer = tracer;
    }
  }

  private static final String[] traceExcludedClasses = {"io.opentracing.Tracer", "io.opentracing.Scope", "io.opentracing.ScopeManager", "io.opentracing.Span", "io.opentracing.SpanBuilder", "io.opentracing.SpanContext"};

  @Override
  public String[] loadTracer(final ClassLoader isoClassLoader) {
    if (!loaded) {
      try {
        loaded = true;
        this.deferredTracer = loadDeferredTracer(isoClassLoader);
      }
      catch (final IOException | ReflectiveOperationException e) {
        throw new IllegalStateException(e);
      }
    }

    return traceExcludedClasses;
  }

  /**
   * Returns the {@code JarFile} referencing the Trace Exporter by the given
   * {@code name} in the specified {@code ClassLoader}.
   *
   * @param classLoader The {@code ClassLoader} in which to find the Tracer
   *          Plugin.
   * @param name The short name of the Trace Exporter.
   * @return The {@code URL} referencing the Trace Exporter by the given
   *         {@code name} in the specified {@code ClassLoader}, or {@code null}
   *         if one was not found.
   */
  private static URL findTracer(final ClassLoader classLoader, final String name) throws IOException {
    final Enumeration<URL> enumeration = classLoader.getResources(TRACER_FACTORY);
    final HashSet<URL> urls = new HashSet<>();
    while (enumeration.hasMoreElements()) {
      final URL url = enumeration.nextElement();
      if (urls.contains(url))
        continue;

      urls.add(url);
      if (logger.isLoggable(Level.FINEST))
        logger.finest("Found " + TRACER_FACTORY + ": <" + AssembleUtil.getNameId(url) + ">" + url);

      final String jarPath = AssembleUtil.getSourceLocation(url, TRACER_FACTORY).getPath();
      final String fileName = AssembleUtil.getName(jarPath);
      final String tracerName = fileName.substring(0, fileName.lastIndexOf('.'));
      if (name.equals(tracerName))
        return new URL("file", null, jarPath);
    }

    return null;
  }

  /**
   * Connects a Trace Exporter to the runtime.
   *
   * @return A {@code Tracer} instance to be deferred, or null if no tracer was
   *         specified or the specified tracer was loaded.
   * @throws IOException If an I/O error has occurred.
   * @throws ReflectiveOperationException If a reflective operation error has
   *           occurred.
   */
  @SuppressWarnings("resource")
  private static Tracer loadDeferredTracer(final ClassLoader isoClassLoader) throws IOException, ReflectiveOperationException {
    if (logger.isLoggable(Level.FINE))
      logger.fine("\n<<<<<<<<<<<<<<<<<<<< Loading Trace Exporter >>>>>>>>>>>>>>>>>>>>\n");

    try {
      if (GlobalTracer.isRegistered()) {
        if (logger.isLoggable(Level.FINE))
          logger.fine("Tracer already registered with GlobalTracer");

        return null;
      }

      String exporterProperty = System.getProperty(EXPORTER_PROPERTY);
      if (exporterProperty == null) {
        exporterProperty = System.getProperty("sa.tracer");
        if (exporterProperty != null)
          logger.warning("Deprecated key (as of v1.7.0): \"sa.tracer\" should be changed to \"" + EXPORTER_PROPERTY + "\"");
      }

      if (exporterProperty == null) {
        if (logger.isLoggable(Level.FINE))
          logger.fine("Trace exporter was not specified with \"" + EXPORTER_PROPERTY + "\" system property");

        return null;
      }

      if (logger.isLoggable(Level.FINE))
        logger.fine("Resolving tracer:\n  " + exporterProperty);

      Tracer tracer;
      if ("mock".equals(exporterProperty)) {
        tracer = new MockTracer();
      }
      else {
        final ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
        final File file = new File(exporterProperty);

        final URL tracerUrl = file.exists() ? new URL("file", null, file.getPath()) : findTracer(isoClassLoader, exporterProperty);
        if (tracerUrl == null)
          throw new IllegalStateException(EXPORTER_PROPERTY + "=" + exporterProperty + " did not resolve to a tracer JAR or name");

        // FIXME: This looks like a hack...
        final String tracerResolverUrl = isoClassLoader.getResource("io/opentracing/contrib/tracerresolver/TracerResolver.class").toString();
        Adapter.tracerClassLoader = new TracerClassLoader(null, tracerUrl, new URL(tracerResolverUrl.substring(4, tracerResolverUrl.indexOf('!'))));
        Thread.currentThread().setContextClassLoader(Adapter.tracerClassLoader);

        final Class<?> tracerResolverClass = Class.forName("io.opentracing.contrib.tracerresolver.TracerResolver", true, Adapter.tracerClassLoader);
        final Method resolveTracerMethod = tracerResolverClass.getMethod("resolveTracer");
        tracer = (Tracer)resolveTracerMethod.invoke(null);
        Thread.currentThread().setContextClassLoader(contextClassLoader);
      }

      if (tracer == null) {
        logger.warning("Trace exporter was NOT RESOLVED");
        return null;
      }

      // FIXME: allow to disable dam tracer reporter or configure to logger only...
      Tracer backendTracer = tracer;
      ScopeManager scopeManager = (backendTracer.scopeManager() == null) ? backendTracer.scopeManager() : new ThreadLocalScopeManager();
      DamTracerReporter reporter = new DamTracerReporter(org.slf4j.LoggerFactory.getLogger("tracer"), new SimpleSpringtUrlsTransformer());
      Tracer combinedTracer = new TracerR(backendTracer, reporter, scopeManager);
      tracer = combinedTracer;

      tracer = initRewritableTracer(tracer, isoClassLoader);
      if (!isAgentRunner() && !GlobalTracer.registerIfAbsent(tracer))
        throw new IllegalStateException("There is already a registered global Tracer.");

      if (logger.isLoggable(Level.FINE))
        logger.fine("Tracer was resolved and " + (isAgentRunner() ? "deferred to be registered" : "registered") + " with GlobalTracer:\n  " + tracer.getClass().getName() + " from " + (tracer.getClass().getProtectionDomain().getCodeSource() == null ? "null" : tracer.getClass().getProtectionDomain().getCodeSource().getLocation().getPath()));

      return tracer;
    }
    finally {
      if (logger.isLoggable(Level.FINE))
        logger.fine("\n>>>>>>>>>>>>>>>>>>>> Loaded Trace Exporter <<<<<<<<<<<<<<<<<<<<<\n");
    }
  }

  @SuppressWarnings("unchecked")
  private static Tracer initRewritableTracer(final Tracer tracer, final ClassLoader isoClassLoader) throws IOException {
    final String rewriteProperty = System.getProperty(REWRITE_ARG);
    if (rewriteProperty == null)
      return tracer;

    if (logger.isLoggable(Level.FINE))
      logger.fine("\n<<<<<<<<<<<<<<<<<<< Loading Rewritable Tracer >>>>>>>>>>>>>>>>>>\n");

    try (final InputStream in = new FileInputStream(rewriteProperty)) {
      final Class<?> rewriteRulesClass = Class.forName("io.opentracing.contrib.specialagent.RewriteRules", true, isoClassLoader);
      final Method parseRulesMethod = rewriteRulesClass.getMethod("parseRules", InputStream.class);
      final List<?> rules = (List<?>)parseRulesMethod.invoke(null, in);
      if (rules.isEmpty())
        return tracer;

      final Class<Tracer> rewritableTracerClass = (Class<Tracer>)Class.forName("io.opentracing.contrib.specialagent.RewritableTracer", true, isoClassLoader);
      final Constructor<?> constructor = rewritableTracerClass.getConstructor(Tracer.class, List.class);
      return (Tracer)constructor.newInstance(tracer, rules);
    }
    catch (final ClassNotFoundException | IllegalAccessException | InstantiationException | InvocationTargetException | NoSuchMethodException e) {
      throw new RuntimeException(e);
    }
    finally {
      if (logger.isLoggable(Level.FINE))
        logger.fine("\n>>>>>>>>>>>>>>>>>>> Loaded Rewritable Tracer <<<<<<<<<<<<<<<<<<<\n");
    }
  }
}