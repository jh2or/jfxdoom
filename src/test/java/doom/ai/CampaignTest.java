package doom.ai;

import doom.play.*;
import doom.system.*;
import doom.wad.WWad;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class CampaignTest {
    static final Path WAD = Path.of("reference-wads/DOOM.WAD");
    static final Level E1M2 = new Level(1, 2);
    @TempDir Path temp;

    @BeforeEach void requireWad() throws Exception {
        assumeTrue(Files.exists(WAD));
        String digest = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(WAD)));
        assumeTrue(digest.equals("6fdf361847b46228cfebd9f3af09cd844282ac75f3edbb61ca4cb27103ce2e7f"));
        PMap.changeSectorOverride = false;
    }
    @AfterEach void cleanup() {
        GGame.demorecording = Doomstat.demoplayback = Doomstat.singledemo = false;
        DMain.advancedemo = false; GGame.gameaction = GGame.ga_nothing;
        doom.ui.StStuff.stStopped = true; doom.ui.StStuff.sbar = null;
        WWad.wShutdown();
    }

    private Path entrance() throws Exception {
        Path first = temp.resolve("first.lmp");
        try (var in = getClass().getResourceAsStream("/ai/e1m1-learned.lmp")) { Files.copy(in, first); }
        CampaignDemo.play(WAD, first, 3, Level.E1M1, true, null, null);
        int health = GGame.players[0].health, armor = GGame.players[0].armorpoints;
        int[] ammo = GGame.players[0].ammo.clone(); boolean[] weapons = GGame.players[0].weaponowned.clone();
        List<Action> actions = new ArrayList<>(DemoInput.read(first, 3, 21000));
        assertEquals(E1M2, CampaignDemo.advance(actions));
        assertEquals(health, GGame.players[0].health); assertEquals(armor, GGame.players[0].armorpoints);
        assertArrayEquals(ammo, GGame.players[0].ammo); assertArrayEquals(weapons, GGame.players[0].weaponowned);
        assertEquals(0, Observation.keys(GGame.players[0]));
        Path entry = temp.resolve("entrance.lmp"); Route.demoActions(entry, 3, actions, Level.E1M1);
        return entry;
    }

    @Test void restoredEntranceMatchesContinuousPlayEveryTicAndClearsTrialContamination() throws Exception {
        Path entry = entrance();
        Entrance snapshot = Entrance.load(WAD, 3, entry, E1M2);
        long initial = Fingerprint.capture();
        Path unrelated = temp.resolve("idle.lmp");
        Route.demoActions(unrelated, 3, List.of(Action.IDLE), Level.E1M1);
        assertThrows(java.io.IOException.class, () -> CampaignDemo.play(WAD, unrelated, 3, E1M2, true, snapshot, null));
        List<Action> actions = new ArrayList<>(); List<Long> expected = new ArrayList<>();
        for (int i = 0; i < 700 && GGame.players[0].health > 0; i++) {
            Action a = new Action(i < 50 ? 50 : 0, 0, i % 100 < 10 ? 256 : 0, Doomdef.BT_ATTACK);
            actions.add(a); GGame.gTicker(a.command()); expected.add(Fingerprint.capture());
        }
        assertTrue(actions.size() >= 100);
        Episode e = new Episode(WAD, 3, 21000, E1M2, snapshot);
        for (int repeat = 0; repeat < 3; repeat++) {
            e.player().ammo[0] = 999; e.player().cards[0] = true; e.player().health = 0;
            e.player().playerstate = Player.PST_DEAD; MRandom.pRandom();
            e.reset(); assertEquals(initial, Fingerprint.capture()); assertEquals(0, e.tics());
            for (int i = 0; i < actions.size(); i++) {
                e.step(actions.get(i));
                assertEquals(expected.get(i).longValue(), Fingerprint.capture(), "repeat " + repeat + " tic " + i);
            }
        }
    }

    @Test void entranceIdentityAndNativePlaybackRejectMismatchesAndFalseSuccess() throws Exception {
        Path entry = entrance();
        assertThrows(java.io.IOException.class, () -> Entrance.load(WAD, 3, entry, new Level(1, 3)));
        assertThrows(java.io.IOException.class, () -> CampaignDemo.play(WAD, entry, 3, E1M2, true, null, null));
        Path truncated = temp.resolve("cut.lmp");
        List<Action> actions = DemoInput.read(entry, 3, CampaignDemo.MAX_TICS);
        Route.demoActions(truncated, 3, actions.subList(0, actions.size() - 1), Level.E1M1);
        assertThrows(java.io.IOException.class, () -> Entrance.load(WAD, 3, truncated, E1M2));
        TrainingConfig a = TrainingConfig.parse(new String[] {"--wad", WAD.toString(), "--map", "E1M2"});
        TrainingConfig b = TrainingConfig.parse(new String[] {"--wad", WAD.toString(), "--map", "E1M2", "--entrance", entry.toString()});
        assertNotEquals(Checkpoint.identity(a), Checkpoint.identity(b));
    }

    @Test void campaignEntranceArchiveResumeMatchesContinuousTraining() throws Exception {
        Path entry = entrance(), split = temp.resolve("split"), whole = temp.resolve("whole");
        List<String> base = List.of("--wad", WAD.toString(), "--map", "E1M2", "--entrance", entry.toString(),
                "--episode-tics", "128", "--archive-size", "64", "--node-limit", "128", "--minutes", "1");
        for (var call : new Object[][] {{split, "1", false}, {split, "1", true}, {whole, "2", false}}) {
            List<String> options = new ArrayList<>(base);
            options.addAll(List.of("--output", call[0].toString(), "--attempts", call[1].toString()));
            if ((boolean) call[2]) options.add("--resume");
            Trainer.main(options.toArray(String[]::new));
        }
        assertArrayEquals(Files.readAllBytes(whole.resolve("archive.bin")), Files.readAllBytes(split.resolve("archive.bin")));
        assertFalse(Files.exists(whole.resolve("winning.lmp")));
        assertTrue(Files.readString(whole.resolve("entrance.txt")).contains("keys=0"));
    }

    @Test void realTransitionCarriesShotgunBackpackAndReservesButClearsKeysAndPowers() throws Exception {
        Episode episode = new Episode(WAD, 3, 21000);
        var p = episode.player();
        p.health = p.mo.health = 75; p.armorpoints = 80; p.armortype = 2;
        p.backpack = true; p.weaponowned[Doomdef.wp_shotgun] = true;
        p.readyweapon = p.pendingweapon = Doomdef.wp_shotgun;
        p.ammo[Doomdef.am_shell] = 42; p.maxammo[Doomdef.am_shell] = 100;
        Arrays.fill(p.cards, true); Arrays.fill(p.powers, 99); p.mo.flags |= Mobj.MF_SHADOW;
        GGame.gExitLevel();
        assertEquals(E1M2, CampaignDemo.advance(new ArrayList<>()));
        assertSame(p, GGame.players[0]); assertEquals(75, p.health); assertEquals(80, p.armorpoints);
        assertEquals(2, p.armortype); assertTrue(p.backpack); assertTrue(p.weaponowned[Doomdef.wp_shotgun]);
        assertEquals(Doomdef.wp_shotgun, p.readyweapon); assertEquals(42, p.ammo[Doomdef.am_shell]);
        assertEquals(100, p.maxammo[Doomdef.am_shell]); assertEquals(0, Observation.keys(p));
        assertArrayEquals(new int[Doomdef.NUMPOWERS], p.powers); assertEquals(0, p.mo.flags & Mobj.MF_SHADOW);
    }

    @Test void e1m3EntranceFromAnE1m2StartRestoresAndReplays() throws Exception {
        // Test transition/snapshot fidelity independently of first-attempt learning success.
        Path demo = temp.resolve("second.lmp");
        try (var in = getClass().getResourceAsStream("/ai/e1m2-learned.lmp")) { Files.copy(in, demo); }
        CampaignDemo.play(WAD, demo, 3, E1M2, true, null, null);
        List<Action> history = new ArrayList<>(DemoInput.read(demo, 3, 21000, E1M2));
        Level third = new Level(1, 3); assertEquals(third, CampaignDemo.advance(history));
        Path entry = temp.resolve("third.lmp"); Route.demoActions(entry, 3, history, E1M2);
        Entrance snapshot = Entrance.load(WAD, 3, entry, third);
        List<Long> hashes = new ArrayList<>(); Action input = new Action(0, 0, 256, Doomdef.BT_ATTACK);
        for (int i = 0; i < 350 && GGame.players[0].health > 0; i++) { GGame.gTicker(input.command()); hashes.add(Fingerprint.capture()); }
        Episode episode = new Episode(WAD, 3, 21000, third, snapshot);
        for (long hash : hashes) { episode.step(input); assertEquals(hash, Fingerprint.capture()); }
    }

    @Test void campaignBudgetDoesNotAdvanceOrClaimCompletion() throws Exception {
        Path output = temp.resolve("campaign");
        String[] args = {"--wad", WAD.toString(), "--output", output.toString(), "--through", "E1M2", "--minutes", "0.000000001"};
        CampaignTrainer.main(args);
        assertTrue(Files.readString(output.resolve("status.txt")).startsWith("INCOMPLETE"));
        assertFalse(Files.exists(output.resolve("campaign.lmp")));
        assertFalse(Files.exists(output.resolve("E1M2")));
        assertThrows(java.io.IOException.class, () -> CampaignTrainer.main(args));
        List<String> resume = new ArrayList<>(List.of(args)); resume.add("--resume");
        assertDoesNotThrow(() -> CampaignTrainer.main(resume.toArray(String[]::new)));
    }
}
