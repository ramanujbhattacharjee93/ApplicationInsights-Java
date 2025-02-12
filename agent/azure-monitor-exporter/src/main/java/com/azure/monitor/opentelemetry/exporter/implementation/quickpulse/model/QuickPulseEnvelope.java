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

package com.azure.monitor.opentelemetry.exporter.implementation.quickpulse.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public class QuickPulseEnvelope {
  @JsonProperty(value = "Documents")
  private List<QuickPulseDocument> documents;

  @JsonProperty(value = "InstrumentationKey")
  private String instrumentationKey;

  @JsonProperty(value = "Metrics")
  private List<QuickPulseMetrics> metrics;

  @JsonProperty(value = "InvariantVersion")
  private int invariantVersion;

  @JsonProperty(value = "Timestamp")
  private String timeStamp;

  @JsonProperty(value = "Version")
  private String version;

  @JsonProperty(value = "StreamId")
  private String streamId;

  @JsonProperty(value = "MachineName")
  private String machineName;

  @JsonProperty(value = "Instance")
  private String instance;

  @JsonProperty(value = "RoleName")
  private String roleName;

  public List<QuickPulseDocument> getDocuments() {
    return documents;
  }

  public void setDocuments(List<QuickPulseDocument> documents) {
    this.documents = documents;
  }

  public String getInstrumentationKey() {
    return instrumentationKey;
  }

  public void setInstrumentationKey(String instrumentationKey) {
    this.instrumentationKey = instrumentationKey;
  }

  public List<QuickPulseMetrics> getMetrics() {
    return metrics;
  }

  public void setMetrics(List<QuickPulseMetrics> metrics) {
    this.metrics = metrics;
  }

  public int getInvariantVersion() {
    return invariantVersion;
  }

  public void setInvariantVersion(int invariantVersion) {
    this.invariantVersion = invariantVersion;
  }

  public String getTimeStamp() {
    return timeStamp;
  }

  public void setTimeStamp(String timeStamp) {
    this.timeStamp = timeStamp;
  }

  public String getVersion() {
    return version;
  }

  public void setVersion(String version) {
    this.version = version;
  }

  public String getStreamId() {
    return streamId;
  }

  public void setStreamId(String streamId) {
    this.streamId = streamId;
  }

  public String getMachineName() {
    return machineName;
  }

  public void setMachineName(String machineName) {
    this.machineName = machineName;
  }

  public String getInstance() {
    return instance;
  }

  public void setInstance(String instance) {
    this.instance = instance;
  }

  public String getRoleName() {
    return roleName;
  }

  public void setRoleName(String roleName) {
    this.roleName = roleName;
  }
}
