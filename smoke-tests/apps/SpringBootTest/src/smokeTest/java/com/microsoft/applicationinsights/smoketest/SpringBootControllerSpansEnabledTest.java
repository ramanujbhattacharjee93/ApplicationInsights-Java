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

package com.microsoft.applicationinsights.smoketest;

import static com.microsoft.applicationinsights.smoketest.WarEnvironmentValue.TOMCAT_8_JAVA_8;
import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.applicationinsights.smoketest.schemav2.Data;
import com.microsoft.applicationinsights.smoketest.schemav2.Envelope;
import com.microsoft.applicationinsights.smoketest.schemav2.EventData;
import com.microsoft.applicationinsights.smoketest.schemav2.ExceptionData;
import com.microsoft.applicationinsights.smoketest.schemav2.RemoteDependencyData;
import com.microsoft.applicationinsights.smoketest.schemav2.RequestData;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

@Environment(TOMCAT_8_JAVA_8)
@UseAgent("controller_spans_enabled_applicationinsights.json")
class SpringBootControllerSpansEnabledTest {

  @RegisterExtension static final SmokeTestExtension testing = new SmokeTestExtension();

  @Test
  @TargetUri("/basic/trackEvent")
  void trackEvent() throws Exception {
    List<Envelope> rdList = testing.mockedIngestion.waitForItems("RequestData", 1);
    Envelope rdEnvelope = rdList.get(0);
    String operationId = rdEnvelope.getTags().get("ai.operation.id");

    testing.mockedIngestion.waitForItemsInOperation("EventData", 2, operationId);

    // TODO get event data envelope and verify value
    List<EventData> data = testing.mockedIngestion.getTelemetryDataByTypeInRequest("EventData");

    assertThat(data).anySatisfy(ed -> assertThat(ed.getName()).isEqualTo("EventDataTest"));

    assertThat(data)
        .anySatisfy(
            ed -> {
              assertThat(ed.getName()).isEqualTo("EventDataPropertyTest");
              assertThat(ed.getProperties()).containsEntry("key", "value");
              assertThat(ed.getMeasurements()).containsEntry("key", 1.0);
            });
  }

  @Test
  @TargetUri("/throwsException")
  void testResultCodeWhenRestControllerThrows() throws Exception {
    List<Envelope> rdList = testing.mockedIngestion.waitForItems("RequestData", 1);

    Envelope rdEnvelope = rdList.get(0);
    String operationId = rdEnvelope.getTags().get("ai.operation.id");
    List<Envelope> rddList =
        testing.mockedIngestion.waitForItemsInOperation("RemoteDependencyData", 1, operationId);
    List<Envelope> edList =
        testing.mockedIngestion.waitForItems(
            new Predicate<Envelope>() {
              @Override
              public boolean test(Envelope input) {
                if (!"ExceptionData".equals(input.getData().getBaseType())) {
                  return false;
                }
                if (!operationId.equals(input.getTags().get("ai.operation.id"))) {
                  return false;
                }
                // lastly, filter out ExceptionData captured from tomcat logger
                ExceptionData data = (ExceptionData) ((Data<?>) input.getData()).getBaseData();
                return !data.getProperties().containsKey("LoggerName");
              }
            },
            1,
            10,
            TimeUnit.SECONDS);
    assertThat(testing.mockedIngestion.getCountForType("EventData")).isZero();

    Envelope rddEnvelope1 = rddList.get(0);
    Envelope edEnvelope1 = edList.get(0);

    RequestData rd = testing.getTelemetryDataForType(0, "RequestData");
    RemoteDependencyData rdd1 =
        (RemoteDependencyData) ((Data<?>) rddEnvelope1.getData()).getBaseData();

    assertThat(rd.getName()).isEqualTo("GET /SpringBootTest/throwsException");
    assertThat(rd.getResponseCode()).isEqualTo("500");
    assertThat(rd.getProperties()).isEmpty();
    assertThat(rd.getSuccess()).isFalse();

    assertThat(rdd1.getName()).isEqualTo("TestController.resultCodeTest");
    assertThat(rdd1.getData()).isNull();
    assertThat(rdd1.getType()).isEqualTo("InProc");
    assertThat(rdd1.getTarget()).isNull();
    assertThat(rdd1.getProperties()).isEmpty();
    assertThat(rdd1.getSuccess()).isFalse();

    SmokeTestExtension.assertParentChild(
        rd, rdEnvelope, edEnvelope1, "GET /SpringBootTest/throwsException");
    SmokeTestExtension.assertParentChild(
        rd, rdEnvelope, rddEnvelope1, "GET /SpringBootTest/throwsException");
  }

