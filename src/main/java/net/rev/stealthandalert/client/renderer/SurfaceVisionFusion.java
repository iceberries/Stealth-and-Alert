package net.rev.stealthandalert.client.renderer;

import net.minecraft.world.phys.Vec3;

import java.awt.geom.Area;
import java.awt.geom.Path2D;
import java.awt.geom.PathIterator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.HashSet;
import java.util.IdentityHashMap;

/**
 * Planar union of the existing 3D cone triangles. Different floors/walls never share a union.
 * Area extracts hole/island boundaries; convex subtraction partitions fill by the strongest danger gradient.
 * No extra native library or dependency is needed (java.desktop is part of Minecraft's Java runtime).
 */
public final class SurfaceVisionFusion {
    private static final double EPS = 1e-7;
    private static final double CELL = 4;
    private static final double SHADE_CELL = 4;
    private static final double BLEND_WIDTH = 1.0;
    private static final double DISPLAY_EDGE = 1.0;

    private SurfaceVisionFusion() {}

    public record Vertex(Vec3 position, float danger, float distanceRatio) {}
    public record Triangle(Vertex a, Vertex b, Vertex c) {}
    public record Edge(Vec3 a, Vec3 b, Vec3 normal) {}
    public record Mesh(List<Triangle> triangles, List<Edge> boundary) {}
    public record GpuPlane(int axis, List<Triangle> coverage, List<Integer> sourceIndices, List<Edge> boundary) {}
    public record GpuGeometry(Vec3 reference, List<GpuPlane> planes, List<Edge> boundary) {}
    private record Point(double u, double v) {}
    private record Segment(Point a, Point b) {}
    private record PlaneKey(long x, long y, long z, long distance) {}

    public static Mesh fuse(List<Triangle> triangles, Vec3 reference) {
        return fuse(triangles, reference, false);
    }

    /** Keeps exact coverage but shades it from one continuous, conservative display field. */
    public static Mesh fuseForDisplay(List<Triangle> triangles, Vec3 reference) {
        return fuse(triangles, reference, true);
    }

    /** Coverage depends only on positions. GPU risk changes never redo polygon ownership cuts. */
    public static GpuGeometry gpuGeometry(List<Triangle> triangles, Vec3 reference) {
        Map<PlaneKey, Plane> planes = new LinkedHashMap<>();
        Map<Triangle, Integer> indices = new IdentityHashMap<>();
        for (int i = 0; i < triangles.size(); i++) {
            Triangle triangle = triangles.get(i);
            indices.put(triangle, i);
            Vec3 normal = triangle.b.position.subtract(triangle.a.position).cross(triangle.c.position.subtract(triangle.a.position));
            if (normal.lengthSqr() < EPS * EPS) continue;
            normal = normal.normalize();
            int axis = dominantAxis(normal);
            if (component(normal, axis) < 0) normal = normal.scale(-1);
            double distance = normal.dot(triangle.a.position.subtract(reference));
            PlaneKey key = new PlaneKey(Math.round(normal.x * 1e6), Math.round(normal.y * 1e6),
                    Math.round(normal.z * 1e6), Math.round(distance * 1e6));
            Plane plane = planes.get(key);
            if (plane == null) { plane = new Plane(reference, normal, distance, axis, false); planes.put(key, plane); }
            plane.add(triangle);
        }
        List<GpuPlane> result = new ArrayList<>();
        List<Edge> boundary = new ArrayList<>();
        for (Plane plane : planes.values()) {
            plane.union = balancedUnion(plane.areas);
            List<Edge> edges = new ArrayList<>();
            plane.boundary(plane.union, edges);
            List<Triangle> coverage = plane.coverage(edges);
            result.add(new GpuPlane(plane.axis, List.copyOf(coverage),
                    plane.sources.stream().map(source -> indices.get(source.triangle)).toList(), List.copyOf(edges)));
            boundary.addAll(edges);
        }
        return new GpuGeometry(reference, List.copyOf(result), removeSurfaceSeams(boundary));
    }

    private static Area balancedUnion(List<Area> areas) {
        if (areas.isEmpty()) return new Area();
        List<Area> pending = areas;
        while (pending.size() > 1) {
            List<Area> next = new ArrayList<>((pending.size() + 1) / 2);
            for (int i = 0; i < pending.size(); i += 2) {
                Area area = pending.get(i);
                if (i + 1 < pending.size()) area.add(pending.get(i + 1));
                next.add(area);
            }
            pending = next;
        }
        return pending.getFirst();
    }

