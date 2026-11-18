package com.gkcontas.gateway.web;

/**
 * Names shared by the web filters, the routes and the BFF client: the headers
 * the gateway owns, the exchange attributes, and the Reactor context keys.
 */
public final class RequestContext {

    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    public static final String USER_ID_HEADER = "X-User-Id";
    public static final String USER_ROLES_HEADER = "X-User-Roles";

    public static final String REQUEST_ID_ATTR = RequestContext.class.getName() + ".requestId";
    public static final String USER_ATTR = RequestContext.class.getName() + ".user";

    /** Reactor context keys. MDC and ThreadLocal do not survive a thread hop. */
    public static final String REQUEST_ID_KEY = "gateway.requestId";
    public static final String USER_KEY = "gateway.user";

    /** Filter order: access log outermost, then correlation, path guard, authentication. */
    public static final int ORDER_ACCESS_LOG = -300;
    public static final int ORDER_CORRELATION = -200;
    public static final int ORDER_PATH_GUARD = -150;
    public static final int ORDER_AUTHENTICATION = -100;

    private RequestContext() {
    }
}
