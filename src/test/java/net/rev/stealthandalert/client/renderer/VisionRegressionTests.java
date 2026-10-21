package net.rev.stealthandalert.client.renderer;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.rev.stealthandalert.util.BodyExposure;
import net.rev.stealthandalert.util.BodyVisibility;
import net.rev.stealthandalert.util.DetectionMath;

import java.util.ArrayList;
import java.util.List;

/** Dependency-free regressions, run with Gradle verifyVision or the cached-classpath verification script. */
public final class VisionRegressionTests {
    private static int checks;

    public static void main(String[] args) {
        if (List.of(args).contains("--display-benchmark")) {
            coneWorkload(true);
            coneWorkload(true);
            coneWorkload(true);
            return;
        }
        bodyOcclusion();
        discoveryTime();
        overlap();
        holesAndIslands();
        planesAndSeams();
        largeCoordinates();
        orderIndependence();
        randomizedUnion();
        displaySeams();
        coneWorkload();
        coneWorkload();
        coneWorkload();
        System.out.println("Vision regressions passed: " + checks + " checks");
    }

    private static void bodyOcclusion() {
        Vec3 eye = new Vec3(0, 1.62, 0), feet = new Vec3(4, 0, 0);
        AABB lowWall = new AABB(3, 0, -2, 3.1, 1.4, 2);
        BodyExposure standing = BodyVisibility.sample(eye, feet, 0.6, 1.8, 1.62,
                point -> lowWall.clip(eye, point).isEmpty());
        near(standing.head(), 1, "head clears low wall");
        near(standing.torso(), 0, "torso behind low wall");
        near(standing.legs(), 0, "legs behind low wall");
        near(standing.fraction(), 0.3, "head-only awareness multiplier");
        BodyExposure crouched = BodyVisibility.sample(eye, feet, 0.6, 1.5, 1.27,
                point -> lowWall.clip(eye, point).isEmpty());
        check(!crouched.visible(), "crouching below low wall hides all parts");
        AABB highWall = new AABB(2, 0, -2, 2.1, 3, 2);
        check(!BodyVisibility.sample(eye, feet, 0.6, 1.8, 1.62,
                point -> highWall.clip(eye, point).isEmpty()).visible(), "solid wall hides body");
        BodyExposure partial = BodyVisibility.sample(eye, feet, 0.6, 1.8, 1.62, point -> point.z > 0);
        near(partial.head(), 1.0 / 3, "partial head exposure");
        near(partial.torso(), 1.0 / 3, "partial torso exposure");
        near(partial.legs(), 1.0 / 3, "partial legs exposure");
        check(BodyExposure.NONE.grade() == BodyExposure.Grade.HIDDEN, "hidden grade");
        check(new BodyExposure(0, 0, 1).grade() == BodyExposure.Grade.LOW, "legs-only low grade");
        check(BodyExposure.FULL.grade() == BodyExposure.Grade.FULL, "full grade");
    }

    private static void discoveryTime() {
        near(DetectionMath.discoveryTicks(2, 40, 10, 0), 40, "remaining reaction plus remaining awareness");
        near(DetectionMath.discoveryTicks(2, 40, 10, 1), 30, "aware target skips reaction");
        near(DetectionMath.discoveryTicks(2, 40, 10, 2), 0, "tracked target already discovered");
        check(Double.isInfinite(DetectionMath.discoveryTicks(0, 0, 0, 0)), "hidden cannot accumulate");
        near(DetectionMath.danger(0), 1, "immediate is red");
        near(DetectionMath.danger(120), 0.5, "common color scale midpoint");
        near(DetectionMath.danger(Double.POSITIVE_INFINITY), 0, "hidden is lowest danger");
        near(DetectionMath.discoveryTicks(100, 0, 0, 0), 1, "transition tick also accumulates awareness");
        near(DetectionMath.danger(200), 0.375, "ten-second discovery color threshold");
        near(DetectionMath.danger(600), 1.0 / 6.0, "thirty-second discovery color threshold");
        check(DetectionMath.danger(200) > DetectionMath.danger(400) && DetectionMath.danger(400) > 0,
                "slower discovery no longer collapses at 200 ticks");
    }

