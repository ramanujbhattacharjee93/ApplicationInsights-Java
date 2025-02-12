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

package com.microsoft.applicationinsights.smoketestapp;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleCounter;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.LongHistogram;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TestController {

  private final DoubleCounter doubleCounter =
      GlobalOpenTelemetry.get()
          .getMeter("trackDoubleCounterMetric")
          .counterBuilder("trackDoubleCounterMetric")
          .ofDoubles()
          .setUnit("1")
          .build();

  private final LongCounter longCounter =
      GlobalOpenTelemetry.get()
          .getMeter("trackLongCounterMetric")
          .counterBuilder("trackLongCounterMetric")
          .setUnit("1")
          .build();

  private final DoubleHistogram doubleHistogram =
      GlobalOpenTelemetry.get()
          .getMeter("trackDoubleHistogramMetric")
          .histogramBuilder("trackDoubleHistogramMetric")
          .setDescription("http.client.duration")
          .setUnit("ms")
          .build();

  private final LongHistogram longHistogram =
      GlobalOpenTelemetry.get()
          .getMeter("trackLongHistogramMetric")
          .histogramBuilder("trackLongHistogramMetric")
          .ofLongs()
          .setDescription("http.client.duration")
          .setUnit("ms")
          .build();

  public TestController() {
    GlobalOpenTelemetry.get()
        .getMeter("trackDoubleGaugeMetric")
        .gaugeBuilder("trackDoubleGaugeMetric")
        .setDescription("the current temperature")
        .setUnit("C")
        .buildWithCallback(
            m -> m.record(10.0, Attributes.of(AttributeKey.stringKey("thing1"), "thing2")));

    GlobalOpenTelemetry.get()
        .getMeter("trackLongGaugeMetric")
        .gaugeBuilder("trackLongGaugeMetric")
        .ofLongs()
        .setDescription("the current temperature")
        .setUnit("C")
        .buildWithCallback(
            m -> m.record(10L, Attributes.of(AttributeKey.stringKey("thing1"), "thing2")));
  }

  @GetMapping("/")
  public String root() {
    return "OK";
  }

  @GetMapping("/trackDoubleCounterMetric")
  public String trackDoubleCounterMetric() {
    doubleCounter.add(
        1.0,
        Attributes.of(
            AttributeKey.stringKey("name"), "apple", AttributeKey.stringKey("color"), "red"));
    doubleCounter.add(
        2.0,
        Attributes.of(
            AttributeKey.stringKey("name"), "lemon", AttributeKey.stringKey("color"), "yellow"));
    doubleCounter.add(
        1.0,
        Attributes.of(
            AttributeKey.stringKey("name"), "lemon", AttributeKey.stringKey("color"), "yellow"));
    doubleCounter.add(
        2.0,
        Attributes.of(
            AttributeKey.stringKey("name"), "apple", AttributeKey.stringKey("color"), "green"));
    doubleCounter.add(
        5.0,
        Attributes.of(
            AttributeKey.stringKey("name"), "apple", AttributeKey.stringKey("color"), "red"));
    doubleCounter.add(
        4.0,
        Attributes.of(
            AttributeKey.stringKey("name"), "lemon", AttributeKey.stringKey("color"), "yellow"));

    return "OK!";
  }

  @GetMapping("/trackLongCounterMetric")
  public String trackLongCounterMetric() {
    longCounter.add(
        1L,
        Attributes.of(
            AttributeKey.stringKey("name"), "apple", AttributeKey.stringKey("color"), "red"));
    longCounter.add(
        2L,
        Attributes.of(
            AttributeKey.stringKey("name"), "lemon", AttributeKey.stringKey("color"), "yellow"));
    longCounter.add(
        1L,
        Attributes.of(
            AttributeKey.stringKey("name"), "lemon", AttributeKey.stringKey("color"), "yellow"));
    longCounter.add(
        2L,
        Attributes.of(
            AttributeKey.stringKey("name"), "apple", AttributeKey.stringKey("color"), "green"));
    longCounter.add(
        5L,
        Attributes.of(
            AttributeKey.stringKey("name"), "apple", AttributeKey.stringKey("color"), "red"));
    longCounter.add(
        4L,
        Attributes.of(
            AttributeKey.stringKey("name"), "lemon", AttributeKey.stringKey("color"), "yellow"));

    return "OK!";
  }

  @GetMapping("/trackDoubleGaugeMetric")
  public String trackDoubleGaugeMetric() {
    return "OK!";
  }

  @GetMapping("/trackLongGaugeMetric")
  public String trackLongGaugeMetric() {
    return "OK!";
  }

  @GetMapping("/trackDoubleHistogramMetric")
  public String trackDoubleHistogramMetric() {
    doubleHistogram.record(456.0);
    return "OK!";
  }

  @GetMapping("/trackLongHistogramMetric")
  public String trackLongHistogramMetric() {
    longHistogram.record(456L);
    return "OK!";
  }
}
