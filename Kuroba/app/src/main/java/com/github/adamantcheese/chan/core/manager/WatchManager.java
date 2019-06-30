/*
 * Kuroba - *chan browser https://github.com/Adamantcheese/Kuroba/
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.github.adamantcheese.chan.core.manager;

import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.Nullable;

import com.android.volley.NetworkResponse;
import com.android.volley.ServerError;
import com.github.adamantcheese.chan.BuildConfig;
import com.github.adamantcheese.chan.Chan;
import com.github.adamantcheese.chan.core.database.DatabaseManager;
import com.github.adamantcheese.chan.core.database.DatabasePinManager;
import com.github.adamantcheese.chan.core.database.DatabaseSavedThreadManager;
import com.github.adamantcheese.chan.core.model.ChanThread;
import com.github.adamantcheese.chan.core.model.Post;
import com.github.adamantcheese.chan.core.model.PostImage;
import com.github.adamantcheese.chan.core.model.orm.Loadable;
import com.github.adamantcheese.chan.core.model.orm.Pin;
import com.github.adamantcheese.chan.core.model.orm.PinType;
import com.github.adamantcheese.chan.core.model.orm.SavedThread;
import com.github.adamantcheese.chan.core.pool.ChanLoaderFactory;
import com.github.adamantcheese.chan.core.settings.ChanSettings;
import com.github.adamantcheese.chan.core.site.loader.ChanThreadLoader;
import com.github.adamantcheese.chan.core.site.sites.chan4.Chan4PagesRequest;
import com.github.adamantcheese.chan.ui.helper.PostHelper;
import com.github.adamantcheese.chan.ui.service.LastPageNotification;
import com.github.adamantcheese.chan.ui.service.WatchNotification;
import com.github.adamantcheese.chan.utils.Logger;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.inject.Inject;

import static com.github.adamantcheese.chan.utils.AndroidUtils.getAppContext;
import static com.github.adamantcheese.chan.utils.BackgroundUtils.isInForeground;

/**
 * Manages all Pin related management.
 * <p/>
 * <p>Pins are threads that are pinned to a pane on the left.
 * <p/>
 * <p>The pin watcher is an optional feature that watches threads for new posts and displays a new
 * post counter next to the pin view. Watching happens with the same backoff timer as used for
 * the auto updater for open threads.
 * <p/>
 * <p>Background watching is a feature that can be enabled. With background watching enabled then
 * the PinManager will register an AlarmManager to check for updates in intervals. It will acquire
 * a wakelock shortly while checking for updates.
 * <p/>
 * <p>All pin adding and removing must go through this class to properly update the watchers.
 */
public class WatchManager implements WakeManager.Wakeable {
    private static final String TAG = "WatchManager";
    private static final Intent WATCH_NOTIFICATION_INTENT = new Intent(getAppContext(), WatchNotification.class);
    private static final byte[] EMPTY_BYTE_ARRAY = new byte[]{};

    enum IntervalType {
        /**
         * A timer that uses a {@link Handler} that calls {@link #update(boolean)} every
         * {@value #FOREGROUND_INTERVAL_ONLY_WATCHES} or
         * {@value #FOREGROUND_INTERVAL_MIXED} or
         * {@value FOREGROUND_INTERVAL_ONLY_DOWNLOADS} ms depending on the current pins.
         */
        FOREGROUND,

        /**
         * A timer that schedules a broadcast to be send that calls {@link #update(boolean)}.
         */
        BACKGROUND,

        /**
         * No scheduling.
         */
        NONE
    }

    private Handler handler;
    /**
     * When all pins have flag WatchNewPosts use short interval
     * */
    private static final long FOREGROUND_INTERVAL_ONLY_WATCHES = 15 * 1000;
    /**
     * When we have pins of both types use mixed interval
     * */
    private static final long FOREGROUND_INTERVAL_MIXED = 60 * 1000;
    /**
     * When all pins have flag DownloadNewPosts use long interval
     * */
    private static final long FOREGROUND_INTERVAL_ONLY_DOWNLOADS = 180 * 1000;
    private static final int MESSAGE_UPDATE = 1;

    private final DatabaseManager databaseManager;
    private final DatabasePinManager databasePinManager;
    private final DatabaseSavedThreadManager databaseSavedThreadManager;
    private final ChanLoaderFactory chanLoaderFactory;
    private final WakeManager wakeManager;
    private final PageRequestManager pageRequestManager;
    private final ThreadSaveManager threadSaveManager;

