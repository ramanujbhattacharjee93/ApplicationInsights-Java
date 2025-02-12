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

package com.azure.monitor.opentelemetry.exporter.implementation.quickpulse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.notNull;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class QuickPulseCoordinatorTest {

  @Test
  void testOnlyPings() throws InterruptedException {
    QuickPulseDataFetcher mockFetcher = mock(QuickPulseDataFetcher.class);
    QuickPulseDataSender mockSender = mock(QuickPulseDataSender.class);
    QuickPulsePingSender mockPingSender = mock(QuickPulsePingSender.class);
    QuickPulseDataCollector collector = new QuickPulseDataCollector(true);
    Mockito.doReturn(new QuickPulseHeaderInfo(QuickPulseStatus.QP_IS_OFF))
        .when(mockPingSender)
        .ping(null);

    QuickPulseCoordinatorInitData initData =
        new QuickPulseCoordinatorInitDataBuilder()
            .withDataFetcher(mockFetcher)
            .withDataSender(mockSender)
            .withPingSender(mockPingSender)
            .withCollector(collector)
            .withWaitBetweenPingsInMillis(10L)
            .withWaitBetweenPostsInMillis(10L)
            .withWaitOnErrorInMillis(10L)
            .build();

    QuickPulseCoordinator coordinator = new QuickPulseCoordinator(initData);
    Thread thread = new Thread(coordinator);
    thread.setDaemon(true);
    thread.start();

    Thread.sleep(1000);
    coordinator.stop();

    thread.join();

    Mockito.verify(mockFetcher, Mockito.never()).prepareQuickPulseDataForSend(null);

    Mockito.verify(mockSender, Mockito.never()).startSending();
    Mockito.verify(mockSender, Mockito.never()).getQuickPulseHeaderInfo();

    Mockito.verify(mockPingSender, Mockito.atLeast(1)).ping(null);
    // make sure QP_IS_OFF after ping
    assertThat(collector.getQuickPulseStatus()).isEqualTo(QuickPulseStatus.QP_IS_OFF);
  }

  @Test
  void testOnePingAndThenOnePost() throws InterruptedException {
    QuickPulseDataFetcher mockFetcher = mock(QuickPulseDataFetcher.class);
    QuickPulseDataSender mockSender = mock(QuickPulseDataSender.class);
    Mockito.doReturn(new QuickPulseHeaderInfo(QuickPulseStatus.QP_IS_OFF))
        .when(mockSender)
        .getQuickPulseHeaderInfo();

    QuickPulsePingSender mockPingSender = mock(QuickPulsePingSender.class);
    Mockito.when(mockPingSender.ping(null))
        .thenReturn(
            new QuickPulseHeaderInfo(QuickPulseStatus.QP_IS_ON),
            new QuickPulseHeaderInfo(QuickPulseStatus.QP_IS_OFF));

    QuickPulseDataCollector collector = new QuickPulseDataCollector(true);
    QuickPulseCoordinatorInitData initData =
        new QuickPulseCoordinatorInitDataBuilder()
            .withDataFetcher(mockFetcher)
            .withDataSender(mockSender)
            .withPingSender(mockPingSender)
            .withCollector(collector)
            .withWaitBetweenPingsInMillis(10L)
            .withWaitBetweenPostsInMillis(10L)
            .withWaitOnErrorInMillis(10L)
            .build();

    QuickPulseCoordinator coordinator = new QuickPulseCoordinator(initData);
    Thread thread = new Thread(coordinator);
    thread.setDaemon(true);
    thread.start();

    Thread.sleep(1000);
    coordinator.stop();

    thread.join();

    Mockito.verify(mockFetcher, Mockito.atLeast(1)).prepareQuickPulseDataForSend(null);

    Mockito.verify(mockSender, Mockito.times(1)).startSending();
    Mockito.verify(mockSender, Mockito.times(1)).getQuickPulseHeaderInfo();

    Mockito.verify(mockPingSender, Mockito.atLeast(1)).ping(null);
    // Make sure QP_IS_OFF after one post and ping
    assertThat(collector.getQuickPulseStatus()).isEqualTo(QuickPulseStatus.QP_IS_OFF);
  }

  // FIXME (trask) sporadically failing on CI
  @Disabled
  @Test
  void testOnePingAndThenOnePostWithRedirectedLink() throws InterruptedException {
    QuickPulseDataFetcher mockFetcher = Mockito.mock(QuickPulseDataFetcher.class);
    QuickPulseDataSender mockSender = Mockito.mock(QuickPulseDataSender.class);
    QuickPulsePingSender mockPingSender = Mockito.mock(QuickPulsePingSender.class);

    Mockito.doNothing().when(mockFetcher).prepareQuickPulseDataForSend(notNull());
    Mockito.doReturn(
            new QuickPulseHeaderInfo(QuickPulseStatus.QP_IS_ON, "https://new.endpoint.com", 100))
        .when(mockPingSender)
        .ping(any());
    Mockito.doReturn(
            new QuickPulseHeaderInfo(QuickPulseStatus.QP_IS_OFF, "https://new.endpoint.com", 400))
        .when(mockSender)
        .getQuickPulseHeaderInfo();

    QuickPulseCoordinatorInitData initData =
        new QuickPulseCoordinatorInitDataBuilder()
            .withDataFetcher(mockFetcher)
            .withDataSender(mockSender)
            .withPingSender(mockPingSender)
            .withCollector(new QuickPulseDataCollector(true))
            .withWaitBetweenPingsInMillis(10L)
            .withWaitBetweenPostsInMillis(10L)
            .withWaitOnErrorInMillis(10L)
            .build();

    QuickPulseCoordinator coordinator = new QuickPulseCoordinator(initData);
    Thread thread = new Thread(coordinator);
    thread.setDaemon(true);
    thread.start();

    Thread.sleep(1100);
    coordinator.stop();

    thread.join();

    Mockito.verify(mockFetcher, Mockito.atLeast(1))
        .prepareQuickPulseDataForSend("https://new.endpoint.com");
    Mockito.verify(mockPingSender, Mockito.atLeast(1)).ping(null);
    Mockito.verify(mockPingSender, Mockito.times(2)).ping("https://new.endpoint.com");
  }
}
