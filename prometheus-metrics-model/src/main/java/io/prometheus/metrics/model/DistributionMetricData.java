package io.prometheus.metrics.model;

/**
 * Common base class for static histogram data, native histogram data, and summary data.
 */
public abstract class DistributionMetricData extends MetricData {
    private final long count;
    private final double sum;
    private final Exemplars exemplars;

    protected DistributionMetricData(long count, double sum, Exemplars exemplars, Labels labels, long createdTimestampMillis, long timestampMillis) {
        super(labels, createdTimestampMillis, timestampMillis);
        this.count = count;
        this.sum = sum;
        this.exemplars = exemplars;
        if (exemplars == null) {
            throw new NullPointerException("Exemplars cannot be null. Use Exemplars.EMPTY if there are no Exemplars.");
        }
    }

    public boolean hasCount() {
        return count >= 0;
    }

    public boolean hasSum() {
        return !Double.isNaN(sum);
    }

    public long getCount() {
        return count;
    }

    public double getSum() {
        return sum;
    }

    public Exemplars getExemplars() {
        return exemplars;
    }

    public static abstract class Builder<T extends Builder<T>> extends MetricData.Builder<T> {

        protected long count = -1;
        protected double sum = Double.NaN;
        protected long createdTimestampMillis = 0L;
        protected Exemplars exemplars = Exemplars.EMPTY;

        public T withCount(long count) {
            this.count = count;
            return self();
        }

        public T withSum(double sum) {
            this.sum = sum;
            return self();
        }

        public T withExemplars(Exemplars exemplars) {
            this.exemplars = exemplars;
            return self();
        }

        public T withCreatedTimestampMillis(long createdTimestampMillis) {
            this.createdTimestampMillis = createdTimestampMillis;
            return self();
        }
    }
}