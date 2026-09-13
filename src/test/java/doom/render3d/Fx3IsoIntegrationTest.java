package doom.render3d;

import doom.play.*;
import doom.system.*;
import doom.testing.TestWads;
import doom.wad.WWad;
import javafx.application.Platform;
import javafx.scene.*;
import javafx.scene.image.WritableImage;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Opt-in real-pipeline integration checks and reproducible screenshots.
 * Run separately: ./gradlew test --tests '*Fx3IsoIntegrationTest' -Diso.probe=1 */
class Fx3IsoIntegrationTest {
    private static final Path OUTPUT = Path.of("build/probe/isometric", TestWads.find().getFileName().toString());

    @Test void renderDemoWithoutChangingSimulationAndExerciseLifecycle() throws Exception {
        assumeTrue("1".equals(System.getProperty("iso.probe")), "Enable with -Diso.probe=1");
        Path wad = TestWads.find();
        TestWads.require(wad);
        CountDownLatch done = new CountDownLatch(1);
        Throwable[] failure = new Throwable[1];
        Platform.startup(() -> {
            try {
                assumeTrue(Platform.isSupported(javafx.application.ConditionalFeature.SCENE3D),
                        "A working JavaFX SCENE3D pipeline is required");
                verifyCutawayRebuild();
                WWad.resetForTests();
                DMain.dDoomMain(new String[] {"-iwad", wad.toString(), "-warp", "1", "1"});
                Files.createDirectories(OUTPUT);
                if (Doomstat.gamemode != Doomstat.GameMode.commercial) traverseE1M1();
                List<String> baseline = demo(false);
                List<String> rendered = demo(true);
                assertEquals(baseline, rendered, "Renderer must not change any sampled gameplay state");
                assertNotEquals(baseline.getFirst(), baseline.getLast());
                freshLevel(1, 1);
                SubScene sub = scene(true);
                Player player = GGame.players[GGame.consoleplayer];
                String before = state();
                Fx3Renderer.renderPlayerView(player);
                assertEquals(before, state());
                assertInstanceOf(ParallelCamera.class, sub.getCamera());
                assertNull(Fx3Renderer.weaponPass1);
                assertNull(Fx3Renderer.weaponPass2);
                snapshot(sub, "start");
                // Framing tracks viewport size, independently of render resolution.
                var pos1 = world(sub).localToParent(player.mo.x/65536.0,
                        -(player.mo.z/65536.0+28), player.mo.y/65536.0);
                assertEquals(320, pos1.getX(), 0.01);
                assertEquals(200, pos1.getY(), 0.01);
                sub.setWidth(1280); sub.setHeight(800);
                var pos2 = world(sub).localToParent(player.mo.x/65536.0,
                        -(player.mo.z/65536.0+28), player.mo.y/65536.0);
                assertEquals(640, pos2.getX(), 0.01);
                assertEquals(400, pos2.getY(), 0.01);
                snapshot(sub, "large");
                sub.setWidth(640); sub.setHeight(400);
                long builds = Fx3Renderer.meshBuildCount;
                // Camera-only tracking does not rebuild level meshes.
                int oldX = player.mo.x;
                for (int i=0; i<20; i++) {
                    player.mo.x += 65536;
                    Fx3Renderer.renderPlayerView(player);
                }
                player.mo.x = oldX;
                Fx3Renderer.renderPlayerView(player);
                assertEquals(builds, Fx3Renderer.meshBuildCount);
                Fx3Sprites sprites = new Fx3Sprites();
                int perspectiveCount = sprites.update(player, 0,0,45,false).size();
                assertEquals(perspectiveCount + 1, sprites.update(player,0,0,45,true).size());
                // Existing playsim door operation updates the isometric geometry.
                doom.render.Line door = Arrays.stream(PSetup.lines)
                        .filter(l -> l.special == 1).findFirst().orElse(null);
                if (door != null) {
                    int ceiling = door.backsector.ceilingheight;
                    PDoors.evVerticalDoor(door, player.mo);
                    for (int i=0; i<20; i++) { GGame.gTicker(); Fx3Renderer.renderPlayerView(player); }
                    assertNotEquals(ceiling, door.backsector.ceilingheight);
                    assertTrue(Fx3Renderer.meshBuildCount > builds);
                    snapshot(sub, "door-moving");
                }
                // Renderer reset must discard scene, buffers, and camera presentation.
                Fx3Renderer.reset();
                assertFalse(Fx3Renderer.isometric);
                assertNull(Fx3Renderer.weaponPass1);
                SubScene perspective = Fx3Renderer.subScene();
                assertInstanceOf(PerspectiveCamera.class, perspective.getCamera());
                perspective.setWidth(640); perspective.setHeight(400);
                new Scene(new Group(perspective));
                Fx3Renderer.renderPlayerView(player);
                snapshot(perspective, "perspective-regression");
                freshLevel(1, 2);
                sub = scene(true);
                Fx3Renderer.renderPlayerView(GGame.players[GGame.consoleplayer]);
                snapshot(sub, "next-level");
                // Save/load replaces the map arrays and player mobj while keeping the view mode.
                byte[] save = SaveGame.save();
                freshLevel(1, 3);
                Fx3Renderer.renderPlayerView(GGame.players[GGame.consoleplayer]);
                SaveGame.load(save);
                byte[] beforeRender = SaveGame.save();
                player = GGame.players[GGame.consoleplayer];
                Fx3Renderer.renderPlayerView(player);
                assertArrayEquals(beforeRender, SaveGame.save());
                snapshot(sub, "loaded");
                PInter.pDamageMobj(player.mo, null, null, 10000);
                for (int i=0; i<35; i++) GGame.gTicker();
                Fx3Renderer.renderPlayerView(player);
                snapshot(sub, "death");
                assertTrue(player.health <= 0);
                freshLevel(1, 1);
                Fx3Renderer.renderPlayerView(GGame.players[GGame.consoleplayer]);
                snapshot(sub, "respawn");
                benchmark(false, 1);
                benchmark(true, 1);
                int busyMap = Doomstat.gamemode == Doomstat.GameMode.commercial ? 16 : 6;
                benchmark(false, busyMap);
                benchmark(true, busyMap);
                shell(wad);
                Files.writeString(OUTPUT.resolve("fixture.txt"), "WAD: " + wad + "\nSHA-256: "
                        + HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                        .digest(Files.readAllBytes(wad))) + "\nJava: " + System.getProperty("java.version")
                        + "\nPipeline: " + System.getProperty("prism.order", "default")
                        + "\n210 demo tics: identical gameplay states with/without isometric rendering\n");
            } catch (Throwable t) {
                failure[0] = t;
            } finally {
                Fx3Renderer.reset();
                Doomstat.demoplayback = false;
                done.countDown();
            }
        });
        assertTrue(done.await(180, TimeUnit.SECONDS), "JavaFX integration timed out");
        if (failure[0] instanceof org.opentest4j.TestAbortedException abort) throw abort;
        if (failure[0] != null) throw new AssertionError(failure[0]);
    }

    private static void verifyCutawayRebuild() {
        doom.render.Seg seg = new doom.render.Seg();
        seg.v1 = new doom.render.Vertex(); seg.v2 = new doom.render.Vertex();
        seg.v1.x = -29*65536; seg.v1.y = -71*65536;
        seg.v2.x = -27*65536; seg.v2.y = -73*65536;
        Map<doom.render.Seg, Integer> selections = new IdentityHashMap<>();
        var first = new Fx3LevelMesh.CutWall(new javafx.scene.shape.MeshView(), null,
                seg, Fx3LevelMesh.SurfaceKind.UPPER, 0, 128);
        first.update(10,0,0,56,selections);
        assertTrue(first.cut);
        var rebuilt = new Fx3LevelMesh.CutWall(new javafx.scene.shape.MeshView(), null,
                seg, Fx3LevelMesh.SurfaceKind.UPPER, 0, 130);
        rebuilt.update(0,0,0,56,selections);
        assertTrue(rebuilt.cut, "Moving sector rebuild must retain cutaway hysteresis");
        selections.clear();
        rebuilt.update(0,0,0,56,selections);
        assertFalse(rebuilt.cut, "Camera discontinuity must discard previous selection");
    }

    private static void traverseE1M1() throws Exception {
        // Fixed map-specific route, driven through the existing keyboard-command builder.
        // No pathfinding, teleport, noclip, or remote door activation is used.
        Doomstat.nomonsters = true;
        freshLevel(1, 1);
        SubScene sub = scene(true);
        int[][] route = {
                {1056,-2976}, {1280,-2976}, {1280,-2624}, {1456,-2496},
                {1664,-2496}, {1664,-2688}, {2200,-2688}, {2400,-2688},
                {2400,-2624}, {2560,-2624}, {2752,-2688}, {2880,-2752},
                {3008,-2880}, {3056,-3008}, {3056,-3100}, {2928,-3232},
                {3180,-3340}, {3056,-3500}, {3008,-3744}, {3008,-3940}, {3008,-4300}, {3008,-4590},
                {3008,-4768}, {2940,-4768}
        };
        try {
            for (int waypoint=0; waypoint<route.length; waypoint++) {
                boolean reached = false;
                for (int tic=0; tic<500; tic++) {
                    Mobj player = GGame.players[GGame.consoleplayer].mo;
                    double dx = route[waypoint][0]-player.x/65536.0;
                    double dy = route[waypoint][1]-player.y/65536.0;
                    if (Math.hypot(dx,dy) < 20) { reached = true; break; }
                    double facing = (player.angle & 0xffffffffL) * Math.PI*2 / 4294967296.0;
                    double delta = Math.atan2(Math.sin(Math.atan2(dy,dx)-facing),
                            Math.cos(Math.atan2(dy,dx)-facing));
                    Arrays.fill(GGame.gamekeydown, false);
                    GGame.gamekeydown[GGame.keyLeft] = delta > 0.045;
                    GGame.gamekeydown[GGame.keyRight] = delta < -0.045;
                    GGame.gamekeydown[GGame.keyUp] = Math.abs(delta) < 0.15;
                    GGame.gamekeydown[GGame.keyUse] = tic % 64 == 0;
                    GGame.gTicker();
                    if (Doomstat.gamestate == Doomstat.GS_INTERMISSION) { reached = true; break; }
                    Fx3Renderer.renderPlayerView(GGame.players[GGame.consoleplayer]);
                }
                Mobj player = GGame.players[GGame.consoleplayer].mo;
                snapshot(sub, "route-" + waypoint);
                assertTrue(reached, "Route waypoint " + waypoint + " blocked at "
                        + player.x/65536.0 + "," + player.y/65536.0);
                if (Doomstat.gamestate == Doomstat.GS_INTERMISSION) break;
            }
            // Last waypoint is within use range of the west-facing exit switch.
            for (int i=0; i<4 && Doomstat.gamestate==Doomstat.GS_LEVEL; i++) {
                Arrays.fill(GGame.gamekeydown, false);
                GGame.gamekeydown[GGame.keyUse] = i % 2 == 0;
                GGame.gTicker();
            }
            assertEquals(Doomstat.GS_INTERMISSION, Doomstat.gamestate, "E1M1 normal-use exit");
            Files.writeString(OUTPUT.resolve("traversal.txt"),
                    "E1M1 completed using turn/forward/use keyboard commands; -nomonsters.\n"
                    + "No actor relocation or direct door/exit activation.\n");
        } finally {
            Doomstat.nomonsters = false;
            Arrays.fill(GGame.gamekeydown, false);
        }
    }

    private static void benchmark(boolean iso, int map) throws Exception {
        freshLevel(1, map);
        SubScene sub = scene(iso);
        long coldStart = System.nanoTime();
        Fx3Renderer.renderPlayerView(GGame.players[GGame.consoleplayer]);
        sub.snapshot(null, null);
        long coldFrame = System.nanoTime()-coldStart;
        for (int i=0; i<10; i++) {
            GGame.gTicker();
            Fx3Renderer.renderPlayerView(GGame.players[GGame.consoleplayer]);
            sub.snapshot(null, null);
        }
        long[] update = new long[70];
        long[] raster = new long[70];
        com.sun.management.ThreadMXBean bean =
                (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
        long threadId = Thread.currentThread().threadId();
        long allocated = bean.getThreadAllocatedBytes(threadId);
        long builds = Fx3Renderer.meshBuildCount;
        for (int i=0; i<update.length; i++) {
            GGame.gTicker();
            long start = System.nanoTime();
            Fx3Renderer.renderPlayerView(GGame.players[GGame.consoleplayer]);
            update[i] = System.nanoTime()-start;
            start = System.nanoTime();
            sub.snapshot(null, null);
            raster[i] = System.nanoTime()-start;
        }
        allocated = bean.getThreadAllocatedBytes(threadId) - allocated;
        Arrays.sort(update); Arrays.sort(raster);
        String name = (iso ? "iso" : "perspective") + "-map" + map;
        Files.writeString(OUTPUT.resolve(name + "-performance.txt"),
                "640x400; 10 warmup + 70 simulation/render/snapshot frames\n"
                + "update median ms: " + update[35]/1e6 + "\nupdate p95 ms: " + update[66]/1e6
                + "\ncold frame ms: " + coldFrame/1e6
                + "\nsnapshot median ms: " + raster[35]/1e6 + "\nsnapshot p95 ms: " + raster[66]/1e6
                + "\nallocated bytes/frame (simulation + update + snapshot): " + allocated/70
                + "\nmesh builds during sample: " + (Fx3Renderer.meshBuildCount-builds)
                + "\nscene nodes: " + countNodes(sub.getRoot()) + "\n");
        snapshot(sub, name);
    }

    private static int countNodes(Node node) {
        int count = 1;
        if (node instanceof Parent parent)
            for (Node child : parent.getChildrenUnmodifiable()) count += countNodes(child);
        return count;
    }

    private static void shell(Path wad) throws Exception {
        // Exercise the actual CLI parser, JavaFX canvases, and engine display dispatch.
        var args = doom.ui.JfxDoomApp.class.getDeclaredField("launchArgs");
        args.setAccessible(true);
        args.set(null, new String[] {"-iwad", wad.toString(), "-iso", "-warp", "1", "1"});
        doom.ui.JfxDoomApp app = new doom.ui.JfxDoomApp();
        javafx.stage.Stage stage = new javafx.stage.Stage();
        Platform.setImplicitExit(false);
        try {
            app.start(stage);
            var timer = doom.ui.JfxDoomApp.class.getDeclaredField("animationTimer");
            timer.setAccessible(true);
            ((javafx.animation.AnimationTimer) timer.get(app)).stop();
            assertTrue(Fx3Renderer.isometric);
            for (int i=0; i<40; i++) DMain.dDoomLoop();
            saveImage(stage.getScene().snapshot(null), "shell-hud");
            assertTrue(Fx3Renderer.subScene().isVisible());
            doom.ui.AmMap.amStart();
            DMain.dDoomLoop();
            assertFalse(Fx3Renderer.subScene().isVisible());
            saveImage(stage.getScene().snapshot(null), "shell-automap");
            doom.ui.AmMap.amStop();
            doom.ui.MMenu.mStartControlPanel();
            DMain.dDoomLoop();
            saveImage(stage.getScene().snapshot(null), "shell-menu");
            doom.ui.MMenu.menuactive = false;
            doom.render.RMain.rSetViewSize(8, 0);
            DMain.dDoomLoop();
            saveImage(stage.getScene().snapshot(null), "shell-reduced");
            Player player = GGame.players[GGame.consoleplayer];
            player.damagecount = 16;
            DMain.dDoomLoop();
            saveImage(stage.getScene().snapshot(null), "shell-damage");
            player.damagecount = 0;
            // Transition through the normal level completion and wipe path.
            GGame.gExitLevel();
            for (int i=0; i<40; i++) DMain.dDoomLoop();
            assertEquals(Doomstat.GS_INTERMISSION, Doomstat.gamestate);
            saveImage(stage.getScene().snapshot(null), "shell-intermission");
            assertFalse(Fx3Renderer.subScene().isVisible());
        } finally {
            stage.hide();
            doom.ui.IVideo.finishUpdateHook = null;
            doom.ui.IVideo.startFrameHook = null;
            doom.ui.IVideo.startTicHook = null;
            doom.ui.IVideo.setPaletteHook = null;
            ISystem.quitHook = null;
            args.set(null, new String[0]);
        }
    }

    private static Group world(SubScene sub) {
        return (Group) ((Group) sub.getRoot()).getChildren().get(1);
    }

    private static SubScene scene(boolean iso) {
        Fx3Renderer.reset();
        Fx3Renderer.isometric = iso;
        Fx3Renderer.active = true;
        SubScene sub = Fx3Renderer.subScene();
        sub.setWidth(640); sub.setHeight(400);
        new Scene(new Group(sub));
        return sub;
    }

    private static void freshLevel(int episode, int map) {
        Doomstat.demoplayback = false;
        Doomstat.paused = false;
        doom.ui.MMenu.menuactive = false;
        GGame.gInitNew(Doomstat.sk_medium, episode, map);
        GGame.gDoLoadLevel();
    }

    private static List<String> demo(boolean render) throws Exception {
        Doomstat.paused = false;
        doom.ui.MMenu.menuactive = false;
        GGame.demorecording = false;
        GGame.defdemoname = "demo1";
        GGame.gDoPlayDemo();
        GGame.gDoLoadLevel();
        PTick.gametic = 0;
        SubScene sub = render ? scene(true) : null;
        List<String> result = new ArrayList<>();
        long[] times = new long[210];
        for (int i=0; i<210; i++) {
            GGame.gTicker();
            assertTrue(Doomstat.demoplayback);
            long start = System.nanoTime();
            if (render) Fx3Renderer.renderPlayerView(GGame.players[GGame.consoleplayer]);
            times[i] = System.nanoTime()-start;
            result.add(state());
            if (render && (i==69 || i==139 || i==209)) snapshot(sub, "demo-" + (i+1));
        }
        if (render) {
            Arrays.sort(times);
            Files.writeString(OUTPUT.resolve("timing.txt"),
                    "Scene update only; snapshot/GPU time excluded.\nmedian ms: " + times[105]/1e6
                    + "\np95 ms: " + times[199]/1e6 + "\nmesh builds: " + Fx3Renderer.meshBuildCount + "\n");
        }
        return result;
    }

    private static String state() {
        StringBuilder b = new StringBuilder().append(MRandom.prndindex).append(':');
        for (Thinker t=PTick.thinkercap.next; t!=PTick.thinkercap; t=t.next) {
            if (t instanceof Mobj m) b.append(m.type).append(',').append(m.x).append(',').append(m.y)
                    .append(',').append(m.z).append(',').append(m.momx).append(',').append(m.momy)
                    .append(',').append(m.momz).append(',').append(m.health).append(',').append(m.tics)
                    .append(',').append(m.angle).append(',').append(m.flags).append(',').append(m.frame)
                    .append(',').append(m.sprite).append(',').append(m.floorz).append(',').append(m.ceilingz).append(';');
        }
        Player p = GGame.players[GGame.consoleplayer];
        b.append(p.health).append(':').append(p.armorpoints).append(':').append(p.readyweapon)
                .append(':').append(Arrays.toString(p.ammo)).append(':').append(Arrays.toString(p.powers));
        for (doom.render.Sector s : PSetup.sectors)
            b.append('/').append(s.floorheight).append(',').append(s.ceilingheight).append(',').append(s.lightlevel);
        return b.toString();
    }

    private static void snapshot(SubScene sub, String name) throws Exception {
        saveImage(sub.snapshot(null, null), name);
    }

    private static void saveImage(WritableImage image, String name) throws Exception {
        BufferedImage out = new BufferedImage((int) image.getWidth(), (int) image.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Set<Integer> colors = new HashSet<>();
        for (int y=0; y<out.getHeight(); y++) for (int x=0; x<out.getWidth(); x++) {
            int argb = image.getPixelReader().getArgb(x,y);
            out.setRGB(x,y,argb); colors.add(argb);
        }
        ImageIO.write(out, "png", OUTPUT.resolve(name + ".png").toFile());
        assertTrue(colors.size() > 32, "Rendered image must contain textured geometry: " + name);
    }
}
