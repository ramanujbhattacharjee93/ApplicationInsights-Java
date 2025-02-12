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

package com.azure.monitor.opentelemetry.exporter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.MapEntry.entry;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.azure.core.http.HttpPipelineCallContext;
import com.azure.core.http.HttpPipelineNextPolicy;
import com.azure.core.http.HttpResponse;
import com.azure.core.http.policy.HttpPipelinePolicy;
import com.azure.core.util.FluxUtil;
import com.azure.monitor.opentelemetry.exporter.implementation.models.MetricsData;
import com.azure.monitor.opentelemetry.exporter.implementation.models.RemoteDependencyData;
import com.azure.monitor.opentelemetry.exporter.implementation.models.TelemetryItem;
import com.azure.monitor.opentelemetry.exporter.implementation.utils.MockLogData;
import com.azure.monitor.opentelemetry.exporter.implementation.utils.TestUtils;
import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.common.CompletableResultCode;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import reactor.core.publisher.Mono;
import uk.org.webcompere.systemstubs.environment.EnvironmentVariables;
import uk.org.webcompere.systemstubs.jupiter.SystemStub;
import uk.org.webcompere.systemstubs.jupiter.SystemStubsExtension;

@ExtendWith(SystemStubsExtension.class)
public class AzureMonitorExportersEndToEndTest extends MonitorExporterClientTestBase {

  @SystemStub EnvironmentVariables envVars = new EnvironmentVariables();

  private static final String TRACE_CONNECTION_STRING =
      "InstrumentationKey=00000000-0000-0000-0000-000000000000";
  private static final String INSTRUMENTATION_KEY = "00000000-0000-0000-0000-0FEEDDADBEEF";

  @BeforeEach
  public void setup() {
    envVars.set(
        "APPLICATIONINSIGHTS_CONNECTION_STRING", "InstrumentationKey=" + INSTRUMENTATION_KEY);
  }

  @Test
  public void testBuildMetricExporter() throws Exception {
    validateMetricExporterEndToEnd("testBuildMetricExporter");
  }

  @Test
  public void testBuildTraceExporter() throws Exception {
    validateTraceExporterEndToEnd("testBuildTraceExporter");
  }

  // OpenTelemetry doesn't have a Log API
  @Test
  public void testBuildLogExporter() throws Exception {
    validateLogExporterEndToEnd();
  }

  @Test
  public void testBuildTraceMetricLogExportersConsecutively() throws Exception {
    validateTraceExporterEndToEnd("testBuildTraceMetricLogExportersConsecutively");
    validateMetricExporterEndToEnd("testBuildTraceMetricLogExportersConsecutively");
    validateLogExporterEndToEnd();
  }

  private static void validateMetricExporterEndToEnd(String testName) throws Exception {
    List<TelemetryItem> actualTelemetryItems = generateMetrics(testName);
    assertThat(actualTelemetryItems.size()).isGreaterThan(0);
    TelemetryItem actualTelemetryItem = actualTelemetryItems.get(0);
    assertThat(actualTelemetryItem.getName()).isEqualTo("Metric");
    assertThat(actualTelemetryItem.getInstrumentationKey()).isEqualTo(INSTRUMENTATION_KEY);
    assertThat(actualTelemetryItem.getTags())
        .containsEntry("ai.cloud.role", "unknown_service:java");
    assertThat(actualTelemetryItem.getTags())
        .hasEntrySatisfying("ai.internal.sdkVersion", v -> assertThat(v).contains("otel"));
    assertThat(actualTelemetryItem.getData().getBaseType()).isEqualTo("MetricData");
    MetricsData actualMetricsData = (MetricsData) actualTelemetryItem.getData().getBaseData();
    assertThat(actualMetricsData.getMetrics().get(0).getValue()).isEqualTo(1);
    assertThat(actualMetricsData.getMetrics().get(0).getName()).isEqualTo(testName);
    assertThat(actualMetricsData.getProperties())
        .containsExactly(entry("color", "red"), entry("name", "apple"));
  }

