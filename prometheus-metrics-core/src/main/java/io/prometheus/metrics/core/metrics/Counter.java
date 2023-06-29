package io.prometheus.metrics.core.metrics;

import io.prometheus.metrics.config.MetricProperties;
import io.prometheus.metrics.config.PrometheusProperties;
import io.prometheus.metrics.core.exemplars.ExemplarSampler;
import io.prometheus.metrics.core.exemplars.ExemplarSamplerConfig;
import io.prometheus.metrics.model.registry.Collector;
import io.prometheus.metrics.model.snapshots.CounterSnapshot;
import io.prometheus.metrics.model.snapshots.Exemplar;
import io.prometheus.metrics.model.snapshots.Labels;
import io.prometheus.metrics.core.observer.CounterDataPoint;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.DoubleSupplier;

public class Counter extends StatefulMetric<CounterDataPoint, Counter.CounterData> implements CounterDataPoint, Collector {

    private final boolean exemplarsEnabled;
    private final ExemplarSamplerConfig exemplarSamplerConfig;

    private Counter(Builder builder, PrometheusProperties prometheusProperties) {
        super(builder);
        MetricProperties[] properties = getMetricProperties(builder, prometheusProperties);
        exemplarsEnabled = getConfigProperty(properties, MetricProperties::getExemplarsEnabled);
        if (exemplarsEnabled) {
            exemplarSamplerConfig = new ExemplarSamplerConfig(prometheusProperties.getExemplarConfig(), 1);
        } else {
            exemplarSamplerConfig = null;
        }
    }

    @Override
    protected boolean isExemplarsEnabled() {
        return exemplarsEnabled;
    }

    @Override
    public void inc(long amount) {
        getNoLabels().inc(amount);
    }

    @Override
    public void inc(double amount) {
        getNoLabels().inc(amount);
    }

    @Override
    public void incWithExemplar(long amount, Labels labels) {
        getNoLabels().incWithExemplar(amount, labels);
    }

    @Override
    public void incWithExemplar(double amount, Labels labels) {
        getNoLabels().incWithExemplar(amount, labels);
    }


    @Override
    protected CounterData newMetricData() {
        if (isExemplarsEnabled()) {
            return new CounterData(new ExemplarSampler(exemplarSamplerConfig));
        } else {
            return new CounterData(null);
        }
    }

    @Override
    protected CounterSnapshot collect(List<Labels> labels, List<CounterData> metricData) {
        List<CounterSnapshot.CounterData> data = new ArrayList<>(labels.size());
        for (int i = 0; i < labels.size(); i++) {
            data.add(metricData.get(i).collect(labels.get(i)));
        }
        return new CounterSnapshot(getMetadata(), data);
    }

    @Override
    public CounterSnapshot collect() {
        return (CounterSnapshot) super.collect();
    }

    public static Builder newBuilder() {
        return new Builder(PrometheusProperties.getInstance());
    }

    public static Builder newBuilder(PrometheusProperties config) {
        return new Builder(config);
    }

    private static String normalizeName(String name) {
        if (name != null && name.endsWith("_total")) {
            name = name.substring(0, name.length() - 6);
        }
        return name;
    }

    class CounterData extends MetricData<CounterDataPoint> implements CounterDataPoint {

        private final DoubleAdder doubleValue = new DoubleAdder();
        // LongAdder is 20% faster than DoubleAdder. So let's use the LongAdder for long observations,
        // and DoubleAdder for double observations. If the user doesn't observe any double at all,
        // we will just use the LongAdder and get the best performance.
        private final LongAdder longValue = new LongAdder();
        private final long createdTimeMillis = System.currentTimeMillis();
        private final ExemplarSampler exemplarSampler; // null if isExemplarsEnabled() is false

        private CounterData(ExemplarSampler exemplarSampler) {
            this.exemplarSampler = exemplarSampler;
        }

        @Override
        public void inc(long amount) {
            validateAndAdd(amount);
            if (isExemplarsEnabled()) {
                exemplarSampler.observe(amount);
            }
        }

        @Override
        public void inc(double amount) {
            validateAndAdd(amount);
            if (isExemplarsEnabled()) {
                exemplarSampler.observe(amount);
            }
        }

        @Override
        public void incWithExemplar(long amount, Labels labels) {
            validateAndAdd(amount);
            if (isExemplarsEnabled()) {
                exemplarSampler.observeWithExemplar(amount, labels);
            }
        }

        @Override
        public void incWithExemplar(double amount, Labels labels) {
            validateAndAdd(amount);
            if (isExemplarsEnabled()) {
                exemplarSampler.observeWithExemplar(amount, labels);
            }
        }

        private void validateAndAdd(long amount) {
            if (amount < 0) {
                throw new IllegalArgumentException("Negative increment " + amount + " is illegal for Counter metrics.");
            }
            longValue.add(amount);
        }

        private void validateAndAdd(double amount) {
            if (amount < 0) {
                throw new IllegalArgumentException("Negative increment " + amount + " is illegal for Counter metrics.");
            }
            doubleValue.add(amount);
        }

        private CounterSnapshot.CounterData collect(Labels labels) {
            // Read the exemplar first. Otherwise, there is a race condition where you might
            // see an Exemplar for a value that's not represented in getValue() yet.
            // If there are multiple Exemplars (by default it's just one), use the oldest
            // so that we don't violate min age.
            Exemplar oldest = null;
            if (exemplarSampler != null) {
                for (Exemplar exemplar : exemplarSampler.collect()) {
                    if (oldest == null || exemplar.getTimestampMillis() < oldest.getTimestampMillis()) {
                        oldest = exemplar;
                    }
                }
            }
            return new CounterSnapshot.CounterData(longValue.sum() + doubleValue.sum(), labels, oldest, createdTimeMillis);
        }

        @Override
        public CounterDataPoint toObserver() {
            return this;
        }
    }

    public static class Builder extends StatefulMetric.Builder<Builder, Counter> {

        private Builder(PrometheusProperties properties) {
            super(Collections.emptyList(), properties);
        }

        @Override
        public Builder withName(String name) {
            return super.withName(normalizeName(name));
        }

        @Override
        public Counter build() {
            return new Counter(this, properties);
        }

        @Override
        protected Builder self() {
            return this;
        }
    }

    public static class FromCallback extends MetricWithFixedMetadata {

        private final DoubleSupplier callback;
        private final long createdTimeMillis = System.currentTimeMillis();

        private FromCallback(Counter.FromCallback.Builder builder) {
            super(builder);
            this.callback = builder.callback;
        }

        @Override
        public CounterSnapshot collect() {
            return new CounterSnapshot(getMetadata(), Collections.singletonList(new CounterSnapshot.CounterData(
                    callback.getAsDouble(),
                    constLabels,
                    null,
                    createdTimeMillis
            )));
        }

        public static class Builder extends MetricWithFixedMetadata.Builder<Counter.FromCallback.Builder, Counter.FromCallback> {

            private DoubleSupplier callback;

            private Builder(PrometheusProperties config) {
                super(Collections.emptyList(), config);
            }

            public Counter.FromCallback.Builder withCallback(DoubleSupplier callback) {
                this.callback = callback;
                return this;
            }

            @Override
            public Counter.FromCallback build() {
                return new Counter.FromCallback(withName(normalizeName(name)));
            }

            @Override
            protected Counter.FromCallback.Builder self() {
                return this;
            }
        }
    }
}
