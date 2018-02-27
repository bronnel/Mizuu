/*
 * Copyright (C) 2014 Michell Bak
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.miz.service;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.support.v4.app.NotificationCompat;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;

import com.miz.abstractclasses.TvShowFileSource;
import com.miz.apis.trakt.Trakt;
import com.miz.db.DbAdapterSources;
import com.miz.db.DbAdapterTvShowEpisodes;
import com.miz.db.DbAdapterTvShows;
import com.miz.filesources.FileTvShow;
import com.miz.filesources.SmbTvShow;
import com.miz.filesources.UpnpTvShow;
import com.miz.functions.FileSource;
import com.miz.functions.MizLib;
import com.miz.functions.TvShowLibraryUpdateCallback;
import com.miz.identification.ShowStructure;
import com.miz.identification.TvShowIdentification;
import com.miz.mizuu.Main;
import com.miz.mizuu.MizuuApplication;
import com.miz.mizuu.R;
import com.miz.utils.FileUtils;
import com.miz.utils.LocalBroadcastUtils;

import java.util.HashSet;

import static com.miz.functions.PreferenceKeys.CLEAR_LIBRARY_TVSHOWS;
import static com.miz.functions.PreferenceKeys.REMOVE_UNAVAILABLE_FILES_TVSHOWS;

public class TvShowsLibraryUpdate
        extends CommonLibraryUpdateService<ShowStructure, TvShowFileSource<?>>
        implements TvShowLibraryUpdateCallback {

    public static final String STOP_TVSHOW_LIBRARY_UPDATE = "mizuu-stop-tvshow-library-update";
    private boolean mDebugging = true;
    private HashSet<String> mUniqueShowIds = new HashSet<String>();
    private int mShowCount, mEpisodeCount;
    private final int NOTIFICATION_ID = 300, POST_UPDATE_NOTIFICATION = 313;
    private NotificationManager mNotificationManager;
    private NotificationCompat.Builder mBuilder;
    private TvShowIdentification mIdentification;

    public TvShowsLibraryUpdate() {
        super("TvShowsLibraryUpdate");
    }

    public TvShowsLibraryUpdate(String name) {
        super(name);
    }

    @Override protected void doIdentification() {
        mIdentification = new TvShowIdentification(getApplicationContext(), this, files);
        mIdentification.start();
    }

    @Override protected ShowStructure createFileFrom(String filePath) {
        return new ShowStructure(filePath);
    }

    @Override protected void reloadLibrary() {
        LocalBroadcastUtils.updateTvShowLibrary(getApplicationContext());

    }

    @Override protected void removeTypeFromDatabase() {
        // Delete all shows from the database
        DbAdapterTvShows db = MizuuApplication.getTvDbAdapter();
        db.deleteAllShowsInDatabase();

        DbAdapterTvShowEpisodes dbEpisodes = MizuuApplication.getTvEpisodeDbAdapter();
        dbEpisodes.deleteAllEpisodes();

        MizuuApplication.getTvShowEpisodeMappingsDbAdapter().deleteAllFilepaths();

        // Delete all downloaded images files from the device
        FileUtils.deleteRecursive(MizuuApplication.getTvShowThumbFolder(this), false);
        FileUtils.deleteRecursive(MizuuApplication.getTvShowEpisodeFolder(this), false);
        FileUtils.deleteRecursive(MizuuApplication.getTvShowBackdropFolder(this), false);
        FileUtils.deleteRecursive(MizuuApplication.getTvShowSeasonFolder(this), false);
    }

    @Override protected TvShowFileSource<?> getFileSourceAsUpnpFile(FileSource fileSource) {
        return new UpnpTvShow(getApplicationContext(), fileSource, clearLibrary);
    }

    @Override protected TvShowFileSource<?> getFileSourceAsSmbFile(FileSource fileSource) {
        return new SmbTvShow(getApplicationContext(), fileSource, clearLibrary);
    }

    @Override protected TvShowFileSource<?> getFileSourceAsLocalFile(FileSource fileSource) {
        return new FileTvShow(getApplicationContext(), fileSource, clearLibrary);
    }

    @Override protected Cursor getDBCursor(DbAdapterSources dbHelperSources) {
        return dbHelperSources.fetchAllShowSources();
    }

    @Override protected String getRemoveUnavailableFilesSettingsTag() {
        return REMOVE_UNAVAILABLE_FILES_TVSHOWS;
    }

    @Override protected String getClearLibrarySettingsTag() {
        return CLEAR_LIBRARY_TVSHOWS;
    }

    @Override protected int getNotificationID() {
        return NOTIFICATION_ID;
    }

    @Override protected int getNotificationDescription() {
        return R.string.updatingTvShows;
    }

    @Override protected int getNotificationTitle() {
        return R.string.updatingTvShows;
    }

    @Override protected String getCancelIntentFilter() {
        return STOP_TVSHOW_LIBRARY_UPDATE;
    }

    @Override protected boolean isMovie() {
        return false;
    }

    @Override protected String getLogTag() {
        return TvShowsLibraryUpdate.class.getSimpleName();
    }

    @Override protected void doCancel() {
        if (mIdentification != null) {
            mIdentification.cancel();
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        log("onDestroy()");

        if (mNotificationManager == null) {
            mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        }

        mNotificationManager.cancel(NOTIFICATION_ID);

        LocalBroadcastUtils.updateTvShowLibrary(this);

        showPostUpdateNotification();

        LocalBroadcastManager.getInstance(this).unregisterReceiver(messageReceiver);

        MizLib.scheduleShowsUpdate(this);

        if (Trakt.hasTraktAccount(this) && syncLibraries && (mEpisodeCount > 0)) {
            startService(new Intent(getApplicationContext(), TraktTvShowsSyncService.class));
        }
    }

    @Override protected int calculateTotalFiles() {
        int episodeCount = 0;
        for (ShowStructure ss : files)
            episodeCount += ss.getEpisodes().size();
        return episodeCount;
    }

    protected void clear() {
        super.clear();
        // Lists
        mUniqueShowIds = new HashSet<String>();


        // Other variables
        mShowCount = 0;
    }

    private void log(String msg) {
        if (mDebugging) {
            Log.d("TvShowsLibraryUpdate", msg);
        }
    }

    @Override
    public void onTvShowAdded(String showId, String title, Bitmap cover, Bitmap backdrop, int count) {
        if (!showId.equals(DbAdapterTvShows.UNIDENTIFIED_ID)) {
            mUniqueShowIds.add(showId);
        }
        updateTvShowAddedNotification(showId, title, cover, backdrop, count);
    }

    @Override
    public void onEpisodeAdded(String showId, String title, Bitmap cover, Bitmap photo) {
        if (!showId.equals(DbAdapterTvShows.UNIDENTIFIED_ID)) {
            mEpisodeCount++;
        }
        updateEpisodeAddedNotification(showId, title, cover, photo);
    }

    private void updateEpisodeAddedNotification(String showId, String title, Bitmap cover, Bitmap backdrop) {
        String contentText;
        if (showId.isEmpty() || showId.equalsIgnoreCase(DbAdapterTvShows.UNIDENTIFIED_ID)) {
            contentText = getString(R.string.unidentified) + ": " + title;
        } else {
            contentText = getString(R.string.stringJustAdded) + ": " + title;
        }

        mBuilder.setLargeIcon(cover);
        mBuilder.setContentTitle(getString(R.string.updatingTvShows) + " (" + (int) ((100.0 / (double) totalFiles) * (double) mEpisodeCount) + "%)");
        mBuilder.setContentText(contentText);
        mBuilder.setStyle(
                new NotificationCompat.BigPictureStyle()
                        .setSummaryText(contentText)
                        .bigPicture(backdrop)
        );

        // Show the updated notification
        mNotificationManager.notify(NOTIFICATION_ID, mBuilder.build());
    }

    private void updateTvShowAddedNotification(String showId, String title, Bitmap cover, Bitmap backdrop, int count) {
        String contentText;
        if (showId.isEmpty() || showId.equalsIgnoreCase(DbAdapterTvShows.UNIDENTIFIED_ID)) {
            contentText = getString(R.string.unidentified) + ": " + title + " (" + count + " " + getResources().getQuantityString(R.plurals.episodes, count, count) + ")";
        } else {
            contentText = getString(R.string.stringJustAdded) + ": " + title + " (" + count + " " + getResources().getQuantityString(R.plurals.episodes, count, count) + ")";
        }

        mBuilder.setLargeIcon(cover);
        mBuilder.setContentTitle(getString(R.string.updatingTvShows) + " (" + (int) ((100.0 / (double) totalFiles) * (double) mEpisodeCount) + "%)");
        mBuilder.setContentText(contentText);
        mBuilder.setStyle(
                new NotificationCompat.BigPictureStyle()
                        .setSummaryText(contentText)
                        .bigPicture(backdrop)
        );

        // Show the updated notification
        mNotificationManager.notify(NOTIFICATION_ID, mBuilder.build());
    }


    private void showPostUpdateNotification() {
        mShowCount = mUniqueShowIds.size();

        // Set up cancel dialog intent
        Intent notificationIntent = new Intent(this, Main.class);
        notificationIntent.putExtra("fromUpdate", true);
        notificationIntent.putExtra("startup", "2");
        notificationIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, notificationIntent, 0);

        // Setup up notification
        mBuilder = new NotificationCompat.Builder(getApplicationContext());
        mBuilder.setColor(getResources().getColor(R.color.color_primary));
        if (!stopUpdate) {
            mBuilder.setSmallIcon(R.drawable.ic_done_white_24dp);
            mBuilder.setTicker(getString(R.string.finishedTvShowsLibraryUpdate));
            mBuilder.setContentTitle(getString(R.string.finishedTvShowsLibraryUpdate));
            mBuilder.setContentText(getString(R.string.stringJustAdded) + " " + mShowCount + " " + getResources().getQuantityString(R.plurals.showsInLibrary, mShowCount, mShowCount) + " (" + mEpisodeCount + " " + getResources().getQuantityString(R.plurals.episodes, mEpisodeCount, mEpisodeCount) + ")");
        } else {
            mBuilder.setSmallIcon(R.drawable.ic_cancel_white_24dp);
            mBuilder.setTicker(getString(R.string.stringUpdateCancelled));
            mBuilder.setContentTitle(getString(R.string.stringUpdateCancelled));
            mBuilder.setContentText(getString(R.string.stringJustAdded) + " " + mShowCount + " " + getResources().getQuantityString(R.plurals.showsInLibrary, mShowCount, mShowCount) + " (" + mEpisodeCount + " " + getResources().getQuantityString(R.plurals.episodes, mEpisodeCount, mEpisodeCount) + ")");
        }
        mBuilder.setContentIntent(contentIntent);
        mBuilder.setAutoCancel(true);

        // Build notification
        Notification updateNotification = mBuilder.build();

        // Show the notification
        mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

        if (mEpisodeCount > 0) {
            mNotificationManager.notify(POST_UPDATE_NOTIFICATION, updateNotification);
        }
    }
}