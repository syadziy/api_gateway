package com.mac.gateway.utils.constant;

import java.util.Set;

public final class GatewayHeaders {

    public static final String AUTHENTICATED_USER = "X-Authenticated-User";
    public static final String AUTHENTICATED_AUTHORITIES = "X-Authenticated-Authorities";
    public static final Set<String> UNTRUSTED_INBOUND = Set.of(
            "Forwarded",
            "X-Forwarded-For",
            "X-Forwarded-Host",
            "X-Forwarded-Proto",
            "X-Forwarded-Port",
            AUTHENTICATED_USER,
            AUTHENTICATED_AUTHORITIES);

    private GatewayHeaders() {}
}
