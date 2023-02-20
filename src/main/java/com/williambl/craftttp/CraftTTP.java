package com.williambl.craftttp;

import com.sun.net.httpserver.HttpServer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.WeakHashMap;

public class CraftTTP implements ModInitializer {
    private static final WeakHashMap<MinecraftServer, HttpServer> SERVERS = new WeakHashMap<>();
    public static final Logger LOGGER = LoggerFactory.getLogger("CraftTTP");

    @Override
    public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            try {
                var httpServer = HttpServer.create(new InetSocketAddress(8394), 0);
                httpServer.createContext("/", httpExchange -> {
                    String response = "Hello, World!";
                    httpExchange.sendResponseHeaders(200, response.length());
                    try (var os = httpExchange.getResponseBody()) {
                        os.write(response.getBytes());
                    }
                });
                httpServer.setExecutor(null);
                httpServer.start();
                SERVERS.put(server, httpServer);
            } catch (IOException e) {
                LOGGER.error("Failed to make HTTP server: {}", e.getMessage());
            }
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            var httpServer = SERVERS.remove(server);
            if (httpServer != null) {
                httpServer.stop(2);
            }
        });
    }
}
