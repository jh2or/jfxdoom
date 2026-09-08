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
        scheduler.pulse(0, () -> fail("first pulse must only set clock"), () -> fail());
        scheduler.pulse(3 * TicScheduler.TIC_NS, () -> {
            calls.add("tic " + ++command[0]);
            return true;
        }, () -> calls.add("frame " + command[0]));
        assertEquals(List.of("tic 1", "tic 2", "tic 3", "frame 3"), calls);
    }

    @Test
    void longStallIsBoundedAndDoesNotLeaveBacklogOrSkipCommands() {
        TicScheduler scheduler = new TicScheduler();
        int[] ticks = {0};
        int[] frames = {0};
        scheduler.pulse(0, () -> true, () -> {});
        scheduler.pulse(60_000_000_000L, () -> { ticks[0]++; return true; }, () -> frames[0]++);
        assertEquals(TicScheduler.MAX_CATCH_UP_TICS, ticks[0]);
        scheduler.pulse(60_000_000_000L, () -> { ticks[0]++; return true; }, () -> frames[0]++);
        assertEquals(TicScheduler.MAX_CATCH_UP_TICS, ticks[0]);
        assertEquals(1, frames[0]);
        scheduler.pulse(60_000_000_000L + TicScheduler.TIC_NS,
                () -> { ticks[0]++; return true; }, () -> frames[0]++);
        assertEquals(TicScheduler.MAX_CATCH_UP_TICS + 1, ticks[0]);
        assertEquals(2, frames[0]);
    }

    @Test
    void fractionalPulsesAccumulateAndQuitStopsRemainingTicsAndRendering() {
        TicScheduler scheduler = new TicScheduler();
        int[] ticks = {0};
        scheduler.pulse(0, () -> true, () -> {});
        scheduler.pulse(TicScheduler.TIC_NS / 2, () -> fail(), () -> fail());
        scheduler.pulse(TicScheduler.TIC_NS, () -> { ticks[0]++; return true; }, () -> {});
        assertEquals(1, ticks[0]);
        scheduler.pulse(5 * TicScheduler.TIC_NS, () -> { ticks[0]++; return false; },
                () -> fail("must not render scene after quit"));
        assertEquals(2, ticks[0]);
    }
}
