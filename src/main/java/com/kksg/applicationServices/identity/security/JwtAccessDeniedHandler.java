package com.kksg.applicationServices.identity.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kksg.applicationServices.common.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Answers authenticated-but-forbidden requests.
 *
 * <p>Without this, Spring Security's default handler returns a 403 with an empty body or the container's
 * HTML error page, so a client that parses the {@code ApiResponse} envelope everywhere else gets something
 * it cannot read - and in the HTML case, a response that leaks the servlet container and its version.
 *
 * <p>The denial is logged with the subject, because a 403 against a token that authenticated successfully
 * is a meaningful signal (probing, or a genuine privilege bug) in a way that an anonymous 401 is not.
 */
@Component
public class JwtAccessDeniedHandler implements AccessDeniedHandler {

    private static final Logger log = LoggerFactory.getLogger(JwtAccessDeniedHandler.class);

    private final ObjectMapper objectMapper;

    public JwtAccessDeniedHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void handle(HttpServletRequest request,
                       HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        log.warn("ACCESS_DENIED: method={}, path={}, authenticated={}",
                request.getMethod(), request.getRequestURI(), authentication != null);

        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");

        // No detail about what was required: that would describe the authorization model to a caller who
        // has already been told no.
        objectMapper.writeValue(response.getOutputStream(),
                ApiResponse.error("You do not have permission to perform this action."));
    }
}
