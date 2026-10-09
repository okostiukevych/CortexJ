package io.cortexj.notebook;

import io.cortexj.lab.LabSession;
import io.cortexj.lab.SnippetResult;
import org.zeromq.SocketType;
import org.zeromq.ZContext;
import org.zeromq.ZMQ;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Jupyter kernel adapter (ADR-018): the ZeroMQ wire protocol with the
 * {@link LabSession} behind execute_request. Sockets: heartbeat (REP),
 * shell (ROUTER), iopub (PUB). Handles kernel_info, execute_request
 * (execute_result/error on iopub + reply on shell), status.
 */
public final class JupyterKernel implements AutoCloseable {

    private final byte[] key;
    private final LabSession session;
    private final ZContext context;
    private final ZMQ.Socket heartbeat;
    private final ZMQ.Socket shell;
    private final ZMQ.Socket iopub;
    private final AtomicBoolean running = new AtomicBoolean();
    private final List<Thread> workers = new ArrayList<>();
    private final String sessionId = java.util.UUID.randomUUID().toString();

    public JupyterKernel(LabSession session, byte[] key,
                         int heartbeatPort, int shellPort, int iopubPort) {
        this.session = session;
        this.key = key;
        this.context = new ZContext();
        this.heartbeat = context.createSocket(SocketType.REP);
        this.shell = context.createSocket(SocketType.ROUTER);
        this.iopub = context.createSocket(SocketType.PUB);
        // port < 0 binds an ephemeral port; bindToRandomPort returns it
        this.heartbeatPort = heartbeatPort < 0
                ? this.heartbeat.bindToRandomPort("tcp://127.0.0.1")
                : bindFixed(this.heartbeat, heartbeatPort);
        this.shellPort = shellPort < 0
                ? this.shell.bindToRandomPort("tcp://127.0.0.1")
                : bindFixed(this.shell, shellPort);
        this.iopubPort = iopubPort < 0
                ? this.iopub.bindToRandomPort("tcp://127.0.0.1")
                : bindFixed(this.iopub, iopubPort);
    }

    private static int bindFixed(ZMQ.Socket socket, int port) {
        socket.bind("tcp://127.0.0.1:" + port);
        return port;
    }

    private final int heartbeatPort;
    private final int shellPort;
    private final int iopubPort;

    public int heartbeatPort() {
        return heartbeatPort;
    }

    public int shellPort() {
        return shellPort;
    }

    public int iopubPort() {
        return iopubPort;
    }