    private static Mesh fuse(List<Triangle> triangles, Vec3 reference, boolean display) {
        Map<PlaneKey, Plane> planes = new LinkedHashMap<>();
        for (Triangle triangle : triangles) {
            Vec3 a = triangle.a.position, b = triangle.b.position, c = triangle.c.position;
            Vec3 normal = b.subtract(a).cross(c.subtract(a));
            if (normal.lengthSqr() < EPS * EPS) continue;
            normal = normal.normalize();
            int axis = dominantAxis(normal);
            if (component(normal, axis) < 0) normal = normal.scale(-1);
            double distance = normal.dot(a.subtract(reference));
            PlaneKey key = new PlaneKey(Math.round(normal.x * 1e6), Math.round(normal.y * 1e6),
                    Math.round(normal.z * 1e6), Math.round(distance * 1e6));
            Plane plane = planes.get(key);
            if (plane == null) {
                plane = new Plane(reference, normal, distance, axis, display);
                planes.put(key, plane);
            }
            plane.add(triangle);
        }
        List<Triangle> fill = new ArrayList<>();
        List<Edge> edges = new ArrayList<>();
        for (Plane plane : planes.values()) plane.build(fill, edges);
        return new Mesh(List.copyOf(fill), removeSurfaceSeams(edges));
    }

    private static int dominantAxis(Vec3 n) {
        return Math.abs(n.x) > Math.abs(n.y) ? (Math.abs(n.x) > Math.abs(n.z) ? 0 : 2)
                : (Math.abs(n.y) > Math.abs(n.z) ? 1 : 2);
    }

    private static double component(Vec3 p, int axis) {
        return axis == 0 ? p.x : axis == 1 ? p.y : p.z;
    }

    private static long cellKey(double u, double v) {
        return cellKey(u, v, CELL);
    }

    private static long cellKey(double u, double v, double size) {
        return ((long) (int) Math.floor(u / size) << 32) ^ ((int) Math.floor(v / size) & 0xffffffffL);
    }

    private static final class Source {
        final Triangle triangle;
        final Point a, b, c;
        final double denominator;
        final int id;
        final Area area;
        final double minU, maxU, minV, maxV;
        final double minDanger, maxDanger, meanDanger;

        Source(Triangle triangle, Plane plane) {
            this.triangle = triangle;
            id = plane.sources.size();
            a = plane.project(triangle.a.position);
            b = plane.project(triangle.b.position);
            c = plane.project(triangle.c.position);
            denominator = (b.v - c.v) * (a.u - c.u) + (c.u - b.u) * (a.v - c.v);
            area = polygonArea(List.of(a, b, c));
            minU = Math.min(a.u, Math.min(b.u, c.u));
            maxU = Math.max(a.u, Math.max(b.u, c.u));
            minV = Math.min(a.v, Math.min(b.v, c.v));
            maxV = Math.max(a.v, Math.max(b.v, c.v));
            minDanger = Math.min(triangle.a.danger, Math.min(triangle.b.danger, triangle.c.danger));
            maxDanger = Math.max(triangle.a.danger, Math.max(triangle.b.danger, triangle.c.danger));
            meanDanger = (triangle.a.danger + triangle.b.danger + triangle.c.danger) / 3.0;
        }

        double dangerAt(Point p) {
            double wa = ((b.v - c.v) * (p.u - c.u) + (c.u - b.u) * (p.v - c.v)) / denominator;
            double wb = ((c.v - a.v) * (p.u - c.u) + (a.u - c.u) * (p.v - c.v)) / denominator;
            return wa * triangle.a.danger + wb * triangle.b.danger + (1 - wa - wb) * triangle.c.danger;
        }

        double distanceAt(Point p) {
            double wa = ((b.v - c.v) * (p.u - c.u) + (c.u - b.u) * (p.v - c.v)) / denominator;
            double wb = ((c.v - a.v) * (p.u - c.u) + (a.u - c.u) * (p.v - c.v)) / denominator;
            return wa * triangle.a.distanceRatio + wb * triangle.b.distanceRatio
                    + (1 - wa - wb) * triangle.c.distanceRatio;
        }

