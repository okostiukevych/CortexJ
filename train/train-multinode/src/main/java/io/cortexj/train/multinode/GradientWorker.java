package io.cortexj.train.multinode;

import org.zeromq.SocketType;
import org.zeromq.ZContext;
import org.zeromq.ZMQ;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Phase B worker (ADR-027): a REP server computing one shard gradient
 * of the reference loss {@code mean((w·x)^2)} — the same math as Phase A,
 * so the multi-node average is parity-equal to the single-worker batch.
 */
public final class GradientWorker implements AutoCloseable {

    private final ZContext context;
    private final ZMQ.Socket socket;
    private final int port;
    private final AtomicBoolean running = new AtomicBoolean();
    private Thread worker;

    public GradientWorker() {
        this.context = new ZContext();
        this.socket = context.createSocket(SocketType.REP);
        this.socket.setReceiveTimeOut(200);
        this.port = socket.bindToRandomPort("tcp://127.0.0.1");
    }

    public String endpoint() {
        return "tcp://127.0.0.1:" + port;
    }

    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        worker = new Thread(() -> {
            try {
                while (running.get() && !Thread.currentThread().isInterrupted()) {
                    byte[] request = socket.recv();
                    if (request == null) {
                        if (!running.get() || Thread.currentThread().isInterrupted()) {
                            break;
                        }
                        continue;
                    }
                    try {
                        socket.send(serve(new String(request, StandardCharsets.UTF_8)));
                    } catch (Throwable e) {
                        socket.send("{\"error\":" + quote(String.valueOf(e.getMessage())) + "}");
                    }
                }
            } catch (org.zeromq.ZMQException interrupted) {
                // close() interrupts the worker mid-recv: that is a normal stop,
                // anything else while still running is a real failure
                if (running.get() && !Thread.currentThread().isInterrupted()) {
                    throw interrupted;
                }
            } finally {
                socket.close();
            }
        }, "cortexj-gradient-worker");
        worker.setDaemon(true);
        worker.start();
    }

    private static String serve(String request) {
        String op = stringField(request, "op", "gradient");
        if ("gather".equals(op) || "scatter".equals(op)) {
            // collective echo: the driver already holds the values;
            // the worker acknowledges receipt (wire parity contract)
            return "{\"values\":" + array(doubles(request, "values")) + "}";
        }
        double[] weights = doubles(request, "weights");
        double[] shard = doubles(request, "shard");
        double rows = shard.length;
        double[] gradient = new double[weights.length];
        for (double x : shard) {
            double prediction = 0;
            for (double weight : weights) {
                prediction += weight * x;
            }
            for (int p = 0; p < weights.length; p++) {
                gradient[p] += 2 * prediction * x / rows;
            }
        }
        StringBuilder reply = new StringBuilder();
        reply.append("{\"gradient\":[");
        for (int i = 0; i < gradient.length; i++) {
            if (i > 0) {
                reply.append(',');
            }
            reply.append(Double.toString(gradient[i]));
        }
        reply.append("]}");
        return reply.toString();
    }

    private static double[] doubles(String json, String field) {
        int at = json.indexOf("\"" + field + "\"");
        if (at < 0) {
            throw new IllegalArgumentException("missing field: " + field);
        }
        int open = json.indexOf('[', at);
        int close = json.indexOf(']', open);
        String[] parts = json.substring(open + 1, close).split(",");
        double[] values = new double[parts.length];
        for (int i = 0; i < parts.length; i++) {
            values[i] = Double.parseDouble(parts[i].trim());
        }
        return values;
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String stringField(String json, String field, String fallback) {
        int at = json.indexOf("\"" + field + "\"");
        if (at < 0) {
            return fallback;
        }
        int colon = json.indexOf(':', at);
        int open = json.indexOf('"', colon);
        int close = json.indexOf('"', open + 1);
        return json.substring(open + 1, close);
    }

    private static String array(double[] values) {
        StringBuilder text = new StringBuilder();
        text.append('[');
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                text.append(',');
            }
            text.append(Double.toString(values[i]));
        }
        text.append(']');
        return text.toString();
    }

    @Override
    public void close() {
        boolean wasRunning = running.getAndSet(false);
        if (worker != null) {
            worker.interrupt();
            try {
                worker.join(2_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        } else if (!wasRunning) {
            socket.close();
        }
        context.close();
    }
}
