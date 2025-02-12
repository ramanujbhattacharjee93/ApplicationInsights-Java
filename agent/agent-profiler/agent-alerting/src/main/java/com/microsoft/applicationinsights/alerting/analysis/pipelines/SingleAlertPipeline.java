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

package com.microsoft.applicationinsights.alerting.analysis.pipelines;

import com.microsoft.applicationinsights.alerting.alert.AlertBreach;
import com.microsoft.applicationinsights.alerting.analysis.AlertPipelineTrigger;
import com.microsoft.applicationinsights.alerting.analysis.aggregations.Aggregation;
import com.microsoft.applicationinsights.alerting.analysis.data.TelemetryDataPoint;
import com.microsoft.applicationinsights.alerting.analysis.filter.AlertRequestFilter;
import com.microsoft.applicationinsights.alerting.config.AlertingConfiguration.AlertConfiguration;
import java.lang.management.ManagementFactory;
import java.time.format.DateTimeFormatter;
import java.util.OptionalDouble;
import java.util.function.Consumer;
import javax.management.MBeanInfo;
import javax.management.ObjectName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Contains a pipeline that receives telemetry, feeds it into the analysis pipeline. */
public class SingleAlertPipeline implements AlertPipeline, AlertPipelineMXBean {

  private static final Logger LOGGER = LoggerFactory.getLogger(SingleAlertPipeline.class);
  private static final String JMX_KEY = "com.microsoft:type=AI-alert,name=";

  private final Aggregation aggregation;
  private final Consumer<AlertBreach> alertObserver;
  private final AlertRequestFilter filter;

  private AlertConfiguration alertConfiguration;
  private AlertPipelineTrigger alertTrigger;

  public SingleAlertPipeline(
      AlertRequestFilter filter,
      Aggregation aggregation,
      Consumer<AlertBreach> alertObserver,
      AlertConfiguration alertConfiguration) {
    this.filter = filter;
    this.aggregation = aggregation;
    this.alertObserver = alertObserver;
    this.alertConfiguration = alertConfiguration;
  }

  public static SingleAlertPipeline create(
      AlertRequestFilter filter,
      Aggregation aggregation,
      AlertConfiguration alertConfiguration,
      Consumer<AlertBreach> alertObserver) {
    SingleAlertPipeline trigger =
        new SingleAlertPipeline(filter, aggregation, alertObserver, alertConfiguration);
    trigger.registerMbean();

    trigger.setAlertTrigger(aggregation, alertConfiguration, alertObserver);
    return trigger;
  }

  private void registerMbean() {
    try {
      ObjectName objectName = new ObjectName(JMX_KEY + alertConfiguration.getType().name());

      try {
        MBeanInfo existing = ManagementFactory.getPlatformMBeanServer().getMBeanInfo(objectName);
        if (existing != null) {
          ManagementFactory.getPlatformMBeanServer().unregisterMBean(objectName);
        }
      } catch (Exception e) {
        // Expected if mbean does not exist
      }

      ManagementFactory.getPlatformMBeanServer().registerMBean(this, objectName);

    } catch (Exception e) {
      LOGGER.error("Failed to register MBEAN", e);
    }
  }

  private void setAlertTrigger(
      Aggregation aggregation,
      AlertConfiguration newAlertConfig,
      Consumer<AlertBreach> alertObserver) {
    this.alertTrigger = new AlertPipelineTrigger(newAlertConfig, alertObserver);
    aggregation.setConsumer(alertTrigger);
  }

  @Override
  public OptionalDouble getValue() {
    return aggregation.compute();
  }

  @Override
  public void updateConfig(AlertConfiguration newAlertConfig) {
    this.alertConfiguration = newAlertConfig;
    setAlertTrigger(aggregation, newAlertConfig, alertObserver);
  }

  @Override
  public void track(TelemetryDataPoint telemetryDataPoint) {
    if (filter.test(telemetryDataPoint.getName())) {
      aggregation.update(telemetryDataPoint);
    }
  }

  @Override
  public long getCoolDown() {
    return alertConfiguration.getCooldown();
  }

  @Override
  public long getProfilerDuration() {
    return alertConfiguration.getProfileDuration();
  }

  @Override
  public float getThreshold() {
    return alertConfiguration.getThreshold();
  }

  @Override
  public double getCurrentAverage() {
    return aggregation.compute().orElse(0.0d);
  }

  @Override
  public boolean getEnabled() {
    return alertConfiguration.isEnabled();
  }

  @Override
  public boolean isOffCooldown() {
    return this.alertTrigger.isOffCooldown();
  }

  @Override
  public String getLastAlertTime() {
    return DateTimeFormatter.ISO_ZONED_DATE_TIME.format(this.alertTrigger.getLastAlertTime());
  }
}