    private IntervalType currentInterval = IntervalType.NONE;

    private final List<Pin> pins;
    private final List<SavedThread> savedThreads;
    private boolean prevIncrementalThreadSavingEnabled;

    private Map<Pin, PinWatcher> pinWatchers = new HashMap<>();
    private Set<PinWatcher> waitingForPinWatchersForBackgroundUpdate;

    @Inject
    public WatchManager(
            DatabaseManager databaseManager,
            ChanLoaderFactory chanLoaderFactory,
            WakeManager wakeManager,
            PageRequestManager pageRequestManager,
            ThreadSaveManager threadSaveManager) {
        //retain local references to needed managers/factories/pins
        this.databaseManager = databaseManager;
        this.chanLoaderFactory = chanLoaderFactory;
        this.wakeManager = wakeManager;
        this.pageRequestManager = pageRequestManager;
        this.threadSaveManager = threadSaveManager;
        this.prevIncrementalThreadSavingEnabled = ChanSettings.watchEnabled.get() && ChanSettings.watchBackground.get();

        databasePinManager = databaseManager.getDatabasePinManager();
        databaseSavedThreadManager = databaseManager.getDatabaseSavedThreadManager();

        pins = databaseManager.runTask(databasePinManager.getPins());
        Collections.sort(pins);

        savedThreads = databaseManager.runTask(databaseSavedThreadManager.getSavedThreads());

        //register this manager to watch for setting changes and post pin changes
        EventBus.getDefault().register(this);

        //setup handler to deal with foreground updates
        handler = new Handler(Looper.getMainLooper(), msg -> {
            if (msg.what == MESSAGE_UPDATE) {
                update(false);
                return true;
            } else {
                return false;
            }
        });

        updateState();
    }

    public boolean createPin(Loadable loadable) {
        return createPin(loadable, null, PinType.WATCH_NEW_POSTS);
    }

    public boolean createPin(Loadable loadable, @Nullable Post opPost, int pinType) {
        return createPin(loadable, opPost, pinType, true);
    }

    public boolean createPin(Loadable loadable, @Nullable Post opPost, int pinType, boolean sendBroadcast) {
        Pin pin = new Pin();
        pin.loadable = loadable;
        pin.loadable.title = PostHelper.getTitle(opPost, loadable);
        pin.pinType = pinType;

        if (opPost != null) {
            PostImage image = opPost.image();
            pin.thumbnailUrl = image == null ? "" : image.getThumbnailUrl().toString();
        }
        return createPin(pin, sendBroadcast);
    }

    public boolean createPin(Pin pin) {
        return createPin(pin, true);
    }

    public boolean createPin(Pin pin, boolean sendBroadcast) {
        // No duplicates
        for (Pin e : pins) {
            if (e.loadable.equals(pin.loadable)) {
                // TODO: update the pin type here
                return false;
            }
        }

        // Default order is 0.
        pin.order = pin.order < 0 ? 0 : pin.order;

        // Move all down one.
        for (Pin p : pins) {
            p.order++;
        }
        pins.add(pin);
        databaseManager.runTask(databasePinManager.createPin(pin));

        // apply orders.
        Collections.sort(pins);
        reorder(pins);
        updateState();

        if (sendBroadcast) {
            EventBus.getDefault().post(new PinMessages.PinAddedMessage(pin));
        }

        return true;
    }

    public void startSavingThread(
            Loadable loadable,
            List<Post> postsToSave) {
        if (!startSavingThreadInternal(loadable)) {
            return;
        }

        threadSaveManager.enqueueThreadToSave(loadable, postsToSave);
    }

    private Boolean startSavingThreadInternal(Loadable loadable) {
        SavedThread savedThread = findSavedThreadByLoadableId(loadable.id);
        if (savedThread != null && (savedThread.isFullyDownloaded)) {
            // If a thread is already fully downloaded or already being downloaded do not attempt to
            // start downloading it again just skip it.
            // If isStopped equals to isThreadSavingEnabled do nothing as well.
            return false;
        }

        if (savedThread == null) {
            savedThread = new SavedThread(false, false, loadable.id);
        } else {
            savedThread.isStopped = false;
        }

        createOrUpdateSavedThread(savedThread);
        databaseManager.runTask(databaseSavedThreadManager.startSavingThread(savedThread));
        return true;
    }

