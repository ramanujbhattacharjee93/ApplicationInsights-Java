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

package com.microsoft.applicationinsights.agent.internal.statsbeat;

import com.azure.core.http.HttpPipelineCallContext;
import com.azure.core.http.HttpPipelineNextPolicy;
import com.azure.core.http.HttpResponse;
import com.azure.core.http.policy.HttpPipelinePolicy;
import com.azure.monitor.opentelemetry.exporter.implementation.utils.StatusCode;
import com.microsoft.applicationinsights.agent.internal.utils.Constant;
import java.util.concurrent.atomic.AtomicLong;
import reactor.core.publisher.Mono;

public class NetworkStatsbeatHttpPipelinePolicy implements HttpPipelinePolicy {

  private static final String INSTRUMENTATION_KEY_DATA = "instrumentationKey";

  private final NetworkStatsbeat networkStatsbeat;

  public NetworkStatsbeatHttpPipelinePolicy(NetworkStatsbeat networkStatsbeat) {
    this.networkStatsbeat = networkStatsbeat;
  }

  @SuppressWarnings("ArgumentSelectionDefectChecker")
  @Override
  public Mono<HttpResponse> process(HttpPipelineCallContext context, HttpPipelineNextPolicy next) {
    // using AtomicLong for both mutable holder and volatile (but atomicity is not needed here)
    AtomicLong startTime = new AtomicLong();
    String host = context.getHttpRequest().getUrl().getHost();
    String instrumentationKey =
        context.getData(INSTRUMENTATION_KEY_DATA).orElse("unknown").toString();
    return next.process()
        .doOnSubscribe(subscription -> startTime.set(System.currentTimeMillis()))
        .doOnSuccess(
            response -> {
              int statusCode = response.getStatusCode();
              if (statusCode == 200) {
                networkStatsbeat.incrementRequestSuccessCount(
                    System.currentTimeMillis() - startTime.get(), instrumentationKey, host);
              } else if (StatusCode.isRedirect(statusCode)) {
                // these are not tracked as success or failure since they are just redirects
              } else if (statusCode == 402 || statusCode == 439) {
                networkStatsbeat.incrementThrottlingCount(
                    instrumentationKey, host, Constant.STATUS_CODE, statusCode);
              } else if (StatusCode.isRetryable(statusCode)) {
                networkStatsbeat.incrementRetryCount(
                    instrumentationKey, host, Constant.STATUS_CODE, statusCode);
              } else {
                // 400 and 404 will be tracked as failure count
                networkStatsbeat.incrementRequestFailureCount(
                    instrumentationKey, host, Constant.STATUS_CODE, statusCode);
              }
            })
        .doOnError(
            throwable -> {
              networkStatsbeat.incrementExceptionCount(
                  instrumentationKey,
                  host,
                  Constant.EXCEPTION_TYPE,
                  throwable.getClass().getName());
            });
  }
}
