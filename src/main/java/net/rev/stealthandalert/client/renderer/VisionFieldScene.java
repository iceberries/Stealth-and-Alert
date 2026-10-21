package net.rev.stealthandalert.client.renderer;

import net.minecraft.world.phys.Vec3;
import java.util.*;

/** Pure snapshot preparation. No world, entity or OpenGL access is allowed here. */
public final class VisionFieldScene {
    public static final int PIXELS_PER_BLOCK = 8;
    private static final int MAX_ATLAS = 2048;
    private static final double CELL = 4;
    public record DrawVertex(Vec3 position, float u, float v, int plane) {}
    public record DrawTriangle(DrawVertex a, DrawVertex b, DrawVertex c) {}
    public record Tile(int x, int y, int width, int height, double minU, double minV,
                       int cellX, int cellY, int cellsU, int cellsV) {}
    public record Geometry(SurfaceVisionFusion.GpuGeometry union, int width, int height,
                           List<Tile> tiles, List<DrawTriangle> fill, List<Buckets> buckets) {}
    public record Data(Geometry geometry, float[] sources, float[] edges, int[] indices, float[] planes) {}
    public record Buckets(List<List<Integer>> sources, List<List<Integer>> edges) {}
    private record PositionTriangle(Vec3 a, Vec3 b, Vec3 c) {}
    private List<PositionTriangle> previousPositions;
    private Vec3 previousReference;
    private Geometry cachedGeometry;

    public Data build(List<SurfaceVisionFusion.Triangle> input, Vec3 reference) {
        List<PositionTriangle> positions = input.stream().map(t -> new PositionTriangle(t.a().position(), t.b().position(), t.c().position())).toList();
        if (!positions.equals(previousPositions) || !reference.equals(previousReference)) {
            cachedGeometry = geometry(SurfaceVisionFusion.gpuGeometry(input, reference), input);
            previousPositions = positions;
            previousReference = reference;
        }
        Geometry geometry = cachedGeometry;
        if (geometry == null) return null; // Too large for one atlas; the CPU display path remains available.
        float[] sources = new float[input.size() * 12];
        int edgeCount = geometry.union.planes().stream().mapToInt(p -> p.boundary().size()).sum();
        float[] edges = new float[edgeCount * 4];
        float[] planes = new float[geometry.tiles.size() * 12];
        IntArray indices = new IntArray();
        int edgeIndex = 0;
        for (int planeId = 0; planeId < geometry.tiles.size(); planeId++) {
            var plane = geometry.union.planes().get(planeId);
            Tile tile = geometry.tiles.get(planeId);
            for (int id : plane.sourceIndices()) {
                var triangle = input.get(id);
                float maximum = Math.max(triangle.a().danger(), Math.max(triangle.b().danger(), triangle.c().danger()));
                putVertex(sources, id * 12, triangle.a(), plane.axis(), reference, maximum);
                putVertex(sources, id * 12 + 4, triangle.b(), plane.axis(), reference, 0);
                putVertex(sources, id * 12 + 8, triangle.c(), plane.axis(), reference, 0);
            }
            for (var edge : plane.boundary()) {
                double[] a = project(edge.a(), plane.axis(), reference), b = project(edge.b(), plane.axis(), reference);
                int at = edgeIndex++ * 4;
                edges[at] = (float) a[0]; edges[at + 1] = (float) a[1];
                edges[at + 2] = (float) b[0]; edges[at + 3] = (float) b[1];
            }
            int cells = tile.cellsU * tile.cellsV, headers = indices.size;
            indices.reserve(cells * 4);
            Buckets bucket = geometry.buckets.get(planeId);
            for (int cell = 0; cell < cells; cell++) {
                List<Integer> sourceIds = new ArrayList<>(bucket.sources.get(cell));
                sourceIds.sort((a, b) -> Float.compare(sources[b * 12 + 3], sources[a * 12 + 3]));
                int at = headers + cell * 4;
                indices.values[at] = indices.size;
                indices.values[at + 1] = sourceIds.size();
                for (int id : sourceIds) indices.add(id);
                indices.values[at + 2] = indices.size;
                indices.values[at + 3] = bucket.edges.get(cell).size();
                for (int id : bucket.edges.get(cell)) indices.add(id);
            }
            int at = planeId * 12;
            planes[at] = (float) tile.minU; planes[at + 1] = (float) tile.minV;
            planes[at + 2] = PIXELS_PER_BLOCK; planes[at + 3] = headers;
            planes[at + 4] = tile.x; planes[at + 5] = tile.y;
            planes[at + 6] = tile.width; planes[at + 7] = tile.height;
            planes[at + 8] = tile.cellX; planes[at + 9] = tile.cellY;
            planes[at + 10] = tile.cellsU; planes[at + 11] = tile.cellsV;
        }
        return new Data(geometry, sources, edges, indices.toArray(), planes);
    }