    public void stopSavingThread(Loadable loadable) {
        SavedThread savedThread = findSavedThreadByLoadableId(loadable.id);
        if (savedThread == null || (savedThread.isFullyDownloaded)) {
            // If a thread is already fully downloaded or already stopped do nothing, if it is being
            // downloaded then stop it. If we could not find it then do nothing as well.
            return;
        }

        savedThread.isStopped = true;
        // Cache stopped savedThread so it's just faster to find it
        createOrUpdateSavedThread(savedThread);
        databaseManager.runTask(
                databaseSavedThreadManager.updateThreadStoppedFlagByLoadableId(loadable.id, true));

        threadSaveManager.stopDownloading(loadable);
    }

    private void createOrUpdateSavedThread(SavedThread savedThread) {
        for (int i = 0; i < savedThreads.size(); i++) {
            SavedThread st = savedThreads.get(i);

            if (st.equals(savedThread)) {
                savedThreads.get(i).update(savedThread);
                return;
            }
        }

        savedThreads.add(savedThread);
    }

    @Nullable
    public SavedThread findSavedThreadByLoadableId(int loadableId) {
        for (SavedThread savedThread : savedThreads) {
            if (savedThread.loadableId == loadableId) {
                return savedThread;
            }
        }

        SavedThread savedThread = databaseManager.runTask(
                databaseSavedThreadManager.getSavedThreadByLoadableId(loadableId));

        if (savedThread != null) {
            savedThreads.add(savedThread);
        }

        return savedThread;
    }

    @Nullable
    public Pin getPinByLoadable(Loadable loadable) {
        for (Pin pin : pins) {
            if (pin.loadable.equals(loadable)) {
                return pin;
            }
        }

        return null;
    }

    public void deletePin(Pin pin) {
        pins.remove(pin);

        destroyPinWatcher(pin);
        deleteSavedThread(pin.loadable.id);

        threadSaveManager.cancelDownloading(pin.loadable);

        databaseManager.runTask(() -> {
            databasePinManager.deletePin(pin).call();
            databaseSavedThreadManager.deleteSavedThread(pin.loadable).call();

            return null;
        });

        // Update the new orders
        Collections.sort(pins);
        reorder(pins);
        updateState();

        EventBus.getDefault().post(new PinMessages.PinRemovedMessage(pin));
    }

    private void deleteSavedThread(int loadableId) {
        SavedThread savedThread = null;

        for (int i = 0; i < savedThreads.size(); i++) {
            if (savedThreads.get(i).loadableId == loadableId) {
                savedThread = savedThreads.get(i);
                break;
            }
        }

        if (savedThread != null) {
            savedThreads.remove(savedThread);
        }
    }

    public void deletePins(List<Pin> pinList) {
        for (Pin pin : pinList) {
            pins.remove(pin);
            destroyPinWatcher(pin);
            deleteSavedThread(pin.loadable.id);

            threadSaveManager.cancelDownloading(pin.loadable);
        }

        databaseManager.runTask(() -> {
            databasePinManager.deletePins(pinList).call();

            List<Loadable> loadableList = new ArrayList<>(pinList.size());
            for (Pin pin : pinList) {
                loadableList.add(pin.loadable);
            }

            databaseSavedThreadManager.deleteSavedThreads(loadableList).call();
            return null;
        });

        // Update the new orders
        Collections.sort(pins);
        reorder(pins);
        updatePinsInDatabase();

        updateState();
        EventBus.getDefault().post(new PinMessages.PinsChangedMessage(pins));
    }

    public void updatePin(Pin pin) {
        databaseManager.runTask(() -> {
            databasePinManager.updatePin(pin).call();

            // TODO: update saved thread (do we need tho? Like what is there to even update?)
            return null;
        });

        updateState();
        EventBus.getDefault().post(new PinMessages.PinChangedMessage(pin));
    }

    public Pin findPinByLoadableId(int loadableId) {
        for (Pin pin : pins) {
            if (pin.loadable.id == loadableId) {
                return pin;
            }
        }

        return null;
    }

    public Pin findPinById(int id) {
        for (int i = 0; i < pins.size(); i++) {
            Pin pin = pins.get(i);
            if (pin.id == id) {
                return pin;
            }
        }

        return null;
    }

    public void reorder(List<Pin> pins) {
        for (int i = 0; i < pins.size(); i++) {
            Pin pin = pins.get(i);
            pin.order = i;
        }
        updatePinsInDatabase();
    }

