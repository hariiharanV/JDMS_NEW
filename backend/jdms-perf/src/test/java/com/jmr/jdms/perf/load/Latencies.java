package com.jmr.jdms.perf.load;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Wall-clock latencies that span several requests and waits (upload until searchable, approval until revoked), which
 * Gatling's request statistics do not measure. Written next to the Gatling reports as
 * {@code target/gatling/<simulation>-latencies.json} with a pass or fail per target.
 */
final class Latencies {

    private final String simulation;
    private final Map<String, List<Long>> samples = new ConcurrentHashMap<>();
    private final Map<String, Long> targets = new ConcurrentHashMap<>();
    private final Map<String, Integer> misses = new ConcurrentHashMap<>();

    Latencies(String simulation) {
        this.simulation = simulation;
    }

    /** A target: the metric's p95 must be below {@code p95Millis}. */
    Latencies target(String metric, long p95Millis) {
        targets.put(metric, p95Millis);
        return this;
    }

    void record(String metric, long millis) {
        samples.computeIfAbsent(metric, key -> new CopyOnWriteArrayList<>()).add(millis);
    }

    /** Something that never happened within the wait limit (counted, and fails the metric). */
    void missed(String metric) {
        misses.merge(metric, 1, Integer::sum);
    }

    /** Writes the summary and prints it; returns whether every target was met. */
    boolean report() {
        Map<String, Map<String, Object>> summary = new TreeMap<>();
        boolean allMet = true;
        for (String metric : union()) {
            List<Long> values = new ArrayList<>(samples.getOrDefault(metric, List.of()));
            Collections.sort(values);
            Map<String, Object> line = new TreeMap<>();
            line.put("count", values.size());
            line.put("missed", misses.getOrDefault(metric, 0));
            if (!values.isEmpty()) {
                line.put("p50_ms", percentile(values, 50));
                line.put("p95_ms", percentile(values, 95));
                line.put("max_ms", values.getLast());
            }
            Long target = targets.get(metric);
            if (target != null) {
                boolean met = !values.isEmpty() && misses.getOrDefault(metric, 0) == 0
                        && percentile(values, 95) < target;
                line.put("target_p95_ms", target);
                line.put("met", met);
                allMet &= met;
            }
            summary.put(metric, line);
        }
        StringBuilder json = new StringBuilder("{\n");
        summary.forEach((metric, line) -> json.append("  \"").append(metric).append("\": ").append(toJson(line))
                .append(",\n"));
        if (json.length() > 2) {
            json.setLength(json.length() - 2);
        }
        json.append("\n}\n");
        try {
            Path out = Path.of(System.getProperty("jdms.perf.out", "target/gatling"), simulation + "-latencies.json");
            Files.createDirectories(out.getParent());
            Files.writeString(out, json);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        System.out.println("---- Latencies (" + simulation + ") ----\n" + json);
        return allMet;
    }

    private List<String> union() {
        List<String> names = new ArrayList<>(samples.keySet());
        targets.keySet().stream().filter(name -> !names.contains(name)).forEach(names::add);
        misses.keySet().stream().filter(name -> !names.contains(name)).forEach(names::add);
        return names;
    }

    private static long percentile(List<Long> sorted, int percentile) {
        int index = (int) Math.ceil(percentile / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
    }

    private static String toJson(Map<String, Object> line) {
        StringBuilder out = new StringBuilder("{");
        line.forEach((key, value) -> out.append('"').append(key).append("\": ").append(value).append(", "));
        out.setLength(out.length() - 2);
        return out.append('}').toString();
    }
}