    private static Geometry geometry(SurfaceVisionFusion.GpuGeometry union, List<SurfaceVisionFusion.Triangle> input) {
        List<Tile> unpacked = new ArrayList<>();
        long area = 0;
        int widest = 1;
        for (var plane : union.planes()) {
            double minU = Double.POSITIVE_INFINITY, minV = minU, maxU = Double.NEGATIVE_INFINITY, maxV = maxU;
            for (int id : plane.sourceIndices()) {
                var t = input.get(id);
                for (var vertex : List.of(t.a(), t.b(), t.c())) {
                    double[] p = project(vertex.position(), plane.axis(), union.reference());
                    minU = Math.min(minU, p[0]); minV = Math.min(minV, p[1]);
                    maxU = Math.max(maxU, p[0]); maxV = Math.max(maxV, p[1]);
                }
            }
            minU = Math.floor(minU * PIXELS_PER_BLOCK) / PIXELS_PER_BLOCK - 1.0 / PIXELS_PER_BLOCK;
            minV = Math.floor(minV * PIXELS_PER_BLOCK) / PIXELS_PER_BLOCK - 1.0 / PIXELS_PER_BLOCK;
            int width = (int) Math.ceil((maxU - minU) * PIXELS_PER_BLOCK) + 2;
            int height = (int) Math.ceil((maxV - minV) * PIXELS_PER_BLOCK) + 2;
            int cellX = (int) Math.floor(minU / CELL), cellY = (int) Math.floor(minV / CELL);
            int cellsU = (int) Math.floor((minU + width / (double) PIXELS_PER_BLOCK) / CELL) - cellX + 1;
            int cellsV = (int) Math.floor((minV + height / (double) PIXELS_PER_BLOCK) / CELL) - cellY + 1;
            unpacked.add(new Tile(0, 0, width, height, minU, minV, cellX, cellY, cellsU, cellsV));
            area += (long) (width + 2) * (height + 2);
            widest = Math.max(widest, width + 2);
        }
        if (widest > MAX_ATLAS || area > (long) MAX_ATLAS * MAX_ATLAS) return null;
        int width = powerOfTwo(Math.max(widest, (int) Math.ceil(Math.sqrt(area))));
        List<Tile> tiles;
        int height;
        while (true) {
            tiles = new ArrayList<>();
            int x = 1, y = 1, row = 0;
            for (Tile tile : unpacked) {
                if (x + tile.width + 1 > width) { x = 1; y += row + 2; row = 0; }
                tiles.add(new Tile(x, y, tile.width, tile.height, tile.minU, tile.minV, tile.cellX, tile.cellY, tile.cellsU, tile.cellsV));
                x += tile.width + 2;
                row = Math.max(row, tile.height);
            }
            height = powerOfTwo(y + row + 1);
            if (height <= MAX_ATLAS) break;
            if (width == MAX_ATLAS) return null;
            width = Math.min(MAX_ATLAS, width * 2);
        }
        List<DrawTriangle> fill = new ArrayList<>();
        List<Buckets> buckets = new ArrayList<>();
        int edgeOffset = 0;
        for (int planeId = 0; planeId < tiles.size(); planeId++) {
            Tile tile = tiles.get(planeId);
            var plane = union.planes().get(planeId);
            for (var triangle : plane.coverage()) fill.add(new DrawTriangle(
                    drawVertex(triangle.a(), planeId, plane.axis(), union.reference(), tile, width, height),
                    drawVertex(triangle.b(), planeId, plane.axis(), union.reference(), tile, width, height),
                    drawVertex(triangle.c(), planeId, plane.axis(), union.reference(), tile, width, height)));
            int cells = tile.cellsU * tile.cellsV;
            List<List<Integer>> sourceCells = lists(cells), edgeCells = lists(cells);
            for (int id : plane.sourceIndices()) {
                var t = input.get(id);
                double[] a = project(t.a().position(), plane.axis(), union.reference());
                double[] b = project(t.b().position(), plane.axis(), union.reference());
                double[] c = project(t.c().position(), plane.axis(), union.reference());
                index(sourceCells, tile, id, Math.min(a[0], Math.min(b[0], c[0])) - 1,
                        Math.min(a[1], Math.min(b[1], c[1])) - 1, Math.max(a[0], Math.max(b[0], c[0])) + 1,
                        Math.max(a[1], Math.max(b[1], c[1])) + 1);
            }
            for (var edge : plane.boundary()) {
                double[] a = project(edge.a(), plane.axis(), union.reference()), b = project(edge.b(), plane.axis(), union.reference());
                index(edgeCells, tile, edgeOffset++, Math.min(a[0], b[0]) - 1, Math.min(a[1], b[1]) - 1,
                        Math.max(a[0], b[0]) + 1, Math.max(a[1], b[1]) + 1);
            }
            buckets.add(new Buckets(sourceCells, edgeCells));
        }
        return new Geometry(union, width, height, List.copyOf(tiles), List.copyOf(fill), List.copyOf(buckets));
    }

