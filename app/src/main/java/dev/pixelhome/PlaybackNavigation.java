package dev.pixelhome;

/** Independent of Android so navigation rules can be tested on a plain JVM. */
public final class PlaybackNavigation {
    public enum Page { HOME, MUSIC, IDLE, SETTINGS }
    private Page page = Page.HOME;
    private boolean wasPlaying;
    private boolean homePinned;
    private long lastInteraction;
    public Page page() { return page; }
    public void touch(long now) { lastInteraction = now; }
    public void open(Page value, long now) {
        page = value;
        homePinned = value == Page.HOME;
        touch(now);
    }
    public void playback(boolean playing, boolean hubEnabled, long now) {
        if (playing && !wasPlaying && hubEnabled && !homePinned && page != Page.SETTINGS) {
            page = Page.MUSIC;
            touch(now);
        }
        if (!playing) {
            homePinned = false;
            if (page == Page.IDLE) page = Page.MUSIC;
        }
        wasPlaying = playing;
    }
    public void tick(boolean hubEnabled, boolean playing, long now, long timeout) {
        if (hubEnabled && playing && page == Page.MUSIC && now - lastInteraction >= timeout)
            page = Page.IDLE;
    }
    public void leaveHub(long now) {
        if (page == Page.IDLE) page = Page.MUSIC;
        touch(now);
    }
    public void reset(long now) {
        page = Page.HOME; wasPlaying = false; homePinned = false; touch(now);
    }
}
