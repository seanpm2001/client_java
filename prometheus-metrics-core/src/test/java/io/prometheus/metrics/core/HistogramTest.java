package io.prometheus.metrics.core;

import io.prometheus.client.exemplars.tracer.common.SpanContextSupplier;
import io.prometheus.com_google_protobuf_3_21_7.TextFormat;
import io.prometheus.expositionformat.PrometheusProtobufWriter;
import io.prometheus.expositionformat.protobuf.generated.com_google_protobuf_3_21_7.Metrics;
import io.prometheus.metrics.exemplars.ExemplarConfig;
import io.prometheus.metrics.model.Exemplar;
import io.prometheus.metrics.model.Exemplars;
import io.prometheus.metrics.model.HistogramSnapshot;
import io.prometheus.metrics.model.Labels;
import org.junit.Assert;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

import static io.prometheus.metrics.core.TestUtil.assertExemplarEquals;
import static org.junit.Assert.assertEquals;

public class HistogramTest {

    private static final double RESET_DURATION_REACHED = -123.456; // just a random value indicating that we should simulate that the reset duration has been reached

    /**
     * Mimic the tests in client_golang.
     */
    private static class GolangTestCase {
        final String name;
        final String expected;
        final Histogram histogram;
        final double[] observations;

        private GolangTestCase(String name, String expected, Histogram histogram, double... observations) {
            this.name = name;
            this.expected = expected;
            this.histogram = histogram;
            this.observations = observations;
        }

        private void run() throws NoSuchFieldException, IllegalAccessException {
            System.out.println("Running " + name + "...");
            for (double observation : observations) {
                if (observation == RESET_DURATION_REACHED) {
                    Field resetAllowed = Histogram.HistogramData.class.getDeclaredField("resetIntervalExpired");
                    resetAllowed.setAccessible(true);
                    resetAllowed.set(histogram.getNoLabels(), true);
                } else {
                    histogram.observe(observation);
                }
            }
            Metrics.MetricFamily protobufData = new PrometheusProtobufWriter().convert(histogram.collect());
            String expectedWithMetadata = "name: \"test\" type: HISTOGRAM metric { histogram { " + expected + " } }";
            assertEquals("test \"" + name + "\" failed", expectedWithMetadata, TextFormat.printer().shortDebugString(protobufData));
        }
    }

