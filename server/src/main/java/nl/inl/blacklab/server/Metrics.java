package nl.inl.blacklab.server;

import io.micrometer.cloudwatch2.CloudWatchConfig;
import io.micrometer.cloudwatch2.CloudWatchMeterRegistry;
import io.micrometer.core.instrument.Clock;
import io.micrometer.core.instrument.binder.jvm.*;
import io.micrometer.core.instrument.binder.system.ProcessorMetrics;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import io.micrometer.core.instrument.config.validate.Validated;
import io.micrometer.prometheus.PrometheusConfig;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import io.prometheus.client.exporter.common.TextFormat;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudwatch.CloudWatchAsyncClient;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public class Metrics {
    private static final Logger logger = LogManager.getLogger(Metrics.class);
    static final String CW_NAMESPACE = "blacklab-metrics";
    static final String CW_NAMESPACE_PROPERTY = "metrics.cloudwatch.namespace";

    /**
     * Registry for metrics. Define to metrics backend
     **/
    final static CompositeMeterRegistry registry = new CompositeMeterRegistry();

    static {
        registry.add(new PrometheusMeterRegistry(PrometheusConfig.DEFAULT));
        registry.add(new CloudWatchMeterRegistry(cloudWatchConfig(), Clock.SYSTEM, createCloudWatchClient()));
        new ClassLoaderMetrics().bindTo(registry);
        new JvmMemoryMetrics().bindTo(registry);
        new JvmGcMetrics().bindTo(registry);
        new JvmHeapPressureMetrics().bindTo(registry);
        new JvmThreadMetrics().bindTo(registry);
        new ProcessorMetrics().bindTo(registry);
        new JvmInfoMetrics().bindTo(registry);
    }

    private static CloudWatchAsyncClient createCloudWatchClient() {
        System.setProperty("io.netty.tryReflectionSetAccessible", "false");
        return CloudWatchAsyncClient
                .builder()
                .region(Region.US_WEST_2)
                .build();
    }

    private static CloudWatchConfig cloudWatchConfig(){
        final Map<String, String> props = new HashMap<>();
        props.put("cloudwatch.namespace", System.getProperty(CW_NAMESPACE_PROPERTY, CW_NAMESPACE));
        props.put("cloudwatch.step", Duration.ofMinutes(1).toString());
        props.put("cloudwatch.batchSize", String.format("%d", CloudWatchConfig.MAX_BATCH_SIZE));

        return new CloudWatchConfig() {
            @Override
            public String get(String key) {
                return props.get(key);
            }
        };
    }

    protected static boolean handlePrometheus(HttpServletRequest request, HttpServletResponse responseObject) {
        // Metrics scrapping endpoint
        if (!request.getRequestURI().contains("/metrics")) {
            return false;
        }

        Optional<PrometheusMeterRegistry> reg = registry.getRegistries().stream()
                .filter(r -> r instanceof PrometheusMeterRegistry)
                .map(t -> (PrometheusMeterRegistry) t)
                .findFirst();
        reg.ifPresent((PrometheusMeterRegistry registry) -> {
            try {
                registry.scrape(responseObject.getWriter());
                responseObject.setStatus(HttpServletResponse.SC_OK);
                responseObject.setCharacterEncoding(BlackLabServer.OUTPUT_ENCODING.name().toLowerCase());
                responseObject.setContentType(TextFormat.CONTENT_TYPE_004);
            } catch (IOException exception) {
                logger.error("Cant scrape prometheus metrics", exception);
            }
        });
        return true;
    }
}