        /** Part of this triangle where its interpolated danger beats the other source. */
        List<Point> beats(Source other) {
            List<Point> input = List.of(a, b, c);
            double da = dangerAt(a) - other.dangerAt(a);
            double db = dangerAt(b) - other.dangerAt(b);
            double dc = dangerAt(c) - other.dangerAt(c);
            boolean distanceTie = Math.abs(da) < 1e-12 && Math.abs(db) < 1e-12 && Math.abs(dc) < 1e-12;
            if (distanceTie) {
                da = other.distanceAt(a) - distanceAt(a);
                db = other.distanceAt(b) - distanceAt(b);
                dc = other.distanceAt(c) - distanceAt(c);
                if (Math.abs(da) < 1e-12 && Math.abs(db) < 1e-12 && Math.abs(dc) < 1e-12)
                    return id < other.id ? input : List.of();
            }
            if (da <= 0 && db <= 0 && dc <= 0) return List.of();
            if (da >= 0 && db >= 0 && dc >= 0) return input;
            List<Point> output = new ArrayList<>();
            Point previous = c;
            double previousValue = dc;
            for (Point current : input) {
                double value = distanceTie ? other.distanceAt(current) - distanceAt(current)
                        : dangerAt(current) - other.dangerAt(current);
                if ((value >= 0) != (previousValue >= 0)) {
                    double t = previousValue / (previousValue - value);
                    output.add(new Point(lerp(previous.u, current.u, t), lerp(previous.v, current.v, t)));
                }
                if (value >= 0) output.add(current);
                previous = current;
                previousValue = value;
            }
            return output.size() < 3 ? List.of() : output;
        }

        Vertex sample(Point p, Vec3 position) {
            double wa = ((b.v - c.v) * (p.u - c.u) + (c.u - b.u) * (p.v - c.v)) / denominator;
            double wb = ((c.v - a.v) * (p.u - c.u) + (a.u - c.u) * (p.v - c.v)) / denominator;
            double wc = 1 - wa - wb;
            if (wa < -EPS || wb < -EPS || wc < -EPS) return null;
            return new Vertex(position,
                    (float) Math.clamp(wa * triangle.a.danger + wb * triangle.b.danger + wc * triangle.c.danger, 0, 1),
                    (float) Math.clamp(wa * triangle.a.distanceRatio + wb * triangle.b.distanceRatio
                            + wc * triangle.c.distanceRatio, 0, 1));
        }

        Point closest(Point p) {
            double wa = ((b.v - c.v) * (p.u - c.u) + (c.u - b.u) * (p.v - c.v)) / denominator;
            double wb = ((c.v - a.v) * (p.u - c.u) + (a.u - c.u) * (p.v - c.v)) / denominator;
            if (wa >= 0 && wb >= 0 && wa + wb <= 1) return p;
            Point ab = closestOnSegment(p, a, b), bc = closestOnSegment(p, b, c), ca = closestOnSegment(p, c, a);
            Point nearest = distanceSqr(p, ab) <= distanceSqr(p, bc) ? ab : bc;
            return distanceSqr(p, nearest) <= distanceSqr(p, ca) ? nearest : ca;
        }

        boolean contains(Point p) {
            if (p.u < minU - EPS || p.u > maxU + EPS || p.v < minV - EPS || p.v > maxV + EPS) return false;
            double wa = ((b.v - c.v) * (p.u - c.u) + (c.u - b.u) * (p.v - c.v)) / denominator;
            double wb = ((c.v - a.v) * (p.u - c.u) + (a.u - c.u) * (p.v - c.v)) / denominator;
            return wa >= -EPS && wb >= -EPS && wa + wb <= 1 + EPS;
        }
    }

    private static final class LocalSource {
        final Source source;
        final List<Point> polygon;
        final double minU, maxU, minV, maxV, minDanger, maxDanger, meanDanger;

        LocalSource(Source source, List<Point> polygon) {
            this.source = source;
            this.polygon = polygon;
            double lowU = Double.POSITIVE_INFINITY, lowV = lowU, lowD = lowU;
            double highU = Double.NEGATIVE_INFINITY, highV = highU, highD = highU, sum = 0;
            for (Point p : polygon) {
                lowU = Math.min(lowU, p.u); highU = Math.max(highU, p.u);
                lowV = Math.min(lowV, p.v); highV = Math.max(highV, p.v);
                double danger = source.dangerAt(p);
                lowD = Math.min(lowD, danger); highD = Math.max(highD, danger); sum += danger;
            }
            minU = lowU; maxU = highU; minV = lowV; maxV = highV;
            minDanger = lowD; maxDanger = highD; meanDanger = sum / polygon.size();
        }
    }

    private static final class Plane {
        final Vec3 reference, normal;
        final double distance;
        final int axis;
        final boolean display;
        final List<Area> areas = new ArrayList<>();
        final Map<Long, List<Source>> sourceIndex = new HashMap<>();
        final List<Source> sources = new ArrayList<>();
        final Map<Long, List<Source>> shadeIndex = new HashMap<>();
        final Map<Long, List<Segment>> boundaryIndex = new HashMap<>();
        final Map<Point, Vertex> shadeCache = new HashMap<>();
        Area union;

