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
import android.app.PendingIntent;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.support.v4.app.NotificationCompat;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;

import com.miz.abstractclasses.MovieFileSource;
import com.miz.apis.trakt.Trakt;
import com.miz.db.DbAdapterSources;
import com.miz.filesources.FileMovie;
import com.miz.filesources.SmbMovie;
import com.miz.filesources.UpnpMovie;
import com.miz.functions.FileSource;
import com.miz.functions.MizLib;
import com.miz.functions.MovieLibraryUpdateCallback;
import com.miz.identification.MovieIdentification;
import com.miz.identification.MovieStructure;
import com.miz.mizuu.BuildConfig;
import com.miz.mizuu.Main;
import com.miz.mizuu.R;
import com.miz.utils.LocalBroadcastUtils;
import com.miz.utils.MovieDatabaseUtils;

import static com.miz.functions.PreferenceKeys.CLEAR_LIBRARY_MOVIES;
import static com.miz.functions.PreferenceKeys.REMOVE_UNAVAILABLE_FILES_MOVIES;

public class MovieLibraryUpdate
        extends CommonLibraryUpdateService<MovieStructure, MovieFileSource<?>>
        implements MovieLibraryUpdateCallback {

    public static final String STOP_MOVIE_LIBRARY_UPDATE = "mizuu-stop-movie-library-update";

    private final int NOTIFICATION_ID = 200, POST_UPDATE_NOTIFICATION = 213;
    private int count;
    private MovieIdentification mMovieIdentification;
    private String NOTIFICATION_CHANNEL_ID = "MIZUU";

    public MovieLibraryUpdate() {
        super("MovieLibraryUpdate");
    }

    public MovieLibraryUpdate(String name) {
        super(name);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        log("onDestroy()");

        if (notificationManager == null) {
            notificationManager = getNotificationManager();
        }

        notificationManager.cancel(NOTIFICATION_ID);

        reloadLibrary();

        showPostUpdateNotification();

        LocalBroadcastManager.getInstance(this).unregisterReceiver(messageReceiver);

        MizLib.scheduleMovieUpdate(this);

        if (Trakt.hasTraktAccount(this) && syncLibraries && count > 0) {
            getApplicationContext().startService(new Intent(getApplicationContext(), TraktMoviesSyncService.class));
        }
    }


    @Override protected MovieFileSource<?> getFileSourceAsUpnpFile(FileSource fileSource) {
        return new UpnpMovie(getApplicationContext(), fileSource, clearLibrary);
    }

    @Override protected MovieFileSource<?> getFileSourceAsSmbFile(FileSource fileSource) {
        return new SmbMovie(getApplicationContext(), fileSource, clearLibrary);
    }

    @Override protected MovieFileSource<?> getFileSourceAsLocalFile(FileSource fileSource) {
        return new FileMovie(getApplicationContext(), fileSource, clearLibrary);
    }

    @Override protected Cursor getDBCursor(DbAdapterSources dbHelperSources) {
        return dbHelperSources.fetchAllMovieSources();
    }

    @Override protected String getRemoveUnavailableFilesSettingsTag() {
        return REMOVE_UNAVAILABLE_FILES_MOVIES;
    }

    @Override protected String getClearLibrarySettingsTag() {
        return CLEAR_LIBRARY_MOVIES;
    }

    @Override protected int getNotificationID() {
        return NOTIFICATION_ID;
    }

    @Override protected int getNotificationDescription() {
        return R.string.updatingMovieInfo;
    }

    @Override protected int getNotificationTitle() {
        return R.string.updatingMovies;
    }

    @Override protected String getCancelIntentFilter() {
        return STOP_MOVIE_LIBRARY_UPDATE;
    }

    @Override protected boolean isMovie() {
        return true;
    }

    @Override protected String getLogTag() {
        return MovieLibraryUpdate.class.getSimpleName();
    }

    @Override protected void doCancel() {
        if (mMovieIdentification != null) {
            mMovieIdentification.cancel();
        }
    }

    @Override protected void removeTypeFromDatabase() {
        MovieDatabaseUtils.deleteAllMovies(this);
    }

    @Override protected MovieStructure createFileFrom(String filePath) {
        return new MovieStructure(filePath);
    }

    @Override protected void reloadLibrary() {
        LocalBroadcastUtils.updateMovieLibrary(getApplicationContext());
    }

    @Override protected void doIdentification() {
        mMovieIdentification = new MovieIdentification(getApplicationContext(), this, files);
        mMovieIdentification.start();
    }

    private void log(String msg) {
        if (BuildConfig.DEBUG) {
            Log.d("MovieLibraryUpdate", msg);
        }
    }

    @Override
    public void onMovieAdded(String title, Bitmap cover, Bitmap backdrop, int count) {
        this.count = count;
        updateMovieAddedNotification(title, cover, backdrop);
    }

    private void updateMovieAddedNotification(String title, Bitmap cover, Bitmap backdrop) {
        builder.setLargeIcon(cover);
        builder.setContentTitle(getString(R.string.updatingMovies) + " (" + (int) ((100.0 / (double) totalFiles) * (double) count) + "%)");
        builder.setContentText(getString(R.string.stringJustAdded) + ": " + title);
        builder.setStyle(
                new NotificationCompat.BigPictureStyle()
                        .setSummaryText(getString(R.string.stringJustAdded) + ": " + title)
                        .bigPicture(backdrop)
        );

        // Show the updated notification
        notificationManager.notify(NOTIFICATION_ID, builder.build());
    }

    private void showPostUpdateNotification() {
        // Set up cancel dialog intent
        Intent notificationIntent = new Intent(this, Main.class);
        notificationIntent.putExtra("fromUpdate", true);
        notificationIntent.putExtra("startup", "1");
        notificationIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, notificationIntent, 0);

        // Setup up notification
        builder = new NotificationCompat.Builder(getApplicationContext(), NOTIFICATION_CHANNEL_ID);
        builder.setColor(getResources().getColor(R.color.color_primary));
        if (!stopUpdate) {
            builder.setSmallIcon(R.drawable.ic_done_white_24dp);
            builder.setTicker(getString(R.string.finishedMovieLibraryUpdate));
            builder.setContentTitle(getString(R.string.finishedMovieLibraryUpdate));
            builder.setContentText(getString(R.string.stringJustAdded) + " " + count + " " + getResources().getQuantityString(R.plurals.moviesInLibrary, count));
        } else {
            builder.setSmallIcon(R.drawable.ic_cancel_white_24dp);
            builder.setTicker(getString(R.string.stringUpdateCancelled));
            builder.setContentTitle(getString(R.string.stringUpdateCancelled));
            builder.setContentText(getString(R.string.stringJustAdded) + " " + count + " " + getResources().getQuantityString(R.plurals.moviesInLibrary, count, count));
        }
        builder.setContentIntent(contentIntent);
        builder.setAutoCancel(true);

        // Build notification
        Notification updateNotification = builder.build();

        // Show the notification
        notificationManager = getNotificationManager();

        if (count > 0) {
            notificationManager.notify(POST_UPDATE_NOTIFICATION, updateNotification);
        }
    }
}