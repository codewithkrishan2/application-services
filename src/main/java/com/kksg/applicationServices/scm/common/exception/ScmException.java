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

    public ScmException(ScmErrorCode errorCode) {
        super(errorCode.getDefaultMessage());
        this.errorCode = errorCode;
    }

    public ScmException(ScmErrorCode errorCode, String detail) {
        super(buildMessage(errorCode, detail));
        this.errorCode = errorCode;
    }

    public ScmException(ScmErrorCode errorCode, String detail, Throwable cause) {
        super(buildMessage(errorCode, detail), cause);
        this.errorCode = errorCode;
    }

    public ScmErrorCode getErrorCode() {
        return errorCode;
    }

    private static String buildMessage(ScmErrorCode errorCode, String detail) {
        if (detail == null || detail.isBlank()) {
            return errorCode.getDefaultMessage();
        }
        return "%s: %s".formatted(errorCode.getDefaultMessage(), detail);
    }
}
