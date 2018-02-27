package com.miz.service;

import android.app.IntentService;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.os.Build;
import android.preference.PreferenceManager;
import android.support.v4.app.NotificationCompat;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;

import com.miz.abstractclasses.AbstractFileSource;
import com.miz.db.DbAdapterSources;
import com.miz.functions.FileSource;
import com.miz.functions.MizLib;
import com.miz.mizuu.CancelLibraryUpdate;
import com.miz.mizuu.MizuuApplication;
import com.miz.mizuu.R;

import java.util.ArrayList;
import java.util.List;

import static com.miz.functions.PreferenceKeys.SYNC_WITH_TRAKT;

/**
 * Created by stumi on 25/02/18.
 */

public abstract class CommonLibraryUpdateService<T, FileSourceType extends AbstractFileSource<?>>
        extends IntentService {

    private String NOTIFICATION_CHANNEL_ID = "MIZUU";

    private ArrayList<FileSource> fileSources;
    private ArrayList<FileSourceType> typeFileSources;
    protected ArrayList<T> files;
    protected boolean clearLibrary;
    private boolean clearUnavailable;
    protected boolean syncLibraries;
    protected boolean stopUpdate;
    private SharedPreferences settings;
    private SharedPreferences.Editor editor;
    protected int totalFiles;
    protected NotificationManager notificationManager;
    protected NotificationCompat.Builder builder;

    protected BroadcastReceiver messageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            stopUpdate = true;
            doCancel();
        }
    };

    /**
     * Creates an IntentService.  Invoked by your subclass's constructor.
     *
     * @param name Used to name the worker thread, important only for debugging.
     */
    public CommonLibraryUpdateService(String name) {
        super(name);
    }

    @Override
    protected void onHandleIntent(Intent intent) {

        log("clear()");

        // Clear and set up all variables
        clear();

        log("setup()");

        // Set up Notification, variables, etc.
        setup();

        log("loadFileSources()");

        // Load all file sources from the database
        loadFileSources();

        log("setupMovieFileSources()");

        // Add the different file sources to the MovieFileSource ArrayList
        setupFileSources();

        if (stopUpdate) {
            return;
        }

        log("removeUnidentifiedFiles()");

        // Remove unavailable movies, so we can try to identify them again
        if (!clearLibrary) {
            removeUnidentifiedFiles();
        }

        if (stopUpdate) {
            return;
        }

        // Check if the library should be cleared
        if (clearLibrary) {

            // Reset the preference, so it isn't checked the next
            // time the user wants to update the library
            editor = settings.edit();
            editor.putBoolean(getClearLibrarySettingsTag(), false);
            editor.apply();

            log("removeTypeFromDatabase()");

            // Remove all entries from the database
            removeTypeFromDatabase();
        }

        if (stopUpdate) {
            return;
        }

        // Check if we should remove all unavailable files.
        // Note that this only makes sense if we haven't already cleared the library.
        if (!clearLibrary && clearUnavailable) {

            log("removeUnavailableFiles()");

            // Remove all unavailable files from the database
            removeUnavailableFiles();
        }

        log("searchFolders()");

        if (stopUpdate) {
            return;
        }

        reloadLibrary();

        // Search all folders
        searchFolders();

        if (stopUpdate) {
            return;
        }
        log("mTotalFiles > 0 check");

        // Check if we've found any files to identify
        if (totalFiles > 0) {
            log("updateMovies()");

            // Start the actual movie update / identification task
            doIdentification();
        }
    }

    protected abstract void doIdentification();

    private void searchFolders() {
        // Temporary collections
        List<String> tempList = null;

        for (int j = 0; j < typeFileSources.size(); j++) {
            updateScanningNotification(typeFileSources.get(j).toString());
            tempList = typeFileSources.get(j).searchFolder();
            for (int i = 0; i < tempList.size(); i++) {
                files.add(createFileFrom(tempList.get(i)));
            }
        }

        // Clean up...
        if (tempList != null) {
            tempList.clear();
        }

        totalFiles = calculateTotalFiles();

    }

    protected int calculateTotalFiles() {
        return files.size();
    }

    private void updateScanningNotification(String filesource) {
        builder.setSmallIcon(R.drawable.ic_sync_white_24dp);
        builder.setContentTitle(getString(getNotificationTitle()));
        builder.setContentText(getString(R.string.scanning) + ": " + filesource);

        // Show the updated notification
        notificationManager.notify(getNotificationID(), builder.build());
    }

    protected abstract T createFileFrom(String filePath);

    protected abstract void reloadLibrary();

    private void removeUnavailableFiles() {
        for (FileSourceType fileSource : typeFileSources) {
            fileSource.removeUnavailableFiles();
        }
    }

    protected abstract void removeTypeFromDatabase();

    private void removeUnidentifiedFiles() {
        for (FileSourceType fileSource : typeFileSources) {
            fileSource.removeUnidentifiedFiles();
        }
    }

    private void setupFileSources() {
        for (FileSource fileSource : fileSources) {
            if (stopUpdate) {
                return;
            }
            switch (fileSource.getFileSourceType()) {
                case FileSource.FILE:
                    typeFileSources.add(getFileSourceAsLocalFile(fileSource));
                    break;
                case FileSource.SMB:
                    typeFileSources.add(getFileSourceAsSmbFile(fileSource));
                    break;
                case FileSource.UPNP:
                    typeFileSources.add(getFileSourceAsUpnpFile(fileSource));
                    break;
            }
        }
    }

    private void log(String msg) {
        Log.d(getLogTag(), msg);
    }

    protected void clear() {
        // Lists
        fileSources = new ArrayList<>();
        typeFileSources = new ArrayList<>();
        files = new ArrayList<>();

        // Booleans
        clearLibrary = false;
        clearUnavailable = false;
        syncLibraries = true;
        stopUpdate = false;

        // Other variables
        editor = null;
        settings = null;
        totalFiles = 0;
        notificationManager = null;
        builder = null;
    }

    private void setup() {
        setupNotificationChannel();
        if (!MizLib.isOnline(this)) {
            stopUpdate = true;
            return;
        }

        LocalBroadcastManager.getInstance(getApplicationContext()).registerReceiver(messageReceiver, new IntentFilter(getCancelIntentFilter()));

        // Set up cancel dialog intent
        Intent notificationIntent = new Intent(this, CancelLibraryUpdate.class);
        notificationIntent.putExtra("isMovie", isMovie());
        notificationIntent.setAction(Intent.ACTION_MAIN);
        notificationIntent.addCategory(Intent.CATEGORY_LAUNCHER);
        PendingIntent contentIntent = PendingIntent.getActivity(this, getNotificationID(), notificationIntent, 0);

        // Setup up notification
        builder = new NotificationCompat
                .Builder(getApplicationContext(), NOTIFICATION_CHANNEL_ID)
                .setColor(getResources().getColor(R.color.color_primary))
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setSmallIcon(R.drawable.ic_sync_white_24dp)
                .setTicker(getString(getNotificationTitle()))
                .setContentTitle(getString(getNotificationTitle()))
                .setContentText(getString(R.string.gettingReady))
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(R.drawable.ic_close_white_24dp, getString(android.R.string.cancel), contentIntent);

        // Build notification
        Notification updateNotification = builder.build();

        // Show the notification
        getNotificationManager().notify(getNotificationID(), updateNotification);

        // Tell the system that this is an ongoing notification, so it shouldn't be killed
        startForeground(getNotificationID(), updateNotification);

        settings = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        clearLibrary = settings.getBoolean(getClearLibrarySettingsTag(), false);
        clearUnavailable = settings.getBoolean(getRemoveUnavailableFilesSettingsTag(), false);
        syncLibraries = settings.getBoolean(SYNC_WITH_TRAKT, true);
    }

    private void setupNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Create the NotificationChannel, but only on API 26+ because
            // the NotificationChannel class is new and not in the support library
            CharSequence name = getString(getNotificationTitle());
            String description = getString(getNotificationDescription());
            int importance = NotificationManager.IMPORTANCE_DEFAULT;
            NotificationChannel channel = new NotificationChannel(NOTIFICATION_CHANNEL_ID, name, importance);
            channel.setDescription(description);
            // Register the channel with the system
            NotificationManager notificationManager = getNotificationManager();
            notificationManager.createNotificationChannel(channel);
        }
    }

    private void loadFileSources() {
        fileSources = new ArrayList<>();
        DbAdapterSources dbHelperSources = MizuuApplication.getSourcesAdapter();
        Cursor c = getDBCursor(dbHelperSources);
        try {
            while (c.moveToNext()) {
                fileSources.add(new FileSource(
                        c.getLong(c.getColumnIndex(DbAdapterSources.KEY_ROWID)),
                        c.getString(c.getColumnIndex(DbAdapterSources.KEY_FILEPATH)),
                        c.getInt(c.getColumnIndex(DbAdapterSources.KEY_FILESOURCE_TYPE)),
                        c.getString(c.getColumnIndex(DbAdapterSources.KEY_USER)),
                        c.getString(c.getColumnIndex(DbAdapterSources.KEY_PASSWORD)),
                        c.getString(c.getColumnIndex(DbAdapterSources.KEY_DOMAIN)),
                        c.getString(c.getColumnIndex(DbAdapterSources.KEY_TYPE))
                ));
            }
        } catch (Exception e) {
            log(e.getMessage());
        } finally {
            c.close();
        }
    }

    protected NotificationManager getNotificationManager() {
        if (notificationManager == null) {
            notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        }
        return notificationManager;
    }

    protected abstract FileSourceType getFileSourceAsUpnpFile(FileSource fileSource);

    protected abstract FileSourceType getFileSourceAsSmbFile(FileSource fileSource);

    protected abstract FileSourceType getFileSourceAsLocalFile(FileSource fileSource);

    protected abstract Cursor getDBCursor(DbAdapterSources dbHelperSources);

    protected abstract String getRemoveUnavailableFilesSettingsTag();

    protected abstract String getClearLibrarySettingsTag();

    protected abstract int getNotificationID();

    protected abstract int getNotificationDescription();

    protected abstract int getNotificationTitle();

    protected abstract String getCancelIntentFilter();

    protected abstract boolean isMovie();

    protected abstract String getLogTag();

    protected abstract void doCancel();

}