    public List<Pin> getWatchingPins() {
        if (ChanSettings.watchEnabled.get()) {
            List<Pin> l = new ArrayList<>();

            for (Pin p : pins) {
                // User may have unpressed the watch thread button but there may still be flags
                if (p.watching || PinType.hasFlags(p.pinType)) {
                    l.add(p);
                }
            }

            return l;
        } else {
            return Collections.emptyList();
        }
    }

    public void toggleWatch(Pin pin) {
        pin.watching = !pin.watching;

        updateState();
        EventBus.getDefault().post(new PinMessages.PinChangedMessage(pin));
    }

    public void onBottomPostViewed(Pin pin) {
        if (pin.watchNewCount >= 0) {
            pin.watchLastCount = pin.watchNewCount;
        }

        if (pin.quoteNewCount >= 0) {
            pin.quoteLastCount = pin.quoteNewCount;
        }

        PinWatcher pinWatcher = getPinWatcher(pin);
        if (pinWatcher != null) {
            //onViewed
            pinWatcher.wereNewPosts = false;
            pinWatcher.wereNewQuotes = false;
        }

        updatePin(pin);
    }

    // Called when the app changes foreground state
    @Subscribe
    public void onEvent(Chan.ForegroundChangedMessage message) {
        updateState();
        if (!message.inForeground) {
            updatePinsInDatabase();
        }
    }

    // Called when either the background watch or watch enable settings are changed
    @Subscribe
    public void onEvent(ChanSettings.SettingChanged<Boolean> settingChanged) {
        if (settingChanged.setting == ChanSettings.watchBackground) {
            onBackgroundWatchingChanged(ChanSettings.watchBackground.get());
        } else if (settingChanged.setting == ChanSettings.watchEnabled) {
            onWatchEnabledChanged(ChanSettings.watchEnabled.get());
        }
    }

    // Called when the user changes the watch enabled preference
    private void onWatchEnabledChanged(boolean watchEnabled) {
        updateState(watchEnabled, ChanSettings.watchBackground.get());
        EventBus.getDefault().post(new PinMessages.PinsChangedMessage(pins));
    }

    // Called when the user changes the watch background enabled preference
    private void onBackgroundWatchingChanged(boolean backgroundEnabled) {
        updateState(isTimerEnabled(), backgroundEnabled);
        EventBus.getDefault().post(new PinMessages.PinsChangedMessage(pins));
    }

    // Called when the broadcast scheduled by the alarm manager was received
    public void onWake() {
        update(true);
    }

    // Called from the button on the notification
    public void pauseAll() {
        for (Pin pin : getWatchingPins()) {
            pin.watching = false;
        }

        updateState();
        updatePinsInDatabase();

        EventBus.getDefault().post(new PinMessages.PinsChangedMessage(pins));
    }

    public boolean hasAtLeastOnePinWithDownloadFlag() {
        for (Pin pin : pins) {
            if (PinType.hasDownloadFlag(pin.pinType)) {
                return true;
            }
        }

        return false;
    }

    // Clear all non watching pins or all pins
    // Returns a list of pins that can later be given to addAll to undo the clearing
    public List<Pin> clearPins(boolean all) {
        List<Pin> toRemove = new ArrayList<>();
        if (all) {
            toRemove.addAll(pins);
        } else {
            for (Pin pin : pins) {
                //if we're watching and a pin isn't being watched and has no flags, it's a candidate for clearing
                //if the pin is archived or errored out, it's a candidate for clearing
                if ((ChanSettings.watchEnabled.get() && !pin.watching && PinType.hasNoFlags(pin.pinType)) || (pin.archived || pin.isError)) {
                    toRemove.add(pin);
                }
            }
        }

        List<Pin> undo = new ArrayList<>(toRemove.size());
        for (Pin pin : toRemove) {
            undo.add(pin.clone());
        }
        deletePins(toRemove);
        return undo;
    }

    public List<Pin> getAllPins() {
        return pins;
    }

    public void addAll(List<Pin> pins) {
        Collections.sort(pins);
        for (Pin pin : pins) {
            createPin(pin);
        }
    }

    public PinWatcher getPinWatcher(Pin pin) {
        return pinWatchers.get(pin);
    }

    private boolean createPinWatcher(Pin pin) {
        if (!pinWatchers.containsKey(pin)) {
            pinWatchers.put(pin, new PinWatcher(pin));
            return true;
        } else {
            return false;
        }
    }

