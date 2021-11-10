package nl.inl.blacklab.instrumentation.impl;

import nl.inl.blacklab.instrumentation.RequestInstrumentationProvider;

import javax.servlet.http.HttpServletRequest;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public class SimpleRequestInstrumentationProvider implements RequestInstrumentationProvider {
    private static final String ANN_REQUEST_ID_HEADER_NAME = "X-Request-ID";
    private static final String ANN_RULE_ID_HEADER_NAME = "X-Ann-Rule-ID";
    private static final String ANN_DOC_COUNT_HEADER = "X-Ann-Doc-Count";

    @Override
    public Optional<String> getRequestID(HttpServletRequest request) {
        return Optional.of(buildRequestId(request));
    }

    @Override
    public Map<String, String> getRequestMetadata(HttpServletRequest request) {
        String docCount = request.getHeader(ANN_DOC_COUNT_HEADER);
        Map<String, String> metadata = new HashMap<>();
        metadata.put("inputDocCount", docCount);
        return metadata;
    }

    private String buildRequestId(HttpServletRequest request) {
        String reqId = getRuleId(request).orElse(
            Base64.getUrlEncoder().encodeToString(UUID.randomUUID().toString().getBytes()));
        return String.format("%s/%s", getAnnRequestId(request), reqId);
    }

    protected String getAnnRequestId(HttpServletRequest request) {
        String requestId = request.getHeader(ANN_REQUEST_ID_HEADER_NAME);
        if (requestId == null) {
            requestId = "unknown";
        }
        return  requestId;
    }

    private Optional<String> getRuleId(HttpServletRequest request) {
        String ruleId = request.getHeader(ANN_RULE_ID_HEADER_NAME);
        return Optional.ofNullable(ruleId);
    }
}
