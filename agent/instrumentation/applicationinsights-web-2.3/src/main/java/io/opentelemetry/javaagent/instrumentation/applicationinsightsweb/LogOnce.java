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

package io.opentelemetry.javaagent.instrumentation.applicationinsightsweb;

import static java.util.logging.Level.FINE;
import static java.util.logging.Level.WARNING;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

public class LogOnce {

  private static final Logger logger =
      Logger.getLogger("com.microsoft.applicationinsights.interop.web");

  private static final Set<String> loggedMessages =
      Collections.newSetFromMap(new ConcurrentHashMap<>());

  public static void logOnce(String message) {
    if (loggedMessages.add(message)) {
      logger.log(WARNING, "{0} (this message will only be logged once)", message);
      if (logger.isLoggable(FINE)) {
        logger.log(
            FINE,
            "location stack trace for the warning above",
            new Exception("location stack trace"));
      }
    }
  }

  private LogOnce() {}
}