    private boolean destroyPinWatcher(Pin pin) {
        PinWatcher pinWatcher = pinWatchers.remove(pin);
        if (pinWatcher != null) {
            pinWatcher.destroy();
        }
        return pinWatcher != null;
    }

    private void updatePinsInDatabase() {
        databaseManager.runTaskAsync(databasePinManager.updatePins(pins));
    }

    private boolean isTimerEnabled() {
        return !getWatchingPins().isEmpty();
    }

    private void updateState() {
        updateState(isTimerEnabled(), ChanSettings.watchBackground.get());
    }

    // Update the interval type according to the current settings,
    // create and destroy PinWatchers where needed and update the notification
    private void updateState(boolean watchEnabled, boolean backgroundEnabled) {
        Logger.d(TAG, "updateState watchEnabled=" + watchEnabled + " backgroundEnabled=" + backgroundEnabled + " foreground=" + isInForeground());

        updateDeletedOrArchivedPins();
        switchIncrementalThreadDownloadingState(watchEnabled && backgroundEnabled);

        //determine expected interval type for current settings
        IntervalType intervalType;
        if (!watchEnabled) {
            intervalType = IntervalType.NONE;
        } else {
            if (isInForeground()) {
                intervalType = IntervalType.FOREGROUND;
            } else {
                if (backgroundEnabled) {
                    intervalType = IntervalType.BACKGROUND;
                } else {
                    intervalType = IntervalType.NONE;
                }
            }
        }

        if (!hasActivePins()) {
            Logger.d(TAG, "No active pins found, removing all wakeables");

            switch (currentInterval) {
                case FOREGROUND:
                    // Stop receiving handler messages
                    handler.removeMessages(MESSAGE_UPDATE);
                    break;
                case BACKGROUND:
                    // Stop receiving scheduled broadcasts
                    wakeManager.unregisterWakeable(this);
                    break;
            }
        } else {
            // Changing interval type, like when watching is disabled or the app goes to the background
            if (currentInterval != intervalType) {
                // Handle the preview state
                switch (currentInterval) {
                    case FOREGROUND:
                        // Stop receiving handler messages
                        handler.removeMessages(MESSAGE_UPDATE);
                        break;
                    case BACKGROUND:
                        // Stop receiving scheduled broadcasts
                        wakeManager.unregisterWakeable(this);
                        break;
                }

                Logger.d(TAG, "Setting interval type from " + currentInterval.name() + " to " + intervalType.name());
                currentInterval = intervalType;

                switch (currentInterval) {
                    case FOREGROUND:
                        // Schedule a delayed handler that will call update(false)
                        handler.sendMessageDelayed(handler.obtainMessage(MESSAGE_UPDATE), getInterval());
                        break;
                    case BACKGROUND:
                        // Start receiving scheduled broadcasts
                        wakeManager.registerWakeable(this);
                        break;
                }
            }
        }

        boolean allPinsHaveCompletedDownloading = true;

        // Update pin watchers
        for (Pin pin : pins) {
            SavedThread savedThread = findSavedThreadByLoadableId(pin.loadable.id);
            boolean isStoppedOrCompleted = savedThread != null &&
                    (savedThread.isStopped || savedThread.isFullyDownloaded);

            if (!isStoppedOrCompleted) {
                allPinsHaveCompletedDownloading = false;
            }

            if (ChanSettings.watchEnabled.get()) {
                createPinWatcher(pin);
            } else {
                destroyPinWatcher(pin);
            }
        }

        // Update notification state
        // Do not start the service when all pins are either stopped ot fully downloaded
        if (watchEnabled && backgroundEnabled && !allPinsHaveCompletedDownloading) {
            getAppContext().startService(WATCH_NOTIFICATION_INTENT);
        } else {
            getAppContext().stopService(WATCH_NOTIFICATION_INTENT);
        }
    }

    private boolean hasActivePins() {
        for (Pin pin : pins) {
            if (!pin.isError && !pin.archived) {
                return true;
            }
        }

        return false;
    }