        Plane(Vec3 reference, Vec3 normal, double distance, int axis, boolean display) {
            this.reference = reference;
            this.normal = normal;
            this.distance = distance;
            this.axis = axis;
            this.display = display;
        }

        Point project(Vec3 p) {
            p = p.subtract(reference);
            return axis == 0 ? new Point(p.z, p.y) : axis == 1 ? new Point(p.x, p.z) : new Point(p.x, p.y);
        }

        Vec3 unproject(Point p) {
            Vec3 local = axis == 0 ? new Vec3((distance - normal.z * p.u - normal.y * p.v) / normal.x, p.v, p.u)
                    : axis == 1 ? new Vec3(p.u, (distance - normal.x * p.u - normal.z * p.v) / normal.y, p.v)
                    : new Vec3(p.u, p.v, (distance - normal.x * p.u - normal.y * p.v) / normal.z);
            return local.add(reference);
        }

        void add(Triangle triangle) {
            Source source = new Source(triangle, this);
            if (Math.abs(source.denominator) < EPS) return;
            sources.add(source);
            areas.add(new Area(source.area));
            double minU = Math.min(source.a.u, Math.min(source.b.u, source.c.u));
            double maxU = Math.max(source.a.u, Math.max(source.b.u, source.c.u));
            double minV = Math.min(source.a.v, Math.min(source.b.v, source.c.v));
            double maxV = Math.max(source.a.v, Math.max(source.b.v, source.c.v));
            for (int u = (int) Math.floor(minU / CELL); u <= (int) Math.floor(maxU / CELL); u++) {
                for (int v = (int) Math.floor(minV / CELL); v <= (int) Math.floor(maxV / CELL); v++) {
                    sourceIndex.computeIfAbsent(cellKey(u * CELL, v * CELL), ignored -> new ArrayList<>()).add(source);
                }
            }
            if (display) {
                for (int u = (int) Math.floor((minU - BLEND_WIDTH) / SHADE_CELL); u <= (int) Math.floor((maxU + BLEND_WIDTH) / SHADE_CELL); u++) {
                    for (int v = (int) Math.floor((minV - BLEND_WIDTH) / SHADE_CELL); v <= (int) Math.floor((maxV + BLEND_WIDTH) / SHADE_CELL); v++)
                        shadeIndex.computeIfAbsent(cellKey(u * SHADE_CELL, v * SHADE_CELL, SHADE_CELL), ignored -> new ArrayList<>()).add(source);
                }
            }
        }

        void build(List<Triangle> fill, List<Edge> boundary) {
            if (areas.isEmpty()) return;
            // Balanced union avoids repeatedly combining a large accumulated boundary with tiny triangles.
            List<Area> pending = areas;
            while (pending.size() > 1) {
                List<Area> next = new ArrayList<>((pending.size() + 1) / 2);
                for (int i = 0; i < pending.size(); i += 2) {
                    Area area = pending.get(i);
                    if (i + 1 < pending.size()) area.add(pending.get(i + 1));
                    next.add(area);
                }
                pending = next;
            }
            union = pending.getFirst();
            if (display) shadeIndex.values().forEach(list -> list.sort((a, b) -> Double.compare(b.maxDanger, a.maxDanger)));
            boundary(union, boundary);
            for (var tile : sourceIndex.entrySet()) {
                long tileKey = tile.getKey();
                double u0 = (int) (tileKey >> 32) * CELL, v0 = (int) tileKey * CELL;
                List<Point> square = List.of(new Point(u0, v0), new Point(u0 + CELL, v0),
                        new Point(u0 + CELL, v0 + CELL), new Point(u0, v0 + CELL));
                List<LocalSource> local = new ArrayList<>();
                for (Source source : tile.getValue()) {
                    List<Point> clipped = List.of(source.a, source.b, source.c);
                    for (int side = 0; side < 4 && clipped.size() >= 3; side++)
                        clipped = clipHalfPlane(clipped, square.get(side), square.get((side + 1) % 4), 1);
                    if (hasArea(clipped)) local.add(new LocalSource(source, clipped));
                }
                local.sort((a, b) -> {
                    int priority = Double.compare(b.meanDanger, a.meanDanger);
                    return priority != 0 ? priority : Integer.compare(a.source.id, b.source.id);
                });
                for (LocalSource source : local) {
                    List<List<Point>> fragments = new ArrayList<>();
                    fragments.add(source.polygon);
                    for (LocalSource other : local) {
                        if (other == source || other.maxDanger < source.minDanger
                                || other.minU >= source.maxU || other.maxU <= source.minU
                                || other.minV >= source.maxV || other.maxV <= source.minV) continue;
                        List<Point> occluder = other.source.beats(source.source);
                        if (occluder.isEmpty()) continue;
                        List<List<Point>> remaining = new ArrayList<>();
                        for (List<Point> fragment : fragments) subtractConvex(fragment, occluder, remaining);
                        fragments = remaining;
                        if (fragments.isEmpty()) break;
                    }
                    for (List<Point> fragment : fragments) {
                        for (int i = 1; i + 1 < fragment.size(); i++)
                            addTriangle(fill, source.source, fragment.getFirst(), fragment.get(i), fragment.get(i + 1));
                    }
                }
            }
        }

