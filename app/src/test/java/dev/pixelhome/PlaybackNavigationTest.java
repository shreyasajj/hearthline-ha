package dev.pixelhome;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
public class PlaybackNavigationTest {
    @Test public void homeRemainsVisibleAcrossTrackUpdates() {
        var n = new PlaybackNavigation();
        n.playback(true, true, 0);
        n.open(PlaybackNavigation.Page.HOME, 100);
        n.playback(true, true, 200);
        n.tick(true, true, 100000, 45000);
        assertEquals(PlaybackNavigation.Page.HOME, n.page());
    }
    @Test public void idleRequiresPlaybackHubAndTimeout() {
        var n = new PlaybackNavigation();
        n.playback(true, true, 0);
        n.touch(10000);
        n.tick(true, true, 54999, 45000);
        assertEquals(PlaybackNavigation.Page.MUSIC, n.page());
        n.tick(true, true, 55000, 45000);
        assertEquals(PlaybackNavigation.Page.IDLE, n.page());
    }
}