    private void updateDeletedOrArchivedPins() {
        for (Pin pin : pins) {
            if (pin.isError || pin.archived) {
                SavedThread savedThread = findSavedThreadByLoadableId(pin.loadable.id);
                if (savedThread == null || savedThread.isStopped || savedThread.isFullyDownloaded) {
                    continue;
                }

                if (pin.isError || pin.archived) {
                    savedThread.isFullyDownloaded = true;
                }

                if (!savedThread.isFullyDownloaded) {
                    continue;
                }

                // TODO: should the pinType be updated here?
                createOrUpdateSavedThread(savedThread);

                databaseManager.runTask(() -> {
                    // Update thread in the DB
                    if (savedThread.isStopped) {
                        databaseManager.getDatabaseSavedThreadManager()
                                .updateThreadStoppedFlagByLoadableId(pin.loadable.id, true).call();
                    }

                    // Update thread in the DB
                    if (savedThread.isFullyDownloaded) {
                        databaseManager.getDatabaseSavedThreadManager()
                                .updateThreadFullyDownloadedByLoadableId(pin.loadable.id).call();
                    }

                    databasePinManager.updatePin(pin).call();
                    return null;
                });
            }
        }
    }

    /**
     * This method is getting called every time a user changes their watcher settings (watcher enabled and
     * background watcher enabled). isEnabled is true when both watchEnabled and backgroundEnabled are
     * true
     * */
    private void switchIncrementalThreadDownloadingState(boolean isEnabled) {
        if (prevIncrementalThreadSavingEnabled == isEnabled) {
            return;
        }

        prevIncrementalThreadSavingEnabled = isEnabled;
        List<Pin> pinsWithDownloadFlag = new ArrayList<>();

        for (Pin pin : pins) {
            if (pin.isError || pin.archived) {
                continue;
            }

            if ((PinType.hasDownloadFlag(pin.pinType))) {
                pinsWithDownloadFlag.add(pin);
            }
        }

        if (pinsWithDownloadFlag.isEmpty()) {
            Logger.d(TAG, "No pins found with download flag");
            return;
        }

        Logger.d(TAG, "Found " + pinsWithDownloadFlag.size() + " pins with download flag");

        for (Pin pin : pinsWithDownloadFlag) {
            if (isEnabled) {
                startSavingThreadInternal(pin.loadable);
            } else {
                stopSavingThread(pin.loadable);
            }
        }
    }

    // Update the watching pins
    private void update(boolean fromBackground) {
        Logger.d(TAG, "update() from " + (fromBackground ? "background" : "foreground"));

        if (currentInterval == IntervalType.FOREGROUND) {
            // reschedule handler message
            handler.sendMessageDelayed(handler.obtainMessage(MESSAGE_UPDATE), getInterval());
        }

        // A set of watchers that all have to complete being updated
        // before the wakelock is released again
        waitingForPinWatchersForBackgroundUpdate = null;
        if (fromBackground) {
            waitingForPinWatchersForBackgroundUpdate = new HashSet<>();
        }

        List<Pin> watchingPins = getWatchingPins();
        for (Pin pin : watchingPins) {
            PinWatcher pinWatcher = getPinWatcher(pin);
            if (pinWatcher != null && pinWatcher.update(fromBackground)) {
                EventBus.getDefault().post(new PinMessages.PinChangedMessage(pin));

                if (fromBackground) {
                    waitingForPinWatchersForBackgroundUpdate.add(pinWatcher);
                }
            }
        }

        if (fromBackground && !waitingForPinWatchersForBackgroundUpdate.isEmpty()) {
            Logger.i(TAG, "Acquiring wakelock for pin watcher updates");
            wakeManager.manageLock(true);
        }
    }

    /**
     * This method figures out what kind of foreground interval we should use.
     * */
    private long getInterval() {
        boolean hasAtLeastOneWatchNewPostsPin = false;
        boolean hasAtLeastOneDownloadNewPostsPin = false;

        for (Pin pin : pins) {
            if (PinType.hasWatchNewPostsFlag(pin.pinType) && pin.watching) {
                hasAtLeastOneWatchNewPostsPin = true;
            }
            if (PinType.hasDownloadFlag(pin.pinType)) {
                hasAtLeastOneDownloadNewPostsPin = true;
            }

            if (hasAtLeastOneDownloadNewPostsPin && hasAtLeastOneWatchNewPostsPin) {
                break;
            }
        }

        if (hasAtLeastOneDownloadNewPostsPin && hasAtLeastOneWatchNewPostsPin) {
            Logger.d(TAG, "Current interval is FOREGROUND_INTERVAL_MIXED");
            return FOREGROUND_INTERVAL_MIXED;
        }

        if (hasAtLeastOneWatchNewPostsPin) {
            Logger.d(TAG, "Current interval is FOREGROUND_INTERVAL_ONLY_WATCHES");
            return FOREGROUND_INTERVAL_ONLY_WATCHES;
        }

        Logger.d(TAG, "Current interval is FOREGROUND_INTERVAL_ONLY_DOWNLOADS");
        return FOREGROUND_INTERVAL_ONLY_DOWNLOADS;
    }