    private static void overlap() {
        List<SurfaceVisionFusion.Triangle> input = new ArrayList<>();
        rectangle(input, 0, 0, 2, 2, 0, 0.2F);
        rectangle(input, 1, 1, 3, 3, 0, 0.9F);
        var mesh = SurfaceVisionFusion.fuse(input, Vec3.ZERO);
        near(area(mesh), 7, "overlapping rectangles form union");
        near(perimeter(mesh), 12, "no internal overlap boundary");
        near(dangerAt(mesh, new Vec3(1.5, 0, 1.5)), 0.9, "fastest observer wins overlap color");
        near(dangerAt(mesh, new Vec3(0.25, 0, 0.25)), 0.2, "single observer retains color");
        check(!covers(mesh, new Vec3(0.5, 0, 2.5)), "concavity remains empty");
        input.addAll(new ArrayList<>(input));
        var duplicate = SurfaceVisionFusion.fuse(input, Vec3.ZERO);
        near(area(duplicate), 7, "duplicate surfaces do not add fill");
        near(perimeter(duplicate), 12, "duplicate surfaces do not add outlines");
    }

    private static void holesAndIslands() {
        List<SurfaceVisionFusion.Triangle> input = new ArrayList<>();
        rectangle(input, 0, 0, 4, 1, 0, 0.5F);
        rectangle(input, 0, 3, 4, 4, 0, 0.5F);
        rectangle(input, 0, 1, 1, 3, 0, 0.5F);
        rectangle(input, 3, 1, 4, 3, 0, 0.5F);
        var ring = SurfaceVisionFusion.fuse(input, Vec3.ZERO);
        near(area(ring), 12, "hole excludes fill area");
        near(perimeter(ring), 24, "hole gets an inner boundary");
        check(!covers(ring, new Vec3(2, 0, 2)), "hole remains transparent");
        rectangle(input, 6, 0, 7, 1, 0, 0.5F);
        var islands = SurfaceVisionFusion.fuse(input, Vec3.ZERO);
        near(area(islands), 13, "disconnected island retained");
        check(!covers(islands, new Vec3(5, 0, 0.5)), "islands are not bridged");
        List<SurfaceVisionFusion.Triangle> touching = new ArrayList<>();
        rectangle(touching, 0, 0, 1, 1, 0, 0.5F);
        rectangle(touching, 1, 1, 2, 2, 0, 0.5F);
        near(area(SurfaceVisionFusion.fuse(touching, Vec3.ZERO)), 2, "point-touch keeps both islands");
        check(SurfaceVisionFusion.fuse(List.of(), Vec3.ZERO).triangles().isEmpty(), "empty scene supported");
    }

    private static void planesAndSeams() {
        List<SurfaceVisionFusion.Triangle> input = new ArrayList<>();
        rectangle(input, 0, 0, 2, 2, 0, 0.2F);
        rectangle(input, 0, 0, 2, 2, 3, 0.8F);
        var floors = SurfaceVisionFusion.fuse(input, Vec3.ZERO);
        near(area(floors), 8, "same XZ on two floors remains separate");
        near(perimeter(floors), 16, "both floor boundaries retained");
        near(dangerAt(floors, new Vec3(1, 0, 1)), 0.2, "upper floor color does not bleed down");
        input.clear();
        rectangle(input, 0, 0, 2, 4, 0, 0.5F);
        wall(input, 0, 0, 1, 2, 3);
        var seam = SurfaceVisionFusion.fuse(input, Vec3.ZERO);
        near(area(seam), 12, "floor and wall surface area retained");
        near(perimeter(seam), 16, "partial floor-wall seam is removed");
        input.clear();
        var a = vertex(new Vec3(0, 0, 0), 0.5F);
        var b = vertex(new Vec3(2, 1, 0), 0.5F);
        var c = vertex(new Vec3(2, 1, 2), 0.5F);
        var d = vertex(new Vec3(0, 0, 2), 0.5F);
        input.add(new SurfaceVisionFusion.Triangle(a, b, c));
        input.add(new SurfaceVisionFusion.Triangle(a, c, d));
        near(area(SurfaceVisionFusion.fuse(input, Vec3.ZERO)), Math.sqrt(5) * 2, "sloped surface retained");
    }

    private static void largeCoordinates() {
        List<SurfaceVisionFusion.Triangle> input = new ArrayList<>();
        rectangle(input, 29_999_990, -29_999_990, 29_999_992, -29_999_988, 64, 0.5F);
        var mesh = SurfaceVisionFusion.fuse(input, new Vec3(29_999_990, 64, -29_999_990));
        near(area(mesh), 4, "local coordinates work near world border");
        near(perimeter(mesh), 8, "world-border outline retained");
    }

