package io.cortexj.notebook;

import io.cortexj.lab.LabSession;
import org.junit.jupiter.api.Test;
import org.zeromq.SocketType;
import org.zeromq.ZContext;
import org.zeromq.ZMQ;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Live in-process contract: kernel_info and execute_request over real
 * sockets, the reply verified against the wire signature.
 */
class JupyterKernelLiveTest {

    @Test
    void kernelAnswersKernelInfoAndExecute() throws Exception {
        try (LabSession session = LabSession.create();
             ZContext client = new ZContext();
             JupyterKernel kernel = new JupyterKernel(
                     session, "live-key".getBytes(StandardCharsets.US_ASCII),
                     -1, -1, -1)) {
            kernel.start();
            assertTrue(kernel.heartbeatPort() > 0);
            assertTrue(kernel.shellPort() > 0);

            ZMQ.Socket shell = client.createSocket(SocketType.DEALER);
            ZMQ.Socket iopub = client.createSocket(SocketType.SUB);
            shell.connect("tcp://127.0.0.1:" + kernel.shellPort());
            iopub.connect("tcp://127.0.0.1:" + kernel.iopubPort());
            iopub.subscribe("kernel.".getBytes(StandardCharsets.US_ASCII));
            // SUB slow-joiner: give the subscription time to propagate
            Thread.sleep(300);

            // kernel_info
            WireMessage request = WireMessage.reply(null, "kernel_info_request", "{}");
            send(shell, request);
            WireMessage reply = receive(shell);
            assertEquals("kernel_info_reply", reply.messageType());
            assertEquals(request.messageId(), reply.parentMessageId());
            assertTrue(reply.content().contains("\"cortexj\""), reply.content());

            // is_complete: balanced code is complete, open bracket incomplete
            WireMessage complete = WireMessage.reply(null, "is_complete_request",
                    "{\"code\":\"1 + 2\"}");
            send(shell, complete);
            WireMessage completeReply = receive(shell);
            assertEquals("is_complete_reply", completeReply.messageType());
            assertTrue(completeReply.content().contains("complete")
                            && !completeReply.content().contains("incomplete"),
                    completeReply.content());
            WireMessage open = WireMessage.reply(null, "is_complete_request",
                    "{\"code\":\"foo(\"}");
            send(shell, open);
            WireMessage openReply = receive(shell);
            assertTrue(openReply.content().contains("incomplete"), openReply.content());

            // control socket: interrupt is answered without stopping
            org.zeromq.ZMQ.Socket control =
                    client.createSocket(org.zeromq.SocketType.DEALER);
            control.connect("tcp://127.0.0.1:" + kernel.controlPort());
            WireMessage interrupt = WireMessage.reply(null, "interrupt_request", "{}");
            send(control, interrupt);
            WireMessage interruptReply = receive(control);
            assertEquals("interrupt_reply", interruptReply.messageType());
            assertTrue(interruptReply.content().contains("ok"), interruptReply.content());

            // connection file round-trip
            java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("kernel-conn");
            java.nio.file.Path connectionFile = kernel.writeConnectionFile(dir);
            String connection = java.nio.file.Files.readString(connectionFile);
            assertTrue(connection.contains("\"shell_port\":" + kernel.shellPort()), connection);
            assertTrue(connection.contains("\"stdin_port\":" + kernel.stdinPort()), connection);
            assertTrue(connection.contains("hmac-sha256"), connection);

            // execute 1 + 2
            WireMessage execute = WireMessage.reply(null, "execute_request",
                    "{\"code\":\"1 + 2\",\"silent\":false}");
            send(shell, execute);
            WireMessage executeReply = receive(shell);
            assertEquals("execute_reply", executeReply.messageType());
            assertTrue(executeReply.content().contains("\"ok\""), executeReply.content());
            // execute_result arrives on iopub
            AtomicReference<String> resultContent = new AtomicReference<>();
            CountDownLatch resultLatch = new CountDownLatch(1);
            Thread reader = new Thread(() -> {
                try {
                    for (int attempts = 0; attempts < 20 && !resultLatch.await(100, TimeUnit.MILLISECONDS)
                            && resultContent.get() == null; attempts++) {
                        List<byte[]> frames = receiveRouted(iopub);
                        if (frames != null && frames.size() >= 7) {
                            String topic = new String(frames.get(0), StandardCharsets.UTF_8);
                            if (topic.contains("execute_result")) {
                                resultContent.set(new String(frames.get(6),
                                        StandardCharsets.UTF_8));
                            }
                        }
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            });
            reader.start();
            reader.join(5000);
            assertNotNull(resultContent.get(), "execute_result must arrive on iopub");
            assertTrue(resultContent.get().contains("\"3\""), resultContent.get());
        }
    }

    private static void send(ZMQ.Socket shell, WireMessage message) {
        shell.send(new byte[0], ZMQ.SNDMORE);
        shell.send(WireMessage.DELIMITER, ZMQ.SNDMORE);
        shell.send(message.signature("live-key".getBytes(StandardCharsets.US_ASCII))
                .getBytes(StandardCharsets.US_ASCII), ZMQ.SNDMORE);
        shell.send(message.header().getBytes(StandardCharsets.UTF_8), ZMQ.SNDMORE);
        shell.send(message.parentHeader().getBytes(StandardCharsets.UTF_8), ZMQ.SNDMORE);
        shell.send(message.metadata().getBytes(StandardCharsets.UTF_8), ZMQ.SNDMORE);
        shell.send(message.content().getBytes(StandardCharsets.UTF_8));
    }

    private static WireMessage receive(ZMQ.Socket socket) {
        List<byte[]> frames = receiveRouted(socket);
        return frames == null ? null : new WireMessage(
                new String(frames.get(3), StandardCharsets.UTF_8),
                new String(frames.get(4), StandardCharsets.UTF_8),
                new String(frames.get(5), StandardCharsets.UTF_8),
                new String(frames.get(6), StandardCharsets.UTF_8),
                List.of());
    }

    private static List<byte[]> receiveRouted(ZMQ.Socket socket) {
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
                for (int i = 0; i < 5; i++) {
                    frames.add(socket.recv());
                }
                return frames;
            }
            frames.add(frame);
        }
    }
}
