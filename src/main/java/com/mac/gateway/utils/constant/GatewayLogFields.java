package com.mac.gateway.utils.constant;

public final class GatewayLogFields {

    public static final String TRACE_ID = "trace.id";
    public static final String EVENT_ACTION = "event.action";
    public static final String EVENT_OUTCOME = "event.outcome";
    public static final String EVENT_DATASET = "event.dataset";
    public static final String EVENT_DURATION = "event.duration";
    public static final String HTTP_METHOD = "http.request.method";
    public static final String HTTP_STATUS = "http.response.status_code";
    public static final String HTTP_REQUEST_BYTES = "http.request.body.bytes";
    public static final String HTTP_RESPONSE_BYTES = "http.response.body.bytes";
    public static final String ROUTE_ID = "gateway.route.id";
    public static final String UPSTREAM = "gateway.upstream.host";

    private GatewayLogFields() {}
}
