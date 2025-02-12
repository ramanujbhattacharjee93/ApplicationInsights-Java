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

package com.microsoft.applicationinsights.agent.internal.legacysdk;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

import com.azure.monitor.opentelemetry.exporter.implementation.AiOperationNameSpanProcessor;
import com.azure.monitor.opentelemetry.exporter.implementation.builders.AbstractTelemetryBuilder;
import com.azure.monitor.opentelemetry.exporter.implementation.builders.EventTelemetryBuilder;
import com.azure.monitor.opentelemetry.exporter.implementation.builders.ExceptionTelemetryBuilder;
import com.azure.monitor.opentelemetry.exporter.implementation.builders.MessageTelemetryBuilder;
import com.azure.monitor.opentelemetry.exporter.implementation.builders.MetricPointBuilder;
import com.azure.monitor.opentelemetry.exporter.implementation.builders.MetricTelemetryBuilder;
import com.azure.monitor.opentelemetry.exporter.implementation.builders.PageViewTelemetryBuilder;
import com.azure.monitor.opentelemetry.exporter.implementation.builders.RemoteDependencyTelemetryBuilder;
import com.azure.monitor.opentelemetry.exporter.implementation.builders.RequestTelemetryBuilder;
import com.azure.monitor.opentelemetry.exporter.implementation.models.ContextTagKeys;
import com.azure.monitor.opentelemetry.exporter.implementation.models.SeverityLevel;
import com.azure.monitor.opentelemetry.exporter.implementation.utils.FormattedDuration;
import com.azure.monitor.opentelemetry.exporter.implementation.utils.FormattedTime;
import com.azure.monitor.opentelemetry.exporter.implementation.utils.Strings;
import com.azure.monitor.opentelemetry.exporter.implementation.utils.TelemetryUtil;
import com.microsoft.applicationinsights.agent.bootstrap.BytecodeUtil.BytecodeUtilDelegate;
import com.microsoft.applicationinsights.agent.internal.legacyheaders.AiLegacyPropagator;
import com.microsoft.applicationinsights.agent.internal.sampling.SamplingScoreGeneratorV2;
import com.microsoft.applicationinsights.agent.internal.statsbeat.FeatureStatsbeat;
import com.microsoft.applicationinsights.agent.internal.telemetry.TelemetryClient;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.context.Context;
import io.opentelemetry.instrumentation.api.instrumenter.LocalRootSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import java.net.URI;
import java.net.URL;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// supporting all properties of event, metric, remove dependency and page view telemetry
public class BytecodeUtilImpl implements BytecodeUtilDelegate {

  private static final Logger logger = LoggerFactory.getLogger(BytecodeUtilImpl.class);

  private static final AtomicBoolean alreadyLoggedError = new AtomicBoolean();

  public static volatile float samplingPercentage = 100;

  public static volatile FeatureStatsbeat featureStatsbeat;

  @Override
  public void trackEvent(
      @Nullable Date timestamp,
      String name,
      Map<String, String> properties,
      Map<String, String> tags,
      Map<String, Double> measurements,
      @Nullable String instrumentationKey) {

    if (Strings.isNullOrEmpty(name)) {
      return;
    }
    EventTelemetryBuilder telemetryBuilder = TelemetryClient.getActive().newEventTelemetryBuilder();

    telemetryBuilder.setName(name);
    for (Map.Entry<String, Double> entry : measurements.entrySet()) {
      telemetryBuilder.addMeasurement(entry.getKey(), entry.getValue());
    }
    for (Map.Entry<String, String> entry : properties.entrySet()) {
      telemetryBuilder.addProperty(entry.getKey(), entry.getValue());
    }

    if (timestamp != null) {
      telemetryBuilder.setTime(FormattedTime.offSetDateTimeFromEpochMillis(timestamp.getTime()));
    } else {
      telemetryBuilder.setTime(FormattedTime.offSetDateTimeFromNow());
    }
    selectivelySetTags(telemetryBuilder, tags);
    if (instrumentationKey != null) {
      telemetryBuilder.setInstrumentationKey(instrumentationKey);
    }

    track(telemetryBuilder, tags, true);
  }