  private static void validateTraceExporterEndToEnd(String testName) throws Exception {
    List<TelemetryItem> actualTelemetryItems = generateTraces(testName);
    assertThat(actualTelemetryItems.size()).isGreaterThan(0);
    TelemetryItem actualTelemetryItem = actualTelemetryItems.get(0);
    assertThat(actualTelemetryItem.getName()).isEqualTo("RemoteDependency");
    assertThat(actualTelemetryItem.getInstrumentationKey()).isEqualTo(INSTRUMENTATION_KEY);
    assertThat(actualTelemetryItem.getTags())
        .containsEntry("ai.cloud.role", "unknown_service:java");
    assertThat(actualTelemetryItem.getTags())
        .hasEntrySatisfying("ai.internal.sdkVersion", v -> assertThat(v).contains("otel"));
    assertThat(actualTelemetryItem.getData().getBaseType()).isEqualTo("RemoteDependencyData");
    RemoteDependencyData actualData =
        (RemoteDependencyData) actualTelemetryItem.getData().getBaseData();
    assertThat(actualData.getName()).isEqualTo(testName);
    assertThat(actualData.getProperties())
        .containsExactly(entry("color", "red"), entry("name", "apple"));
  }

  private void validateLogExporterEndToEnd() throws Exception {
    AzureMonitorLogExporter azureMonitorLogExporter =
        getClientBuilder().connectionString(TRACE_CONNECTION_STRING).buildLogExporter();
    CompletableResultCode export =
        azureMonitorLogExporter.export(Collections.singleton(new MockLogData()));
    export.join(30, TimeUnit.SECONDS);
    Assertions.assertTrue(export.isDone());
    Assertions.assertTrue(export.isSuccess());
  }

  private static List<TelemetryItem> generateTraces(String testName) throws Exception {
    CountDownLatch traceExporterCountDown = new CountDownLatch(1);
    CustomValidationPolicy customValidationPolicy =
        new CustomValidationPolicy(traceExporterCountDown);
    Tracer tracer = TestUtils.configureAzureMonitorTraceExporter(customValidationPolicy);
    Span span = tracer.spanBuilder(testName).startSpan();
    try (Scope scope = span.makeCurrent()) {
      span.setAttribute("name", "apple");
      span.setAttribute("color", "red");
    } finally {
      span.end();
    }
    assertTrue(traceExporterCountDown.await(60, TimeUnit.SECONDS));
    return customValidationPolicy.actualTelemetryItems;
  }

  private static List<TelemetryItem> generateMetrics(String testName) throws Exception {
    CountDownLatch metricExporterCountDown = new CountDownLatch(1);
    CustomValidationPolicy customValidationPolicy =
        new CustomValidationPolicy(metricExporterCountDown);
    Meter meter = TestUtils.configureAzureMonitorMetricExporter(customValidationPolicy);
    LongCounter counter = meter.counterBuilder(testName).build();
    counter.add(
        1L,
        Attributes.of(
            AttributeKey.stringKey("name"), "apple", AttributeKey.stringKey("color"), "red"));
    metricExporterCountDown.await(60, TimeUnit.SECONDS);
    return customValidationPolicy.actualTelemetryItems;
  }

  private static class CustomValidationPolicy implements HttpPipelinePolicy {

    private final CountDownLatch countDown;
    private final List<TelemetryItem> actualTelemetryItems = new ArrayList<>();

    CustomValidationPolicy(CountDownLatch countDown) {
      this.countDown = countDown;
    }

    @Override
    public Mono<HttpResponse> process(
        HttpPipelineCallContext context, HttpPipelineNextPolicy next) {
      Mono<String> asyncBytes =
          FluxUtil.collectBytesInByteBufferStream(context.getHttpRequest().getBody())
              .map(
                  bytes -> {
                    return ungzip(bytes);
                  });
      asyncBytes.subscribe(
          value -> {
            ObjectMapper objectMapper = createObjectMapper();
            try (MappingIterator<TelemetryItem> i =
                objectMapper.readerFor(TelemetryItem.class).readValues(value)) {
              while (i.hasNext()) {
                actualTelemetryItems.add(i.next());
              }
              countDown.countDown();
            } catch (Exception e) {
              // e.printStackTrace();
            }
          });
      return next.process();
    }

    // decode gzipped request raw bytes back to original request body
    private static String ungzip(byte[] rawBytes) {
      try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(rawBytes))) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] data = new byte[1024];
        int read;
        while ((read = in.read(data, 0, data.length)) != -1) {
          baos.write(data, 0, read);
        }
        return new String(baos.toByteArray(), StandardCharsets.UTF_8);
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }

    private static ObjectMapper createObjectMapper() {
      ObjectMapper objectMapper = new ObjectMapper();
      // handle JSR-310 (java 8) dates with Jackson by configuring ObjectMapper to use this
      // dependency and not (de)serialize Instant as timestamps that it does by default
      objectMapper.findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
      return objectMapper;
    }
  }
}
