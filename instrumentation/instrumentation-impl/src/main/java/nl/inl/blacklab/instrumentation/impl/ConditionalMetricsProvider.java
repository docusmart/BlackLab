package nl.inl.blacklab.instrumentation.impl;

import io.micrometer.cloudwatch2.CloudWatchConfig;
import io.micrometer.cloudwatch2.CloudWatchMeterRegistry;
import io.micrometer.core.instrument.Clock;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmHeapPressureMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics;
import io.micrometer.core.instrument.binder.system.ProcessorMetrics;
import io.micrometer.core.instrument.binder.tomcat.TomcatMetrics;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheus.PrometheusConfig;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import nl.inl.blacklab.instrumentation.MetricsProvider;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.regions.internal.util.EC2MetadataUtils;
import software.amazon.awssdk.services.cloudwatch.CloudWatchAsyncClient;
import software.amazon.awssdk.services.cloudwatch.model.Dimension;
import software.amazon.awssdk.services.cloudwatch.model.MetricDatum;
import software.amazon.awssdk.services.cloudwatch.model.PutMetricDataRequest;
import software.amazon.awssdk.services.cloudwatch.model.PutMetricDataResponse;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.DescribeTagsRequest;
import software.amazon.awssdk.services.ec2.model.DescribeTagsResponse;
import software.amazon.awssdk.services.ec2.model.Filter;
import software.amazon.awssdk.services.ec2.model.TagDescription;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * ConditionalMetricsProvider creates a registry dependent on the enviroment it
 * is being deployed on. On aws instances it will default to cloudwatch,
 * otherwise it will use Prometheus as the metrics backend.
 */
public class ConditionalMetricsProvider implements MetricsProvider {

    private static final Logger logger = LogManager.getLogger(ConditionalMetricsProvider.class);
    private static final String CW_NAMESPACE = "Blacklab";
    private static final String CW_NAMESPACE_PROPERTY = "metrics.cloudwatch.namespace";
    private final MeterRegistry registry;

    /**
     * CustomCWClient injects base dimensions to all metrics published to CloudWatch
     */
    private static class CustomCWClient implements CloudWatchAsyncClient {
        private final CloudWatchAsyncClient client = CloudWatchAsyncClient.builder().build();
        private final List<Dimension> hostDimensions = new ArrayList<>();
        private final CloudWatchConfig cwConfig;

        public CustomCWClient(Map<String, String> instanceTags, CloudWatchConfig cwConfig) {
            hostDimensions.add(fromOptional("Application",
                () -> Optional.ofNullable(System.getenv("APPLICATION")), "Blacklab"));
            hostDimensions.add(fromOptional("ContainerId",
                () -> Optional.ofNullable(System.getenv("HOSTNAME")), "Unknown"));
            hostDimensions.add(fromOptional("Environment",
                () -> Optional.ofNullable(instanceTags.get("Environment")), "Unknown"));
            hostDimensions.add(fromOptional("InstanceId",
                () -> Optional.ofNullable(instanceTags.get("InstanceId")), "Unknown"));
            this.cwConfig = cwConfig;
            logger.info("Will publish CloudWatch metrics with the following dimensions: " + hostDimensions);
        }

        private Dimension fromOptional(String dimensionName, Supplier<Optional<String>> dimensionGetter, String defaultValue) {
            String value = dimensionGetter.get().orElse(defaultValue);
            return Dimension.builder().name(dimensionName).value(value).build();
        }

        @Override
        public String serviceName() {
            return client.serviceName();
        }

        @Override
        public void close() {
            client.close();
        }

        @Override
        public CompletableFuture<PutMetricDataResponse> putMetricData(PutMetricDataRequest putMetricDataRequest) {
            List<MetricDatum> newData = new ArrayList<>();
            for(MetricDatum m : putMetricDataRequest.metricData()) {
                ArrayList<Dimension> dimensions = new ArrayList<>(m.dimensions());
                dimensions.addAll(hostDimensions);
                MetricDatum newDatum = m.toBuilder()
                    .dimensions(dimensions)
                    .build();
                newData.add(newDatum);
            }

            PutMetricDataRequest req = PutMetricDataRequest.builder()
                .namespace(cwConfig.namespace())
                .metricData(newData)
                .build();

            return client.putMetricData(req);
        }
    }

    public ConditionalMetricsProvider() {
        if (!metricsEnabled()) {
            logger.info("Metrics are disabled. No metrics will be published.");
            registry = new SimpleMeterRegistry();
            return;
        }

        // Add cloudwatch metrics on ec2 instances only
        Optional<Map<String, String>> tags = getInstanceTags();
        if (!tags.isPresent()) {
            logger.info("No EC2 information. Will not publish metrics to CloudWatch");
            registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
            logger.info("Publishing metrics to Prometheus");
        } else {
            CloudWatchConfig config = getCloudWatchConfig();
            logger.info("Found EC2 information. Will publish to CloudWatch");
            registry = new CloudWatchMeterRegistry(config, Clock.SYSTEM, new CustomCWClient(tags.get(), config));
        }

        addSystemMetrics();
    }

    /**
     * Adds metrics to measure the behaviour of the underlying JVM.
     * In addition, adds metrics to measure Tomcat.
     */
    private void addSystemMetrics() {
        new JvmMemoryMetrics().bindTo(registry);
        new JvmGcMetrics().bindTo(registry);
        new JvmHeapPressureMetrics().bindTo(registry);
        new JvmThreadMetrics().bindTo(registry);
        new ProcessorMetrics().bindTo(registry);
        new TomcatMetrics(null, Tags.empty()).bindTo(registry);
    }

    @Override
    public MeterRegistry getRegistry() {
        return registry;
    }

    private CloudWatchConfig getCloudWatchConfig(){
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

    private Optional<Map<String, String>> getInstanceTags() {
        try {
            EC2MetadataUtils.InstanceInfo instanceInfo = EC2MetadataUtils.getInstanceInfo();
            if (instanceInfo == null) {
                logger.info("Instance info is not valid");
                return Optional.empty();
            }

            Ec2Client ec2 = Ec2Client.builder()
                .region(Region.US_WEST_2)
                .build();

            Filter filter = Filter.builder()
                .name("resource-id")
                .values(instanceInfo.getInstanceId())
                .build();

            DescribeTagsResponse describeTagsResponse = ec2.describeTags(DescribeTagsRequest.builder().filters(filter).build());
            List<TagDescription> tags = describeTagsResponse.tags();
            Map<String, String> tagsMap = new HashMap<>();
            tagsMap.put("InstanceId", instanceInfo.getInstanceId());
            for (TagDescription tag: tags) {
                tagsMap.put(tag.key(), tag.value());
            }
            return Optional.of(tagsMap);
        } catch(Exception ex) {
            logger.info("Can not read instance info");
            return Optional.empty();
        }
    }
}
