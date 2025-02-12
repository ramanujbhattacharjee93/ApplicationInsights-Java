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

package com.azure.monitor.opentelemetry.exporter.implementation.localstorage;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.azure.core.http.HttpClient;
import com.azure.core.http.HttpPipelineBuilder;
import com.azure.core.http.HttpRequest;
import com.azure.core.test.http.MockHttpResponse;
import com.azure.core.util.Context;
import com.azure.monitor.opentelemetry.exporter.implementation.models.TelemetryItem;
import com.azure.monitor.opentelemetry.exporter.implementation.pipeline.TelemetryItemExporter;
import com.azure.monitor.opentelemetry.exporter.implementation.pipeline.TelemetryPipeline;
import com.azure.monitor.opentelemetry.exporter.implementation.utils.TestUtils;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Mono;

public class IntegrationTests {

  private static final String INSTRUMENTATION_KEY = "00000000-0000-0000-0000-0FEEDDADBEEF";
  private static final String PERSISTED_FILENAME = "gzipped-raw-bytes.trn";

  private TelemetryItemExporter telemetryItemExporter;

  @TempDir File tempFolder;

  // TODO (trask) test with both
  private static final boolean testWithException = false;

  @BeforeEach
  public void setup() throws Exception {
    HttpClient mockedClient = mock(HttpClient.class);
    if (testWithException) {
      when(mockedClient.send(any(HttpRequest.class), any(Context.class)))
          .then(
              invocation ->
                  Mono.error(
                      () ->
                          new Exception("this is expected to be logged by the operation logger")));
    } else {
      // 401, 403, 408, 429, 500, and 503 response codes result in storing to disk
      when(mockedClient.send(any(HttpRequest.class), any(Context.class)))
          .then(
              invocation ->
                  Mono.just(
                      new MockHttpResponse(invocation.getArgument(0, HttpRequest.class), 500)));
    }
    HttpPipelineBuilder pipelineBuilder = new HttpPipelineBuilder().httpClient(mockedClient);

    URL url = new URL("http://foo.bar");
    TelemetryPipeline telemetryPipeline = new TelemetryPipeline(pipelineBuilder.build(), () -> url);
    telemetryItemExporter =
        new TelemetryItemExporter(
            telemetryPipeline,
            new LocalStorageTelemetryPipelineListener(
                50, tempFolder, telemetryPipeline, LocalStorageStats.noop(), false));
  }

  @Test
  public void integrationTest() throws Exception {
    List<TelemetryItem> telemetryItems = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      TelemetryItem item = TestUtils.createMetricTelemetry("metric" + i, i, INSTRUMENTATION_KEY);
      item.setTime(OffsetDateTime.parse("2021-11-09T03:12:19.06Z"));
      telemetryItems.add(item);
    }

    ExecutorService executorService = Executors.newFixedThreadPool(10);
    for (int i = 0; i < 10; i++) {
      executorService.execute(
          () -> {
            for (int j = 0; j < 10; j++) {
              telemetryItemExporter.send(telemetryItems);
            }
          });
    }

    telemetryItemExporter.flush();

    executorService.shutdown();
    executorService.awaitTermination(10, TimeUnit.MINUTES);

    Thread.sleep(1000);

    LocalFileCache localFileCache = new LocalFileCache(tempFolder);
    LocalFileLoader localFileLoader =
        new LocalFileLoader(localFileCache, tempFolder, LocalStorageStats.noop(), false);

    assertThat(localFileCache.getPersistedFilesCache().size()).isEqualTo(100);

    for (int i = 100; i > 0; i--) {
      LocalFileLoader.PersistedFile file = localFileLoader.loadTelemetriesFromDisk();
      assertThat(ungzip(file.rawBytes.array()))
          .isEqualTo(new String(getByteBufferFromFile("ungzip-source.txt").array(), UTF_8));
      assertThat(file.instrumentationKey).isEqualTo(INSTRUMENTATION_KEY);
      assertThat(localFileCache.getPersistedFilesCache().size()).isEqualTo(i - 1);
    }

    assertThat(localFileCache.getPersistedFilesCache().size()).isEqualTo(0);
  }

  @Test
  public void verifyGzipRawBytesTest() throws Exception {
    File sourceFile =
        new File(getClass().getClassLoader().getResource(PERSISTED_FILENAME).getPath());
    File persistedFile = new File(tempFolder, PERSISTED_FILENAME);
    Files.copy(sourceFile.toPath(), persistedFile.toPath());

    assertThat(persistedFile.exists()).isTrue();

    LocalFileCache localFileCache = new LocalFileCache(tempFolder);
    localFileCache.addPersistedFile(persistedFile);

    LocalFileLoader localFileLoader = new LocalFileLoader(localFileCache, tempFolder, null, false);
    LocalFileLoader.PersistedFile loadedPersistedFile = localFileLoader.loadTelemetriesFromDisk();

    ByteBuffer expectedGzipByteBuffer = getByteBufferFromFile(PERSISTED_FILENAME);
    byte[] ikeyBytes = new byte[36];
    expectedGzipByteBuffer.get(ikeyBytes, 0, 36);
    assertThat(new String(ikeyBytes, UTF_8)).isEqualTo(INSTRUMENTATION_KEY);
    int length = expectedGzipByteBuffer.remaining();
    byte[] telemetryBytes = new byte[length];

    expectedGzipByteBuffer.get(telemetryBytes, 0, length);
    assertThat(loadedPersistedFile.rawBytes).isEqualTo(ByteBuffer.wrap(telemetryBytes));
  }

  private ByteBuffer getByteBufferFromFile(String filename) throws Exception {
    Path path = new File(getClass().getClassLoader().getResource(filename).getPath()).toPath();
    return ByteBuffer.wrap(Files.readAllBytes(path));
  }

  private static String ungzip(byte[] rawBytes) throws Exception {
    try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(rawBytes))) {
      ByteArrayOutputStream baos = new ByteArrayOutputStream();
      byte[] data = new byte[1024];
      int read;
      while ((read = in.read(data, 0, data.length)) != -1) {
        baos.write(data, 0, read);
      }
      return new String(baos.toByteArray(), StandardCharsets.UTF_8);
    }
  }
}