        void boundary(Area union, List<Edge> boundary) {
            PathIterator iterator = union.getPathIterator(null);
            double[] coordinates = new double[6];
            Point first = null, previous = null;
            while (!iterator.isDone()) {
                int type = iterator.currentSegment(coordinates);
                Point point = new Point(coordinates[0], coordinates[1]);
                if (type == PathIterator.SEG_MOVETO) first = previous = point;
                else if (type == PathIterator.SEG_LINETO) {
                    if (!previous.equals(point)) addBoundary(previous, point, boundary);
                    previous = point;
                } else if (type == PathIterator.SEG_CLOSE) {
                    if (!previous.equals(first)) addBoundary(previous, first, boundary);
                } else throw new IllegalStateException("A triangle union must have straight edges");
                iterator.next();
            }
        }

        /** Even/odd scan strips triangulate islands and holes without overlapping fill. */
        List<Triangle> coverage(List<Edge> edges) {
            TreeSet<Double> levels = new TreeSet<>();
            List<Segment> segments = new ArrayList<>();
            for (Edge edge : edges) {
                Point a = project(edge.a), b = project(edge.b);
                levels.add(a.v); levels.add(b.v);
                if (Math.abs(a.v - b.v) > EPS) segments.add(new Segment(a, b));
            }
            List<Double> sorted = new ArrayList<>(levels);
            List<Triangle> fill = new ArrayList<>();
            for (int i = 0; i + 1 < sorted.size(); i++) {
                double low = sorted.get(i), high = sorted.get(i + 1), middle = (low + high) / 2;
                if (high - low < EPS) continue;
                List<Segment> active = new ArrayList<>();
                for (Segment edge : segments) if (middle > Math.min(edge.a.v, edge.b.v) && middle < Math.max(edge.a.v, edge.b.v)) active.add(edge);
                active.sort((a, b) -> Double.compare(intersectionU(a, middle), intersectionU(b, middle)));
                if ((active.size() & 1) != 0) throw new IllegalStateException("Unbalanced union boundary");
                for (int j = 0; j + 1 < active.size(); j += 2) {
                    Point a = new Point(intersectionU(active.get(j), low), low), b = new Point(intersectionU(active.get(j + 1), low), low);
                    Point c = new Point(intersectionU(active.get(j + 1), high), high), d = new Point(intersectionU(active.get(j), high), high);
                    coverageTriangle(fill, a, b, c);
                    coverageTriangle(fill, a, c, d);
                }
            }
            return fill;
        }

        double intersectionU(Segment edge, double v) { return lerp(edge.a.u, edge.b.u, (v - edge.a.v) / (edge.b.v - edge.a.v)); }
        void coverageTriangle(List<Triangle> fill, Point a, Point b, Point c) {
            if (Math.abs(side(a, b, c)) > EPS * EPS) fill.add(new Triangle(new Vertex(unproject(a), 0, 0.5F),
                    new Vertex(unproject(b), 0, 0.5F), new Vertex(unproject(c), 0, 0.5F)));
        }

        void addTriangle(List<Triangle> fill, Source source, Point a, Point b, Point c) {
            if (Math.abs((b.u - a.u) * (c.v - a.v) - (b.v - a.v) * (c.u - a.u)) < EPS) return;
            if (display) {
                displayTriangle(fill, a, b, c, 0);
                return;
            }
            Vertex va = source.sample(a, unproject(a)), vb = source.sample(b, unproject(b)), vc = source.sample(c, unproject(c));
            if (va != null && vb != null && vc != null) fill.add(new Triangle(va, vb, vc));
        }

