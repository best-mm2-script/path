package com.example.autopath;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;

import java.util.*;

/** Simple grid A* for walking. Returns a partial path if the goal is not reachable/loaded. */
public class Pathfinder {
    private static final int MAX_NODES = 25000;
    private static final int[][] DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    private static final int[] DYS = {0, 1, -1, -2, -3};

    private static class Node {
        final BlockPos pos; double g, f; Node parent;
        Node(BlockPos p) { pos = p; }
    }

    private final ClientWorld world;

    public Pathfinder(ClientWorld world) { this.world = world; }

    public List<BlockPos> find(BlockPos start, BlockPos goal) {
        PriorityQueue<Node> open = new PriorityQueue<>(Comparator.comparingDouble(n -> n.f));
        Map<BlockPos, Node> all = new HashMap<>();
        Set<BlockPos> closed = new HashSet<>();

        Node s = new Node(start);
        s.g = 0; s.f = h(start, goal);
        open.add(s); all.put(start, s);
        Node best = s;
        int expanded = 0;

        while (!open.isEmpty() && expanded < MAX_NODES) {
            Node cur = open.poll();
            if (!closed.add(cur.pos)) continue;
            expanded++;
            if (h(cur.pos, goal) < h(best.pos, goal)) best = cur;
            if (cur.pos.equals(goal)) { best = cur; break; }

            for (int[] d : DIRS) {
                int dx = d[0], dz = d[1];
                // body must be able to move horizontally into the next column
                if (!passable(cur.pos.add(dx, 0, dz)) || !passable(cur.pos.add(dx, 1, dz))) {
                    // maybe a 1-block step up
                    if (!(canStand(cur.pos.add(dx, 1, dz)) && passable(cur.pos.up(2)))) continue;
                }
                for (int dy : DYS) {
                    BlockPos np = cur.pos.add(dx, dy, dz);
                    if (dy == 1 && !passable(cur.pos.up(2))) continue;
                    if (!canStand(np)) continue;
                    if (dy < 0 && !(passable(cur.pos.add(dx, 0, dz)) && passable(cur.pos.add(dx, 1, dz)))) break;
                    double cost = 1 + (dy == 1 ? 1.0 : 0) + (dy < 0 ? 0.4 * -dy : 0);
                    double ng = cur.g + cost;
                    Node ex = all.get(np);
                    if (ex == null || ng < ex.g) {
                        Node n = ex == null ? new Node(np) : ex;
                        n.g = ng; n.f = ng + h(np, goal); n.parent = cur;
                        all.put(np, n); open.add(n);
                    }
                    break;
                }
            }
        }

        LinkedList<BlockPos> path = new LinkedList<>();
        for (Node n = best; n != null; n = n.parent) path.addFirst(n.pos);
        return path;
    }

    private static double h(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX(), dy = a.getY() - b.getY(), dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dy * dy * 0.5 + dz * dz);
    }

    private boolean loaded(BlockPos p) {
        return world.getChunkManager().isChunkLoaded(p.getX() >> 4, p.getZ() >> 4);
    }

    private boolean passable(BlockPos p) {
        if (!loaded(p)) return false;
        BlockState s = world.getBlockState(p);
        return s.getCollisionShape(world, p).isEmpty() && s.getFluidState().isEmpty() && !hazard(s.getBlock());
    }

    private boolean hazard(Block b) {
        return b == Blocks.FIRE || b == Blocks.SOUL_FIRE || b == Blocks.CACTUS || b == Blocks.MAGMA_BLOCK
                || b == Blocks.SWEET_BERRY_BUSH || b == Blocks.COBWEB || b == Blocks.POWDER_SNOW;
    }

    private boolean canStand(BlockPos p) {
        if (!passable(p) || !passable(p.up())) return false;
        BlockPos below = p.down();
        if (!loaded(below)) return false;
        BlockState g = world.getBlockState(below);
        return !g.getCollisionShape(world, below).isEmpty() && g.getFluidState().isEmpty() && !hazard(g.getBlock());
    }
}