    private static List<List<Integer>> lists(int count) {
        List<List<Integer>> result = new ArrayList<>(count);
        for (int i = 0; i < count; i++) result.add(new ArrayList<>());
        return result;
    }
    private static void index(List<List<Integer>> buckets, Tile tile, int id, double minU, double minV, double maxU, double maxV) {
        int u0 = Math.max(0, (int) Math.floor(minU / CELL) - tile.cellX), v0 = Math.max(0, (int) Math.floor(minV / CELL) - tile.cellY);
        int u1 = Math.min(tile.cellsU - 1, (int) Math.floor(maxU / CELL) - tile.cellX), v1 = Math.min(tile.cellsV - 1, (int) Math.floor(maxV / CELL) - tile.cellY);
        for (int v = v0; v <= v1; v++) for (int u = u0; u <= u1; u++) buckets.get(v * tile.cellsU + u).add(id);
    }
    private static DrawVertex drawVertex(SurfaceVisionFusion.Vertex vertex, int plane, int axis, Vec3 reference, Tile tile, int width, int height) {
        double[] p = project(vertex.position(), axis, reference);
        return new DrawVertex(vertex.position(), (float) ((tile.x + (p[0] - tile.minU) * PIXELS_PER_BLOCK) / width),
                (float) ((tile.y + (p[1] - tile.minV) * PIXELS_PER_BLOCK) / height), plane);
    }
    private static void putVertex(float[] data, int at, SurfaceVisionFusion.Vertex vertex, int axis, Vec3 reference, float max) {
        double[] p = project(vertex.position(), axis, reference);
        data[at] = (float) p[0]; data[at + 1] = (float) p[1]; data[at + 2] = vertex.danger(); data[at + 3] = max;
    }
    public static double[] project(Vec3 p, int axis, Vec3 reference) {
        p = p.subtract(reference);
        return axis == 0 ? new double[]{p.z, p.y} : axis == 1 ? new double[]{p.x, p.z} : new double[]{p.x, p.y};
    }
    private static int powerOfTwo(int value) { int n = 1; while (n < value) n <<= 1; return n; }
    private static final class IntArray {
        int[] values = new int[256]; int size;
        void reserve(int count) { if (size + count > values.length) values = Arrays.copyOf(values, Math.max(size + count, values.length * 2)); size += count; }
        void add(int value) { reserve(1); values[size - 1] = value; }
        int[] toArray() { return Arrays.copyOf(values, size); }
    }
}