        void addBoundary(Point a, Point b, List<Edge> boundary) {
            boundary.add(new Edge(unproject(a), unproject(b), normal));
            if (!display) return;
            Segment segment = new Segment(a, b);
            for (int u = (int) Math.floor(Math.min(a.u, b.u) / CELL); u <= (int) Math.floor(Math.max(a.u, b.u) / CELL); u++) {
                for (int v = (int) Math.floor(Math.min(a.v, b.v) / CELL); v <= (int) Math.floor(Math.max(a.v, b.v) / CELL); v++)
                    boundaryIndex.computeIfAbsent(cellKey(u * CELL, v * CELL), ignored -> new ArrayList<>()).add(segment);
            }
        }

        Vertex displayVertex(Point p) {
            // Shared positions must have identical danger even when adjacent fragments have different owners.
            Point key = new Point(Math.rint(p.u * 1e6) / 1e6, Math.rint(p.v * 1e6) / 1e6);
            Vertex shaded = shadeCache.computeIfAbsent(key, ignored -> {
                double risk = 0;
                for (Source source : shadeIndex.getOrDefault(cellKey(p.u, p.v, SHADE_CELL), List.of())) {
                    if (source.maxDanger <= risk) break;
                    if (p.u < source.minU - BLEND_WIDTH || p.u > source.maxU + BLEND_WIDTH
                            || p.v < source.minV - BLEND_WIDTH || p.v > source.maxV + BLEND_WIDTH) continue;
                    Point nearest = source.closest(p);
                    double distance = Math.sqrt(distanceSqr(p, nearest));
                    if (distance >= BLEND_WIDTH) continue;
                    double t = distance / BLEND_WIDTH;
                    double weight = 1 - t * t * (3 - 2 * t);
                    double candidate = Math.clamp(source.dangerAt(nearest), 0, 1) * weight;
                    if (candidate <= risk) continue;
                    if (distance > EPS && !connected(p, nearest)) continue;
                    // Feather outward into existing union coverage: high risk is never reduced to hide a seam.
                    risk = candidate;
                }
                return new Vertex(unproject(p), (float) risk, 0.5F);
            });
            return new Vertex(unproject(p), shaded.danger, 0.5F);
        }

        boolean connected(Point a, Point b) {
            // Both endpoints are covered. A connection crossing a union edge leaves the surface.
            // Local segment queries also catch holes narrower than a fixed sampling step.
            double du = b.u - a.u, dv = b.v - a.v;
            Point middle = midpoint(a, b);
            boolean covered = false;
            for (Source source : sourceIndex.getOrDefault(cellKey(middle.u, middle.v), List.of())) {
                if (source.contains(middle)) { covered = true; break; }
            }
            // Endpoints can both lie on boundaries, with the entire connecting segment in a gap.
            if (!covered) return false;
            for (int u = (int) Math.floor(Math.min(a.u, b.u) / CELL); u <= (int) Math.floor(Math.max(a.u, b.u) / CELL); u++) {
                for (int v = (int) Math.floor(Math.min(a.v, b.v) / CELL); v <= (int) Math.floor(Math.max(a.v, b.v) / CELL); v++) {
                    for (Segment edge : boundaryIndex.getOrDefault(cellKey(u * CELL, v * CELL), List.of())) {
                        double eu = edge.b.u - edge.a.u, ev = edge.b.v - edge.a.v;
                        double cross = du * ev - dv * eu;
                        if (Math.abs(cross) < 1e-12) continue;
                        double au = edge.a.u - a.u, av = edge.a.v - a.v;
                        double t = (au * ev - av * eu) / cross;
                        double s = (au * dv - av * du) / cross;
                        if (t > EPS && t < 1 - EPS && s >= -EPS && s <= 1 + EPS) return false;
                    }
                }
            }
            return true;
        }