    private void pinWatcherUpdated(PinWatcher pinWatcher) {
        updateState();
        EventBus.getDefault().post(new PinMessages.PinChangedMessage(pinWatcher.pin));

        if (waitingForPinWatchersForBackgroundUpdate != null) {
            waitingForPinWatchersForBackgroundUpdate.remove(pinWatcher);

            if (waitingForPinWatchersForBackgroundUpdate.isEmpty()) {
                Logger.i(TAG, "All watchers updated, removing wakelock");
                waitingForPinWatchersForBackgroundUpdate = null;
                wakeManager.manageLock(false);
            }
        }
    }

    public static class PinMessages {
        public static class PinAddedMessage {
            public Pin pin;

            public PinAddedMessage(Pin pin) {
                this.pin = pin;
            }
        }

        public static class PinRemovedMessage {
            public Pin pin;

            public PinRemovedMessage(Pin pin) {
                this.pin = pin;
            }
        }

        public static class PinChangedMessage {
            public Pin pin;

            public PinChangedMessage(Pin pin) {
                this.pin = pin;
            }
        }

        public static class PinsChangedMessage {
            public List<Pin> pins;

            public PinsChangedMessage(List<Pin> pins) {
                this.pins = pins;
            }
        }
    }

    public class PinWatcher implements ChanThreadLoader.ChanLoaderCallback, PageRequestManager.PageCallback {
        private static final String TAG = "PinWatcher";

        private final Pin pin;
        private ChanThreadLoader chanLoader;

        private final List<Post> posts = new ArrayList<>();
        private final List<Post> quotes = new ArrayList<>();
        private boolean wereNewQuotes = false;
        private boolean wereNewPosts = false;
        private boolean notified = true;

        public PinWatcher(Pin pin) {
            this.pin = pin;

            Logger.d(TAG, "PinWatcher: created for " + pin);
            chanLoader = chanLoaderFactory.obtain(pin.loadable, this);
            pageRequestManager.addListener(this);
        }

        public int getReplyCount() {
            if (chanLoader != null && chanLoader.getThread() != null && chanLoader.getThread().posts != null) {
                return chanLoader.getThread().posts.size() - 1;
            }
            return 0;
        }

        public int getImageCount() {
            if (chanLoader != null && chanLoader.getThread() != null && chanLoader.getThread().posts != null) {
                int total = 0;
                for (Post p : chanLoader.getThread().posts) {
                    if (!p.isOP) total += p.images.size();
                }
                return total;
            }
            return 0;
        }

        public List<Post> getPosts() {
            return posts;
        }

        public List<Post> getUnviewedPosts() {
            if (posts.isEmpty()) {
                return posts;
            } else {
                return posts.subList(Math.max(0, posts.size() - pin.getNewPostCount()), posts.size());
            }
        }

        public List<Post> getUnviewedQuotes() {
            return quotes.subList(Math.max(0, quotes.size() - pin.getNewQuoteCount()), quotes.size());
        }

        public boolean getWereNewQuotes() {
            if (wereNewQuotes) {
                wereNewQuotes = false;
                return true;
            } else {
                return false;
            }
        }

        public boolean getWereNewPosts() {
            if (wereNewPosts) {
                wereNewPosts = false;
                return true;
            } else {
                return false;
            }
        }

        private void destroy() {
            if (chanLoader != null) {
                Logger.d(TAG, "PinWatcher: destroyed for " + pin);
                chanLoaderFactory.release(chanLoader, this);
                chanLoader = null;
            }
            pageRequestManager.removeListener(this);
        }

        private boolean update(boolean fromBackground) {
            if (!pin.isError && pin.watching) {
                //check last page stuff, get the page for the OP and notify in the onPages method
                Chan4PagesRequest.Page page = pageRequestManager.getPage(chanLoader.getLoadable());
                if (page != null) {
                    doPageNotification(page);
                }
                if (fromBackground) {
                    // Always load regardless of timer, since the time left is not accurate for 15min+ intervals
                    chanLoader.clearTimer();
                    chanLoader.requestMoreData();
                    return true;
                } else {
                    // true if a load was started
                    return chanLoader.loadMoreIfTime();
                }
            } else {
                return false;
            }
        }

