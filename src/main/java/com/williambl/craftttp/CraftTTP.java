package com.williambl.craftttp;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static net.minecraft.commands.Commands.*;

public class CraftTTP implements ModInitializer {
    private static final WeakHashMap<MinecraftServer, HttpServer> SERVERS = new WeakHashMap<>();
    public static final Logger LOGGER = LoggerFactory.getLogger("CraftTTP");

    @Override
    public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            try {
                var httpServer = HttpServer.create(new InetSocketAddress(8394), 0);
                httpServer.createContext("/get_block", httpExchange -> {
                    if (!verifyHttpMethod(httpExchange, "GET")) {
                        LOGGER.warn("Rejecting {} because incorrect method {}", httpExchange.getRequestURI(), httpExchange.getRequestMethod());
                        return;
                    }

                    Map<String, String> queryParams = getQueryParams(httpExchange.getRequestURI().getRawQuery());
                    @Nullable BlockPos pos = getBlockPosFromQueryString(httpExchange, queryParams);
                    if (pos == null) {
                        LOGGER.warn("Rejecting {} because no blockpos", httpExchange.getRequestURI());
                        return;
                    }

                    var state = server.submit(() -> server.overworld().getBlockState(pos)).join();
                    respondOk(httpExchange, BlockStateParser.serialize(state));
                });
                httpServer.createContext("/set_block", httpExchange -> {
                    if (!verifyHttpMethod(httpExchange, "PUT")) {
                        LOGGER.warn("Rejecting {} because incorrect method {}", httpExchange.getRequestURI(), httpExchange.getRequestMethod());
                        return;
                    }

                    Map<String, String> queryParams = getQueryParams(httpExchange.getRequestURI().getRawQuery());
                    @Nullable BlockPos pos = getBlockPosFromQueryString(httpExchange, queryParams);
                    if (pos == null) {
                        LOGGER.warn("Rejecting {} because no blockpos", httpExchange.getRequestURI());
                        return;
                    }

                    BlockState state;
                    try (var is = new BufferedReader(new InputStreamReader(httpExchange.getRequestBody()))) {
                        String stateStr = is.readLine();
                        state = BlockStateParser.parseForBlock(server.overworld().holderLookup(Registries.BLOCK), stateStr, false).blockState();
                    } catch (CommandSyntaxException e) {
                        httpExchange.sendResponseHeaders(400, -1);
                        httpExchange.getResponseBody().close();
                        LOGGER.warn("Rejecting {} because incorrect blockstate", httpExchange.getRequestURI());
                        return;
                    }

                    server.execute(() -> server.overworld().setBlockAndUpdate(pos, state));
                    respondOk(httpExchange, BlockStateParser.serialize(state));
                });
                httpServer.createContext("/read_chunk", httpExchange -> {
                    if (!verifyHttpMethod(httpExchange, "GET")) {
                        LOGGER.warn("Rejecting {} because incorrect method {}", httpExchange.getRequestURI(), httpExchange.getRequestMethod());
                        return;
                    }

                    Map<String, String> queryParams = getQueryParams(httpExchange.getRequestURI().getRawQuery());
                    @Nullable ChunkPos pos = getChunkPosFromQueryString(httpExchange, queryParams);
                    if (pos == null) {
                        LOGGER.warn("Rejecting {} because no chunkpos", httpExchange.getRequestURI());
                        return;
                    }

                    @Nullable Integer offset = getIntegerFromQueryString(httpExchange, queryParams, "offset", 0);
                    if (offset == null) {
                        LOGGER.warn("Rejecting {} because invalid offset", httpExchange.getRequestURI());
                        return;
                    }

                    int maxLengthForChunk = server.submit(() -> server.overworld().getMaxBuildHeight() - server.overworld().getMinBuildHeight() - 1).join() * 8;
                    @Nullable Integer length = getIntegerFromQueryString(httpExchange, queryParams, "length", maxLengthForChunk-offset);
                    if (length == null) {
                        LOGGER.warn("Rejecting {} because invalid length", httpExchange.getRequestURI());
                        return;
                    }
                    if (length > maxLengthForChunk) {
                        LOGGER.warn("Rejecting {} because more content ({}) than can fit in a chunk {}", httpExchange.getRequestURI(), length, maxLengthForChunk);
                        httpExchange.sendResponseHeaders(400, -1);
                        httpExchange.getResponseBody().close();
                    }
                    if (length < 0) {
                        LOGGER.warn("Rejecting {} because negative length ({})", httpExchange.getRequestURI(), length);
                        httpExchange.sendResponseHeaders(400, -1);
                        httpExchange.getResponseBody().close();
                    }

                    LOGGER.info("Request to read {} bytes of data @ {} offset {}", length, pos, offset);
                    byte[] chunkContents = server.submit(() -> readChunk(server.overworld(), pos, offset, length)).join();
                    respondOk(httpExchange, Base64.getEncoder().encodeToString(chunkContents));
                });
                httpServer.createContext("/write_chunk", httpExchange -> {
                    if (!verifyHttpMethod(httpExchange, "PUT")) {
                        LOGGER.warn("Rejecting {} because incorrect method {}", httpExchange.getRequestURI(), httpExchange.getRequestMethod());
                        return;
                    }

                    Map<String, String> queryParams = getQueryParams(httpExchange.getRequestURI().getRawQuery());
                    @Nullable ChunkPos pos = getChunkPosFromQueryString(httpExchange, queryParams);
                    if (pos == null) {
                        LOGGER.warn("Rejecting {} because no chunkpos", httpExchange.getRequestURI());
                        return;
                    }
                    @Nullable Integer offset = getIntegerFromQueryString(httpExchange, queryParams, "offset", 0);
                    if (offset == null) {
                        LOGGER.warn("Rejecting {} because invalid offset", httpExchange.getRequestURI());
                        return;
                    }

                    byte[] contents;
                    try (var is = new BufferedReader(new InputStreamReader(httpExchange.getRequestBody()))) {
                        String contentsStr = is.readLine();
                        if (contentsStr == null || contentsStr.isEmpty()) {
                            respondOk(httpExchange, "Complete");
                            LOGGER.warn("Request to write no data @ {} offset {}", pos, offset);
                            return;
                        }
                        contents = Base64.getDecoder().decode(contentsStr);
                    }

                    LOGGER.info("Request to write {} bytes of data @ {} offset {}", contents.length, pos, offset);
                    server.execute(() -> writeChunk(server.overworld(), pos, offset, contents));
                    respondOk(httpExchange, "Complete");
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

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(
                    literal("generate_memory").executes(ctx -> {
                        ChunkPos chunkPos = new ChunkPos(new BlockPos(ctx.getSource().getPosition()));
                        ServerLevel level = ctx.getSource().getLevel();
                        LevelChunk chunk = level.getChunk(chunkPos.x, chunkPos.z);
                        int minY = level.getMinBuildHeight();
                        int maxY = level.getMaxBuildHeight();
                        LevelChunkSection[] sections = chunk.getSections();
                        int i = 0;
                        for (int y = minY; y < maxY; y++) {
                            LevelChunkSection section = sections[level.getSectionIndex(y)];
                            if (section == null) {
                                section = new LevelChunkSection(y, level.registryAccess().registryOrThrow(Registries.BIOME));
                                sections[level.getSectionIndex(y)] = section;
                            }
                            if (y == minY) {
                                for (int x = 0; x < 16; x++)
                                    for (int z = 0; z < 16; z++) {
                                        section.setBlockState(x, y & 15, z, Blocks.WHITE_WOOL.defaultBlockState());
                                    }
                            } else {
                                for (int z = 0; z < 16; z++) {
                                    BlockState state = switch (z % 4) {
                                        case 0 -> Blocks.REDSTONE_WALL_TORCH.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH).setValue(BlockStateProperties.LIT, false);
                                        case 1 -> Blocks.ORANGE_WOOL.defaultBlockState();
                                        case 2 -> Blocks.LEVER.defaultBlockState().setValue(BlockStateProperties.ATTACH_FACE, AttachFace.WALL).setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH).setValue(BlockStateProperties.POWERED, true);
                                        case 3 -> Blocks.SCAFFOLDING.defaultBlockState().setValue(BlockStateProperties.STABILITY_DISTANCE, 0);
                                        default -> throw new IllegalStateException("Unexpected value: " + z % 4);
                                    };

                                    for (int x = 0; x < 16; x++) {
                                        if (state.getBlock() == Blocks.REDSTONE_WALL_TORCH) {
                                            i++;
                                        }
                                        if (state.getBlock() == Blocks.ORANGE_WOOL && x == 8) {
                                            state = Blocks.MAGENTA_WOOL.defaultBlockState();
                                        }
                                        section.setBlockState(x, y & 15, z, state);
                                    }
                                }
                            }
                        }

                        for (var section : sections)
                            for (int x = 0; x < 16; x++)
                                for (int z = 0; z < 16; z++) {
                                    level.getChunkSource().getLightEngine().updateSectionStatus(chunkPos.getBlockAt(x, section.bottomBlockY(), z), false);
                                }

                        for (int x = 0; x < 16; x++)
                            for (int z = 0; z < 16; z++)
                                for (var type : Heightmap.Types.values()) {
                                    chunk.getOrCreateHeightmapUnprimed(type).update(x, maxY, z, chunk.getBlockState(chunkPos.getBlockAt(x, maxY, z)));
                                }

                        chunk.clearAllBlockEntities();
                        chunk.setUnsaved(true);
                        level.getChunkSource().chunkMap.resendChunk(chunk);

                        ctx.getSource().sendSuccess(Component.literal("Created a block of %s bits (%s bytes) of memory.".formatted(i, i / 8)), true);

                        return Command.SINGLE_SUCCESS;
                    })
            );
            dispatcher.register(
                    literal("encode_chunk").then(argument("value", StringArgumentType.string()).then(argument("offset", IntegerArgumentType.integer(0)).executes(ctx -> {
                        String value = StringArgumentType.getString(ctx, "value");
                        int offset = IntegerArgumentType.getInteger(ctx, "offset");
                        ChunkPos chunkPos = new ChunkPos(new BlockPos(ctx.getSource().getPosition()));
                        writeChunk(ctx.getSource().getLevel(), chunkPos, offset, value.getBytes(StandardCharsets.UTF_8));
                        ctx.getSource().sendSuccess(Component.literal("Written %s to %s @ an offset of %s bytes".formatted(value, chunkPos, offset)), false);
                        return Command.SINGLE_SUCCESS;
                    })))
            );
            dispatcher.register(
                    literal("decode_chunk").then(argument("length", IntegerArgumentType.integer(1)).executes(ctx -> {
                        int length = IntegerArgumentType.getInteger(ctx, "length");
                        ServerLevel level = ctx.getSource().getLevel();
                        byte[] result = readChunk(level, new ChunkPos(new BlockPos(ctx.getSource().getPosition())), 0, length);
                        String resultString = new String(result, 0, length, StandardCharsets.UTF_8);
                        ctx.getSource().sendSuccess(Component.literal(resultString), false);
                        return Command.SINGLE_SUCCESS;
                    }))
            );
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

    private static boolean verifyHttpMethod(HttpExchange httpExchange, String acceptedMethod) throws IOException {
        if (!httpExchange.getRequestMethod().equals(acceptedMethod)) {
            httpExchange.sendResponseHeaders(405, -1);
            httpExchange.getResponseBody().close();
            return false;
        }

        return true;
    }

    private static @Nullable BlockPos getBlockPosFromQueryString(HttpExchange httpExchange, Map<String, String> queryParams) throws IOException {
        if (!queryParams.containsKey("x") || !queryParams.containsKey("y") || !queryParams.containsKey("z")) {
            httpExchange.sendResponseHeaders(400, -1);
            httpExchange.getResponseBody().close();
            return null;
        }

        int x, y, z;
        try {
            x = Integer.parseInt(queryParams.get("x"));
            y = Integer.parseInt(queryParams.get("y"));
            z = Integer.parseInt(queryParams.get("z"));
        } catch (NumberFormatException e) {
            httpExchange.sendResponseHeaders(400, -1);
            httpExchange.getResponseBody().close();
            return null;
        }

        return new BlockPos(x, y, z);
    }

    private static @Nullable ChunkPos getChunkPosFromQueryString(HttpExchange httpExchange, Map<String, String> queryParams) throws IOException {
        if (!queryParams.containsKey("x") || !queryParams.containsKey("z")) {
            httpExchange.sendResponseHeaders(400, -1);
            httpExchange.getResponseBody().close();
            return null;
        }

        int x, z;
        try {
            x = Integer.parseInt(queryParams.get("x"));
            z = Integer.parseInt(queryParams.get("z"));
        } catch (NumberFormatException e) {
            httpExchange.sendResponseHeaders(400, -1);
            httpExchange.getResponseBody().close();
            return null;
        }

        return new ChunkPos(x, z);
    }

    private static @Nullable Integer getIntegerFromQueryString(HttpExchange httpExchange, Map<String, String> queryParams, String key, int defaultValue) throws IOException {
        if (!queryParams.containsKey(key)) {
            return defaultValue;
        }

        int value;
        try {
            value = Integer.parseInt(queryParams.get(key));
        } catch (NumberFormatException e) {
            httpExchange.sendResponseHeaders(400, -1);
            httpExchange.getResponseBody().close();
            return null;
        }

        return value;
    }

    private static void respondOk(HttpExchange httpExchange, String response) throws IOException {
        httpExchange.sendResponseHeaders(200, response.length());
        try (var os = httpExchange.getResponseBody()) {
            os.write(response.getBytes());
        }
    }

    private static byte[] readChunk(ServerLevel level, ChunkPos chunkPos, int offset, int length) {
        LevelChunk chunk = level.getChunk(chunkPos.x, chunkPos.z);
        int minY = level.getMinBuildHeight()+1;
        int maxY = level.getMaxBuildHeight();
        LevelChunkSection[] sections = chunk.getSections();
        byte[] results = new byte[length];
        int i = 0;
        for (int y = minY; y < maxY; y++) {
            @Nullable LevelChunkSection section = sections[level.getSectionIndex(y)];

            for (int z = 0; z < 16; z += 4) {
                for (int byteIndex = 0; byteIndex < 2; byteIndex++) {
                    if (offset > 0) {
                        offset--;
                        continue;
                    }
                    if (i >= results.length) {
                        return results;
                    }

                    byte result = 0x0;
                    if (section != null) {
                        for (int x = 0; x < 8; x++) {
                            BlockState state = section.getBlockState(x + byteIndex * 8, y & 15, z);
                            byte bit = (byte) (state.hasProperty(BlockStateProperties.LIT) ? state.getValue(BlockStateProperties.LIT) ? 1 : 0 : 0);
                            result = (byte) (result | (bit << x));
                        }
                    }
                    results[i++] = result;
                }
            }
        }

        return results;
    }

    private static void writeChunk(ServerLevel level, ChunkPos chunkPos, int offset, byte[] toWrite) {
        LevelChunk chunk = level.getChunk(chunkPos.x, chunkPos.z);
        int minY = level.getMinBuildHeight()+1;
        int maxY = level.getMaxBuildHeight();
        LevelChunkSection[] sections = chunk.getSections();
        int i = 0;
        for (int y = minY; y < maxY; y++) {
            @Nullable LevelChunkSection section = sections[level.getSectionIndex(y)];

            for (int z = 2; z < 16; z += 4) {
                for (int byteIndex = 0; byteIndex < 2; byteIndex++) {
                    if (i >= toWrite.length) {
                        return;
                    }
                    if (offset > 0) {
                        offset--;
                        continue;
                    }
                    byte byteToWrite = toWrite[i++];
                    for (int x = 0; x < 8; x++) {
                        BlockState state = section == null ? Blocks.AIR.defaultBlockState() : section.getBlockState(x + byteIndex * 8, y & 15, z);
                        if (state.hasProperty(BlockStateProperties.POWERED) && state.hasProperty(BlockStateProperties.ATTACH_FACE)) {
                            boolean isBitOn = ((byteToWrite >> x) & 1) != 0;
                            // we don't set it straight to the section because we want block updates from redstone + clicky sounds :)
                            if (isBitOn == state.getValue(BlockStateProperties.POWERED)) {
                                var pos = new BlockPos(chunkPos.getMinBlockX() + x + byteIndex * 8, y, chunkPos.getMinBlockZ() + z);
                                var result = ((LeverBlock)Blocks.LEVER).pull(state, level, pos);
                                float f = result.getValue(BlockStateProperties.POWERED) ? 0.6F : 0.5F;
                                level.playSound(null, pos, SoundEvents.LEVER_CLICK, SoundSource.BLOCKS, 0.3F, f);
                                level.gameEvent(null, result.getValue(BlockStateProperties.POWERED) ? GameEvent.BLOCK_ACTIVATE : GameEvent.BLOCK_DEACTIVATE, pos);
                            }
                        }
                    }
                }
            }
        }
    }
}