        void displayTriangle(List<Triangle> fill, Point a, Point b, Point c, int depth) {
            double ab = distanceSqr(a, b), bc = distanceSqr(b, c), ca = distanceSqr(c, a);
            boolean refine = Math.max(ab, Math.max(bc, ca)) > CELL * CELL;
            if (depth < 10) {
                float ra = displayVertex(a).danger, rb = displayVertex(b).danger, rc = displayVertex(c).danger;
                double low = Math.min(ra, Math.min(rb, rc)), high = Math.max(ra, Math.max(rb, rc));
                boolean threshold = low < 95.5 / 255.0 && high >= 95.5 / 255.0 || low < 42.5 / 255.0 && high >= 42.5 / 255.0;
                refine |= threshold && Math.max(ab, Math.max(bc, ca)) > DISPLAY_EDGE * DISPLAY_EDGE;
                Point center = new Point((a.u + b.u + c.u) / 3, (a.v + b.v + c.v) / 3);
                refine |= Math.abs(displayVertex(center).danger - (ra + rb + rc) / 3) > 0.02;
                Point middle = ab >= bc && ab >= ca ? midpoint(a, b) : bc >= ca ? midpoint(b, c) : midpoint(c, a);
                double average = ab >= bc && ab >= ca ? (ra + rb) / 2 : bc >= ca ? (rb + rc) / 2 : (rc + ra) / 2;
                refine |= Math.abs(displayVertex(middle).danger - average) > 0.02;
            }
            // Refine nonlinear shading near a hole corner, even when the triangle is already small.
            if (depth < 10 && refine) {
                if (ab >= bc && ab >= ca) {
                    Point mid = midpoint(a, b);
                    displayTriangle(fill, a, mid, c, depth + 1);
                    displayTriangle(fill, mid, b, c, depth + 1);
                } else if (bc >= ca) {
                    Point mid = midpoint(b, c);
                    displayTriangle(fill, a, b, mid, depth + 1);
                    displayTriangle(fill, a, mid, c, depth + 1);
                } else {
                    Point mid = midpoint(c, a);
                    displayTriangle(fill, a, b, mid, depth + 1);
                    displayTriangle(fill, mid, b, c, depth + 1);
                }
            } else fill.add(new Triangle(displayVertex(a), displayVertex(b), displayVertex(c)));
        }
    }

    private static Point midpoint(Point a, Point b) { return new Point((a.u + b.u) / 2, (a.v + b.v) / 2); }

    private static double distanceSqr(Point a, Point b) {
        double u = a.u - b.u, v = a.v - b.v;
        return u * u + v * v;
    }

    private static Point closestOnSegment(Point p, Point a, Point b) {
        double length = distanceSqr(a, b);
        double t = length < EPS * EPS ? 0 : Math.clamp(((p.u - a.u) * (b.u - a.u)
                + (p.v - a.v) * (b.v - a.v)) / length, 0, 1);
        return new Point(lerp(a.u, b.u, t), lerp(a.v, b.v, t));
    }

    /** Convex half-plane subtraction keeps disjoint fragments without expensive general polygon overlays. */
    private static void subtractConvex(List<Point> polygon, List<Point> cutter, List<List<Point>> output) {
        double signedArea = 0;
        for (int i = 0; i < cutter.size(); i++) {
            Point a = cutter.get(i), b = cutter.get((i + 1) % cutter.size());
            signedArea += a.u * b.v - b.u * a.v;
        }
        if (Math.abs(signedArea) < EPS * EPS) { output.add(polygon); return; }
        double sign = Math.signum(signedArea);
        // If any cutter half-plane excludes the entire fragment, it is untouched.
        for (int i = 0; i < cutter.size(); i++) {
            Point a = cutter.get(i), b = cutter.get((i + 1) % cutter.size());
            boolean intersects = false;
            for (Point p : polygon) if (side(a, b, p) * sign > 0) { intersects = true; break; }
            if (!intersects) { output.add(polygon); return; }
        }
        List<Point> inside = polygon;
        for (int i = 0; i < cutter.size() && inside.size() >= 3; i++) {
            Point a = cutter.get(i), b = cutter.get((i + 1) % cutter.size());
            List<Point> outside = clipHalfPlane(inside, a, b, -sign);
            if (hasArea(outside)) output.add(outside);
            inside = clipHalfPlane(inside, a, b, sign);
        }
    }

    private static double side(Point a, Point b, Point p) {
        return (b.u - a.u) * (p.v - a.v) - (b.v - a.v) * (p.u - a.u);
    }

    private static List<Point> clipHalfPlane(List<Point> input, Point a, Point b, double sign) {
        boolean anyInside = false, anyOutside = false;
        for (Point p : input) {
            double value = side(a, b, p) * sign;
            if (value > 0) anyInside = true;
            else if (value < 0) anyOutside = true;
        }
        if (!anyInside) return List.of();
        if (!anyOutside) return input;
        List<Point> output = new ArrayList<>();
        Point previous = input.getLast();
        double previousValue = side(a, b, previous) * sign;
        for (Point current : input) {
            double value = side(a, b, current) * sign;
            if ((value >= 0) != (previousValue >= 0)) {
                double t = previousValue / (previousValue - value);
                output.add(new Point(lerp(previous.u, current.u, t), lerp(previous.v, current.v, t)));
            }
            if (value >= 0) output.add(current);
            previous = current;
            previousValue = value;
        }
        return output;
    }