  // TODO do not track if perf counter (?)
  @Override
  public void trackMetric(
      @Nullable Date timestamp,
      String name,
      double value,
      @Nullable Integer count,
      @Nullable Double min,
      @Nullable Double max,
      @Nullable Double stdDev,
      Map<String, String> properties,
      Map<String, String> tags,
      @Nullable String instrumentationKey) {

    if (Strings.isNullOrEmpty(name)) {
      return;
    }
    MetricTelemetryBuilder telemetryBuilder =
        TelemetryClient.getActive().newMetricTelemetryBuilder();

    MetricPointBuilder point = new MetricPointBuilder();
    point.setName(name);
    point.setValue(value);
    point.setCount(count);
    point.setMin(min);
    point.setMax(max);
    point.setStdDev(stdDev);
    telemetryBuilder.setMetricPoint(point);

    for (Map.Entry<String, String> entry : properties.entrySet()) {
      telemetryBuilder.addProperty(entry.getKey(), entry.getValue());
    }

    if (timestamp != null) {
      telemetryBuilder.setTime(FormattedTime.offSetDateTimeFromEpochMillis(timestamp.getTime()));
    } else {
      telemetryBuilder.setTime(FormattedTime.offSetDateTimeFromNow());
    }
    selectivelySetTags(telemetryBuilder, tags);
    if (instrumentationKey != null) {
      telemetryBuilder.setInstrumentationKey(instrumentationKey);
    }

    track(telemetryBuilder, tags, false);
  }

  @Override
  public void trackDependency(
      @Nullable Date timestamp,
      String name,
      @Nullable String id,
      String resultCode,
      @Nullable Long totalMillis,
      boolean success,
      String commandName,
      String type,
      String target,
      Map<String, String> properties,
      Map<String, String> tags,
      Map<String, Double> measurements,
      @Nullable String instrumentationKey) {

    if (Strings.isNullOrEmpty(name)) {
      return;
    }
    RemoteDependencyTelemetryBuilder telemetryBuilder =
        TelemetryClient.getActive().newRemoteDependencyTelemetryBuilder();

    telemetryBuilder.setName(name);
    if (id == null) {
      telemetryBuilder.setId(AiLegacyPropagator.generateSpanId());
    } else {
      telemetryBuilder.setId(id);
    }
    telemetryBuilder.setResultCode(resultCode);
    if (totalMillis != null) {
      telemetryBuilder.setDuration(FormattedDuration.fromNanos(MILLISECONDS.toNanos(totalMillis)));
    }
    telemetryBuilder.setSuccess(success);
    telemetryBuilder.setData(commandName);
    telemetryBuilder.setType(type);
    telemetryBuilder.setTarget(target);
    for (Map.Entry<String, Double> entry : measurements.entrySet()) {
      telemetryBuilder.addMeasurement(entry.getKey(), entry.getValue());
    }
    for (Map.Entry<String, String> entry : properties.entrySet()) {
      telemetryBuilder.addProperty(entry.getKey(), entry.getValue());
    }

    if (timestamp != null) {
      telemetryBuilder.setTime(FormattedTime.offSetDateTimeFromEpochMillis(timestamp.getTime()));
    } else {
      telemetryBuilder.setTime(FormattedTime.offSetDateTimeFromNow());
    }
    selectivelySetTags(telemetryBuilder, tags);
    if (instrumentationKey != null) {
      telemetryBuilder.setInstrumentationKey(instrumentationKey);
    }

    track(telemetryBuilder, tags, true);
  }

  @Override
  public void trackPageView(
      @Nullable Date timestamp,
      String name,
      @Nullable URI uri,
      long totalMillis,
      Map<String, String> properties,
      Map<String, String> tags,
      Map<String, Double> measurements,
      @Nullable String instrumentationKey) {

    if (Strings.isNullOrEmpty(name)) {
      return;
    }
    PageViewTelemetryBuilder telemetryBuilder =
        TelemetryClient.getActive().newPageViewTelemetryBuilder();

    telemetryBuilder.setName(name);
    if (uri != null) {
      telemetryBuilder.setUrl(uri.toString());
    }
    telemetryBuilder.setDuration(FormattedDuration.fromNanos(MILLISECONDS.toNanos(totalMillis)));
    for (Map.Entry<String, Double> entry : measurements.entrySet()) {
      telemetryBuilder.addMeasurement(entry.getKey(), entry.getValue());
    }
    for (Map.Entry<String, String> entry : properties.entrySet()) {
      telemetryBuilder.addProperty(entry.getKey(), entry.getValue());
    }

    if (timestamp != null) {
      telemetryBuilder.setTime(FormattedTime.offSetDateTimeFromEpochMillis(timestamp.getTime()));
    } else {
      telemetryBuilder.setTime(FormattedTime.offSetDateTimeFromNow());
    }
    selectivelySetTags(telemetryBuilder, tags);
    if (instrumentationKey != null) {
      telemetryBuilder.setInstrumentationKey(instrumentationKey);
    }

    track(telemetryBuilder, tags, true);
  }