        @Override
        public void onChanLoaderError(ChanThreadLoader.ChanLoaderException error) {
            // Ignore normal network errors, we only pause pins when there is absolutely no way
            // we'll ever need watching again: a 404.
            if (error.isNotFound()) {
                pin.isError = true;
                pin.watching = false;
            }

            pinWatcherUpdated(this);
        }

        @Override
        public void onChanLoaderData(ChanThread thread) {
            if (thread.archived || thread.closed) {
                NetworkResponse networkResponse = new NetworkResponse(
                        NetworkResponse.STATUS_NOT_FOUND,
                        EMPTY_BYTE_ARRAY,
                        Collections.emptyMap(),
                        true);
                ServerError serverError = new ServerError(networkResponse);

                onChanLoaderError(new ChanThreadLoader.ChanLoaderException(serverError));
                return;
            }

            pin.isError = false;
            /*
             * Forcibly update watched thread titles
             * This solves the issue of when you post a thread and the site doesn't have the thread listed yet,
             * resulting in the thread title being something like /test/918324919 instead of a proper title
             *
             * The thread title will be updated as soon as the site has the thread listed in the thread directory
             *
             */
            pin.loadable.setTitle(PostHelper.getTitle(thread.op, pin.loadable));

            if (pin.thumbnailUrl == null && thread.op != null && thread.op.image() != null) {
                pin.thumbnailUrl = thread.op.image().getThumbnailUrl().toString();
            }

            // Populate posts list
            posts.clear();
            posts.addAll(thread.posts);

            // Populate quotes list
            quotes.clear();

            // Get list of saved replies from this thread
            List<Post> savedReplies = new ArrayList<>();
            for (Post item : thread.posts) {
                // saved.title = pin.loadable.title;

                if (item.isSavedReply) {
                    savedReplies.add(item);
                }
            }

            // Now get a list of posts that have a quote to a saved reply, but not self-replies
            for (Post post : thread.posts) {
                for (Post saved : savedReplies) {
                    if (post.repliesTo.contains(saved.no) && !post.isSavedReply) {
                        quotes.add(post);
                    }
                }
            }

            boolean isFirstLoad = pin.watchNewCount < 0 || pin.quoteNewCount < 0;

            // If it was more than before processing
            int lastWatchNewCount = pin.watchNewCount;
            int lastQuoteNewCount = pin.quoteNewCount;

            if (isFirstLoad) {
                pin.watchLastCount = posts.size();
                pin.quoteLastCount = quotes.size();
            }

            pin.watchNewCount = posts.size();
            pin.quoteNewCount = quotes.size();

            if (!isFirstLoad) {
                // There were new posts after processing
                if (pin.watchNewCount > lastWatchNewCount) {
                    wereNewPosts = true;
                }

                // There were new quotes after processing
                if (pin.quoteNewCount > lastQuoteNewCount) {
                    wereNewQuotes = true;
                }
            }

            if (BuildConfig.DEBUG) {
                Logger.d(TAG, String.format(Locale.ENGLISH,
                        "postlast=%d postnew=%d werenewposts=%b quotelast=%d quotenew=%d werenewquotes=%b nextload=%ds",
                        pin.watchLastCount, pin.watchNewCount, wereNewPosts, pin.quoteLastCount,
                        pin.quoteNewCount, wereNewQuotes, chanLoader.getTimeUntilLoadMore() / 1000));
            }

            if (thread.archived || thread.closed) {
                pin.archived = true;
                pin.watching = false;
            }

            pinWatcherUpdated(this);
        }

        @Override
        public void onPagesReceived() {
            //this call will return the proper value now, but if it returns null just skip everything
            Chan4PagesRequest.Page p = pageRequestManager.getPage(chanLoader.getLoadable());
            doPageNotification(p);
        }

        private void doPageNotification(Chan4PagesRequest.Page page) {
            if (ChanSettings.watchEnabled.get()
                    && ChanSettings.watchLastPageNotify.get()
                    && ChanSettings.watchBackground.get()) {
                if (page != null && page.page >= pin.loadable.board.pages && !notified) {
                    Intent pageNotifyIntent = new Intent(getAppContext(), LastPageNotification.class);
                    pageNotifyIntent.putExtra("pin_id", pin.id);
                    getAppContext().startService(pageNotifyIntent);
                    notified = true;
                } else if (page != null && page.page < pin.loadable.board.pages) {
                    getAppContext().stopService(new Intent(getAppContext(), LastPageNotification.class));
                    notified = false;
                }
            }
        }
    }
}