    private static void orderIndependence() {
        List<SurfaceVisionFusion.Triangle> input = new ArrayList<>();
        rectangle(input, 0, 0, 4, 4, 0, 0.1F);
        rectangle(input, 1, 1, 2, 2, 0, 0.95F);
        var forward = SurfaceVisionFusion.fuse(input, Vec3.ZERO);
        java.util.Collections.reverse(input);
        var backward = SurfaceVisionFusion.fuse(input, Vec3.ZERO);
        near(area(forward), area(backward), "union independent of entity order");
        near(dangerAt(forward, new Vec3(1.5, 0, 1.5)), dangerAt(backward, new Vec3(1.5, 0, 1.5)),
                "danger independent of entity order");
        near(dangerAt(forward, new Vec3(1.5, 0, 1.5)), 0.95, "enclosed danger peak retained");
        input.clear();
        var a = new SurfaceVisionFusion.Vertex(new Vec3(0, 0, 0), 0.5F, 0.2F);
        var b = new SurfaceVisionFusion.Vertex(new Vec3(2, 0, 0), 0.5F, 0.2F);
        var c = new SurfaceVisionFusion.Vertex(new Vec3(0, 0, 2), 0.5F, 0.2F);
        input.add(new SurfaceVisionFusion.Triangle(a, b, c));
        input.add(new SurfaceVisionFusion.Triangle(new SurfaceVisionFusion.Vertex(a.position(), 0.5F, 0.8F),
                new SurfaceVisionFusion.Vertex(b.position(), 0.5F, 0.8F),
                new SurfaceVisionFusion.Vertex(c.position(), 0.5F, 0.8F)));
        var tied = SurfaceVisionFusion.fuse(input, Vec3.ZERO);
        check(tied.triangles().stream().allMatch(t -> Math.abs(t.a().distanceRatio() - 0.2) < 1e-5),
                "equal danger uses the nearer cone fade");
        java.util.Collections.reverse(input);
        tied = SurfaceVisionFusion.fuse(input, Vec3.ZERO);
        check(tied.triangles().stream().allMatch(t -> Math.abs(t.a().distanceRatio() - 0.2) < 1e-5),
                "equal danger fade is independent of entity order");
    }

