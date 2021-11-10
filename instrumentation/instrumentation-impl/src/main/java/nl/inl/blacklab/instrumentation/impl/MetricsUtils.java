package nl.inl.blacklab.instrumentation.impl;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.function.ToDoubleFunction;
import java.util.function.ToLongFunction;

public class MetricsUtils {
    private static final Logger logger = LogManager.getLogger(MetricsUtils.class);

//    protected boolean handlePrometheus(HttpServletRequest request, HttpServletResponse responseObject, String charEncoding) {
//        // Metrics scrapping endpoint
//        if (!request.getRequestURI().contains("/metrics")) {
//            return false;
//        }
//
//        Optional<PrometheusMeterRegistry> reg = theRegistry.getRegistries().stream()
//                .filter(r -> r instanceof PrometheusMeterRegistry)
//                .map(t -> (PrometheusMeterRegistry) t)
//                .findFirst();
//        reg.ifPresent((PrometheusMeterRegistry registry) -> {
//            try {
//                registry.scrape(responseObject.getWriter());
//                responseObject.setStatus(HttpServletResponse.SC_OK);
//                responseObject.setCharacterEncoding(charEncoding);
//                responseObject.setContentType(TextFormat.CONTENT_TYPE_004);
//            } catch (IOException exception) {
//                logger.error("Can't scrape prometheus metrics", exception);
//            }
//        });
//        return true;
//    }


    public static <T> ToDoubleFunction<T> toDoubleFn(ToLongFunction<T> intGenerator) {
        return  (T obj) -> (double) intGenerator.applyAsLong(obj);
    }
}