  @Override
  public void trackTrace(
      @Nullable Date timestamp,
      String message,
      int severityLevel,
      Map<String, String> properties,
      Map<String, String> tags,
      @Nullable String instrumentationKey) {
    if (message == null) {
      return;
    }
    MessageTelemetryBuilder telemetryBuilder =
        TelemetryClient.getActive().newMessageTelemetryBuilder();

    telemetryBuilder.setMessage(message);
    if (severityLevel != -1) {
      telemetryBuilder.setSeverityLevel(getSeverityLevel(severityLevel));
    }

    for (Map.Entry<String, String> entry : properties.entrySet()) {
      telemetryBuilder.addProperty(entry.getKey(), entry.getValue());
    }

    if (timestamp != null) {
      telemetryBuilder.setTime(FormattedTime.offSetDateTimeFromEpochMillis(timestamp.getTime()));
    } else {
      telemetryBuilder.setTime(FormattedTime.offSetDateTimeFromNow());
    }
    selectivelySetTags(telemetryBuilder, tags);
    if (instrumentationKey != null) {
      telemetryBuilder.setInstrumentationKey(instrumentationKey);
    }

    track(telemetryBuilder, tags, true);
  }

  @Override
  public void trackRequest(
      @Nullable String id,
      String name,
      @Nullable URL url,
      @Nullable Date timestamp,
      @Nullable Long duration,
      String responseCode,
      boolean success,
      String source,
      Map<String, String> properties,
      Map<String, String> tags,
      Map<String, Double> measurements,
      @Nullable String instrumentationKey) {
    if (Strings.isNullOrEmpty(name)) {
      return;
    }
    RequestTelemetryBuilder telemetryBuilder =
        TelemetryClient.getActive().newRequestTelemetryBuilder();

    if (id == null) {
      telemetryBuilder.setId(AiLegacyPropagator.generateSpanId());
    } else {
      telemetryBuilder.setId(id);
    }
    telemetryBuilder.setName(name);
    if (url != null) {
      telemetryBuilder.setUrl(url.toString());
    }
    if (duration != null) {
      telemetryBuilder.setDuration(FormattedDuration.fromNanos(MILLISECONDS.toNanos(duration)));
    }
    telemetryBuilder.setResponseCode(responseCode);
    telemetryBuilder.setSuccess(success);
    telemetryBuilder.setSource(source);
    for (Map.Entry<String, Double> entry : measurements.entrySet()) {
      telemetryBuilder.addMeasurement(entry.getKey(), entry.getValue());
    }
    for (Map.Entry<String, String> entry : properties.entrySet()) {
      telemetryBuilder.addProperty(entry.getKey(), entry.getValue());
    }

    if (timestamp != null) {
      telemetryBuilder.setTime(FormattedTime.offSetDateTimeFromEpochMillis(timestamp.getTime()));
    } else {
      telemetryBuilder.setTime(FormattedTime.offSetDateTimeFromNow());
    }
    selectivelySetTags(telemetryBuilder, tags);
    if (instrumentationKey != null) {
      telemetryBuilder.setInstrumentationKey(instrumentationKey);
    }

    track(telemetryBuilder, tags, true);
  }

  @Override
  public void trackException(
      @Nullable Date timestamp,
      @Nullable Exception exception,
      Map<String, String> properties,
      Map<String, String> tags,
      Map<String, Double> measurements,
      @Nullable String instrumentationKey) {
    if (exception == null) {
      return;
    }
    ExceptionTelemetryBuilder telemetryBuilder =
        TelemetryClient.getActive().newExceptionTelemetryBuilder();

    telemetryBuilder.setExceptions(TelemetryUtil.getExceptions(exception));
    telemetryBuilder.setSeverityLevel(SeverityLevel.ERROR);
    for (Map.Entry<String, Double> entry : measurements.entrySet()) {
      telemetryBuilder.addMeasurement(entry.getKey(), entry.getValue());
    }
    for (Map.Entry<String, String> entry : properties.entrySet()) {
      telemetryBuilder.addProperty(entry.getKey(), entry.getValue());
    }

    if (timestamp != null) {
      telemetryBuilder.setTime(FormattedTime.offSetDateTimeFromEpochMillis(timestamp.getTime()));
    } else {
      telemetryBuilder.setTime(FormattedTime.offSetDateTimeFromNow());
    }
    selectivelySetTags(telemetryBuilder, tags);
    if (instrumentationKey != null) {
      telemetryBuilder.setInstrumentationKey(instrumentationKey);
    }

    track(telemetryBuilder, tags, true);
  }

