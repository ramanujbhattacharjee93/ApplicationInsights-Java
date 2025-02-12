/*
 * ApplicationInsights-Java
 * Copyright (c) Microsoft Corporation
 * All rights reserved.
 *
 * MIT License
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this
 * software and associated documentation files (the ""Software""), to deal in the Software
 * without restriction, including without limitation the rights to use, copy, modify, merge,
 * publish, distribute, sublicense, and/or sell copies of the Software, and to permit
 * persons to whom the Software is furnished to do so, subject to the following conditions:
 * The above copyright notice and this permission notice shall be included in all copies or
 * substantial portions of the Software.
 * THE SOFTWARE IS PROVIDED *AS IS*, WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED,
 * INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR
 * PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE
 * FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR
 * OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER
 * DEALINGS IN THE SOFTWARE.
 */

package com.microsoft.applicationinsights.agent.internal.profiler;

import com.azure.core.http.HttpMethod;
import com.azure.core.http.HttpPipeline;
import com.azure.core.http.policy.DefaultRedirectStrategy;
import com.azure.core.http.policy.RedirectPolicy;
import com.azure.monitor.opentelemetry.exporter.implementation.builders.EventTelemetryBuilder;
import com.azure.monitor.opentelemetry.exporter.implementation.builders.MessageTelemetryBuilder;
import com.azure.monitor.opentelemetry.exporter.implementation.utils.FormattedTime;
import com.azure.monitor.opentelemetry.exporter.implementation.utils.ThreadPoolUtils;
import com.microsoft.applicationinsights.agent.internal.configuration.Configuration;
import com.microsoft.applicationinsights.agent.internal.httpclient.LazyHttpClient;
import com.microsoft.applicationinsights.agent.internal.telemetry.TelemetryClient;
import com.microsoft.applicationinsights.agent.internal.telemetry.TelemetryObservers;
import com.microsoft.applicationinsights.alerting.AlertingSubsystem;
import com.microsoft.applicationinsights.alerting.alert.AlertBreach;
import com.microsoft.applicationinsights.diagnostics.DiagnosticEngine;
import com.microsoft.applicationinsights.diagnostics.DiagnosticEngineFactory;
import com.microsoft.applicationinsights.profiler.ProfilerConfigurationHandler;
import com.microsoft.applicationinsights.profiler.ProfilerService;
import com.microsoft.applicationinsights.profiler.ProfilerServiceFactory;
import com.microsoft.applicationinsights.profiler.config.AlertConfigParser;
import com.microsoft.applicationinsights.profiler.config.ServiceProfilerServiceConfig;
import com.microsoft.applicationinsights.profiler.uploader.ServiceProfilerIndex;
import com.microsoft.applicationinsights.profiler.uploader.UploadCompleteHandler;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Service profiler main entry point, wires up the items below.
 *
 * <ul>
 *   <li>Alerting telemetry monitor subsystem
 *   <li>JFR Profiling service
 *   <li>JFR Uploader service
 * </ul>
 */
public class ProfilerServiceInitializer {

  private static final Logger LOGGER = LoggerFactory.getLogger(ProfilerServiceInitializer.class);

  private static boolean initialized = false;
  private static ProfilerService profilerService;
  private static DiagnosticEngine diagnosticEngine;

  public static synchronized void initialize(
      Supplier<String> appIdSupplier,
      String processId,
      ServiceProfilerServiceConfig config,
      String machineName,
      String roleName,
      TelemetryClient telemetryClient,
      String userAgent,
      Configuration configuration) {

    // Cannot use default creator, as we need to add POST to the allowed redirects
    HttpPipeline httpPipeline =
        LazyHttpClient.newHttpPipeLine(
            telemetryClient.getAadAuthentication(),
            new RedirectPolicy(
                new DefaultRedirectStrategy(
                    3,
                    "Location",
                    new HashSet<>(
                        Arrays.asList(HttpMethod.GET, HttpMethod.HEAD, HttpMethod.POST)))));

    initialize(
        appIdSupplier,
        processId,
        config,
        machineName,
        roleName,
        telemetryClient,
        userAgent,
        configuration,
        httpPipeline);
  }

  public static synchronized void initialize(
      Supplier<String> appIdSupplier,
      String processId,
      ServiceProfilerServiceConfig config,
      String machineName,
      String roleName,
      TelemetryClient telemetryClient,
      String userAgent,
      Configuration configuration,
      HttpPipeline httpPipeline) {
    if (!initialized) {
      initialized = true;
      ProfilerServiceFactory factory = null;

      try {
        factory = loadProfilerServiceFactory();
      } catch (RuntimeException e) {
        LOGGER.error("Failed to load profiler factory", e);
      }

      if (factory == null) {
        LOGGER.error(
            "Profiling has been enabled however no profiler implementation was provided. Please install an ApplicationInsights agent which provides a profiler.");
        return;
      }

      if (config.enableDiagnostics()) {
        // Initialise diagnostic service
        startDiagnosticEngine();
      }

      ScheduledExecutorService serviceProfilerExecutorService =
          Executors.newScheduledThreadPool(
              1,
              ThreadPoolUtils.createDaemonThreadFactory(
                  ProfilerServiceFactory.class, "ServiceProfilerService"));

      ScheduledExecutorService alertServiceExecutorService =
          Executors.newScheduledThreadPool(
              2,
              ThreadPoolUtils.createDaemonThreadFactory(
                  ProfilerServiceFactory.class, "ServiceProfilerAlertingService"));

      AlertingSubsystem alerting =
          createAlertMonitor(alertServiceExecutorService, telemetryClient, configuration);

      Future<ProfilerService> future =
          factory.initialize(
              appIdSupplier,
              updateAlertingConfig(alerting),
              processId,
              config,
              machineName,
              telemetryClient.getInstrumentationKey(),
              httpPipeline,
              serviceProfilerExecutorService,
              userAgent,
              roleName);

      serviceProfilerExecutorService.submit(
          () -> {
            try {
              profilerService = future.get();
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            } catch (Exception e) {
              LOGGER.error(
                  "Unable to obtain JFR connection, this may indicate that your JVM does not have JFR enabled. JFR profiling system will shutdown",
                  e);
              alertServiceExecutorService.shutdown();
              serviceProfilerExecutorService.shutdown();
            }
          });
    }
  }

