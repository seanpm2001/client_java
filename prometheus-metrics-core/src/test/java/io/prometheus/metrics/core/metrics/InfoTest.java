package io.prometheus.metrics.core.metrics;

import io.prometheus.metrics.model.snapshots.InfoSnapshot;
import io.prometheus.metrics.model.snapshots.Labels;
import org.junit.Assert;
import org.junit.Test;

public class InfoTest {

    @Test
    public void testIncrement() {
        Info info = Info.newBuilder().withName("target_info").build();
        info.info(Labels.of("key", "value"));
        InfoSnapshot snapshot = info.collect();
        Assert.assertEquals("target", snapshot.getMetadata().getName());
        Assert.assertEquals(1, snapshot.getData().size());
        InfoSnapshot.InfoData data = snapshot.getData().stream().findAny().orElseThrow(RuntimeException::new);
        Assert.assertEquals(Labels.of("key", "value"), data.getLabels());
    }
}