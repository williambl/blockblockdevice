package com.williambl.craftttp;

import com.sun.net.httpserver.HttpServer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class CraftTTP implements ModInitializer {
    private static final WeakHashMap<MinecraftServer, HttpServer> SERVERS = new WeakHashMap<>();
    public static final Logger LOGGER = LoggerFactory.getLogger("CraftTTP");

    @Override
    public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            try {
                var httpServer = HttpServer.create(new InetSocketAddress(8394), 0);
                httpServer.createContext("/get_block", httpExchange -> {
                    Map<String, String> queryParams = getQueryParams(httpExchange.getRequestURI().getRawQuery());
                    if (!queryParams.containsKey("x") || !queryParams.containsKey("y") || !queryParams.containsKey("z")) {
                        httpExchange.sendResponseHeaders(400, -1);
                        httpExchange.getResponseBody().close();
                        return;
                    }
                    int x, y, z;
                    try {
                        x = Integer.parseInt(queryParams.get("x"));
                        y = Integer.parseInt(queryParams.get("y"));
                        z = Integer.parseInt(queryParams.get("z"));
                    } catch (NumberFormatException e) {
                        httpExchange.sendResponseHeaders(400, -1);
                        httpExchange.getResponseBody().close();
                        return;
                    }

                    var state = server.submit(() -> server.overworld().getBlockState(new BlockPos(x, y, z))).join();

                    String response = state.toString();
                    httpExchange.sendResponseHeaders(200, response.length());
                    try (var os = httpExchange.getResponseBody()) {
                        os.write(response.getBytes());
                    }
                });
                httpServer.setExecutor(null);
                httpServer.start();
                SERVERS.put(server, httpServer);
                LOGGER.info("Started CraftTTP server.");
            } catch (IOException e) {
                LOGGER.error("Failed to make HTTP server: {}", e.getMessage());
            }
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            var httpServer = SERVERS.remove(server);
            if (httpServer != null) {
                httpServer.stop(2);
                LOGGER.info("Stopped CraftTTP server.");
            }
        });
    }

    private static Map<String, String> getQueryParams(@Nullable String rawQueryString) {
        if (rawQueryString == null || rawQueryString.isEmpty()) {
            return Map.of();
        }

        return Stream.of(rawQueryString.split("&"))
                .filter(s -> s.contains("="))
                .map(kv -> kv.split("=", 2))
                .collect(Collectors.toMap(x -> URLDecoder.decode(x[0], StandardCharsets.UTF_8), x -> URLDecoder.decode(x[1], StandardCharsets.UTF_8)));
    }
}
