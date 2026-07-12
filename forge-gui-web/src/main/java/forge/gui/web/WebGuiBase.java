package forge.gui.web;

import forge.gamemodes.match.HostedMatch;
import forge.gui.download.GuiDownloadService;
import forge.gui.interfaces.IGuiBase;
import forge.gui.interfaces.IGuiGame;
import forge.item.PaperCard;
import forge.localinstance.skin.FSkinProp;
import forge.localinstance.skin.ISkinImage;
import forge.sound.IAudioClip;
import forge.sound.IAudioMusic;
import forge.util.BuildInfo;
import forge.util.FSerializableFunction;
import forge.util.ImageFetcher;

import org.apache.commons.lang3.StringUtils;
import org.jupnp.UpnpServiceConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Headless {@link IGuiBase} for the web host. Provides the process-wide GUI hook the engine needs
 * ({@code GuiBase.setInterface(new WebGuiBase())}) without any Swing/libgdx dependency — the browser
 * is the real UI, reached via {@link WebGuiGame} per session.
 *
 * <p>Skin/image/audio/native-dialog methods are safe no-ops (there is no local UI); threading,
 * version, asset-dir, and match-hosting methods are real so {@code FModel.initialize(...)} and
 * {@code HostedMatch} work.</p>
 */
public final class WebGuiBase implements IGuiBase {

    /** A single "EDT-like" thread so invokeInEdtLater/AndWait have somewhere to run. */
    private volatile Thread edtThread;
    private final ExecutorService edt = Executors.newSingleThreadExecutor(r -> {
        final Thread t = new Thread(r, "web-edt");
        edtThread = t;
        return t;
    });

    /** The browser session new local GUIs bind to (set when a browser connects). */
    private volatile WebSession activeSession;

    public void setActiveSession(final WebSession session) {
        this.activeSession = session;
    }

    // ── Platform / version ───────────────────────────────────────────────────

    @Override public boolean isRunningOnDesktop() { return true; }
    @Override public boolean isLibgdxPort() { return false; }
    @Override public String getCurrentVersion() { return BuildInfo.getVersionString(); }

    @Override
    public String getAssetsDir() {
        // Mirror GuiDesktop: dev/git builds run from a module dir and reach res/ via ../forge-gui/.
        return StringUtils.containsIgnoreCase(BuildInfo.getVersionString(), "git") ? "../forge-gui/" : "";
    }

    // ── Threading ────────────────────────────────────────────────────────────

    @Override public boolean isGuiThread() { return Thread.currentThread() == edtThread; }
    @Override public void invokeInEdtNow(final Runnable runnable) { runnable.run(); }
    @Override public void invokeInEdtLater(final Runnable runnable) { edt.execute(runnable); }

    @Override
    public void invokeInEdtAndWait(final Runnable proc) {
        if (isGuiThread()) {
            proc.run();
            return;
        }
        try {
            edt.submit(proc).get();
        } catch (final Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void runBackgroundTask(final String message, final Runnable task) {
        new Thread(task, "web-bg").start();
    }

    // ── Match hosting / GUI creation ─────────────────────────────────────────

    @Override public HostedMatch hostMatch() { return new HostedMatch(); }
    @Override public IGuiGame getNewGuiGame() { return new WebGuiGame(activeSession); }
    @Override public boolean hasNetGame() { return false; }
    @Override public UpnpServiceConfiguration getUpnpPlatformService() { return null; }

    // ── Skins / images (no local UI) ─────────────────────────────────────────

    @Override public ImageFetcher getImageFetcher() { return null; }
    @Override public ISkinImage getSkinIcon(final FSkinProp skinProp) { return null; }
    @Override public ISkinImage getUnskinnedIcon(final String path) { return null; }
    @Override public ISkinImage getCardArt(final PaperCard card, final boolean backFace) { return null; }
    @Override public ISkinImage createLayeredImage(final PaperCard card, final FSkinProp background,
                                                   final String overlayFilename, final float opacity) { return null; }
    @Override public void clearImageCache() { }
    @Override public void refreshSkin() { }
    @Override public int getAvatarCount() { return 0; }
    @Override public int getSleevesCount() { return 0; }
    @Override public float getScreenScale() { return 1f; }
    @Override public String encodeSymbols(final String str, final boolean formatReminderText) { return str; }

    // ── Native dialogs / OS integration (no-op headless) ─────────────────────

    @Override public void preventSystemSleep(final boolean preventSleep) { }
    @Override public void browseToUrl(final String url) { }
    @Override public void copyToClipboard(final String text) { }
    @Override public void download(final GuiDownloadService service, final Consumer<Boolean> callback) {
        if (callback != null) {
            callback.accept(Boolean.FALSE);
        }
    }
    @Override public void showCardList(final String title, final String message, final List<PaperCard> list) { }
    @Override public boolean showBoxedProduct(final String title, final String message, final List<PaperCard> list) { return false; }
    @Override public void showBugReportDialog(final String title, final String text, final boolean showExitAppBtn) { }
    @Override public void showImageDialog(final ISkinImage image, final String message, final String title) { }
    @Override public int showOptionDialog(final String message, final String title, final FSkinProp icon,
                                          final List<String> options, final int defaultOption) { return defaultOption; }
    @Override public String showInputDialog(final String message, final String title, final FSkinProp icon,
                                            final String initialInput, final List<String> inputOptions, final boolean isNumeric) { return initialInput; }
    @Override public String showFileDialog(final String title, final String defaultDir) { return null; }
    @Override public File getSaveFile(final File defaultFile) { return defaultFile; }
    @Override public PaperCard chooseCard(final String title, final String message, final List<PaperCard> list) {
        return (list == null || list.isEmpty()) ? null : list.get(0);
    }
    @Override public void showSpellShop() { }
    @Override public void showBazaar() { }

    @Override
    public <T> List<T> order(final String title, final String top, final int remainingObjectsMin,
                             final int remainingObjectsMax, final List<T> sourceChoices, final List<T> destChoices) {
        return sourceChoices;
    }

    @Override
    public <T> List<T> getChoices(final String message, final int min, final int max,
                                  final Collection<T> choices, final Collection<T> selected,
                                  final FSerializableFunction<T, String> display) {
        return selected == null ? Collections.emptyList() : new ArrayList<>(selected);
    }

    // ── Audio (no-op headless) ───────────────────────────────────────────────

    @Override public boolean isSupportedAudioFormat(final File file) { return false; }
    @Override public IAudioClip createAudioClip(final String filename) { return null; }
    @Override public IAudioMusic createAudioMusic(final String filename) { return null; }
    @Override public void startAltSoundSystem(final String filename, final boolean isSynchronized) { }
}
