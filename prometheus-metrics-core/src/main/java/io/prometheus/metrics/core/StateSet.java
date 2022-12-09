package io.prometheus.metrics.core;

import io.prometheus.metrics.model.Labels;
import io.prometheus.metrics.model.MetricType;
import io.prometheus.metrics.model.StateSetSnapshot;
import io.prometheus.metrics.observer.StateObserver;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

// experimental
public class StateSet extends ObservingMetric<StateObserver, StateSet.StateSetData> implements StateObserver {

    private final String[] names;

    private StateSet(Builder builder) {
        super(builder);
        this.names = builder.names; // builder.names is already a validated copy
        for (String name : names) {
            if (this.getMetadata().getName().equals(name)) {
                throw new IllegalArgumentException("Label name " + name + " is illegal (can't use the metric name as label name in state set metrics)");
            }
        }
    }

    @Override
    public StateSetSnapshot collect() {
        return (StateSetSnapshot) super.collect();
    }

    @Override
    protected StateSetSnapshot collect(List<Labels> labels, List<StateSetData> metricDataList) {
        List<StateSetSnapshot.StateSetData> data = new ArrayList<>(labels.size());
        for (int i = 0; i < labels.size(); i++) {
            data.add(new StateSetSnapshot.StateSetData(labels.get(i), names, metricDataList.get(i).values));
        }
        return new StateSetSnapshot(getMetadata(), data);
    }

    @Override
    protected StateSetData newMetricData() {
        return new StateSetData();
    }

    @Override
    public void setTrue(String state) {
        getNoLabels().setTrue(state);
    }

    @Override
    public void setFalse(String state) {
        getNoLabels().setFalse(state);
    }

    class StateSetData extends MetricData<StateObserver> implements StateObserver {

        private final boolean[] values = new boolean[names.length];

        private StateSetData() {
        }

        @Override
        StateObserver toObserver() {
            return this;
        }

        @Override
        public void setTrue(String state) {
            set(state, true);
        }

        @Override
        public void setFalse(String state) {
            set(state, false);
        }

        private void set(String name, boolean value) {
            for (int i = 0; i < names.length; i++) {
                if (names[i].equals(name)) {
                    values[i] = value;
                    return;
                }
            }
            throw new IllegalArgumentException(name + ": unknown state");
        }
    }


    public static class Builder extends ObservingMetric.Builder<Builder, StateSet> {

        private String[] names;

        private Builder() {
            super(Collections.emptyList());
        }

        public Builder withStates(Class<? extends Enum<?>> enumClass) {
            return withStates(Stream.of(enumClass.getEnumConstants()).map(Enum::toString).toArray(String[]::new));
        }

        public Builder withStates(String... stateNames) {
            if (stateNames.length == 0) {
                throw new IllegalArgumentException("states cannot be empty");
            }
            this.names = Stream.of(stateNames)
                    .distinct()
                    .sorted()
                    .toArray(String[]::new);
            return this;
        }

        @Override
        public StateSet build() {
            return new StateSet(this);
        }

        @Override
        protected MetricType getType() {
            return MetricType.STATE_SET;
        }

        @Override
        protected Builder self() {
            return this;
        }
    }

    public static Builder newBuilder() {
        return new Builder();
    }
}
