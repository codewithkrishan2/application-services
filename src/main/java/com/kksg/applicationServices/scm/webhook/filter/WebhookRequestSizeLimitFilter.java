package com.kksg.applicationServices.scm.webhook.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kksg.applicationServices.common.response.ApiResponse;
import com.kksg.applicationServices.scm.webhook.ScmWebhookProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Bounds the size of an inbound webhook body before anything reads it.
 *
 * <p>The webhook endpoint is the one place where an anonymous caller can hand this service a body of
 * arbitrary size, and that body is not merely read: it is buffered whole into a {@code byte[]}, HMAC'd,
 * parsed by Jackson and then persisted into a {@code jsonb} column. Without a limit, a single request can
 * drive memory and storage, and no amount of signature verification helps because the allocation happens
 * before authenticity is known.
 *
 * <p>Nothing in the surrounding stack covers this. {@code spring.servlet.multipart.max-request-size}
 * applies only to multipart requests, and Tomcat's {@code maxPostSize} only to form-encoded ones; a
 * {@code application/json} body is unbounded by default.
 *
 * <p>Two paths, because a provider may or may not declare a length:
 * <ul>
 *   <li>{@code Content-Length} present and over the cap: refused immediately, without reading the body.</li>
 *   <li>Length unknown (chunked): the body is read through a bounded buffer that stops one byte past the
 *       cap, so the worst case is still bounded, and the buffered bytes are replayed to the controller so
 *       the HMAC is computed over exactly what was sent.</li>
 * </ul>
 */
@Component
public class WebhookRequestSizeLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(WebhookRequestSizeLimitFilter.class);

    private static final String WEBHOOK_PATH_PREFIX = "/api/v1/scm/webhooks";

    private final ScmWebhookProperties properties;
    private final ObjectMapper objectMapper;

    public WebhookRequestSizeLimitFilter(ScmWebhookProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /** The cost of buffering is only justified on the endpoint that is both public and unbounded. */
    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        String path = request.getServletPath();
        return path == null || !path.startsWith(WEBHOOK_PATH_PREFIX);
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        int maxBytes = properties.getMaxRequestBytes();
        long declaredLength = request.getContentLengthLong();

        if (declaredLength > maxBytes) {
            reject(request, response, declaredLength, maxBytes);
            return;
        }

        if (declaredLength >= 0) {
            // Length is known and within the cap; the container will not hand us more than it declared.
            filterChain.doFilter(request, response);
            return;
        }

        byte[] body = readAtMost(request.getInputStream(), maxBytes + 1);
        if (body.length > maxBytes) {
            reject(request, response, -1, maxBytes);
            return;
        }

        filterChain.doFilter(new BufferedBodyRequest(request, body), response);
    }

    private void reject(HttpServletRequest request,
                        HttpServletResponse response,
                        long declaredLength,
                        int maxBytes) throws IOException {
        log.warn("SCM_WEBHOOK_BODY_TOO_LARGE: path={}, declaredLength={}, maxBytes={}",
                request.getRequestURI(), declaredLength, maxBytes);

        response.setStatus(HttpStatus.PAYLOAD_TOO_LARGE.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getOutputStream(),
                ApiResponse.error("Webhook payload exceeds the maximum accepted size."));
    }

    /** Reads at most {@code limit} bytes, so a hostile stream cannot drive allocation past that. */
    private static byte[] readAtMost(InputStream source, int limit) throws IOException {
        byte[] buffer = new byte[limit];
        int total = 0;

        while (total < limit) {
            int read = source.read(buffer, total, limit - total);
            if (read == -1) {
                break;
            }
            total += read;
        }

        if (total == limit) {
            return buffer;
        }
        byte[] exact = new byte[total];
        System.arraycopy(buffer, 0, exact, 0, total);
        return exact;
    }

    /**
     * Replays an already-consumed body.
     *
     * <p>Required because the filter has to read the stream to measure it, and a servlet input stream can
     * only be read once. The bytes handed downstream are byte-for-byte what arrived, which is what keeps
     * the HMAC verification valid.
     */
    private static final class BufferedBodyRequest extends HttpServletRequestWrapper {

        private final byte[] body;

        private BufferedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream source = new ByteArrayInputStream(body);

            return new ServletInputStream() {
                @Override
                public boolean isFinished() {
                    return source.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener readListener) {
                    throw new UnsupportedOperationException("Asynchronous reads are not supported here");
                }

                @Override
                public int read() {
                    return source.read();
                }

                @Override
                public int read(@NonNull byte[] target, int offset, int length) {
                    return source.read(target, offset, length);
                }

                @Override
                public int available() {
                    return source.available();
                }
            };
        }
    }
}