    private static boolean hasArea(List<Point> polygon) {
        if (polygon.size() < 3) return false;
        Point origin = polygon.getFirst();
        double area = 0;
        for (int i = 1; i + 1 < polygon.size(); i++) area += side(origin, polygon.get(i), polygon.get(i + 1));
        return Math.abs(area) > EPS * EPS;
    }

    private static Area polygonArea(List<Point> points) {
        Path2D.Double path = new Path2D.Double();
        Point first = points.getFirst();
        path.moveTo(first.u, first.v);
        for (int i = 1; i < points.size(); i++) path.lineTo(points.get(i).u, points.get(i).v);
        path.closePath();
        return new Area(path);
    }

    private static double lerp(double a, double b, double t) { return a + (b - a) * t; }

    /** Remove shared edge intervals between adjacent planes without dropping partial exposed boundaries. */
    private static List<Edge> removeSurfaceSeams(List<Edge> edges) {
        Map<BoundaryCell, List<Integer>> index = new HashMap<>();
        for (int i = 0; i < edges.size(); i++) {
            Edge edge = edges.get(i);
            for (int x = boundaryCell(Math.min(edge.a.x, edge.b.x) - EPS); x <= boundaryCell(Math.max(edge.a.x, edge.b.x) + EPS); x++)
                for (int y = boundaryCell(Math.min(edge.a.y, edge.b.y) - EPS); y <= boundaryCell(Math.max(edge.a.y, edge.b.y) + EPS); y++)
                    for (int z = boundaryCell(Math.min(edge.a.z, edge.b.z) - EPS); z <= boundaryCell(Math.max(edge.a.z, edge.b.z) + EPS); z++)
                        index.computeIfAbsent(new BoundaryCell(x, y, z), ignored -> new ArrayList<>()).add(i);
        }
        List<Edge> result = new ArrayList<>();
        for (int i = 0; i < edges.size(); i++) {
            Edge edge = edges.get(i);
            Vec3 direction = edge.b.subtract(edge.a);
            double lengthSqr = direction.lengthSqr();
            if (lengthSqr < EPS * EPS) continue;
            List<double[]> overlaps = new ArrayList<>();
            TreeSet<Double> cuts = new TreeSet<>();
            cuts.add(0.0);
            cuts.add(1.0);
            HashSet<Integer> candidates = new HashSet<>();
            for (int x = boundaryCell(Math.min(edge.a.x, edge.b.x) - EPS); x <= boundaryCell(Math.max(edge.a.x, edge.b.x) + EPS); x++)
                for (int y = boundaryCell(Math.min(edge.a.y, edge.b.y) - EPS); y <= boundaryCell(Math.max(edge.a.y, edge.b.y) + EPS); y++)
                    for (int z = boundaryCell(Math.min(edge.a.z, edge.b.z) - EPS); z <= boundaryCell(Math.max(edge.a.z, edge.b.z) + EPS); z++)
                        candidates.addAll(index.getOrDefault(new BoundaryCell(x, y, z), List.of()));
            for (int j : candidates) {
                if (i == j) continue;
                Edge other = edges.get(j);
                Vec3 deltaA = other.a.subtract(edge.a), deltaB = other.b.subtract(edge.a);
                if (deltaA.cross(direction).lengthSqr() > EPS * EPS * lengthSqr
                        || deltaB.cross(direction).lengthSqr() > EPS * EPS * lengthSqr) continue;
                double ta = deltaA.dot(direction) / lengthSqr, tb = deltaB.dot(direction) / lengthSqr;
                double start = Math.max(0, Math.min(ta, tb)), end = Math.min(1, Math.max(ta, tb));
                if (end - start > EPS) {
                    overlaps.add(new double[]{start, end});
                    cuts.add(start);
                    cuts.add(end);
                }
            }
            List<Double> ordered = new ArrayList<>(cuts);
            for (int cut = 0; cut + 1 < ordered.size(); cut++) {
                double start = ordered.get(cut), end = ordered.get(cut + 1), mid = (start + end) / 2;
                boolean shared = false;
                for (double[] overlap : overlaps) if (mid > overlap[0] && mid < overlap[1]) { shared = true; break; }
                if (!shared && end - start > EPS) result.add(new Edge(edge.a.add(direction.scale(start)),
                        edge.a.add(direction.scale(end)), edge.normal));
            }
        }
        return List.copyOf(result);
    }
    private record BoundaryCell(int x, int y, int z) {}
    private static int boundaryCell(double value) { return (int) Math.floor(value / CELL); }
}
