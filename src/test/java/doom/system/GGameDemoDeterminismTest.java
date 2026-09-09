package doom.system;

import doom.play.*;
import doom.wad.WWad;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class GGameDemoDeterminismTest {
    @Test
    void repeatedRealWadDemoProducesIdenticalActorStatesAfterRandomActivity(@org.junit.jupiter.api.io.TempDir Path temp) throws Exception {
        Path wad = Path.of("reference-wads/DOOM.WAD");
        assumeTrue(Files.isRegularFile(wad), "Requires reference-wads/DOOM.WAD");
        try {
            WWad.resetForTests();
            DMain.dDoomMain(new String[] {"-iwad", wad.toString(), "-warp", "1", "1", "-respawn"});
            assertTrue(Doomstat.respawnparm);
            assertTrue(Doomstat.respawnmonsters);
            List<String> first = playDemo(false);
            for (int i = 0; i < 137; i++) { MRandom.pRandom(); MRandom.mRandom(); }
            List<String> second = playDemo(true);
            assertEquals(first, second);
            assertNotEquals(first.getFirst(), first.getLast(), "The playsim must actually advance");
            byte[] requested = WWad.wCacheLumpName("demo1", doom.wad.ZZone.PU_STATIC).clone();
            requested[1] = (byte) Doomstat.sk_medium;
            requested[5] = 1; // respawnparm in the vanilla demo header
            Path demo = temp.resolve("respawn.lmp");
            Files.write(demo, requested);
            WWad.resetForTests();
            WWad.wInitMultipleFiles(new String[] {wad.toString(), demo.toString(), null});
            GGame.defdemoname = "respawn";
            GGame.gDoPlayDemo();
            assertTrue(Doomstat.respawnparm);
            assertTrue(Doomstat.respawnmonsters, "A non-Nightmare demo must retain requested respawning");
        } finally {
            doom.render.RenderDepth.disable();
            doom.render.RenderSpriteMask.disable();
            Doomstat.demoplayback = false;
            Doomstat.fastparm = false;
            Doomstat.respawnparm = false;
            Doomstat.nomonsters = false;
            GGame.gInitNew(Doomstat.sk_medium, 1, 1);
            GGame.gameaction = GGame.ga_nothing;
            WWad.resetForTests();
        }
    }

    private List<String> playDemo(boolean depth) {
        doom.render.RenderDepth.enabled = depth;
        doom.render.RenderSpriteMask.enabled = depth;
        doom.render.RDraw.fuzzpos = 0;
        Doomstat.paused = false;
        doom.ui.MMenu.menuactive = false;
        GGame.demorecording = false;
        GGame.defdemoname = "demo1";
        GGame.gDoPlayDemo();
        assertTrue(Doomstat.demoplayback);
        GGame.gDoLoadLevel();
        PTick.gametic = 0;
        List<String> result = new ArrayList<>();
        for (int tic = 0; tic < 210; tic++) {
            GGame.gTicker();
            doom.render.RMain.rRenderPlayerView(GGame.players[0]);
            assertEquals(tic + 1, PTick.gametic, "One game tic per demo command");
            assertTrue(Doomstat.demoplayback, "Built-in demo must cover the sampled interval");
            StringBuilder state = new StringBuilder().append(MRandom.prndindex).append(':');
            for (Thinker thinker = PTick.thinkercap.next; thinker != PTick.thinkercap; thinker = thinker.next) {
                if (thinker instanceof Mobj mo) {
                    state.append(mo.type).append(',').append(mo.x).append(',').append(mo.y).append(',')
                            .append(mo.z).append(',').append(mo.momx).append(',').append(mo.momy).append(',')
                            .append(mo.health).append(',').append(mo.tics).append(',').append(mo.angle).append(';');
                }
            }
            state.append(":frame=").append(Arrays.hashCode(doom.ui.VVideo.viewBuffer));
            result.add(state.toString());
        }
        return result;
    }
}