  @Test
  @TargetUri("/asyncDependencyCall")
  void testAsyncDependencyCall() throws Exception {
    List<Envelope> rdList = testing.mockedIngestion.waitForItems("RequestData", 1);

    Envelope rdEnvelope = rdList.get(0);
    String operationId = rdEnvelope.getTags().get("ai.operation.id");
    List<Envelope> rddList =
        testing.mockedIngestion.waitForItemsInOperation("RemoteDependencyData", 3, operationId);
    assertThat(testing.mockedIngestion.getCountForType("EventData")).isZero();

    Envelope rddEnvelope1 = rddList.get(0);
    Envelope rddEnvelope2 = rddList.get(1);
    Envelope rddEnvelope3 = rddList.get(2);

    RequestData rd = (RequestData) ((Data<?>) rdEnvelope.getData()).getBaseData();
    RemoteDependencyData rdd1 =
        (RemoteDependencyData) ((Data<?>) rddEnvelope1.getData()).getBaseData();
    RemoteDependencyData rdd2 =
        (RemoteDependencyData) ((Data<?>) rddEnvelope2.getData()).getBaseData();
    RemoteDependencyData rdd3 =
        (RemoteDependencyData) ((Data<?>) rddEnvelope3.getData()).getBaseData();

    assertThat(rd.getName()).isEqualTo("GET /SpringBootTest/asyncDependencyCall");
    assertThat(rd.getResponseCode()).isEqualTo("200");
    assertThat(rd.getProperties()).isEmpty();
    assertThat(rd.getSuccess()).isTrue();

    assertThat(rdd1.getName()).isEqualTo("TestController.asyncDependencyCall");
    assertThat(rdd1.getData()).isNull();
    assertThat(rdd1.getType()).isEqualTo("InProc");
    assertThat(rdd1.getTarget()).isNull();
    assertThat(rdd1.getProperties()).isEmpty();
    assertThat(rdd1.getSuccess()).isTrue();

    assertThat(rdd2.getName()).isEqualTo("GET /");
    assertThat(rdd2.getData()).isEqualTo("https://www.bing.com");
    assertThat(rdd2.getTarget()).isEqualTo("www.bing.com");
    assertThat(rdd2.getProperties()).isEmpty();
    assertThat(rdd2.getSuccess()).isTrue();

    // TODO (trask): why is spring-webmvc instrumentation capturing this twice?
    assertThat(rdd3.getName()).isEqualTo("TestController.asyncDependencyCall");
    assertThat(rdd3.getProperties()).isEmpty();
    assertThat(rdd3.getSuccess()).isTrue();

    SmokeTestExtension.assertParentChild(
        rd, rdEnvelope, rddEnvelope1, "GET /SpringBootTest/asyncDependencyCall");
    SmokeTestExtension.assertParentChild(
        rdd1, rddEnvelope1, rddEnvelope2, "GET /SpringBootTest/asyncDependencyCall");
    try {
      SmokeTestExtension.assertParentChild(
          rdd1, rddEnvelope1, rddEnvelope3, "GET /SpringBootTest/asyncDependencyCall");
    } catch (AssertionError e) {
      // on wildfly the duplicate controller spans is nested under the request span for some reason
      SmokeTestExtension.assertParentChild(
          rd, rdEnvelope, rddEnvelope3, "GET /SpringBootTest/asyncDependencyCall");
    }
  }
}
