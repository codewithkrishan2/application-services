package com.kksg.applicationServices.scm.common.exception;

import java.io.Serial;

/**
 * Single exception type for the SCM integration layer, carrying a {@link ScmErrorCode}.
 *
 * <p><b>Why one class instead of an exception per error code?</b> The failure modes here differ in
 * <i>data</i>, not in behaviour: every caller either propagates the failure or inspects the code.
 * A dozen near-empty subclasses would add files without adding a single decision point, and would
 * force a dozen handlers in {@code GlobalExceptionHandler}. The enum keeps the taxonomy explicit
 * and exhaustive while one handler maps it to HTTP.
 *
 * <p>The optional {@code detail} is appended to the client-visible message and must therefore
 * never contain secret material - see {@link ScmErrorCode} for the rationale. Diagnostic context
 * that is too sensitive or too verbose for a response belongs in a log statement at the throw
 * site instead.
 */
public class ScmException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final ScmErrorCode errorCode;

    /** Kept so a derived copy can rebuild the message without appending the detail twice. */
    private final String detail;

    /**
     * Seconds to wait before retrying, when the provider said so.
     *
     * <p>Only ever set for a rate limit, and only when the provider sent {@code Retry-After}. It lives
     * on the exception because this is the one moment the value exists: the response header is gone by
     * the time any other layer could ask for it.
     *
     * <p>Carried so a rate limit is <i>actionable</i> rather than merely reported. Without it the only
     * honest thing a client can say is "try again sometime"; with it the UI can say how long, and no
     * layer in between has to invent a retry loop of its own.
     */
    private final Integer retryAfterSeconds;

    public ScmException(ScmErrorCode errorCode) {
        this(errorCode, null, null, null);
    }

    public ScmException(ScmErrorCode errorCode, String detail) {
        this(errorCode, detail, null, null);
    }

    public ScmException(ScmErrorCode errorCode, String detail, Throwable cause) {
        this(errorCode, detail, cause, null);
    }

    public ScmException(ScmErrorCode errorCode, String detail, Throwable cause, Integer retryAfterSeconds) {
        super(buildMessage(errorCode, detail), cause);
        this.errorCode = errorCode;
        this.detail = detail;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    /**
     * @return a copy carrying the provider's retry hint, or {@code this} when there is nothing to add -
     *         so a caller can apply it unconditionally without first checking whether the provider
     *         sent a header.
     */
    public ScmException withRetryAfterSeconds(Integer seconds) {
        if (seconds == null || seconds <= 0) {
            return this;
        }
        return new ScmException(errorCode, detail, getCause(), seconds);
    }

    public ScmErrorCode getErrorCode() {
        return errorCode;
    }

    /** @return the provider's retry hint in seconds, or {@code null} when it gave none. */
    public Integer getRetryAfterSeconds() {
        return retryAfterSeconds;
    }

    private static String buildMessage(ScmErrorCode errorCode, String detail) {
        if (detail == null || detail.isBlank()) {
            return errorCode.getDefaultMessage();
        }
        return "%s: %s".formatted(errorCode.getDefaultMessage(), detail);
    }
}
