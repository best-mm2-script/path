package com.example.autopath;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class AutoPathClient implements ClientModInitializer {

    // ---------- state ----------
    private enum Mode { IDLE, RECORDING, GOING, FINE, PLAYING }
    private Mode mode = Mode.IDLE;

    private BlockPos point1, point2;
    private final Deque<BlockPos> goals = new ArrayDeque<>();
    private List<BlockPos> path;
    private int index, stuckTicks, replans;
    private double lastX, lastZ;

    // recording / playback
    private record Frame(double x, double y, double z, float yaw, float pitch, int flags) {}
    private static final int MAX_FRAMES = 24000; // 20 minutes
    private final List<Frame> frames = new ArrayList<>();
    private int playIdx, fineTicks;

    @Override
    public void onInitializeClient() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registry) -> {
            var root = ClientCommandManager.literal("path");

            // /path set <1|2> [x y z]
            for (int i = 1; i <= 2; i++) {
                final int which = i;
                root.then(ClientCommandManager.literal("set").then(ClientCommandManager.literal(String.valueOf(which))
                    .executes(ctx -> {
                        ClientPlayerEntity p = MinecraftClient.getInstance().player;
                        if (p == null) return 0;
                        setPoint(which, p.getBlockPos(), ctx.getSource());
                        return 1;
                    })
                    .then(ClientCommandManager.argument("x", IntegerArgumentType.integer())
                    .then(ClientCommandManager.argument("y", IntegerArgumentType.integer())
                    .then(ClientCommandManager.argument("z", IntegerArgumentType.integer())
                        .executes(ctx -> {
                            setPoint(which, new BlockPos(
                                IntegerArgumentType.getInteger(ctx, "x"),
                                IntegerArgumentType.getInteger(ctx, "y"),
                                IntegerArgumentType.getInteger(ctx, "z")), ctx.getSource());
                            return 1;
                        }))))));
            }

            // /path go -> point 1 then point 2
            root.then(ClientCommandManager.literal("go").executes(ctx -> {
                if (point1 == null || point2 == null) {
                    msg(ctx.getSource(), "Set both points first: /path set 1 and /path set 2");
                    return 0;
                }
                cancelAll();
                goals.add(point1); goals.add(point2);
                startNext(ctx.getSource());
                return 1;
            }));

            // /path to x y z
            root.then(ClientCommandManager.literal("to")
                .then(ClientCommandManager.argument("x", IntegerArgumentType.integer())
                .then(ClientCommandManager.argument("y", IntegerArgumentType.integer())
                .then(ClientCommandManager.argument("z", IntegerArgumentType.integer())
                    .executes(ctx -> {
                        cancelAll();
                        goals.add(new BlockPos(
                            IntegerArgumentType.getInteger(ctx, "x"),
                            IntegerArgumentType.getInteger(ctx, "y"),
                            IntegerArgumentType.getInteger(ctx, "z")));
                        startNext(ctx.getSource());
                        return 1;
                    })))));

            root.then(ClientCommandManager.literal("stop").executes(ctx -> {
                if (mode == Mode.RECORDING) { msg(ctx.getSource(), "Still recording. Use /path record stop."); return 0; }
                cancelAll();
                msg(ctx.getSource(), "Stopped.");
                return 1;
            }));

            // ---------- recording ----------
            var rec = ClientCommandManager.literal("record");

            rec.then(ClientCommandManager.literal("start").executes(ctx -> {
                cancelAll();
                frames.clear();
                mode = Mode.RECORDING;
                msg(ctx.getSource(), "Recording... move and jump, then /path record stop");
                return 1;
            }));

            rec.then(ClientCommandManager.literal("stop").executes(ctx -> {
                if (mode != Mode.RECORDING) { msg(ctx.getSource(), "Not recording."); return 0; }
                mode = Mode.IDLE;
                int jumps = 0; boolean prev = false;
                for (Frame f : frames) {
                    boolean j = (f.flags & 16) != 0;
                    if (j && !prev) jumps++;
                    prev = j;
                }
                msg(ctx.getSource(), "Recorded " + frames.size() + " ticks (" + (frames.size() / 20) + "s), "
                        + jumps + " jumps. /path record play to replay it.");
                return 1;
            }));

            rec.then(ClientCommandManager.literal("play").executes(ctx -> {
                if (frames.size() < 2) { msg(ctx.getSource(), "Nothing recorded."); return 0; }
                if (mode == Mode.RECORDING) { msg(ctx.getSource(), "Stop recording first."); return 0; }
                cancelAll();
                Frame f0 = frames.get(0);
                goals.add(BlockPos.ofFloored(f0.x, f0.y, f0.z));
                mode = Mode.GOING;
                wantsReplay = true;
                startNext(ctx.getSource());
                msg(ctx.getSource(), "Going to the start of the recording, then replaying it.");
                return 1;
            }));

            rec.then(ClientCommandManager.literal("save")
                .then(ClientCommandManager.argument("name", StringArgumentType.word()).executes(ctx -> {
                    saveFrames(StringArgumentType.getString(ctx, "name"), ctx.getSource());
                    return 1;
                })));

            rec.then(ClientCommandManager.literal("load")
                .then(ClientCommandManager.argument("name", StringArgumentType.word()).executes(ctx -> {
                    loadFrames(StringArgumentType.getString(ctx, "name"), ctx.getSource());
                    return 1;
                })));

            root.then(rec);
            dispatcher.register(root);
        });

        ClientTickEvents.START_CLIENT_TICK.register(this::startTick);
        ClientTickEvents.END_CLIENT_TICK.register(this::navTick);
    }

    // ---------- helpers ----------
    private void msg(FabricClientCommandSource src, String s) { src.sendFeedback(Text.literal(s)); }

    private void setPoint(int which, BlockPos pos, FabricClientCommandSource src) {
        if (which == 1) point1 = pos; else point2 = pos;
        msg(src, "Point " + which + " = " + pos.getX() + " " + pos.getY() + " " + pos.getZ());
    }

    private void startNext(FabricClientCommandSource src) {
        path = null; index = 0; replans = 0; stuckTicks = 0;
        if (mode == Mode.IDLE) mode = Mode.GOING;
        if (!goals.isEmpty()) {
            BlockPos g = goals.peek();
            msg(src, "Heading to " + g.getX() + " " + g.getY() + " " + g.getZ());
        }
    }

    private void cancelAll() {
        goals.clear(); path = null; wantsReplay = false;
        if (mode != Mode.RECORDING) mode = Mode.IDLE;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.options != null && mode != Mode.RECORDING) releaseKeys(mc);
    }

    private void releaseKeys(MinecraftClient mc) {
        mc.options.forwardKey.setPressed(false);
        mc.options.backKey.setPressed(false);
        mc.options.leftKey.setPressed(false);
        mc.options.rightKey.setPressed(false);
        mc.options.jumpKey.setPressed(false);
        mc.options.sneakKey.setPressed(false);
        mc.options.sprintKey.setPressed(false);
    }

    private int readFlags(MinecraftClient mc) {
        int f = 0;
        if (mc.options.forwardKey.isPressed()) f |= 1;
        if (mc.options.backKey.isPressed())    f |= 2;
        if (mc.options.leftKey.isPressed())    f |= 4;
        if (mc.options.rightKey.isPressed())   f |= 8;
        if (mc.options.jumpKey.isPressed())    f |= 16;
        if (mc.options.sneakKey.isPressed())   f |= 32;
        if (mc.options.sprintKey.isPressed())  f |= 64;
        return f;
    }

    private void applyFlags(MinecraftClient mc, int f) {
        mc.options.forwardKey.setPressed((f & 1) != 0);
        mc.options.backKey.setPressed((f & 2) != 0);
        mc.options.leftKey.setPressed((f & 4) != 0);
        mc.options.rightKey.setPressed((f & 8) != 0);
        mc.options.jumpKey.setPressed((f & 16) != 0);
        mc.options.sneakKey.setPressed((f & 32) != 0);
        mc.options.sprintKey.setPressed((f & 64) != 0);
    }

    // ---------- save / load ----------
    private Path dir() {
        Path d = FabricLoader.getInstance().getConfigDir().resolve("autopath");
        try { Files.createDirectories(d); } catch (Exception ignored) {}
        return d;
    }

    private String cleanName(String n) { return n.replaceAll("[^a-zA-Z0-9_-]", ""); }

    private void saveFrames(String name, FabricClientCommandSource src) {
        if (frames.isEmpty()) { msg(src, "Nothing to save."); return; }
        try {
            List<String> lines = new ArrayList<>(frames.size());
            for (Frame f : frames)
                lines.add(String.format(Locale.ROOT, "%.5f %.5f %.5f %.3f %.3f %d", f.x, f.y, f.z, f.yaw, f.pitch, f.flags));
            Path file = dir().resolve(cleanName(name) + ".txt");
            Files.write(file, lines);
            msg(src, "Saved to config/autopath/" + file.getFileName());
        } catch (Exception e) { msg(src, "Save failed: " + e.getMessage()); }
    }

    private void loadFrames(String name, FabricClientCommandSource src) {
        try {
            Path file = dir().resolve(cleanName(name) + ".txt");
            List<Frame> loaded = new ArrayList<>();
            for (String line : Files.readAllLines(file)) {
                String[] s = line.trim().split(" ");
                if (s.length < 6) continue;
                loaded.add(new Frame(Double.parseDouble(s[0]), Double.parseDouble(s[1]), Double.parseDouble(s[2]),
                        Float.parseFloat(s[3]), Float.parseFloat(s[4]), Integer.parseInt(s[5])));
            }
            frames.clear(); frames.addAll(loaded);
            msg(src, "Loaded " + frames.size() + " ticks. /path record play to run it.");
        } catch (Exception e) { msg(src, "Load failed: " + e.getMessage()); }
    }

    // ---------- tick: recording + replay (runs before the player moves) ----------
    private void startTick(MinecraftClient mc) {
        ClientPlayerEntity p = mc.player;
        if (p == null || mc.world == null) return;

        if (mode == Mode.RECORDING) {
            if (frames.size() >= MAX_FRAMES) {
                mode = Mode.IDLE;
                p.sendMessage(Text.literal("Recording hit the 20 minute limit and stopped."), false);
                return;
            }
            frames.add(new Frame(p.getX(), p.getY(), p.getZ(), p.getYaw(), p.getPitch(), readFlags(mc)));
            return;
        }

        if (mode == Mode.FINE) {
            Frame f0 = frames.get(0);
            double dx = f0.x - p.getX(), dz = f0.z - p.getZ();
            double d = Math.hypot(dx, dz);
            if (d < 0.3 || fineTicks++ > 80) {
                releaseKeys(mc);
                playIdx = 0;
                mode = Mode.PLAYING;
            } else {
                p.setYaw((float) (MathHelper.atan2(dz, dx) * 180.0 / Math.PI) - 90.0f);
                mc.options.forwardKey.setPressed(true);
                mc.options.sprintKey.setPressed(false);
                mc.options.jumpKey.setPressed(false);
                return;
            }
        }

        if (mode == Mode.PLAYING) {
            if (playIdx >= frames.size()) {
                releaseKeys(mc);
                mode = Mode.IDLE;
                p.sendMessage(Text.literal("Replay finished."), false);
                return;
            }
            Frame f = frames.get(playIdx);
            double drift = Math.sqrt(Math.pow(p.getX() - f.x, 2) + Math.pow(p.getZ() - f.z, 2))
                    + Math.abs(p.getY() - f.y);
            if (playIdx > 5 && drift > 3.0) {
                releaseKeys(mc);
                mode = Mode.IDLE;
                p.sendMessage(Text.literal("Replay stopped: drifted " + String.format(Locale.ROOT, "%.1f", drift)
                        + " blocks off the recording at tick " + playIdx + "."), false);
                return;
            }
            p.setYaw(f.yaw);
            p.setPitch(f.pitch);
            applyFlags(mc, f.flags);
            playIdx++;
        }
    }

    // ---------- tick: automatic path walking ----------
    private void navTick(MinecraftClient mc) {
        if (mc.player == null || mc.world == null) return;
        ClientPlayerEntity p = mc.player;

        // finished walking to the start of a recording -> fine alignment -> replay
        if (mode == Mode.GOING && goals.isEmpty() && frames.size() > 1 && wantsReplay) {
            wantsReplay = false;
            fineTicks = 0;
            mode = Mode.FINE;
            return;
        }
        if (mode != Mode.GOING || goals.isEmpty()) return;

        BlockPos goal = goals.peek();
        double gx = goal.getX() + 0.5, gz = goal.getZ() + 0.5;
        double distGoal = Math.hypot(p.getX() - gx, p.getZ() - gz);
        if (distGoal < 1.2 && Math.abs(p.getY() - goal.getY()) < 2) {
            goals.poll();
            path = null;
            releaseKeys(mc);
            if (goals.isEmpty()) {
                if (wantsReplay) return; // handled at the top of the next tick
                mode = Mode.IDLE;
                p.sendMessage(Text.literal("Arrived."), false);
            } else {
                p.sendMessage(Text.literal("Reached point, continuing..."), false);
            }
            return;
        }

        if (path == null || index >= path.size() || stuckTicks > 40) {
            if (replans++ > 60) {
                p.sendMessage(Text.literal("Can't find a way, giving up."), false);
                cancelAll();
                return;
            }
            path = new Pathfinder(mc.world).find(p.getBlockPos(), goal);
            index = Math.min(1, path.size() - 1);
            stuckTicks = 0;
            if (path.size() <= 1) { releaseKeys(mc); return; }
        }

        while (index < path.size()) {
            BlockPos n = path.get(index);
            double d = Math.hypot(p.getX() - (n.getX() + 0.5), p.getZ() - (n.getZ() + 0.5));
            if (d < 0.45 && Math.abs(p.getY() - n.getY()) < 1.3) index++; else break;
        }
        if (index >= path.size()) { path = null; return; }

        BlockPos next = path.get(index);
        double dx = next.getX() + 0.5 - p.getX();
        double dz = next.getZ() + 0.5 - p.getZ();
        p.setYaw((float) (MathHelper.atan2(dz, dx) * 180.0 / Math.PI) - 90.0f);

        mc.options.forwardKey.setPressed(true);
        mc.options.sprintKey.setPressed(true);
        mc.options.jumpKey.setPressed(next.getY() > p.getY() + 0.1 && p.isOnGround());

        if (Math.hypot(p.getX() - lastX, p.getZ() - lastZ) < 0.02) stuckTicks++; else stuckTicks = 0;
        lastX = p.getX(); lastZ = p.getZ();
    }

    private boolean wantsReplay = false;
}