    /**
     * Test cases copied from histogram_test.go in client_golang.
     */
    @Test
    public void testGolangTests() throws NoSuchFieldException, IllegalAccessException {
        GolangTestCase[] testCases = new GolangTestCase[]{
                new GolangTestCase("'no sparse buckets' from client_golang",
                        "sample_count: 3 " +
                                "sample_sum: 6.0 " +
                                "bucket { cumulative_count: 0 upper_bound: 0.005 } " +
                                "bucket { cumulative_count: 0 upper_bound: 0.01 } " +
                                "bucket { cumulative_count: 0 upper_bound: 0.025 } " +
                                "bucket { cumulative_count: 0 upper_bound: 0.05 } " +
                                "bucket { cumulative_count: 0 upper_bound: 0.1 } " +
                                "bucket { cumulative_count: 0 upper_bound: 0.25 } " +
                                "bucket { cumulative_count: 0 upper_bound: 0.5 } " +
                                "bucket { cumulative_count: 1 upper_bound: 1.0 } " +
                                "bucket { cumulative_count: 2 upper_bound: 2.5 } " +
                                "bucket { cumulative_count: 3 upper_bound: 5.0 } " +
                                "bucket { cumulative_count: 3 upper_bound: 10.0 } " +
                                "bucket { cumulative_count: 3 upper_bound: Infinity }",
                        Histogram.newBuilder()
                                .withName("test")
                                .classicHistogramOnly()
                                .build(),
                        1.0, 2.0, 3.0),
                new GolangTestCase("'factor 1.1 results in schema 3' from client_golang",
                        "sample_count: 4 " +
                                "sample_sum: 6.0 " +
                                "schema: 3 " +
                                "zero_threshold: 0.0 " +
                                "zero_count: 1 " +
                                "positive_span { offset: 0 length: 1 } " +
                                "positive_span { offset: 7 length: 1 } " +
                                "positive_span { offset: 4 length: 1 } " +
                                "positive_delta: 1 " +
                                "positive_delta: 0 " +
                                "positive_delta: 0",
                        Histogram.newBuilder()
                                .withName("test")
                                .nativeHistogramOnly()
                                .withNativeSchema(3)
                                .withNativeMaxZeroThreshold(0)
                                .build(),
                        0.0, 1.0, 2.0, 3.0),
                new GolangTestCase("'factor 1.2 results in schema 2' from client_golang",
                        "sample_count: 6 " +
                                "sample_sum: 7.4 " +
                                "schema: 2 " +
                                "zero_threshold: 0.0 " +
                                "zero_count: 1 " +
                                "positive_span { offset: 0 length: 5 } " +
                                "positive_delta: 1 " +
                                "positive_delta: -1 " +
                                "positive_delta: 2 " +
                                "positive_delta: -2 " +
                                "positive_delta: 2",
                        Histogram.newBuilder()
                                .withName("test")
                                .nativeHistogramOnly()
                                .withNativeSchema(2)
                                .withNativeMaxZeroThreshold(0)
                                .build(),
                        0, 1, 1.2, 1.4, 1.8, 2),
                new GolangTestCase("'factor 4 results in schema -1' from client_golang",
                        "sample_count: 14 " +
                                "sample_sum: 63.2581251 " +
                                "schema: -1 " +
                                "zero_threshold: 0.0 " +
                                "zero_count: 0 " +
                                "positive_span { offset: -2 length: 6 } " +
                                "positive_delta: 2 " +
                                "positive_delta: 0 " +
                                "positive_delta: 0 " +
                                "positive_delta: 2 " +
                                "positive_delta: -1 " +
                                "positive_delta: -2",
                        Histogram.newBuilder()
                                .withName("test")
                                .nativeHistogramOnly()
                                .withNativeSchema(-1)
                                .withNativeMaxZeroThreshold(0)
                                .build(),
                        0.0156251, 0.0625, // Bucket -2: (0.015625, 0.0625)
                        0.1, 0.25, // Bucket -1: (0.0625, 0.25]
                        0.5, 1, // Bucket 0: (0.25, 1]
                        1.5, 2, 3, 3.5, // Bucket 1: (1, 4]
                        5, 6, 7, // Bucket 2: (4, 16]
                        33.33 // Bucket 3: (16, 64]
                ),
                new GolangTestCase("'factor 17 results in schema -2' from client_golang",
                        "sample_count: 14 " +
                                "sample_sum: 63.2581251 " +
                                "schema: -2 " +
                                "zero_threshold: 0.0 " +
                                "zero_count: 0 " +
                                "positive_span { offset: -1 length: 4 } " +
                                "positive_delta: 2 " +
                                "positive_delta: 2 " +
                                "positive_delta: 3 " +
                                "positive_delta: -6",
                        Histogram.newBuilder()
                                .withName("test")
                                .nativeHistogramOnly()
                                .withNativeSchema(-2)
                                .withNativeMaxZeroThreshold(0)
                                .build(),
                        0.0156251, 0.0625, // Bucket -1: (0.015625, 0.0625]
                        0.1, 0.25, 0.5, 1, // Bucket 0: (0.0625, 1]
                        1.5, 2, 3, 3.5, 5, 6, 7, // Bucket 1: (1, 16]
                        33.33 // Bucket 2: (16, 256]
                ),
                new GolangTestCase("'negative buckets' from client_golang",
                        "sample_count: 6 " +
                                "sample_sum: -7.4 " +
                                "schema: 2 " +
                                "zero_threshold: 0.0 " +
                                "zero_count: 1 " +
                                "negative_span { offset: 0 length: 5 } " +
                                "negative_delta: 1 " +
                                "negative_delta: -1 " +
                                "negative_delta: 2 " +
                                "negative_delta: -2 " +
                                "negative_delta: 2",
                        Histogram.newBuilder()
                                .withName("test")
                                .nativeHistogramOnly()
                                .withNativeSchema(2)
                                .withNativeMaxZeroThreshold(0)
                                .build(),
                        0, -1, -1.2, -1.4, -1.8, -2
                ),
                new GolangTestCase("'negative and positive buckets' from client_golang",
                        "sample_count: 11 " +
                                "sample_sum: 0.0 " +
                                "schema: 2 " +
                                "zero_threshold: 0.0 " +
                                "zero_count: 1 " +
                                "negative_span { offset: 0 length: 5 } " +
                                "negative_delta: 1 " +
                                "negative_delta: -1 " +
                                "negative_delta: 2 " +
                                "negative_delta: -2 " +
                                "negative_delta: 2 " +
                                "positive_span { offset: 0 length: 5 } " +
                                "positive_delta: 1 " +
                                "positive_delta: -1 " +
                                "positive_delta: 2 " +
                                "positive_delta: -2 " +
                                "positive_delta: 2",
                        Histogram.newBuilder()
                                .withName("test")
                                .nativeHistogramOnly()
                                .withNativeSchema(2)
                                .withNativeMaxZeroThreshold(0)
                                .build(),
                        0, -1, -1.2, -1.4, -1.8, -2, 1, 1.2, 1.4, 1.8, 2
                ),
                new GolangTestCase("'wide zero bucket' from client_golang",
                        "sample_count: 11 " +
                                "sample_sum: 0.0 " +
                                "schema: 2 " +
                                "zero_threshold: 1.4 " +
                                "zero_count: 7 " +
                                "negative_span { offset: 4 length: 1 } " +
                                "negative_delta: 2 " +
                                "positive_span { offset: 4 length: 1 } " +
                                "positive_delta: 2",
                        Histogram.newBuilder()
                                .withName("test")
                                .nativeHistogramOnly()
                                .withNativeSchema(2)
                                .withNativeMinZeroThreshold(1.4)
                                .build(),
                        0, -1, -1.2, -1.4, -1.8, -2, 1, 1.2, 1.4, 1.8, 2
                ),
                /*
                // See https://github.com/prometheus/client_golang/issues/1275
                new TestCase("'NaN observation' from client_golang",
                        "sample_count: 7 " +
                                "sample_sum: NaN " +
                                "schema: 2 " +
                                "zero_threshold: 0.0 " +
                                "zero_count: 1 " +
                                "positive_span { offset: 0 length: 5 } " +
                                "positive_delta: 1 " +
                                "positive_delta: -1 " +
                                "positive_delta: 2 " +
                                "positive_delta: -2 " +
                                "positive_delta: 2",
                        Histogram.newBuilder()
                                .withName("test")
                                .asNativeHistogram()
                                .withNativeSchema(2)
                                .withNativeMaxZeroThreshold(0)
                                .build(),
                        0, 1, 1.2, 1.4, 1.8, 2, Double.NaN
                ),
                */
                new GolangTestCase("'+Inf observation' from client_golang",
                        "sample_count: 7 " +
                                "sample_sum: Infinity " +
                                "schema: 2 " +
                                "zero_threshold: 0.0 " +
                                "zero_count: 1 " +
                                "positive_span { offset: 0 length: 5 } " +
                                "positive_span { offset: 4092 length: 1 } " +
                                "positive_delta: 1 " +
                                "positive_delta: -1 " +
                                "positive_delta: 2 " +
                                "positive_delta: -2 " +
                                "positive_delta: 2 " +
                                "positive_delta: -1",
                        Histogram.newBuilder()
                                .withName("test")
                                .nativeHistogramOnly()
                                .withNativeSchema(2)
                                .withNativeMaxZeroThreshold(0)
                                .build(),
                        0, 1, 1.2, 1.4, 1.8, 2, Double.POSITIVE_INFINITY
                ),
                new GolangTestCase("'-Inf observation' from client_golang",
                        "sample_count: 7 " +
                                "sample_sum: -Infinity " +
                                "schema: 2 " +
                                "zero_threshold: 0.0 " +
                                "zero_count: 1 " +
                                "negative_span { offset: 4097 length: 1 } " +
                                "negative_delta: 1 " +
                                "positive_span { offset: 0 length: 5 } " +
                                "positive_delta: 1 " +
                                "positive_delta: -1 " +
                                "positive_delta: 2 " +
                                "positive_delta: -2 " +
                                "positive_delta: 2",
                        Histogram.newBuilder()
                                .withName("test")
                                .nativeHistogramOnly()
                                .withNativeSchema(2)
                                .withNativeMaxZeroThreshold(0)
                                .build(),
                        0, 1, 1.2, 1.4, 1.8, 2, Double.NEGATIVE_INFINITY
                ),
                new GolangTestCase("'limited buckets but nothing triggered' from client_golang",
                        "sample_count: 6 " +
                                "sample_sum: 7.4 " +
                                "schema: 2 " +
                                "zero_threshold: 0.0 " +
                                "zero_count: 1 " +
                                "positive_span { offset: 0 length: 5 } " +
                                "positive_delta: 1 " +
                                "positive_delta: -1 " +
                                "positive_delta: 2 " +
                                "positive_delta: -2 " +
                                "positive_delta: 2",
                        Histogram.newBuilder()
                                .withName("test")
                                .nativeHistogramOnly()
                                .withNativeSchema(2)
                                .withNativeMaxZeroThreshold(0)
                                .withNativeMaxBuckets(4)
                                .build(),
                        0, 1, 1.2, 1.4, 1.8, 2
                ),
                new GolangTestCase("'buckets limited by halving resolution' from client_golang",
                        "sample_count: 8 " +
                                "sample_sum: 11.5 " +
                                "schema: 1 " +
                                "zero_threshold: 0.0 " +
                                "zero_count: 1 " +
                                "positive_span { offset: 0 length: 5 } " +
                                "positive_delta: 1 " +
                                "positive_delta: 2 " +
                                "positive_delta: -1 " +
                                "positive_delta: -2 " +
                                "positive_delta: 1",
                        Histogram.newBuilder()
                                .withName("test")
                                .nativeHistogramOnly()
                                .withNativeSchema(2)
                                .withNativeMaxZeroThreshold(0)
                                .withNativeMaxBuckets(4)
                                .build(),
                        0, 1, 1.1, 1.2, 1.4, 1.8, 2, 3
                ),
                new GolangTestCase("'buckets limited by widening the zero bucket' from client_golang",
                        "sample_count: 8 " +
                                "sample_sum: 11.5 " +
                                "schema: 2 " +
                                "zero_threshold: 1.0 " +
                                "zero_count: 2 " +
                                "positive_span { offset: 1 length: 7 } " +
                                "positive_delta: 1 " +
                                "positive_delta: 1 " +
                                "positive_delta: -2 " +
                                "positive_delta: 2 " +
                                "positive_delta: -2 " +
                                "positive_delta: 0 " +
                                "positive_delta: 1",
                        Histogram.newBuilder()
                                .withName("test")
                                .nativeHistogramOnly()
                                .withNativeSchema(2)
                                .withNativeMaxZeroThreshold(1.2)
                                .withNativeMaxBuckets(4)
                                .build(),
                        0, 1, 1.1, 1.2, 1.4, 1.8, 2, 3
                ),
                new GolangTestCase("'buckets limited by widening the zero bucket twice' from client_golang",
                        "sample_count: 9 " +
                                "sample_sum: 15.5 " +
                                "schema: 2 " +
                                "zero_threshold: 1.189207115002721 " +
                                "zero_count: 3 " +
                                "positive_span { offset: 2 length: 7 } " +
                                "positive_delta: 2 " +
                                "positive_delta: -2 " +
                                "positive_delta: 2 " +
                                "positive_delta: -2 " +
                                "positive_delta: 0 " +
                                "positive_delta: 1 " +
                                "positive_delta: 0",
                        Histogram.newBuilder()
                                .withName("test")
                                .nativeHistogramOnly()
                                .withNativeSchema(2)
                                .withNativeMaxZeroThreshold(1.2)
                                .withNativeMaxBuckets(4)
                                .build(),
                        0, 1, 1.1, 1.2, 1.4, 1.8, 2, 3, 4),
                new GolangTestCase("'buckets limited by reset' from client_golang",
                        "sample_count: 2 " +
                                "sample_sum: 7.0 " +
                                "schema: 2 " +
                                "zero_threshold: 0.0 " +
                                "zero_count: 0 " +
                                "positive_span { offset: 7 length: 2 } " +
                                "positive_delta: 1 " +
                                "positive_delta: 0",
                        Histogram.newBuilder()
                                .withName("test")
                                .nativeHistogramOnly()
                                .withNativeSchema(2)
                                .withNativeMaxZeroThreshold(1.2)
                                .withNativeMinZeroThreshold(0)
                                .withNativeMaxBuckets(4)
                                .build(),
                        0, 1, 1.1, 1.2, 1.4, 1.8, 2, RESET_DURATION_REACHED, 3, 4),
                new GolangTestCase("'limited buckets but nothing triggered, negative observations' from client_golang",
                        "sample_count: 6 " +
                                "sample_sum: -7.4 " +
                                "schema: 2 " +
                                "zero_threshold: 0.0 " +
                                "zero_count: 1 " +
                                "negative_span { offset: 0 length: 5 } " +
                                "negative_delta: 1 " +
                                "negative_delta: -1 " +
                                "negative_delta: 2 " +
                                "negative_delta: -2 " +
                                "negative_delta: 2",
                        Histogram.newBuilder()
                                .withName("test")
                                .nativeHistogramOnly()
                                .withNativeSchema(2)
                                .withNativeMaxZeroThreshold(0)
                                .withNativeMaxBuckets(4)
                                .build(),
                        0, -1, -1.2, -1.4, -1.8, -2),
                new GolangTestCase("'buckets limited by halving resolution, negative observations' from client_golang",
                        "sample_count: 8 " +
                                "sample_sum: -11.5 " +
                                "schema: 1 " +
                                "zero_threshold: 0.0 " +
                                "zero_count: 1 " +
                                "negative_span { offset: 0 length: 5 } " +
                                "negative_delta: 1 " +
                                "negative_delta: 2 " +
                                "negative_delta: -1 " +
                                "negative_delta: -2 " +
                                "negative_delta: 1",
                        Histogram.newBuilder()
                                .withName("test")
                                .nativeHistogramOnly()
                                .withNativeSchema(2)
                                .withNativeMaxZeroThreshold(0)
                                .withNativeMaxBuckets(4)
                                .build(),
                        0, -1, -1.1, -1.2, -1.4, -1.8, -2, -3),
                new GolangTestCase("'buckets limited by widening the zero bucket, negative observations' from client_golang",
                        "sample_count: 8 " +
                                "sample_sum: -11.5 " +
                                "schema: 2 " +
                                "zero_threshold: 1.0 " +
                                "zero_count: 2 " +
                                "negative_span { offset: 1 length: 7 } " +
                                "negative_delta: 1 " +
                                "negative_delta: 1 " +
                                "negative_delta: -2 " +
                                "negative_delta: 2 " +
                                "negative_delta: -2 " +
                                "negative_delta: 0 " +
                                "negative_delta: 1",
                        Histogram.newBuilder()
                                .withName("test")
                                .nativeHistogramOnly()
                                .withNativeSchema(2)
                                .withNativeMaxZeroThreshold(1.2)
                                .withNativeMaxBuckets(4)
                                .build(),
                        0, -1, -1.1, -1.2, -1.4, -1.8, -2, -3),
                new GolangTestCase("'buckets limited by widening the zero bucket twice, negative observations' from client_golang",
                        "sample_count: 9 " +
                                "sample_sum: -15.5 " +
                                "schema: 2 " +
                                "zero_threshold: 1.189207115002721 " +
                                "zero_count: 3 " +
                                "negative_span { offset: 2 length: 7 } " +
                                "negative_delta: 2 " +
                                "negative_delta: -2 " +
                                "negative_delta: 2 " +
                                "negative_delta: -2 " +
                                "negative_delta: 0 " +
                                "negative_delta: 1 " +
                                "negative_delta: 0",
                        Histogram.newBuilder()
                                .withName("test")
                                .nativeHistogramOnly()
                                .withNativeSchema(2)
                                .withNativeMaxZeroThreshold(1.2)
                                .withNativeMaxBuckets(4)
                                .build(),
                        0, -1, -1.1, -1.2, -1.4, -1.8, -2, -3, -4),
                new GolangTestCase("'buckets limited by reset, negative observations' from client_golang",
                        "sample_count: 2 " +
                                "sample_sum: -7.0 " +
                                "schema: 2 " +
                                "zero_threshold: 2.9387358770557188E-39 " +
                                "zero_count: 0 " +
                                "negative_span { offset: 7 length: 2 } " +
                                "negative_delta: 1 " +
                                "negative_delta: 0",
                        Histogram.newBuilder()
                                .withName("test")
                                .nativeHistogramOnly()
                                .withNativeSchema(2)
                                .withNativeMaxZeroThreshold(1.2)
                                .withNativeMaxBuckets(4)
                                .build(),
                        0, -1, -1.1, -1.2, -1.4, -1.8, -2, RESET_DURATION_REACHED, -3, -4),
                new GolangTestCase("'buckets limited by halving resolution, then reset' from client_golang",
                        "sample_count: 2 " +
                                "sample_sum: 7.0 " +
                                "schema: 2 " +
                                "zero_threshold: 0.0 " +
                                "zero_count: 0 " +
                                "positive_span { offset: 7 length: 2 } " +
                                "positive_delta: 1 " +
                                "positive_delta: 0",
                        Histogram.newBuilder()
                                .withName("test")
                                .nativeHistogramOnly()
                                .withNativeSchema(2)
                                .withNativeMaxZeroThreshold(0)
                                .withNativeMaxBuckets(4)
                                .build(),
                        0, 1, 1.1, 1.2, 1.4, 1.8, 2, 5, 5.1, RESET_DURATION_REACHED, 3, 4),
                new GolangTestCase("'buckets limited by widening the zero bucket, then reset' from client_golang",
                        "sample_count: 2 " +
                                "sample_sum: 7.0 " +
                                "schema: 2 " +
                                "zero_threshold: 2.9387358770557188E-39 " +
                                "zero_count: 0 " +
                                "positive_span { offset: 7 length: 2 } " +
                                "positive_delta: 1 " +
                                "positive_delta: 0",
                        Histogram.newBuilder()
                                .withName("test")
                                .nativeHistogramOnly()
                                .withNativeSchema(2)
                                .withNativeMaxZeroThreshold(1.2)
                                .withNativeMaxBuckets(4)
                                .build(),
                        0, 1, 1.1, 1.2, 1.4, 1.8, 2, 5, 5.1, RESET_DURATION_REACHED, 3, 4)
        };
        for (GolangTestCase testCase : testCases) {
            testCase.run();
        }
    }

