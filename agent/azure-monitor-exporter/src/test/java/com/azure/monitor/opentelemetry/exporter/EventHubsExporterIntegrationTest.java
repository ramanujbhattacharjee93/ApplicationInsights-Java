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

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.azure.core.test.TestBase;
import com.azure.core.test.TestMode;
import com.azure.core.util.FluxUtil;
import com.azure.messaging.eventhubs.EventData;
import com.azure.messaging.eventhubs.EventHubClientBuilder;
import com.azure.messaging.eventhubs.EventHubProducerAsyncClient;
import com.azure.messaging.eventhubs.EventProcessorClient;
import com.azure.messaging.eventhubs.EventProcessorClientBuilder;
import com.azure.messaging.eventhubs.LoadBalancingStrategy;
import com.azure.messaging.eventhubs.checkpointstore.blob.BlobCheckpointStore;
import com.azure.messaging.eventhubs.models.CreateBatchOptions;
import com.azure.monitor.opentelemetry.exporter.implementation.utils.TestUtils;
import com.azure.storage.blob.BlobContainerAsyncClient;
import com.azure.storage.blob.BlobContainerClientBuilder;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import reactor.core.publisher.Mono;

public class EventHubsExporterIntegrationTest extends TestBase {

  private static final String CONNECTION_STRING =
      System.getenv("AZURE_EVENTHUBS_CONNECTION_STRING");
  private static final String STORAGE_CONNECTION_STRING =
      System.getenv("STORAGE_CONNECTION_STRING");
  private static final String CONTAINER_NAME = System.getenv("STORAGE_CONTAINER_NAME");

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
  public void producerTest() throws InterruptedException {
    CountDownLatch exporterCountDown = new CountDownLatch(2);
    String spanName = "event-hubs-producer-testing";
    Tracer tracer =
        TestUtils.configureAzureMonitorTraceExporter(
            (context, next) -> {
              Mono<String> asyncString =
                  FluxUtil.collectBytesInByteBufferStream(context.getHttpRequest().getBody())
                      .map(bytes -> new String(bytes, StandardCharsets.UTF_8));
              asyncString.subscribe(
                  value -> {
                    if (value.contains(spanName)) {
                      exporterCountDown.countDown();
                    }
                    if (value.contains("EventHubs.send")) {
                      exporterCountDown.countDown();
                    }
                  });
              return next.process();
            });
    EventHubProducerAsyncClient producer =
        new EventHubClientBuilder().connectionString(CONNECTION_STRING).buildAsyncProducerClient();
    Span span = tracer.spanBuilder(spanName).startSpan();
    Scope scope = span.makeCurrent();
    try {
      producer
          .createBatch()
          .flatMap(
              batch -> {
                batch.tryAdd(new EventData("test event"));
                return producer.send(batch);
              })
          .subscribe();
    } finally {
      span.end();
      scope.close();
    }
    assertTrue(exporterCountDown.await(5, TimeUnit.SECONDS));
  }

  @Disabled(
      "Processor integration tests require separate consumer group to not have partition contention in CI - https://github.com/Azure/azure-sdk-for-java/issues/23567")
  @Test
  public void processorTest() throws InterruptedException {
    CountDownLatch exporterCountDown = new CountDownLatch(3);
    EventHubProducerAsyncClient producer =
        new EventHubClientBuilder().connectionString(CONNECTION_STRING).buildAsyncProducerClient();

    Tracer tracer =
        TestUtils.configureAzureMonitorTraceExporter(
            (context, next) -> {
              Mono<String> asyncString =
                  FluxUtil.collectBytesInByteBufferStream(context.getHttpRequest().getBody())
                      .map(bytes -> new String(bytes, StandardCharsets.UTF_8));
              asyncString.subscribe(
                  value -> {
                    // user span
                    if (value.contains("event-hubs-consumer-testing")) {
                      exporterCountDown.countDown();
                    }
                    // process span
                    if (value.contains("EventHubs.process")) {
                      exporterCountDown.countDown();
                    }
                    // Storage call
                    if (value.contains("AzureBlobStorageBlobs.setMetadata")) {
                      exporterCountDown.countDown();
                    }
                  });
              return next.process();
            });

    CountDownLatch partitionOwned = new CountDownLatch(1);
    CountDownLatch eventCountDown = new CountDownLatch(1);
    BlobContainerAsyncClient blobContainerAsyncClient =
        new BlobContainerClientBuilder()
            .connectionString(STORAGE_CONNECTION_STRING)
            .containerName(CONTAINER_NAME)
            .buildAsyncClient();
    EventProcessorClient processorClient =
        new EventProcessorClientBuilder()
            .consumerGroup(EventHubClientBuilder.DEFAULT_CONSUMER_GROUP_NAME)
            .connectionString(CONNECTION_STRING)
            .processPartitionInitialization(
                partition -> {
                  if (partition.getPartitionContext().getPartitionId().equals("0")) {
                    partitionOwned.countDown();
                  }
                })
            .processEvent(
                event -> {
                  event.updateCheckpoint();
                  eventCountDown.countDown();
                })
            .processError(error -> {})
            .loadBalancingStrategy(LoadBalancingStrategy.GREEDY)
            .checkpointStore(new BlobCheckpointStore(blobContainerAsyncClient))
            .buildEventProcessorClient();

    Span span = tracer.spanBuilder("event-hubs-consumer-testing").startSpan();
    Scope scope = span.makeCurrent();
    try {
      processorClient.start();
    } finally {
      span.end();
      scope.close();
    }
    partitionOwned.await(10, TimeUnit.SECONDS);

    // send an event after partition 0 is owned
    producer
        .createBatch(new CreateBatchOptions().setPartitionId("0"))
        .flatMap(
            batch -> {
              batch.tryAdd(new EventData("test event "));
              return producer.send(batch);
            })
        .block();

    assertTrue(eventCountDown.await(10, TimeUnit.SECONDS));
    assertTrue(exporterCountDown.await(10, TimeUnit.SECONDS));
  }
}
