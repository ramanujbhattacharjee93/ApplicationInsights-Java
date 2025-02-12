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

import static com.azure.core.util.tracing.Tracer.DISABLE_TRACING_KEY;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.azure.core.http.HttpPipelineCallContext;
import com.azure.core.http.HttpPipelineNextPolicy;
import com.azure.core.http.HttpResponse;
import com.azure.core.http.policy.HttpPipelinePolicy;
import com.azure.core.test.TestBase;
import com.azure.core.test.TestMode;
import com.azure.core.util.Context;
import com.azure.core.util.FluxUtil;
import com.azure.data.appconfiguration.ConfigurationClient;
import com.azure.data.appconfiguration.ConfigurationClientBuilder;
import com.azure.data.appconfiguration.models.ConfigurationSetting;
import com.azure.monitor.opentelemetry.exporter.implementation.utils.TestUtils;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import reactor.core.publisher.Mono;

public class AppConfigurationExporterIntegrationTest extends TestBase {

  @Override
  @BeforeEach
  public void setupTest(TestInfo testInfo) {
    Assumptions.assumeFalse(getTestMode() == TestMode.PLAYBACK, "Skipping playback tests");
  }

  @Override
  @AfterEach
  public void teardownTest(TestInfo testInfo) {
    GlobalOpenTelemetry.resetForTest();
  }

  @Test
  public void setConfigurationTest() throws InterruptedException {
    CountDownLatch appConfigCountDown = new CountDownLatch(1);
    CountDownLatch exporterCountDown = new CountDownLatch(1);

    Tracer tracer =
        TestUtils.configureAzureMonitorTraceExporter(
            new ValidationPolicy(exporterCountDown, "AppConfig.setKey"));
    ConfigurationClient client = getConfigurationClient(appConfigCountDown);

    Span span = tracer.spanBuilder("set-config-exporter-testing").startSpan();
    Scope scope = span.makeCurrent();
    try {
      // Thread bound (sync) calls will automatically pick up the parent span and you don't need to
      // pass it explicitly.
      client.setConfigurationSetting("hello", "text", "World");
    } finally {
      span.end();
      scope.close();
    }
    assertTrue(appConfigCountDown.await(60, TimeUnit.SECONDS));
    assertTrue(exporterCountDown.await(60, TimeUnit.SECONDS));
  }

  @Disabled(
      "Multiple tests fail to trigger end span - https://github.com/Azure/azure-sdk-for-java/issues/23567")
  @Test
  public void testDisableTracing() throws InterruptedException {
    CountDownLatch appConfigCountDown = new CountDownLatch(1);
    CountDownLatch exporterCountDown = new CountDownLatch(1);

    Tracer tracer =
        TestUtils.configureAzureMonitorTraceExporter(
            new ValidationPolicy(exporterCountDown, "disable-config-exporter-testing"));
    ConfigurationClient client = getConfigurationClient(appConfigCountDown);

    Span span = tracer.spanBuilder("disable-config-exporter-testing").startSpan();
    Scope scope = span.makeCurrent();
    try {
      ConfigurationSetting configurationSetting =
          new ConfigurationSetting().setKey("hello").setLabel("text").setValue("World");
      client.setConfigurationSettingWithResponse(
          configurationSetting, false, Context.NONE.addData(DISABLE_TRACING_KEY, true));
    } finally {
      span.end();
      scope.close();
    }
    assertTrue(appConfigCountDown.await(60, TimeUnit.SECONDS));
    assertTrue(exporterCountDown.await(60, TimeUnit.SECONDS));
  }

  private static ConfigurationClient getConfigurationClient(CountDownLatch appConfigCountDown) {
    return new ConfigurationClientBuilder()
        .connectionString(System.getenv("AZURE_APPCONFIG_CONNECTION_STRING"))
        .addPolicy(
            (context, next) -> {
              Optional<Object> data =
                  context.getData(com.azure.core.util.tracing.Tracer.AZ_TRACING_NAMESPACE_KEY);
              if (data.isPresent() && data.get().equals("Microsoft.AppConfiguration")) {
                appConfigCountDown.countDown();
              }
              return next.process();
            })
        .buildClient();
  }

  static class ValidationPolicy implements HttpPipelinePolicy {

    private final CountDownLatch countDown;
    private final String expectedSpanName;

    ValidationPolicy(CountDownLatch countDown, String expectedSpanName) {
      this.countDown = countDown;
      this.expectedSpanName = expectedSpanName;
    }

    @Override
    public Mono<HttpResponse> process(
        HttpPipelineCallContext context, HttpPipelineNextPolicy next) {
      Mono<String> asyncString =
          FluxUtil.collectBytesInByteBufferStream(context.getHttpRequest().getBody())
              .map(bytes -> new String(bytes, StandardCharsets.UTF_8));
      asyncString.subscribe(
          value -> {
            if (value.contains(expectedSpanName)) {
              countDown.countDown();
            }
          });
      return next.process();
    }
  }
}
