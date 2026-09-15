package com.kksg.applicationServices.scm.common.http;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.time.Duration;

/**
 * Dedicated {@link RestTemplate} for provider API traffic.
 *
 * <p>A separate bean rather than a shared one, for two reasons that matter to this module:
 * <ul>
 *   <li><b>Errors must not throw.</b> The default handler raises on any non-2xx, which discards the
 *       response body. Provider error bodies carry the diagnostic detail worth logging (rate-limit
 *       headers, validation messages), and the engine needs the status code to classify the failure -
 *       404 means "not found", 401 means "reauthorize", 429 means "back off". The no-op handler below
 *       hands every response back so the engine can decide.</li>
 *   <li><b>Timeouts must be bounded.</b> An unbounded read against a slow provider would pin a
 *       request thread indefinitely.</li>
 * </ul>
 *
 * <p>Named {@code scmRestTemplate} so it cannot be confused with, or accidentally displace, any
 * {@code RestTemplate} used elsewhere in the application.
 */
@Configuration
public class ScmHttpClientConfig {

    @Bean
    public RestTemplate scmRestTemplate(ScmHttpProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(properties.getConnectTimeoutMs()));
        requestFactory.setReadTimeout(Duration.ofMillis(properties.getReadTimeoutMs()));

        RestTemplate restTemplate = new RestTemplate(requestFactory);
        restTemplate.setErrorHandler(new NonThrowingErrorHandler());
        return restTemplate;
    }

    /**
     * Treats every response as "handled" so the caller inspects the status itself.
     */
    private static final class NonThrowingErrorHandler implements ResponseErrorHandler {

        @Override
        public boolean hasError(ClientHttpResponse response) throws IOException {
            return false;
        }

        @Override
        public void handleError(ClientHttpResponse response) {
            // Unreachable: hasError always reports false.
        }
    }
}
