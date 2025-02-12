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

package com.azure.monitor.opentelemetry.exporter.implementation.heartbeat;

import com.azure.monitor.opentelemetry.exporter.implementation.utils.VersionGenerator;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 *
 * <h1>Base Heartbeat Property Provider</h1>
 *
 * <p>This class is a concrete implementation of {@link HeartBeatPayloadProviderInterface} It
 * enables setting SDK Metadata to heartbeat payload.
 */
public class DefaultHeartBeatPropertyProvider implements HeartBeatPayloadProviderInterface {

  private static final Logger logger =
      LoggerFactory.getLogger(DefaultHeartBeatPropertyProvider.class);

  /** Collection holding default properties for this default provider. */
  private final Set<String> defaultFields;

  /**
   * Random GUID that would help in analysis when app has stopped and restarted. Each restart will
   * have a new GUID. If the application is unstable and goes through frequent restarts this will
   * help us identify instability in the analytics backend.
   */
  private static UUID uniqueProcessId;

  private static final String JRE_VERSION = "jreVersion";

  private static final String SDK_VERSION = "sdkVersion";

  private static final String OS_VERSION = "osVersion";

  private static final String PROCESS_SESSION_ID = "processSessionId";

  private static final String OS_TYPE = "osType";

  public DefaultHeartBeatPropertyProvider() {
    defaultFields = new HashSet<>();
    initializeDefaultFields(defaultFields);
  }

  @Override
  public Callable<Boolean> setDefaultPayload(HeartbeatExporter provider) {
    return new Callable<Boolean>() {

      final Set<String> enabledProperties = defaultFields;

      @Override
      public Boolean call() {
        boolean hasSetValues = false;
        for (String fieldName : enabledProperties) {
          try {
            switch (fieldName) {
              case JRE_VERSION:
                provider.addHeartBeatProperty(fieldName, getJreVersion(), true);
                hasSetValues = true;
                break;
              case SDK_VERSION:
                provider.addHeartBeatProperty(fieldName, getSdkVersion(), true);
                hasSetValues = true;
                break;
              case OS_VERSION:
              case OS_TYPE:
                provider.addHeartBeatProperty(fieldName, getOsVersion(), true);
                hasSetValues = true;
                break;
              case PROCESS_SESSION_ID:
                provider.addHeartBeatProperty(fieldName, getProcessSessionId(), true);
                hasSetValues = true;
                break;
              default:
                // We won't accept unknown properties in default providers.
                logger.trace("Encountered unknown default property");
                break;
            }
          } catch (RuntimeException e) {
            if (logger.isWarnEnabled()) {
              logger.warn("Failed to obtain heartbeat property", e);
            }
          }
        }
        return hasSetValues;
      }
    };
  }

  /**
   * This method initializes the collection with Default Properties of this provider.
   *
   * @param defaultFields collection to hold default properties.
   */
  private static void initializeDefaultFields(Set<String> defaultFields) {
    defaultFields.add(JRE_VERSION);
    defaultFields.add(SDK_VERSION);
    defaultFields.add(OS_VERSION);
    defaultFields.add(PROCESS_SESSION_ID);
    defaultFields.add(OS_TYPE);
  }

  /** Returns the JDK version being used by the application. */
  private static String getJreVersion() {
    return System.getProperty("java.version");
  }

  /** Returns the Application Insights SDK version user is using to instrument his application. */
  private static String getSdkVersion() {
    return VersionGenerator.getSdkVersion();
  }

  /** Returns the OS version on which application is running. */
  private static String getOsVersion() {
    return System.getProperty("os.name");
  }

  /** Returns the Unique GUID for the application's current session. */
  private static String getProcessSessionId() {
    if (uniqueProcessId == null) {
      uniqueProcessId = UUID.randomUUID();
    }
    return uniqueProcessId.toString();
  }
}
