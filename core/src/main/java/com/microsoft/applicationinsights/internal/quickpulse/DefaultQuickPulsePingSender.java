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

package com.microsoft.applicationinsights.internal.quickpulse;

import java.io.IOException;
import java.net.URISyntaxException;
import java.util.Date;
import java.util.concurrent.atomic.AtomicBoolean;
import com.google.common.annotations.VisibleForTesting;
import com.microsoft.applicationinsights.customExceptions.FriendlyException;
import com.microsoft.applicationinsights.internal.util.LocalStringsUtils;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ByteArrayEntity;
import com.microsoft.applicationinsights.TelemetryConfiguration;
import com.microsoft.applicationinsights.internal.channel.common.ApacheSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Created by gupele on 12/12/2016.
 */
final class DefaultQuickPulsePingSender implements QuickPulsePingSender {

    private static final Logger logger = LoggerFactory.getLogger(DefaultQuickPulsePingSender.class);

    private final TelemetryConfiguration configuration;
    private final ApacheSender apacheSender;
    private final QuickPulseNetworkHelper networkHelper = new QuickPulseNetworkHelper();
    private String pingPrefix;
    private String roleName;
    private String instanceName;
    private String machineName;
    private String quickPulseId;
    private long lastValidTransmission = 0;
    private static volatile AtomicBoolean friendlyExceptionThrown = new AtomicBoolean();

    public DefaultQuickPulsePingSender(ApacheSender sender, TelemetryConfiguration configuration, String machineName, String instanceName, String roleName, String quickPulseId) {
        this.configuration = configuration;
        this.apacheSender = sender;
        this.roleName = roleName;
        this.instanceName = instanceName;
        this.machineName = machineName;
        this.quickPulseId = quickPulseId;

        if (!LocalStringsUtils.isNullOrEmpty(roleName)) {
            roleName = "\"" + roleName + "\"";
        }

        pingPrefix = "{" +
                "\"Documents\": null," +
                "\"Instance\":\"" + instanceName + "\"," +
                "\"InstrumentationKey\": null," +
                "\"InvariantVersion\": " + QuickPulse.QP_INVARIANT_VERSION + "," +
                "\"MachineName\":\"" + machineName + "\"," +
                "\"RoleName\":" + roleName + "," +
                "\"Metrics\": null," +
                "\"StreamId\": \"" + quickPulseId + "\"," +
                "\"Timestamp\": \"\\/Date(";

        if (logger.isTraceEnabled()) {
            try {
                logger.trace("{} using endpoint {}", DefaultQuickPulsePingSender.class.getSimpleName(), QuickPulseNetworkHelper.getQuickPulseEndpoint(configuration));
            } catch (URISyntaxException use) {
                logger.error("{} using invalid endpoint: {}", DefaultQuickPulsePingSender.class.getSimpleName(), use.getMessage());
            }
        }
    }

    /**
     * @deprecated Use {@link #DefaultQuickPulsePingSender(ApacheSender, TelemetryConfiguration, String, String, String, String)}
     */
    @Deprecated
    public DefaultQuickPulsePingSender(final ApacheSender apacheSender, final String machineName, final String instanceName, final String roleName, final String quickPulseId) {
        this(apacheSender, null, machineName, instanceName, roleName, quickPulseId);
    }

    @Override
    public QuickPulseHeaderInfo ping(String redirectedEndpoint) {

        HttpResponse response = null;
        final long sendTime = System.nanoTime();
        final Date currentDate = new Date();

        try {
            final String endpointPrefix = LocalStringsUtils.isNullOrEmpty(redirectedEndpoint) ? QuickPulseNetworkHelper.getQuickPulseEndpoint(configuration) : redirectedEndpoint;
            final HttpPost request = networkHelper.buildPingRequest(currentDate, getQuickPulsePingUri(endpointPrefix), quickPulseId, machineName, roleName, instanceName);
            final ByteArrayEntity pingEntity = buildPingEntity(currentDate.getTime());
            request.setEntity(pingEntity);


            response = apacheSender.sendRequest(request);
            if (networkHelper.isSuccess(response)) {
                final QuickPulseHeaderInfo quickPulseHeaderInfo = networkHelper.getQuickPulseHeaderInfo(response);
                switch (quickPulseHeaderInfo.getQuickPulseStatus()) {
                    case QP_IS_OFF:
                    case QP_IS_ON:
                        lastValidTransmission = sendTime;
                        return quickPulseHeaderInfo;

                    default:
                        break;
                }
            }
        } catch (FriendlyException e) {
            if(!friendlyExceptionThrown.getAndSet(true)) {
                logger.error(e.getMessage());
            }
        } catch (URISyntaxException use) {
            logger.error(use.getMessage());
        } catch (IOException e) {
            // chomp
        } finally {
            if (response != null) {
                apacheSender.dispose(response);
            }
        }
        return onPingError(sendTime);
    }

    @VisibleForTesting
    String getQuickPulsePingUri(String endpointPrefix) {
        return endpointPrefix + "/QuickPulseService.svc/ping?ikey=" + getInstrumentationKey();
    }

    private String getInstrumentationKey() {
        TelemetryConfiguration config = this.configuration == null ? TelemetryConfiguration.getActive() : configuration;
        return config.getInstrumentationKey();
    }

    private ByteArrayEntity buildPingEntity(long timeInMillis) {
        String sb = pingPrefix + timeInMillis +
                ")\\/\"," +
                "\"Version\":\"2.2.0-738\"" +
                "}";
        return new ByteArrayEntity(sb.getBytes());
    }

    private QuickPulseHeaderInfo onPingError(long sendTime) {
        final double timeFromLastValidTransmission = (sendTime - lastValidTransmission) / 1000000000.0;
        if (timeFromLastValidTransmission >= 60.0) {
            return new QuickPulseHeaderInfo(QuickPulseStatus.ERROR);
        }

        return new QuickPulseHeaderInfo(QuickPulseStatus.QP_IS_OFF);
    }
}
