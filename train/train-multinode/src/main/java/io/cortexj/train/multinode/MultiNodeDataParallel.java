package io.cortexj.train.multinode;

import org.zeromq.SocketType;
import org.zeromq.ZContext;
import org.zeromq.ZMQ;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Phase B driver (ADR-027): multi-node data-parallel gradient
 * averaging. The driver scatters weights+shards to worker endpoints,
 * each worker computes its shard gradient (same reference loss as
 * Phase A), the driver all-reduces by averaging. A dead worker fails
 * typed with its identity after one reconnect retry — never silent.
 */
public final class MultiNodeDataParallel implements AutoCloseable {

    private final ZContext context;
    private final List<String> workerEndpoints;
    private final long timeoutMillis;

    public MultiNodeDataParallel(List<String> workerEndpoints, long timeoutMillis) {
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

    public int workerCount() {
        return workerEndpoints.size();
    }

    @Override
    public void close() {
        context.close();
    }

    /**
     * Averages the per-worker gradients of the reference loss over the
     * batch shards. Must equal the single-worker full-batch gradient
     * (the Phase B parity contract).
     */
    public double[] gradients(double[] weights, double[][] shards) throws IOException {
        if (weights == null || weights.length == 0) {
            throw new IllegalArgumentException("weights must not be empty");
        }
        if (shards == null || shards.length != workerEndpoints.size()) {
            throw new IllegalArgumentException("one shard per worker is required");
        }
        int totalRows = 0;
        for (double[] shard : shards) {
            if (shard != null) {
                totalRows += shard.length;
            }
        }
        if (totalRows == 0) {
            throw new IllegalArgumentException("batch must not be empty");
        }
        double[] averaged = new double[weights.length];
        for (int w = 0; w < workerEndpoints.size(); w++) {
            double[] shard = shards[w];
            if (shard == null || shard.length == 0) {
                continue;
            }
            double shardWeight = (double) shard.length / totalRows;
            double[] shardGradient = gradientOnce(workerEndpoints.get(w), weights, shard);
            for (int p = 0; p < weights.length; p++) {
                averaged[p] += shardGradient[p] * shardWeight;
            }
        }
        return averaged;
    }

    private double[] gradientOnce(String endpoint, double[] weights, double[] shard)
            throws IOException {
        try {
            return requestGradient(endpoint, weights, shard);
        } catch (IOException first) {
            // one reconnect retry (fault semantics, ADR-027), then typed failure
            try {
                return requestGradient(endpoint, weights, shard);
            } catch (IOException second) {
                throw new IOException("worker unreachable after retry: " + endpoint
                        + " (" + second.getMessage() + ")", second);
            }
        }
    }

    private double[] requestGradient(String endpoint, double[] weights, double[] shard)
            throws IOException {
        ZMQ.Socket socket;
        synchronized (context) {
            socket = context.createSocket(SocketType.REQ);
        }
        try {
            socket.setReceiveTimeOut((int) Math.min(timeoutMillis, Integer.MAX_VALUE));
            socket.setSendTimeOut((int) Math.min(timeoutMillis, Integer.MAX_VALUE));
            socket.connect(endpoint);
            socket.send(encodeRequest(weights, shard));
            byte[] reply = socket.recv();
            if (reply == null) {
                throw new IOException("worker timeout: " + endpoint);
            }
            String text = new String(reply, java.nio.charset.StandardCharsets.UTF_8);
            if (text.startsWith("{\"error\"")) {
                throw new IOException("worker error: " + text);
            }
            return decodeGradient(text, weights.length);
        } catch (IOException e) {
            throw e;
        } catch (Throwable e) {
            throw new IOException("worker call failed: " + endpoint + ": " + e, e);
        } finally {
            synchronized (context) {
                socket.close();
            }
        }
    }

    private static String encodeRequest(double[] weights, double[] shard) {
        StringBuilder request = new StringBuilder();
        request.append("{\"op\":\"gradient\",\"weights\":[");
        appendDoubles(request, weights);
        request.append("],\"shard\":[");
        appendDoubles(request, shard);
        request.append("]}");
        return request.toString();
    }

    private static void appendDoubles(StringBuilder target, double[] values) {
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                target.append(',');
            }
            target.append(Double.toString(values[i]));
        }
    }

    private static double[] decodeGradient(String text, int parameters) throws IOException {
        int at = text.indexOf("\"gradient\"");
        if (at < 0) {
            throw new IOException("malformed worker reply: " + text);
        }
        int open = text.indexOf('[', at);
        int close = text.indexOf(']', open);
        String[] parts = text.substring(open + 1, close).split(",");
        if (parts.length != parameters) {
            throw new IOException("worker gradient has " + parts.length
                    + " values, expected " + parameters);
        }
        double[] gradient = new double[parameters];
        for (int i = 0; i < parameters; i++) {
            gradient[i] = Double.parseDouble(parts[i].trim());
        }
        return gradient;
    }
}