    /**
     * Additional tests that are not part of client_golang's test suite.
     */
    @Test
    public void testAdditional() throws NoSuchFieldException, IllegalAccessException {
        GolangTestCase[] testCases = new GolangTestCase[]{
                new GolangTestCase("observed values are exactly at bucket boundaries",
                        "sample_count: 3 " +
                                "sample_sum: 1.5 " +
                                "schema: 0 " +
                                "zero_threshold: 0.0 " +
                                "zero_count: 1 " +
                                "positive_span { offset: -1 length: 2 } " +
                                "positive_delta: 1 " +
                                "positive_delta: 0",
                        Histogram.newBuilder()
                                .withName("test")
                                .nativeHistogramOnly()
                                .withNativeSchema(0)
                                .withNativeMaxZeroThreshold(0)
                                .build(),
                        0.0, 0.5, 1.0)
        };
        for (GolangTestCase testCase : testCases) {
            testCase.run();
        }
    }

    /**
     * Tests HistogramData.nativeBucketIndexToUpperBound(int, int).
     * <p>
     * This test is ported from client_golang's TestGetLe().
     */
    @Test
    public void testNativeBucketIndexToUpperBound() throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        int[] indexes = new int[]{-1, 0, 1, 512, 513, -1, 0, 1, 1024, 1025, -1, 0, 1, 4096, 4097};
        int[] schemas = new int[]{-1, -1, -1, -1, -1, 0, 0, 0, 0, 0, 2, 2, 2, 2, 2};
        double[] expectedUpperBounds = new double[]{0.25, 1, 4, Double.MAX_VALUE, Double.POSITIVE_INFINITY,
                0.5, 1, 2, Double.MAX_VALUE, Double.POSITIVE_INFINITY,
                0.8408964152537144, 1, 1.189207115002721, Double.MAX_VALUE, Double.POSITIVE_INFINITY};
        Method method = Histogram.HistogramData.class.getDeclaredMethod("nativeBucketIndexToUpperBound", int.class, int.class);
        method.setAccessible(true);
        for (int i = 0; i < indexes.length; i++) {
            Histogram histogram = Histogram.newBuilder()
                    .withName("test")
                    .withNativeSchema(schemas[i])
                    .build();
            Histogram.HistogramData histogramData = histogram.newMetricData();
            double result = (double) method.invoke(histogramData, schemas[i], indexes[i]);
            Assert.assertEquals("index=" + indexes[i] + ", schema=" + schemas[i], expectedUpperBounds[i], result, 0.0000000000001);
        }
    }

    /**
     * Test if lowerBound < value <= upperBound is true for the bucket index returned by findBucketIndex()
     */
    @Test
    public void testFindBucketIndex() throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        Random rand = new Random();
        Method findBucketIndex = Histogram.HistogramData.class.getDeclaredMethod("findBucketIndex", double.class);
        Method nativeBucketIndexToUpperBound = Histogram.HistogramData.class.getDeclaredMethod("nativeBucketIndexToUpperBound", int.class, int.class);
        findBucketIndex.setAccessible(true);
        nativeBucketIndexToUpperBound.setAccessible(true);
        for (int schema = -4; schema <= 8; schema++) {
            Histogram histogram = Histogram.newBuilder()
                    .nativeHistogramOnly()
                    .withName("test")
                    .withNativeSchema(schema)
                    .build();
            System.out.println("growth factor for schema " + schema + " is " + nativeBucketIndexToUpperBound.invoke(histogram.getNoLabels(), schema, 1));
            for (int i = 0; i < 10_000; i++) {
                for (int zeros = -5; zeros <= 10; zeros++) {
                    double value = rand.nextDouble() * Math.pow(10, zeros);
                    int bucketIndex = (int) findBucketIndex.invoke(histogram.getNoLabels(), value);
                    double lowerBound = (double) nativeBucketIndexToUpperBound.invoke(histogram.getNoLabels(), schema, bucketIndex - 1);
                    double upperBound = (double) nativeBucketIndexToUpperBound.invoke(histogram.getNoLabels(), schema, bucketIndex);
                    Assert.assertTrue("Bucket index " + bucketIndex + " with schema " + schema + " has range [" + lowerBound + ", " + upperBound + "]. Value " + value + " is outside of that range.", lowerBound < value && upperBound >= value);
                }
            }
        }
    }

    @Test
    public void testDefaults() {
        Histogram histogram = Histogram.newBuilder().withName("test").build();
        histogram.observe(0.5);
        Metrics.MetricFamily protobufData = new PrometheusProtobufWriter().convert(histogram.collect());
        String expected = "" +
                "name: \"test\" " +
                "type: HISTOGRAM " +
                "metric { " +
                "histogram { " +
                "sample_count: 1 " +
                "sample_sum: 0.5 " +
                // Default should have classic buckets as well as native buckets.
                // Default classic bucket boundaries should be the same as in client_golang.
                "bucket { cumulative_count: 0 upper_bound: 0.005 } " +
                "bucket { cumulative_count: 0 upper_bound: 0.01 } " +
                "bucket { cumulative_count: 0 upper_bound: 0.025 } " +
                "bucket { cumulative_count: 0 upper_bound: 0.05 } " +
                "bucket { cumulative_count: 0 upper_bound: 0.1 } " +
                "bucket { cumulative_count: 0 upper_bound: 0.25 } " +
                "bucket { cumulative_count: 1 upper_bound: 0.5 } " +
                "bucket { cumulative_count: 1 upper_bound: 1.0 } " +
                "bucket { cumulative_count: 1 upper_bound: 2.5 } " +
                "bucket { cumulative_count: 1 upper_bound: 5.0 } " +
                "bucket { cumulative_count: 1 upper_bound: 10.0 } " +
                "bucket { cumulative_count: 1 upper_bound: Infinity } " +
                // default native schema is 5
                "schema: 5 " +
                // default zero threshold is 2^-128
                "zero_threshold: " + Math.pow(2.0, -128.0) + " " +
                "zero_count: 0 " +
                "positive_span { offset: -32 length: 1 } " +
                "positive_delta: 1 " +
                "} }";
        Assert.assertEquals(expected, TextFormat.printer().shortDebugString(protobufData));
    }

    @Test
    public void testExemplarSampler() {

        SpanContextSupplier spanContextSupplier = new SpanContextSupplier() {
            int callCount = 0;

            @Override
            public String getTraceId() {
                return "traceId-" + callCount;
            }

            @Override
            public String getSpanId() {
                return "spanId-" + callCount;
            }

            @Override
            public boolean isSampled() {
                callCount++;
                return true;
            }
        };
        long sampleIntervalMillis = 10;
        Histogram histogram = Histogram.newBuilder()
                .withName("test")
                .withExemplarConfig(ExemplarConfig.newBuilder()
                        .withSpanContextSupplier(spanContextSupplier)
                        .withSampleIntervalMillis(sampleIntervalMillis)
                        .build())
                .withLabelNames("path")
                .build();

        Exemplar ex1 = Exemplar.newBuilder()
                .withValue(3.11)
                .withSpanId("spanId-1")
                .withTraceId("traceId-1")
                .build();
        Exemplar ex2 = Exemplar.newBuilder()
                .withValue(3.12)
                .withSpanId("spanId-2")
                .withTraceId("traceId-2")
                .build();
        Exemplar ex3 = Exemplar.newBuilder()
                .withValue(3.13)
                .withSpanId("spanId-3")
                .withTraceId("traceId-3")
                .withLabels(Labels.of("key1", "value1", "key2", "value2"))
                .build();

        histogram.withLabels("/hello").observe(3.11);
        histogram.withLabels("/world").observe(3.12);
        assertEquals(1, getData(histogram, "path", "/hello").getExemplars().size());
        assertExemplarEquals(ex1, getData(histogram, "path", "/hello").getExemplars().iterator().next());
        assertEquals(1, getData(histogram, "path", "/world").getExemplars().size());
        assertExemplarEquals(ex2, getData(histogram, "path", "/world").getExemplars().iterator().next());
        histogram.withLabels("/world").observeWithExemplar(3.13, Labels.of("key1", "value1", "key2", "value2"));
        assertEquals(1, getData(histogram, "path", "/hello").getExemplars().size());
        assertExemplarEquals(ex1, getData(histogram, "path", "/hello").getExemplars().iterator().next());
        Exemplars exemplars = getData(histogram, "path", "/world").getExemplars();
        List<Exemplar> exemplarList = new ArrayList<>(exemplars.size());
        for (Exemplar exemplar : exemplars) {
            exemplarList.add(exemplar);
        }
        exemplarList.sort(Comparator.comparingDouble(Exemplar::getValue));
        assertEquals(2, exemplars.size());
        assertExemplarEquals(ex2, exemplarList.get(0));
        assertExemplarEquals(ex3, exemplarList.get(1));
    }

    private HistogramSnapshot.HistogramData getData(Histogram histogram, String... labels) {
        return histogram.collect().getData().stream()
                .filter(d -> d.getLabels().equals(Labels.of(labels)))
                .findAny()
                .orElseThrow(() -> new RuntimeException("histogram with labels " + labels + " not found"));
    }
}