  @Nullable
  private static SeverityLevel getSeverityLevel(int value) {
    // these mappings from the 2.x SDK
    switch (value) {
      case 0:
        return SeverityLevel.VERBOSE;
      case 1:
        return SeverityLevel.INFORMATION;
      case 2:
        return SeverityLevel.WARNING;
      case 3:
        return SeverityLevel.ERROR;
      case 4:
        return SeverityLevel.CRITICAL;
      default:
        return null;
    }
  }

  @Override
  public void flush() {
    // this is not null because sdk instrumentation is not added until TelemetryClient.setActive()
    // is called
    TelemetryClient.getActive().forceFlush().join(10, SECONDS);
  }

  @Override
  public void logErrorOnce(Throwable t) {
    if (!alreadyLoggedError.getAndSet(true)) {
      logger.error(t.getMessage(), t);
    }
  }

  private static void track(
      AbstractTelemetryBuilder telemetryBuilder, Map<String, String> tags, boolean applySampling) {

    String operationId = tags.get(ContextTagKeys.AI_OPERATION_ID.toString());

    SpanContext context = Span.current().getSpanContext();
    if (context.isValid()) {
      String operationParentId = tags.get(ContextTagKeys.AI_OPERATION_PARENT_ID.toString());
      String operationName = tags.get(ContextTagKeys.AI_OPERATION_NAME.toString());

      trackInsideValidSpanContext(
          telemetryBuilder, operationId, operationParentId, operationName, context, applySampling);
    } else {
      trackAsStandalone(telemetryBuilder, operationId, applySampling);
    }

    if (featureStatsbeat != null) {
      featureStatsbeat.track2xBridgeUsage();
    }
  }

  private static void trackInsideValidSpanContext(
      AbstractTelemetryBuilder telemetryBuilder,
      @Nullable String operationId,
      @Nullable String operationParentId,
      @Nullable String operationName,
      SpanContext spanContext,
      boolean applySampling) {

    if (operationId != null && !operationId.equals(spanContext.getTraceId())) {
      trackAsStandalone(telemetryBuilder, operationId, applySampling);
      return;
    }

    if (!spanContext.isSampled()) {
      // sampled out
      return;
    }

    telemetryBuilder.addTag(ContextTagKeys.AI_OPERATION_ID.toString(), spanContext.getTraceId());

    if (operationParentId == null) {
      telemetryBuilder.addTag(
          ContextTagKeys.AI_OPERATION_PARENT_ID.toString(), spanContext.getSpanId());
    }

    if (operationName == null) {
      Span localRootSpan = LocalRootSpan.fromContextOrNull(Context.current());
      if (localRootSpan instanceof ReadableSpan) {
        telemetryBuilder.addTag(
            ContextTagKeys.AI_OPERATION_NAME.toString(),
            AiOperationNameSpanProcessor.getOperationName((ReadableSpan) localRootSpan));
      }
    }

    if (applySampling) {
      float samplingPercentage =
          TelemetryUtil.getSamplingPercentage(
              spanContext.getTraceState(), BytecodeUtilImpl.samplingPercentage, false);

      if (samplingPercentage != 100) {
        telemetryBuilder.setSampleRate(samplingPercentage);
      }
    }
    // this is not null because sdk instrumentation is not added until TelemetryClient.setActive()
    // is called
    TelemetryClient.getActive().trackAsync(telemetryBuilder.build());
  }

  private static void trackAsStandalone(
      AbstractTelemetryBuilder telemetryBuilder, String operationId, boolean applySampling) {
    if (applySampling) {
      // sampling is done using the configured sampling percentage
      float samplingPercentage = BytecodeUtilImpl.samplingPercentage;
      if (!sample(operationId, samplingPercentage)) {
        logger.debug("Item {} sampled out", telemetryBuilder.getClass().getSimpleName());
        // sampled out
        return;
      }
      // sampled in

      if (samplingPercentage != 100) {
        telemetryBuilder.setSampleRate(samplingPercentage);
      }
    }

    // this is not null because sdk instrumentation is not added until TelemetryClient.setActive()
    // is called
    TelemetryClient.getActive().trackAsync(telemetryBuilder.build());
  }

  private static boolean sample(String operationId, double samplingPercentage) {
    if (samplingPercentage == 100) {
      // just an optimization
      return true;
    }
    return SamplingScoreGeneratorV2.getSamplingScore(operationId) < samplingPercentage;
  }

  private static void selectivelySetTags(
      AbstractTelemetryBuilder telemetryBuilder, Map<String, String> sourceTags) {
    for (Map.Entry<String, String> entry : sourceTags.entrySet()) {
      if (!entry.getKey().equals(ContextTagKeys.AI_INTERNAL_SDK_VERSION.toString())) {
        telemetryBuilder.addTag(entry.getKey(), entry.getValue());
      }
    }
  }
}
