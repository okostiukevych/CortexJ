package io.cortexj.server;

import io.cortexj.CortexJ;
import io.cortexj.model.ModelHandle;
import io.cortexj.model.ModelReference;

import java.util.concurrent.CountDownLatch;

/**
 * Entrypoint for the optional embedded server mode (spec 60):
 *
 * <pre>
 *   java -Dcortexj.server.port=8080 -jar ... Qwen/Qwen3-8B
 * </pre>
 */
public final class CortexJServerMain {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("usage: CortexJServerMain <model-reference> [port]");
            System.exit(1);
        }
        String modelReference = args[0];
        int port = args.length > 1
                ? Integer.parseInt(args[1])
                : Integer.parseInt(System.getProperty("cortexj.server.port", "8080"));

        CortexJ platform = CortexJ.builder().build();
        try {
            CortexJServer server = new CortexJServer(platform, modelReference);
            int bound = server.start(port);
            System.out.println("cortexj-server listening on http://127.0.0.1:" + bound
                    + " (model: " + modelReference + ")");
            Runtime.getRuntime().addShutdownHook(shutdownHook(server, platform));
            CountDownLatch keepAlive = new CountDownLatch(1);
            keepAlive.await();
        } finally {
            platform.close();
        }
    }

    public static Thread shutdownHook(final CortexJServer server, final CortexJ platform) {
        return new Thread(new Runnable() {
            @Override
            public void run() {
                server.stop();
                platform.close();
            }
        });
    }

    private CortexJServerMain() {
    }
}
