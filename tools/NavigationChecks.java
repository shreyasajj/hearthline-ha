import dev.pixelhome.PlaybackNavigation;
import dev.pixelhome.PlaybackNavigation.Page;
public class NavigationChecks {
    private static int checks;
    private static void expect(Page expected, PlaybackNavigation n) {
        checks++;
        if (expected != n.page()) throw new AssertionError("Check " + checks + ": expected " + expected + " got " + n.page());
    }
    public static void main(String[] args) {
        var n = new PlaybackNavigation();
        n.playback(true, true, 100); expect(Page.MUSIC, n);
        n.tick(true, true, 44999, 45000); expect(Page.MUSIC, n);
        n.touch(45000); n.tick(true, true, 89999, 45000); expect(Page.MUSIC, n);
        n.tick(true, true, 90000, 45000); expect(Page.IDLE, n);
        n.open(Page.HOME, 91000); n.playback(true, true, 92000); expect(Page.HOME, n);
        n.tick(true, true, 200000, 45000); expect(Page.HOME, n);
        n.playback(false, true, 200100); n.playback(true, true, 200200); expect(Page.MUSIC, n);
        n.open(Page.SETTINGS, 200300); n.playback(false, true, 200400); n.playback(true, true, 200500); expect(Page.SETTINGS, n);
        n.reset(0); n.playback(true, false, 100); expect(Page.HOME, n);
        n.open(Page.MUSIC, 200); n.tick(false, true, 99999, 45000); expect(Page.MUSIC, n);
        n.tick(true, false, 99999, 45000); expect(Page.MUSIC, n);
        n.tick(true, true, 99999, 45000); expect(Page.IDLE, n);
        n.playback(false, true, 100000); expect(Page.MUSIC, n);
        n.open(Page.IDLE, 100001); n.leaveHub(100002); expect(Page.MUSIC, n);
        n.reset(0); n.playback(true, true, 10); n.open(Page.HOME, 20);
        for (int i=0;i<20;i++) n.playback(true, true, 30+i); expect(Page.HOME, n);
        System.out.println("Passed " + checks + " navigation checks.");
    }
}
