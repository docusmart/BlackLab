package nl.inl.blacklab.instrumentation.impl;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import io.prometheus.client.exporter.common.TextFormat;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import java.util.function.ToDoubleFunction;
import java.util.function.ToLongFunction;

public class MetricsUtils {
    private static final Logger logger = LogManager.getLogger(MetricsUtils.class);
    private static final String DEFAULT_PROM_ENDPOINT = "/metrics";

    /**
     * A simple request handler that responds to prometheus  metrics scrapping requests
     * @param registry must be of type {@link PrometheusMeterRegistry}
     * @param request
     * @param responseObject
     * @param charEncoding
     * @return
     */
    protected static boolean handlePrometheus(MeterRegistry registry, HttpServletRequest request, HttpServletResponse responseObject, String charEncoding) {
        if (!request.getRequestURI().contains(DEFAULT_PROM_ENDPOINT)) {
            return false;
        }
        if (!(registry instanceof PrometheusMeterRegistry)) {
            logger.warn("Can not respond to /metrics without a PrometheusRegistry");
            return true;
        }

        PrometheusMeterRegistry prometheusMeterRegistry = (PrometheusMeterRegistry) registry;

        try {
            prometheusMeterRegistry.scrape(responseObject.getWriter());
            responseObject.setStatus(HttpServletResponse.SC_OK);
            responseObject.setCharacterEncoding(charEncoding);
            responseObject.setContentType(TextFormat.CONTENT_TYPE_004);
        } catch (IOException exception) {
            logger.error("Can't scrape prometheus metrics", exception);
        }
        return true;
    }


    public static <T> ToDoubleFunction<T> toDoubleFn(ToLongFunction<T> intGenerator) {
        return  (T obj) -> (double) intGenerator.applyAsLong(obj);
    }
}

