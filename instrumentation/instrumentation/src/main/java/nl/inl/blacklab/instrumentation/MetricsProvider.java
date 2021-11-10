package nl.inl.blacklab.instrumentation;

import io.micrometer.core.instrument.MeterRegistry;

public interface MetricsProvider {
    public MeterRegistry getRegistry();

    default  boolean metricsEnabled() {
        String result = System.getProperty("metrics.enabled", "true");
        boolean disabled = result.equalsIgnoreCase("false");
        return !disabled;
    }
}
