package com.kksg.applicationServices.scm.connection.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * The provider consent URL a client should navigate to in order to start a connection.
 *
 * <p>Returned as JSON rather than issued as an HTTP redirect so that a single-page application can
 * initiate the flow from an authenticated XHR - a 302 to a third-party origin cannot be followed by
 * {@code fetch} in a way that lets the browser own the navigation.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScmAuthorizationUrlResponse {

    private String authorizationUrl;

    private String providerCode;

    /**
     * The signed state embedded in {@code authorizationUrl}.
     *
     * <p>Returned so a client may retain it to correlate its own callback handling. It is not a secret
     * belonging to the client - it is already visible in the authorization URL - and it is only
     * meaningful to this application, which verifies its signature.
     */
    private String state;
}