  private static void startDiagnosticEngine() {
    try {
      DiagnosticEngineFactory diagnosticEngineFactory = loadDiagnosticEngineFactory();
      if (diagnosticEngineFactory != null) {
        ScheduledExecutorService diagnosticEngineExecutorService =
            Executors.newScheduledThreadPool(
                1, ThreadPoolUtils.createNamedDaemonThreadFactory("DiagnosisThreadPool"));

        diagnosticEngine = diagnosticEngineFactory.create(diagnosticEngineExecutorService);

        if (diagnosticEngine != null) {
          diagnosticEngine.init();
        } else {
          diagnosticEngineExecutorService.shutdown();
        }
      } else {
        LOGGER.warn("No diagnostic engine implementation provided");
      }
    } catch (RuntimeException e) {
      LOGGER.error("Failed to load profiler factory", e);
    }
  }

  private static ProfilerServiceFactory loadProfilerServiceFactory() {
    return findServiceLoader(ProfilerServiceFactory.class);
  }

  private static DiagnosticEngineFactory loadDiagnosticEngineFactory() {
    return findServiceLoader(DiagnosticEngineFactory.class);
  }

  protected static <T> T findServiceLoader(Class<T> clazz) {
    ServiceLoader<T> factory = ServiceLoader.load(clazz);
    Iterator<T> iterator = factory.iterator();
    if (iterator.hasNext()) {
      return iterator.next();
    }
    return null;
  }

  static ProfilerConfigurationHandler updateAlertingConfig(AlertingSubsystem alertingSubsystem) {
    return config ->
        alertingSubsystem.updateConfiguration(AlertConfigParser.toAlertingConfig(config));
  }

  static UploadCompleteHandler sendServiceProfilerIndex(TelemetryClient telemetryClient) {
    return done -> {
      EventTelemetryBuilder telemetryBuilder = telemetryClient.newEventTelemetryBuilder();

      telemetryBuilder.setName("ServiceProfilerIndex");

      ServiceProfilerIndex serviceProfilerIndex = done.getServiceProfilerIndex();
      for (Map.Entry<String, String> entry : serviceProfilerIndex.getProperties().entrySet()) {
        telemetryBuilder.addProperty(entry.getKey(), entry.getValue());
      }
      for (Map.Entry<String, Double> entry : serviceProfilerIndex.getMetrics().entrySet()) {
        telemetryBuilder.addMeasurement(entry.getKey(), entry.getValue());
      }

      telemetryBuilder.setTime(FormattedTime.offSetDateTimeFromNow());

      telemetryClient.trackAsync(telemetryBuilder.build());

      // This is an event that the backend specifically looks for to track when a profile is
      // complete
      sendMessageTelemetry(telemetryClient, "StopProfiler succeeded.");
    };
  }

  static AlertingSubsystem createAlertMonitor(
      ScheduledExecutorService alertServiceExecutorService,
      TelemetryClient telemetryClient,
      Configuration configuration) {
    return AlertingServiceFactory.create(
        configuration,
        alertAction(telemetryClient),
        TelemetryObservers.INSTANCE,
        telemetryClient,
        alertServiceExecutorService);
  }

  public static Consumer<AlertBreach> alertAction(TelemetryClient telemetryClient) {
    return alert -> {
      if (profilerService != null) {
        // This is an event that the backend specifically looks for to track when a profile is
        // started
        sendMessageTelemetry(telemetryClient, "StartProfiler triggered.");

        profilerService.getProfiler().accept(alert, sendServiceProfilerIndex(telemetryClient));

        scheduleDiagnoses(alert);
      }
    };
  }

  // Invokes the diagnostic engine while a profile is in progress
  private static void scheduleDiagnoses(AlertBreach alert) {
    if (diagnosticEngine != null) {
      diagnosticEngine.performDiagnosis(alert);
    }
  }

  private static void sendMessageTelemetry(TelemetryClient telemetryClient, String message) {
    MessageTelemetryBuilder telemetryBuilder = telemetryClient.newMessageTelemetryBuilder();

    telemetryBuilder.setMessage(message);
    telemetryBuilder.setTime(FormattedTime.offSetDateTimeFromNow());

    telemetryClient.trackAsync(telemetryBuilder.build());
  }

  private ProfilerServiceInitializer() {}
}
