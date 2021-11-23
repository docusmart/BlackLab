package nl.inl.blacklab.instrumentation.impl;

import nl.inl.blacklab.instrumentation.RequestInstrumentationProvider;

import javax.servlet.http.HttpServletRequest;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public class SimpleRequestInstrumentationProvider implements RequestInstrumentationProvider {
    private static final String REQUEST_ID = "X-Request-ID";
    private static final String QUERY_ID = "X-Query-ID";
    private static final String DOC_COUNT = "X-Doc-Count";

    @Override
    public Optional<String> getRequestID(HttpServletRequest request) {
        return Optional.of(buildRequestId(request));
    }

    private String buildRequestId(HttpServletRequest request) {
        String reqId = getQueryId(request).orElse(
            Base64.getUrlEncoder().encodeToString(UUID.randomUUID().toString().getBytes()));
        return String.format("%s/%s", getRequestId(request), reqId);
    }

    protected String getRequestId(HttpServletRequest request) {
        String requestId = request.getHeader(SimpleRequestInstrumentationProvider.REQUEST_ID);
        if (requestId == null) {
            requestId = "unknown";
        }
        return  requestId;
    }

    private Optional<String> getQueryId(HttpServletRequest request) {
        String queryId = request.getHeader(SimpleRequestInstrumentationProvider.QUERY_ID);
        return Optional.ofNullable(queryId);
    }
}
