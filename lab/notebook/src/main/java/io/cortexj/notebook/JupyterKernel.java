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
    private final ZMQ.Socket stdin;
    private final ZMQ.Socket control;
    private final AtomicBoolean running = new AtomicBoolean();
    private final List<Thread> workers = new ArrayList<>();
    private final String sessionId = java.util.UUID.randomUUID().toString();

    public JupyterKernel(LabSession session, byte[] key,
                         int heartbeatPort, int shellPort, int iopubPort) {
        this(session, key, heartbeatPort, shellPort, iopubPort, -1);
    }

    public JupyterKernel(LabSession session, byte[] key,
                         int heartbeatPort, int shellPort, int iopubPort, int stdinPort) {
        this(session, key, heartbeatPort, shellPort, iopubPort, stdinPort, -1);
    }

    public JupyterKernel(LabSession session, byte[] key,
                         int heartbeatPort, int shellPort, int iopubPort, int stdinPort,
                         int controlPort) {
        this.session = session;
        // audit 2026-10-10 (SpotBugs EI_EXPOSE_REP2): own a private copy of
        // the HMAC key - the caller's array must not alias kernel state
        this.key = key == null ? new byte[0]
                : java.util.Arrays.copyOf(key, key.length);
        this.context = new ZContext();
        this.heartbeat = context.createSocket(SocketType.REP);
        this.shell = context.createSocket(SocketType.ROUTER);
        this.iopub = context.createSocket(SocketType.PUB);
        this.stdin = context.createSocket(SocketType.ROUTER);
        this.control = context.createSocket(SocketType.ROUTER);
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
        this.stdinPort = stdinPort < 0
                ? this.stdin.bindToRandomPort("tcp://127.0.0.1")
                : bindFixed(this.stdin, stdinPort);
        this.controlPort = controlPort < 0
                ? this.control.bindToRandomPort("tcp://127.0.0.1")
                : bindFixed(this.control, controlPort);
    }

    private static int bindFixed(ZMQ.Socket socket, int port) {
        socket.bind("tcp://127.0.0.1:" + port);
        return port;
    }

    private final int heartbeatPort;
    private final int shellPort;
    private final int iopubPort;
    private final int stdinPort;
    private final int controlPort;

    public int heartbeatPort() {
        return heartbeatPort;
    }

    public int shellPort() {
        return shellPort;
    }

    public int iopubPort() {
        return iopubPort;
    }

    public int stdinPort() {
        return stdinPort;
    }

    public int controlPort() {
        return controlPort;
    }

    /**
     * Writes the Jupyter connection file (kernel.json) for this kernel:
     * transport, ip, the five ports, signature scheme and key.
     */
    public java.nio.file.Path writeConnectionFile(java.nio.file.Path directory) throws java.io.IOException {
        if (directory == null) {
            throw new IllegalArgumentException("directory must not be null");
        }
        java.nio.file.Files.createDirectories(directory);
        String json = "{"
                + "\"transport\":\"tcp\","
                + "\"ip\":\"127.0.0.1\","
                + "\"shell_port\":" + shellPort + ","
                + "\"iopub_port\":" + iopubPort + ","
                + "\"stdin_port\":" + stdinPort + ","
                + "\"control_port\":" + controlPort + ","
                + "\"hb_port\":" + heartbeatPort + ","
                + "\"signature_scheme\":\"hmac-sha256\","
                + "\"key\":" + WireMessage.quoteValue(new String(key, java.nio.charset.StandardCharsets.US_ASCII))
                + "}";
        java.nio.file.Path file = directory.resolve("kernel.json");
        java.nio.file.Files.writeString(file, json, java.nio.charset.StandardCharsets.UTF_8);
        return file;
    }

    /**
     * Code completeness for is_complete_request: balanced brackets and
     * quotes means complete; anything else is incomplete (the frontend
     * keeps the cell open). A heuristic, documented as such.
     */
    static String completenessOf(String code) {
        if (code == null || code.trim().isEmpty()) {
            return "complete";
        }
        int round = 0;
        int square = 0;
        int curly = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < code.length(); i++) {
            char ch = code.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (ch == '\\') {
                escaped = true;
                continue;
            }
            if (ch == '"') {
                inString = !inString;
                continue;
            }
            if (inString) {
                continue;
            }
            switch (ch) {
                case '(':
                    round++;
                    break;
                case ')':
                    round--;
                    break;
                case '[':
                    square++;
                    break;
                case ']':
                    square--;
                    break;
                case '{':
                    curly++;
                    break;
                case '}':
                    curly--;
                    break;
                default:
                    break;
            }
            if (round < 0 || square < 0 || curly < 0) {
                return "invalid";
            }
        }
        if (inString || round > 0 || square > 0 || curly > 0) {
            return "incomplete";
        }
        return "complete";
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
        Thread controlWorker = new Thread(this::controlLoop, "cortexj-kernel-control");
        controlWorker.setDaemon(true);
        workers.add(controlWorker);
        controlWorker.start();
    }

    private void controlLoop() {
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            List<byte[]> frames = receiveRouted(control);
            if (frames == null) {
                break;
            }
            try {
                handleControl(frames);
            } catch (Throwable e) {
                // a handler failure must not kill the control worker
            }
        }
    }

    private void handleControl(List<byte[]> frames) {
        int delimiterAt = -1;
        for (int i = 0; i < frames.size(); i++) {
            if (java.util.Arrays.equals(frames.get(i), WireMessage.delimiter())) {
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
            case "shutdown_request": {
                boolean restart = message.content().contains("\"restart\":true");
                sendFrames(control, routing, WireMessage.reply(message, "shutdown_reply",
                        "{\"status\":\"ok\",\"restart\":" + restart + "}"));
                if (!restart) {
                    close();
                }
                break;
            }
            case "interrupt_request":
                sendFrames(control, routing, WireMessage.reply(message, "interrupt_reply",
                        "{\"status\":\"ok\"}"));
                break;
            default:
                sendFrames(control, routing, WireMessage.reply(message, "error_reply",
                        "{\"status\":\"error\",\"ename\":\"NotImplemented\","
                                + "\"evalue\":\"" + message.messageType() + "\"}"));
        }
    }

    private void sendFrames(ZMQ.Socket socket, List<byte[]> routing, WireMessage message) {
        // the ROUTER routes by the leading identity frames: echo them back
        // in the received order
        for (byte[] routingFrame : routing) {
            socket.send(routingFrame, ZMQ.SNDMORE);
        }
        socket.send(WireMessage.delimiter(), ZMQ.SNDMORE);
        socket.send(message.signature(key).getBytes(java.nio.charset.StandardCharsets.US_ASCII), ZMQ.SNDMORE);
        socket.send(message.header().getBytes(java.nio.charset.StandardCharsets.UTF_8), ZMQ.SNDMORE);
        socket.send(message.parentHeader().getBytes(java.nio.charset.StandardCharsets.UTF_8), ZMQ.SNDMORE);
        socket.send(message.metadata().getBytes(java.nio.charset.StandardCharsets.UTF_8), ZMQ.SNDMORE);
        socket.send(message.content().getBytes(java.nio.charset.StandardCharsets.UTF_8));
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
            if (java.util.Arrays.equals(frame, WireMessage.delimiter())) {
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
            if (java.util.Arrays.equals(frames.get(i), WireMessage.delimiter())) {
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
            case "is_complete_request": {
                String code = stringValue(message.content(), "code", "");
                send(routing, WireMessage.reply(message, "is_complete_reply",
                        "{\"status\":\"" + completenessOf(code) + "\"}"));
                break;
            }
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
                topic("status"), WireMessage.delimiter(),
                status.signature(key).getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                status.header().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                status.parentHeader().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                status.metadata().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                status.content().getBytes(java.nio.charset.StandardCharsets.UTF_8)};
        sendFrames(iopub, statusFrames);
    }

    private void publish(List<byte[]> routing, WireMessage message) {
        byte[][] resultFrames = {
                topic(message.messageType()), WireMessage.delimiter(),
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
        sendFrames(shell, routing, message);
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
        stdin.close();
        control.close();
        context.close();
        session.close();
    }
}