    /**
     * Starts the heartbeat and shell workers.
     */
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        Thread heart = new Thread(this::heartbeatLoop, "cortexj-kernel-heartbeat");
        heart.setDaemon(true);
        workers.add(heart);
        heart.start();
        Thread shellWorker = new Thread(this::shellLoop, "cortexj-kernel-shell");
        shellWorker.setDaemon(true);
        workers.add(shellWorker);
        shellWorker.start();
    }

    private void heartbeatLoop() {
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            byte[] ping = heartbeat.recv();
            if (ping == null) {
                break;
            }
            heartbeat.send(ping);
        }
    }

    private void shellLoop() {
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            List<byte[]> frames = receiveRouted(shell);
            if (frames == null) {
                break;
            }
            try {
                handleShell(frames);
            } catch (Throwable e) {
                // a handler failure must not kill the shell worker
            }
        }
    }

    private List<byte[]> receiveRouted(ZMQ.Socket socket) {
        List<byte[]> frames = new ArrayList<>();
        byte[] routing = socket.recv();
        if (routing == null) {
            return null;
        }
        frames.add(routing);
        while (true) {
            byte[] frame = socket.recv();
            if (frame == null) {
                return null;
            }
            if (java.util.Arrays.equals(frame, WireMessage.DELIMITER)) {
                frames.add(frame);
                // signature + 4 JSON parts
                for (int i = 0; i < 5; i++) {
                    frames.add(socket.recv());
                }
                return frames;
            }
            frames.add(frame);
        }
    }

    private void handleShell(List<byte[]> frames) {
        // frames: [routing..., delimiter, signature, header, parent, metadata,
        // content] - every frame before the delimiter is a routing identity
        // (the DEALER prefix plus the client's own routing frames)
        int delimiterAt = -1;
        for (int i = 0; i < frames.size(); i++) {
            if (java.util.Arrays.equals(frames.get(i), WireMessage.DELIMITER)) {
                delimiterAt = i;
                break;
            }
        }
        if (delimiterAt < 0 || frames.size() < delimiterAt + 6) {
            return;
        }
        WireMessage message = new WireMessage(
                new String(frames.get(delimiterAt + 2), java.nio.charset.StandardCharsets.UTF_8),
                new String(frames.get(delimiterAt + 3), java.nio.charset.StandardCharsets.UTF_8),
                new String(frames.get(delimiterAt + 4), java.nio.charset.StandardCharsets.UTF_8),
                new String(frames.get(delimiterAt + 5), java.nio.charset.StandardCharsets.UTF_8),
                List.of());
        List<byte[]> routing = new ArrayList<>(frames.subList(0, delimiterAt));
        switch (message.messageType()) {
            case "kernel_info_request":
                send(routing, WireMessage.reply(message, "kernel_info_reply",
                        "{\"protocol_version\":\"5.3\",\"implementation\":\"cortexj\","
                                + "\"implementation_version\":\"0.1.0\","
                                + "\"language_info\":{\"name\":\"java\",\"version\":\""
                                + System.getProperty("java.version") + "\"},"
                                + "\"status\":\"ok\"}"));
                break;
            case "execute_request":
                executeAndPublish(routing, message);
                break;
            default:
                send(routing, WireMessage.reply(message, "error_reply",
                        "{\"status\":\"error\",\"ename\":\"NotImplemented\","
                                + "\"evalue\":\"" + message.messageType() + "\"}"));
        }
    }

    private void executeAndPublish(List<byte[]> routing, WireMessage request) {
        String code = stringValue(request.content(), "code", "");
        publishStatus(request, "busy");
        SnippetResult result = session.eval(code);
        if (result.hasErrors()) {
            send(routing, WireMessage.reply(request, "execute_reply",
                    "{\"status\":\"error\",\"ename\":\"EvalError\",\"evalue\":"
                            + WireMessage.quoteValue(first(result.errors())) + ",\"execution_count\":1}"));
            publishStatus(request, "idle");
            return;
        }
        // execute_result on iopub
        WireMessage resultMessage = WireMessage.reply(request, "execute_result",
                "{\"execution_count\":1,\"data\":{\"text/plain\":"
                        + WireMessage.quoteValue(result.value()) + "},\"metadata\":{}}");
        publish(routing, resultMessage);
        send(routing, WireMessage.reply(request, "execute_reply",
                "{\"status\":\"ok\",\"execution_count\":1}"));
        publishStatus(request, "idle");
    }

    private void publishStatus(WireMessage request, String state) {
        WireMessage status = WireMessage.reply(request, "status",
                "{\"execution_state\":\"" + state + "\"}");
        byte[][] statusFrames = {
                topic("status"), WireMessage.DELIMITER,
                status.signature(key).getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                status.header().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                status.parentHeader().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                status.metadata().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                status.content().getBytes(java.nio.charset.StandardCharsets.UTF_8)};
        sendFrames(iopub, statusFrames);
    }

    private void publish(List<byte[]> routing, WireMessage message) {
        byte[][] resultFrames = {
                topic(message.messageType()), WireMessage.DELIMITER,
                message.signature(key).getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                message.header().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                message.parentHeader().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                message.metadata().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                message.content().getBytes(java.nio.charset.StandardCharsets.UTF_8)};
        sendFrames(iopub, resultFrames);
    }

    private void sendFrames(ZMQ.Socket socket, byte[][] frames) {
        for (int i = 0; i < frames.length; i++) {
            socket.send(frames[i], i < frames.length - 1 ? ZMQ.SNDMORE : 0);
        }
    }

    private void send(List<byte[]> routing, WireMessage message) {
        // the ROUTER routes by the leading identity frames: echo them back
        // in the received order
        for (byte[] routingFrame : routing) {
            shell.send(routingFrame, ZMQ.SNDMORE);
        }
        shell.send(WireMessage.DELIMITER, ZMQ.SNDMORE);
        shell.send(message.signature(key).getBytes(java.nio.charset.StandardCharsets.US_ASCII), ZMQ.SNDMORE);
        shell.send(message.header().getBytes(java.nio.charset.StandardCharsets.UTF_8), ZMQ.SNDMORE);
        shell.send(message.parentHeader().getBytes(java.nio.charset.StandardCharsets.UTF_8), ZMQ.SNDMORE);
        shell.send(message.metadata().getBytes(java.nio.charset.StandardCharsets.UTF_8), ZMQ.SNDMORE);
        shell.send(message.content().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static byte[] topic(String messageType) {
        return ("kernel." + messageType).getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    }

    private static String first(List<String> values) {
        return values.isEmpty() ? "" : values.get(0);
    }

    private static String stringValue(String json, String field, String fallback) {
        int at = json.indexOf("\"" + field + "\"");
        if (at < 0) {
            return fallback;
        }
        int colon = json.indexOf(':', at);
        int open = json.indexOf('"', colon);
        int close = json.indexOf('"', open + 1);
        return json.substring(open + 1, close);
    }

    @Override
    public void close() {
        running.set(false);
        for (Thread worker : workers) {
            worker.interrupt();
        }
        heartbeat.close();
        shell.close();
        iopub.close();
        context.close();
        session.close();
    }
}
