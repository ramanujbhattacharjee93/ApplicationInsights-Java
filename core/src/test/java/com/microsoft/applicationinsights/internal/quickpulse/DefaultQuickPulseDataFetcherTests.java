package com.microsoft.applicationinsights.internal.quickpulse;

import com.microsoft.applicationinsights.TelemetryConfiguration;
import com.microsoft.applicationinsights.internal.channel.common.ApacheSender;
import org.apache.http.HttpResponse;
import org.apache.http.ProtocolVersion;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.message.BasicHttpResponse;
import org.apache.http.message.BasicStatusLine;
import org.junit.*;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mockito;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import static org.junit.Assert.*;
import static org.mockito.Matchers.notNull;
import static org.mockito.Mockito.*;

@RunWith(JUnit4.class)
public class DefaultQuickPulseDataFetcherTests {

    @Test
    public void testGetCurrentSdkVersion() {
        DefaultQuickPulseDataFetcher dataFetcher = new DefaultQuickPulseDataFetcher(null, (TelemetryConfiguration) null, null,
                null, null,null);
        String sdkVersion = dataFetcher.getCurrentSdkVersion();
        assertNotNull(sdkVersion);
        Assert.assertNotEquals("java:unknown", sdkVersion);
    }

    @Test
    public void endpointIsFormattedCorrectlyWhenUsingConfig() {
        final TelemetryConfiguration config = new TelemetryConfiguration();
        config.setConnectionString("InstrumentationKey=testing-123");
        DefaultQuickPulseDataFetcher defaultQuickPulseDataFetcher = new DefaultQuickPulseDataFetcher(null, config, null, null,null,null);
        String endpointUrl = null;
        try {
            final String quickPulseEndpoint = QuickPulseNetworkHelper.getQuickPulseEndpoint(null);
            endpointUrl = defaultQuickPulseDataFetcher.getEndpointUrl(quickPulseEndpoint);
            URI uri = new URI(endpointUrl);
            assertNotNull(uri);
            assertEquals("https://rt.services.visualstudio.com/QuickPulseService.svc/post?ikey=testing-123", endpointUrl);
        } catch (URISyntaxException e) {
            fail("Not a valid uri: "+endpointUrl);
        }
    }

    @Test
    public void endpointIsFormattedCorrectlyWhenConfigIsNull() {
        DefaultQuickPulseDataFetcher defaultQuickPulseDataFetcher = new DefaultQuickPulseDataFetcher(null, (TelemetryConfiguration)null,
                null,null, null,null);
        String endpointUrl = null;
        try {
            final String quickPulseEndpoint = QuickPulseNetworkHelper.getQuickPulseEndpoint(null);
            endpointUrl = defaultQuickPulseDataFetcher.getEndpointUrl(quickPulseEndpoint);

            URI uri = new URI(endpointUrl);
            assertNotNull(uri);
            assertEquals("https://rt.services.visualstudio.com/QuickPulseService.svc/post?ikey=null", endpointUrl);
        } catch (URISyntaxException e) {
            fail("Not a valid uri: "+endpointUrl);
        }
    }

    @Test
    public void endpointChangesWithRedirectHeaderAndGetNewPingInterval() throws IOException {
        final ApacheSender apacheSender = mock(ApacheSender.class);
        final QuickPulsePingSender quickPulsePingSender = new DefaultQuickPulsePingSender(apacheSender, null, "machine1",
                "instance1", "role1", "qpid123");

        HttpResponse response = new BasicHttpResponse(new BasicStatusLine(new ProtocolVersion("a",1,2), 200, "OK"));
        response.addHeader("x-ms-qps-service-polling-interval-hint", "1000");
        response.addHeader("x-ms-qps-service-endpoint-redirect", "https://new.endpoint.com");
        response.addHeader("x-ms-qps-subscribed", "true");

        Mockito.doReturn(response).when(apacheSender).sendRequest((HttpPost) notNull());
        QuickPulseHeaderInfo quickPulseHeaderInfo = quickPulsePingSender.ping(null);

        Assert.assertEquals(quickPulseHeaderInfo.getQuickPulseStatus(), QuickPulseStatus.QP_IS_ON);
        Assert.assertEquals(quickPulseHeaderInfo.getQpsServicePollingInterval(), 1000);
        Assert.assertEquals(quickPulseHeaderInfo.getQpsServiceEndpointRedirect(), "https://new.endpoint.com");
    }
}
