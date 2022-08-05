package com.microsoft.applicationinsights.internal.quickpulse;

import com.microsoft.applicationinsights.TelemetryConfiguration;
import com.microsoft.applicationinsights.TelemetryConfigurationTestHelper;
import com.microsoft.applicationinsights.internal.channel.common.ApacheSender;
import org.apache.http.HttpResponse;
import org.apache.http.ProtocolVersion;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.message.BasicHttpResponse;
import org.apache.http.message.BasicStatusLine;
import org.junit.*;
import org.mockito.Mockito;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;

import static org.hamcrest.Matchers.endsWith;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class DefaultQuickPulsePingSenderTests {
    @Before
    public void cleanUpActive() {
        TelemetryConfigurationTestHelper.resetActiveTelemetryConfiguration();
    }

    @After
    public void cleanUpActiveAgain() {
        TelemetryConfigurationTestHelper.resetActiveTelemetryConfiguration();
    }

    @Test
    public void endpointIsFormattedCorrectlyWhenUsingConnectionString() {
        final TelemetryConfiguration config = new TelemetryConfiguration();
        String quickPulseEndpoint = null;
        config.setConnectionString("InstrumentationKey=testing-123");
        String endpointUrl = null;
        DefaultQuickPulsePingSender defaultQuickPulsePingSender = new DefaultQuickPulsePingSender(null, config, null,null, null,null);
        try {
            quickPulseEndpoint = QuickPulseNetworkHelper.getQuickPulseEndpoint(config);
            endpointUrl = defaultQuickPulsePingSender.getQuickPulsePingUri(quickPulseEndpoint);
            URI uri = new URI(endpointUrl);
            assertNotNull(uri);
            assertThat(endpointUrl, endsWith("/ping?ikey=testing-123"));
            assertEquals("https://rt.services.visualstudio.com/QuickPulseService.svc/ping?ikey=testing-123", endpointUrl);

        } catch (URISyntaxException e) {
            fail("Not a valid uri: "+endpointUrl);
        }
    }

    @Test
    public void endpointIsFormattedCorrectlyWhenUsingInstrumentationKey() {
        final TelemetryConfiguration config = new TelemetryConfiguration();
        config.setInstrumentationKey("A-test-instrumentation-key");
        String endpointUrl = null;
        DefaultQuickPulsePingSender defaultQuickPulsePingSender = new DefaultQuickPulsePingSender(null, config, null, null,null,null);
        try {
            final String quickPulseEndpoint = QuickPulseNetworkHelper.getQuickPulseEndpoint(null);
            endpointUrl = defaultQuickPulsePingSender.getQuickPulsePingUri(quickPulseEndpoint);

            URI uri = new URI(endpointUrl);
            assertNotNull(uri);
            assertThat(endpointUrl, endsWith("/ping?ikey=A-test-instrumentation-key")); // from resources/ApplicationInsights.xml
            assertEquals("https://rt.services.visualstudio.com/QuickPulseService.svc/ping?ikey=A-test-instrumentation-key", endpointUrl);

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
