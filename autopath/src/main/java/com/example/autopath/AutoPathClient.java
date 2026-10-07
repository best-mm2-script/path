package com.example.autopath;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

public class AutoPathClient implements ClientModInitializer {
    private BlockPos point1, point2;
    private final Deque<BlockPos> goals = new ArrayDeque<>();
    private List<BlockPos> path;
    private int index;
    private int stuckTicks, replans;
    private double lastX, lastZ;

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

            // /path go  -> point 1 then point 2
            root.then(ClientCommandManager.literal("go").executes(ctx -> {
                if (point1 == null || point2 == null) {
                    ctx.getSource().sendFeedback(Text.literal("Set both points first: /path set 1 and /path set 2"));
                    return 0;
                }
                goals.clear(); goals.add(point1); goals.add(point2);
                startNext(ctx.getSource());
                return 1;
            }));

            // /path to x y z
            root.then(ClientCommandManager.literal("to")
                .then(ClientCommandManager.argument("x", IntegerArgumentType.integer())
                .then(ClientCommandManager.argument("y", IntegerArgumentType.integer())
                .then(ClientCommandManager.argument("z", IntegerArgumentType.integer())
                    .executes(ctx -> {
                        goals.clear();
                        goals.add(new BlockPos(
                            IntegerArgumentType.getInteger(ctx, "x"),
                            IntegerArgumentType.getInteger(ctx, "y"),
                            IntegerArgumentType.getInteger(ctx, "z")));
                        startNext(ctx.getSource());
                        return 1;
                    })))));

            root.then(ClientCommandManager.literal("stop").executes(ctx -> {
                stop();
                ctx.getSource().sendFeedback(Text.literal("Stopped."));
                return 1;
            }));

            dispatcher.register(root);
        });

        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    private void setPoint(int which, BlockPos pos, FabricClientCommandSource src) {
        if (which == 1) point1 = pos; else point2 = pos;
        src.sendFeedback(Text.literal("Point " + which + " = " + pos.getX() + " " + pos.getY() + " " + pos.getZ()));
    }

    private void startNext(FabricClientCommandSource src) {
        path = null; index = 0; replans = 0;
        if (!goals.isEmpty()) {
            BlockPos g = goals.peek();
            src.sendFeedback(Text.literal("Heading to " + g.getX() + " " + g.getY() + " " + g.getZ()));
        }
    }

    private void stop() {
        goals.clear(); path = null;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.options != null) releaseKeys(mc);
    }

    private void releaseKeys(MinecraftClient mc) {
        mc.options.forwardKey.setPressed(false);
        mc.options.sprintKey.setPressed(false);
        mc.options.jumpKey.setPressed(false);
    }

    private void tick(MinecraftClient mc) {
        if (mc.player == null || mc.world == null || goals.isEmpty()) return;
        ClientPlayerEntity p = mc.player;
        BlockPos goal = goals.peek();

        // arrived?
        double gx = goal.getX() + 0.5, gz = goal.getZ() + 0.5;
        double distGoal = Math.hypot(p.getX() - gx, p.getZ() - gz);
        if (distGoal < 1.2 && Math.abs(p.getY() - goal.getY()) < 2) {
            goals.poll();
            path = null;
            releaseKeys(mc);
            p.sendMessage(Text.literal(goals.isEmpty() ? "Arrived." : "Reached point, continuing..."), false);
            return;
        }

        if (path == null || index >= path.size() || stuckTicks > 40) {
            if (replans++ > 60) {
                p.sendMessage(Text.literal("Can't find a way, giving up."), false);
                stop();
                return;
            }
            path = new Pathfinder(mc.world).find(p.getBlockPos(), goal);
            index = Math.min(1, path.size() - 1);
            stuckTicks = 0;
            if (path.size() <= 1) { releaseKeys(mc); stuckTicks = 0; return; }
        }

        // advance along the path
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

        // stuck detection
        if (Math.hypot(p.getX() - lastX, p.getZ() - lastZ) < 0.02) stuckTicks++; else stuckTicks = 0;
        lastX = p.getX(); lastZ = p.getZ();
    }
}