    private static void randomizedUnion() {
        java.util.Random random = new java.util.Random(42381);
        List<SurfaceVisionFusion.Triangle> input = new ArrayList<>();
        List<double[]> rectangles = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            double x = random.nextDouble() * 8, z = random.nextDouble() * 8;
            double w = 0.5 + random.nextDouble() * 3, h = 0.5 + random.nextDouble() * 3;
            float danger = random.nextFloat();
            rectangle(input, x, z, x + w, z + h, 0, danger);
            rectangles.add(new double[]{x, z, x + w, z + h, danger});
        }
        var mesh = SurfaceVisionFusion.fuse(input, Vec3.ZERO);
        for (int i = 0; i < 300; i++) {
            double x = random.nextDouble() * 12, z = random.nextDouble() * 12;
            double expected = -1;
            for (double[] r : rectangles) {
                if (x > r[0] && x < r[2] && z > r[1] && z < r[3]) expected = Math.max(expected, r[4]);
            }
            near(dangerAt(mesh, new Vec3(x, 0, z)), expected, "random union coverage and strongest danger");
        }
        // Two intersecting danger gradients must exchange ownership where their values cross.
        input.clear();
        var a = vertex(new Vec3(0, 0, 0), 0);
        var b = vertex(new Vec3(4, 0, 0), 1);
        var c = vertex(new Vec3(0, 0, 4), 0);
        input.add(new SurfaceVisionFusion.Triangle(a, b, c));
        input.add(new SurfaceVisionFusion.Triangle(vertex(a.position(), 1), vertex(b.position(), 0),
                vertex(c.position(), 1)));
        mesh = SurfaceVisionFusion.fuse(input, Vec3.ZERO);
        near(area(mesh), 8, "gradient intersection preserves union area");
        near(dangerAt(mesh, new Vec3(0.5, 0, 0.5)), 0.875, "first danger gradient wins");
        near(dangerAt(mesh, new Vec3(3, 0, 0.5)), 0.75, "second danger gradient wins");
        near(dangerAt(mesh, new Vec3(2, 0, 0.5)), 0.5, "gradient ownership intersection");
    }

    private static void displaySeams() {
        List<SurfaceVisionFusion.Triangle> input = new ArrayList<>();
        rectangle(input, 0, 0, 2, 2, 0, 0.85F);
        rectangle(input, 1, 0, 4, 2, 0, 0.2F);
        var mesh = SurfaceVisionFusion.fuseForDisplay(input, Vec3.ZERO);
        near(area(mesh), 8, "display blending does not change coverage");
        near(perimeter(mesh), 12, "display blending does not change boundary");
        near(dangerAt(mesh, new Vec3(1.5, 0, 1)), 0.85, "seam blending preserves high danger");
        double left = dangerAt(mesh, new Vec3(1.999, 0, 1));
        double right = dangerAt(mesh, new Vec3(2.001, 0, 1));
        check(Math.abs(left - right) < 0.01, "ending observer coverage has no danger jump");
        near(dangerAt(mesh, new Vec3(3.2, 0, 1)), 0.2, "feathering is limited to the seam neighborhood");
        check(mesh.triangles().stream().allMatch(t -> t.a().distanceRatio() == 0.5F
                && t.b().distanceRatio() == 0.5F && t.c().distanceRatio() == 0.5F),
                "display surface has no observer-dependent distance tint");
        java.util.Map<Vec3, Float> colors = new java.util.HashMap<>();
        boolean consistent = true;
        for (var triangle : mesh.triangles()) {
            for (var vertex : List.of(triangle.a(), triangle.b(), triangle.c())) {
                Vec3 p = vertex.position();
                Vec3 key = new Vec3(Math.rint(p.x * 1e6), Math.rint(p.y * 1e6), Math.rint(p.z * 1e6));
                Float previous = colors.putIfAbsent(key, vertex.danger());
                if (previous != null && previous.floatValue() != vertex.danger()) consistent = false;
            }
        }
        check(consistent, "shared display vertices use one fused danger value");
        input.clear();
        rectangle(input, 0, 0, 1, 1, 0, 0.95F);
        rectangle(input, 1.2, 0, 2.2, 1, 0, 0.2F);
        mesh = SurfaceVisionFusion.fuseForDisplay(input, Vec3.ZERO);
        near(area(mesh), 2, "nearby islands remain separate");
        check(!covers(mesh, new Vec3(1.1, 0, 0.5)), "blending never fills a gap");
        near(dangerAt(mesh, new Vec3(1.25, 0, 0.5)), 0.2, "color cannot jump across an island gap");
        near(dangerAt(mesh, new Vec3(1.2, 0, 0.5)), 0.2, "island boundary cannot receive color across a gap");
        input.clear();
        rectangle(input, 0, 0, 1, 1, 0, 0.95F);
        rectangle(input, 1.01, 0, 2.01, 1, 0, 0.2F);
        mesh = SurfaceVisionFusion.fuseForDisplay(input, Vec3.ZERO);
        near(dangerAt(mesh, new Vec3(1.02, 0, 0.5)), 0.2, "even a narrow gap blocks color transfer");
        input.clear();
        rectangle(input, 0, 0, 3, 1, 0, 0.2F);
        rectangle(input, 0, 1, 1, 2, 0, 0.95F);
        rectangle(input, 1.01, 1, 3, 2, 0, 0.2F);
        rectangle(input, 0, 2, 3, 3, 0, 0.2F);
        mesh = SurfaceVisionFusion.fuseForDisplay(input, Vec3.ZERO);
        check(!covers(mesh, new Vec3(1.005, 0, 1.5)), "narrow hole retains empty coverage");
        near(dangerAt(mesh, new Vec3(1.02, 0, 1.5)), 0.2, "same island does not blend across its hole");
    }

    private static void coneWorkload() {
        coneWorkload(false);
    }

    private static void coneWorkload(boolean display) {
        List<SurfaceVisionFusion.Triangle> input = new ArrayList<>();
        for (int mob = 0; mob < 12; mob++) {
            Vec3[][] points = new Vec3[17][7];
            for (int yaw = 0; yaw <= 16; yaw++) {
                double angle = mob * 0.21 + (yaw / 16.0 - 0.5) * 1.8;
                for (int pitch = 0; pitch <= 6; pitch++) {
                    double distance = pitch == 6 ? 32 : Math.min(32, 1.6 / Math.tan((6 - pitch) / 6.0));
                    points[yaw][pitch] = new Vec3(mob % 4 * 3 + Math.cos(angle) * distance, 0,
                            mob / 4 * 3 + Math.sin(angle) * distance);
                }
            }
            for (int yaw = 0; yaw < 16; yaw++) {
                for (int pitch = 0; pitch < 6; pitch++) {
                    float danger = (6 - pitch) / 6.0F;
                    var a = vertex(points[yaw][pitch], danger);
                    var b = vertex(points[yaw + 1][pitch], danger);
                    var c = vertex(points[yaw + 1][pitch + 1], Math.max(0, danger - 1 / 6.0F));
                    var d = vertex(points[yaw][pitch + 1], Math.max(0, danger - 1 / 6.0F));
                    input.add(new SurfaceVisionFusion.Triangle(a, b, c));
                    input.add(new SurfaceVisionFusion.Triangle(a, c, d));
                }
            }
        }
        long started = System.nanoTime();
        var mesh = display ? SurfaceVisionFusion.fuseForDisplay(input, Vec3.ZERO)
                : SurfaceVisionFusion.fuse(input, Vec3.ZERO);
        check(!mesh.triangles().isEmpty(), "dense cone workload has fill");
        System.out.printf("12-cone %s fusion: %.1f ms, %d input triangles, %d output triangles%n",
                display ? "display" : "exact", (System.nanoTime() - started) / 1_000_000.0,
                input.size(), mesh.triangles().size());
    }

    private static void rectangle(List<SurfaceVisionFusion.Triangle> list, double x0, double z0,
                                  double x1, double z1, double y, float danger) {
        var a = vertex(new Vec3(x0, y, z0), danger);
        var b = vertex(new Vec3(x1, y, z0), danger);
        var c = vertex(new Vec3(x1, y, z1), danger);
        var d = vertex(new Vec3(x0, y, z1), danger);
        list.add(new SurfaceVisionFusion.Triangle(a, b, c));
        list.add(new SurfaceVisionFusion.Triangle(a, c, d));
    }

    private static void wall(List<SurfaceVisionFusion.Triangle> list, double x, double y0,
                             double z0, double y1, double z1) {
        var a = vertex(new Vec3(x, y0, z0), 0.5F);
        var b = vertex(new Vec3(x, y1, z0), 0.5F);
        var c = vertex(new Vec3(x, y1, z1), 0.5F);
        var d = vertex(new Vec3(x, y0, z1), 0.5F);
        list.add(new SurfaceVisionFusion.Triangle(a, b, c));
        list.add(new SurfaceVisionFusion.Triangle(a, c, d));
    }

    private static SurfaceVisionFusion.Vertex vertex(Vec3 point, float danger) {
        return new SurfaceVisionFusion.Vertex(point, danger, 0.5F);
    }

    private static double area(SurfaceVisionFusion.Mesh mesh) {
        return mesh.triangles().stream().mapToDouble(t -> t.b().position().subtract(t.a().position())
                .cross(t.c().position().subtract(t.a().position())).length() * 0.5).sum();
    }

    private static double perimeter(SurfaceVisionFusion.Mesh mesh) {
        return mesh.boundary().stream().mapToDouble(edge -> edge.a().distanceTo(edge.b())).sum();
    }

    private static boolean covers(SurfaceVisionFusion.Mesh mesh, Vec3 point) {
        return dangerAt(mesh, point) >= 0;
    }

    private static double dangerAt(SurfaceVisionFusion.Mesh mesh, Vec3 point) {
        double danger = -1;
        for (var triangle : mesh.triangles()) {
            Vec3 a = triangle.a().position(), ab = triangle.b().position().subtract(a);
            Vec3 ac = triangle.c().position().subtract(a), ap = point.subtract(a);
            Vec3 normal = ab.cross(ac);
            if (Math.abs(ap.dot(normal.normalize())) > 1e-6) continue;
            double aa = ab.dot(ab), bb = ac.dot(ac), cc = ab.dot(ac);
            double determinant = aa * bb - cc * cc;
            if (determinant < 1e-12) continue;
            double b = (ap.dot(ab) * bb - ap.dot(ac) * cc) / determinant;
            double c = (ap.dot(ac) * aa - ap.dot(ab) * cc) / determinant;
            double wa = 1 - b - c;
            if (wa >= -1e-6 && b >= -1e-6 && c >= -1e-6) danger = Math.max(danger,
                    wa * triangle.a().danger() + b * triangle.b().danger() + c * triangle.c().danger());
        }
        return danger;
    }

    private static void near(double actual, double expected, String message) {
        check(Math.abs(actual - expected) < 1e-5, message + ": expected " + expected + ", got " + actual);
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
