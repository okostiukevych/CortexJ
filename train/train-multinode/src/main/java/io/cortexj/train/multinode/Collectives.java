package io.cortexj.train.multinode;

import org.zeromq.SocketType;
import org.zeromq.ZContext;
import org.zeromq.ZMQ;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Phase C collectives (ADR-027): all-gather and reduce-scatter over
 * the same ZeroMQ wire discipline as the Phase B all-reduce (typed
 * errors, worker identity, reconnect retry). Parity-tested against
 * single-node reference math.
 */
public final class Collectives implements AutoCloseable {

    private final ZContext context;
    private final List<String> workerEndpoints;
    private final long timeoutMillis;

    public Collectives(List<String> workerEndpoints, long timeoutMillis) {
        if (workerEndpoints == null || workerEndpoints.isEmpty()) {
            throw new IllegalArgumentException("at least one worker endpoint is required");
        }
        if (timeoutMillis < 1) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        this.context = new ZContext();
        this.workerEndpoints = new ArrayList<>(workerEndpoints);
        this.timeoutMillis = timeoutMillis;
    }

    /**
     * All-gather: every worker contributes its shard; every worker
     * receives the concatenation in worker order.
     */
    public double[][] allGather(List<double[]> shards) throws IOException {
        if (shards == null || shards.size() != workerEndpoints.size()) {
            throw new IllegalArgumentException("one shard per worker is required");
        }
        double[][] gathered = new double[workerEndpoints.size()][];
        for (int w = 0; w < workerEndpoints.size(); w++) {
            gathered[w] = roundTrip(workerEndpoints.get(w), "gather", shards.get(w));
        }
        return gathered;
    }

    /**
     * Reduce-scatter: sums the contributions elementwise, then scatters
     * equal chunks back (the last worker takes the remainder).
     */
    public double[][] reduceScatter(List<double[]> contributions) throws IOException {
        if (contributions == null || contributions.size() != workerEndpoints.size()) {
            throw new IllegalArgumentException("one contribution per worker is required");
        }
        int length = contributions.get(0).length;
        for (double[] contribution : contributions) {
            if (contribution.length != length) {
                throw new IllegalArgumentException("contributions must share the length");
            }
        }
        double[] reduced = new double[length];
        for (double[] contribution : contributions) {
            for (int i = 0; i < length; i++) {
                reduced[i] += contribution[i];
            }
        }
        int workers = workerEndpoints.size();
        int base = length / workers;
        double[][] scattered = new double[workers][];
        for (int w = 0; w < workers; w++) {
            int from = w * base;
            int to = w == workers - 1 ? length : from + base;
            double[] chunk = new double[to - from];
            System.arraycopy(reduced, from, chunk, 0, chunk.length);
            double[] echoed = roundTrip(workerEndpoints.get(w), "scatter", chunk);
            if (echoed.length != chunk.length) {
                throw new IOException("worker " + workerEndpoints.get(w)
                        + " returned " + echoed.length + " values, expected " + chunk.length);
            }
            scattered[w] = echoed;
        }
        return scattered;
    }

    private double[] roundTrip(String endpoint, String op, double[] payload) throws IOException {
        IOException first = call(endpoint, op, payload);
        if (first == null) {
            return lastResult;
        }
        IOException second = call(endpoint, op, payload);
        if (second == null) {
            return lastResult;
        }
        throw new IOException("worker unreachable after retry: " + endpoint
                + " (" + second.getMessage() + ")");
    }

    private double[] lastResult;

    private IOException call(String endpoint, String op, double[] payload) {
        ZMQ.Socket socket = context.createSocket(SocketType.REQ);
        try {
            socket.setReceiveTimeOut((int) Math.min(timeoutMillis, Integer.MAX_VALUE));
            socket.setSendTimeOut((int) Math.min(timeoutMillis, Integer.MAX_VALUE));
            socket.connect(endpoint);
            socket.send(encode(op, payload));
            byte[] reply = socket.recv();
            if (reply == null) {
                return new IOException("worker timeout: " + endpoint);
            }
            String text = new String(reply, StandardCharsets.UTF_8);
            if (text.startsWith("{\"error\"")) {
                return new IOException("worker error: " + text);
            }
            lastResult = decode(text);
            return null;
        } catch (Throwable e) {
            return new IOException("worker call failed: " + endpoint + ": " + e, e);
        } finally {
            socket.close();
        }
    }

    private static String encode(String op, double[] payload) {
        StringBuilder request = new StringBuilder();
        request.append("{\"op\":\"").append(op).append("\",\"values\":[");
        for (int i = 0; i < payload.length; i++) {
            if (i > 0) {
                request.append(',');
            }
            request.append(Double.toString(payload[i]));
        }
        request.append("]}");
        return request.toString();
    }

    private static double[] decode(String text) throws IOException {
        int open = text.indexOf('[');
        int close = text.indexOf(']', open);
        if (open < 0 || close < 0) {
            throw new IOException("malformed worker reply: " + text);
        }
        String[] parts = text.substring(open + 1, close).split(",");
        double[] values = new double[parts.length];
        for (int i = 0; i < parts.length; i++) {
            values[i] = Double.parseDouble(parts[i].trim());
        }
        return values;
    }

    @Override
    public void close() {
        context.close();
    }
}
