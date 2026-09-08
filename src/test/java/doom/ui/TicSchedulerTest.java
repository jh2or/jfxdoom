package doom.ui;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class TicSchedulerTest {
    @Test
    void catchUpConsumesCommandsInOrderAndPresentsOnlyLatestState() {
        TicScheduler scheduler = new TicScheduler();
        List<String> calls = new ArrayList<>();
        int[] command = {0};
        scheduler.pulse(0, () -> fail("first pulse must only set clock"), tics -> fail());
        scheduler.pulse(3 * TicScheduler.TIC_NS, () -> {
            calls.add("tic " + ++command[0]);
            return true;
        }, tics -> { assertEquals(3, tics); calls.add("frame " + command[0]); });
        assertEquals(List.of("tic 1", "tic 2", "tic 3", "frame 3"), calls);
    }

    @Test
    void longStallIsBoundedAndDoesNotLeaveBacklogOrSkipCommands() {
        TicScheduler scheduler = new TicScheduler();
        int[] ticks = {0};
        int[] frames = {0};
        scheduler.pulse(0, () -> true, tics -> {});
        scheduler.pulse(60_000_000_000L, () -> { ticks[0]++; return true; }, tics -> frames[0]++);
        assertEquals(TicScheduler.MAX_CATCH_UP_TICS, ticks[0]);
        scheduler.pulse(60_000_000_000L, () -> { ticks[0]++; return true; }, tics -> frames[0]++);
        assertEquals(TicScheduler.MAX_CATCH_UP_TICS, ticks[0]);
        assertEquals(1, frames[0]);
        scheduler.pulse(60_000_000_000L + TicScheduler.TIC_NS,
                () -> { ticks[0]++; return true; }, tics -> frames[0]++);
        assertEquals(TicScheduler.MAX_CATCH_UP_TICS + 1, ticks[0]);
        assertEquals(2, frames[0]);
    }

    @Test
    void fractionalPulsesAccumulateAndQuitStopsRemainingTicsAndRendering() {
        TicScheduler scheduler = new TicScheduler();
        int[] ticks = {0};
        scheduler.pulse(0, () -> true, tics -> {});
        scheduler.pulse(TicScheduler.TIC_NS / 2, () -> fail(), tics -> fail());
        scheduler.pulse(TicScheduler.TIC_NS, () -> { ticks[0]++; return true; }, tics -> {});
        assertEquals(1, ticks[0]);
        scheduler.pulse(5 * TicScheduler.TIC_NS, () -> { ticks[0]++; return false; },
                tics -> fail("must not render scene after quit"));
        assertEquals(2, ticks[0]);
    }
    @Test
    void quitFromRealEventStopsTicsBeforeDeferredLauncherHookRuns() {
        Runnable previousQuit = doom.system.ISystem.quitHook;
        Runnable previousFinish = IVideo.finishUpdateHook;
        Runnable previousStart = IVideo.startTicHook;
        java.util.ArrayDeque<Runnable> deferred = new java.util.ArrayDeque<>();
        boolean[] launcherShown = {false};
        int before = doom.play.PTick.gametic;
        try {
            doom.system.ISystem.iResetForRelaunch();
            doom.system.ISystem.quitHook = () -> deferred.add(() -> launcherShown[0] = true);
            IVideo.finishUpdateHook = () -> fail("quit must prevent presentation");
            IVideo.startTicHook = null;
            doom.system.DMain.eventhead = doom.system.DMain.eventtail = 0;
            MMenu.mStartMessage("Quit?", key -> doom.system.ISystem.iQuit(), true);
            doom.system.DMain.dPostEvent(new doom.system.DEvent(doom.system.DEvent.EV_KEYDOWN, 'y'));
            TicScheduler scheduler = new TicScheduler();
            scheduler.pulse(0, () -> true, tics -> fail());
            scheduler.pulse(4 * TicScheduler.TIC_NS, () -> {
                doom.system.DMain.dRunTic();
                return !doom.system.ISystem.isQuitting();
            }, doom.system.DMain::dDisplay);
            assertTrue(doom.system.ISystem.isQuitting());
            assertFalse(launcherShown[0]);
            assertEquals(1, deferred.size());
            assertEquals(before, doom.play.PTick.gametic, "G_Ticker must not run after quit event");
            doom.system.DMain.dDoomLoop(); // headless callers must also stop
            assertEquals(before, doom.play.PTick.gametic);
            deferred.remove().run();
            assertTrue(launcherShown[0]);
        } finally {
            doom.system.ISystem.quitHook = previousQuit;
            doom.system.ISystem.iResetForRelaunch();
            IVideo.finishUpdateHook = previousFinish;
            IVideo.startTicHook = previousStart;
            MMenu.messageToPrint = 0;
            MMenu.messageRoutine = null;
            MMenu.menuactive = false;
        }
    }

}
