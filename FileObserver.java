/*
 * Copyright (C) 2015 JRummy Apps, Inc. - All Rights Reserved
 *
 * Unauthorized copying or redistribution of this file in source and binary forms via any medium
 * is strictly prohibited.
 * 
 * Created by Jared Rummler <jared.rummler@gmail.com>, Mar 31, 2015
 */
package com.jrummyapps.rootbrowser.utils;

import java.io.File;
import java.util.Locale;

import android.content.Context;
import android.os.FileObserver;
import android.os.Handler;

import com.jrummyapps.android.exceptions.Confirm;
import com.jrummyapps.android.io.AFile;
import com.jrummyapps.android.log.Lg;
import com.jrummyapps.android.os.Architecture;
import com.jrummyapps.android.rootchecker.RootChecker;
import com.jrummyapps.android.shell.chainfire.Shell;
import com.jrummyapps.android.util.AssetUtils;

/**
 * Monitors files (using <a href="http://en.wikipedia.org/wiki/Inotify">inotify</a>) to fire an
 * event after files are accessed or changed by by any process on the device (including this one).
 * FileObserver is an abstract class; subclasses must implement the event handler
 * {@link #onEvent(int, String)}.
 *
 * <p>
 * Each FileObserver instance monitors a single file or directory. If a directory is monitored,
 * events will be triggered for all files and subdirectories inside the monitored directory.
 * </p>
 *
 * <p>
 * An event mask is used to specify which changes or actions to report. Event type constants are
 * used to describe the possible changes in the event mask as well as what actually happened in
 * event callbacks.
 * </p>
 *
 * <p class="caution">
 * <b>Warning</b>: If a FileObserver is garbage collected, it will stop sending events. To ensure
 * you keep receiving events, you must keep a reference to the FileObserver instance from some other
 * live object.
 * </p>
 * 
 * @author Jared Rummler <jared.rummler@gmail.com>
 */
public abstract class RootFileObserver extends FileObserver {

    // ~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~
    // Static Fields
    // ~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~

    private static final String CSV_REGEX = ",(?=([^\"]*\"[^\"]*\")*[^\"]*$)";

    private static final String TAG = "RootFileObserver";

    // ~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~
    // Static Initializers
    // ~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~

    // ~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~
    // Static Methods
    // ~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~

    // parse stdout
    private static int parseMask(String value) {
        int event = 0;

        final String[] events;
        if (value.contains(",")) {
            // multiple events
            events = value.replaceAll("\"", "").split(",");
        } else {
            events = new String[] {
                value
            };
        }

        for (String name : events) {
            switch (name.toUpperCase(Locale.US)) {
            case "ACCESS":
                event |= ACCESS;
                break;
            case "MODIFY":
                event |= MODIFY;
                break;
            case "ATTRIB":
                event |= ATTRIB;
                break;
            case "CLOSE_WRITE":
                event |= CLOSE_WRITE;
                break;
            case "CLOSE_NOWRITE":
                event |= CLOSE_NOWRITE;
                break;
            case "OPEN":
                event |= OPEN;
                break;
            case "MOVED_FROM":
                event |= MOVED_FROM;
                break;
            case "MOVED_TO":
                event |= MOVED_TO;
                break;
            case "CREATE":
                event |= CREATE;
                break;
            case "DELETE":
                event |= DELETE;
                break;
            case "DELETE_SELF":
                event |= DELETE_SELF;
                break;
            }
        }

        return event;
    }

    // ~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~
    // Fields
    // ~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~

    private final Shell.OnCommandLineListener mOnCommandLineListener;

    private Shell.Interactive mShell;

    private final Context mContext;

    private final Handler mHandler;

    private final String mPath;

    private File mINotifyWait;

    private boolean mRunInShell;

    private boolean mIsWatching;

    // ~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~
    // Initializers
    // ~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~

    {
        mOnCommandLineListener = new Shell.OnCommandLineListener() {

            @Override
            public void onCommandResult(int commandCode, int exitCode) {
            }

            @Override
            public void onLine(String line) {
                try {
                    final ParsedEvent event = new ParsedEvent(line);
                    onEvent(event.event, event.filename);
                } catch (Exception e) {
                    Lg.d(TAG, "Failed parsing output from inotifywait. line: %s", line);
                }
            }
        };
    }

    // ~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~
    // Constructors
    // ~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~

    public RootFileObserver(Context context, Handler handler, String path) {
        this(context, handler, path, ALL_EVENTS);
    }

    public RootFileObserver(Context context, Handler handler, String path, int mask) {
        super(path, mask);
        mContext = context.getApplicationContext();
        mHandler = handler;
        mPath = path;
        mRunInShell = !new File(path).canRead() && RootChecker.hasRootAccess();
        // TODO: add mask when running as root or filter results.
    }

    // ~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~
    // Methods
    // ~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~

    public boolean isWatching() {
        return mIsWatching;
    }

    public abstract void onEvent(int event, AFile file);

    @Override
    public void onEvent(final int event, final String path) {
        mHandler.post(new Runnable() {

            @Override
            public void run() {
                if (path == null || mPath == null) {
                    return;
                }
                onEvent(event, new AFile(mPath, path));
            }
        });
    }

    /**
     * Start watching for events. The monitored file or directory must exist at this time, or else
     * no events will be reported (even if it appears later). If monitoring is already started, this
     * call has no effect.
     */
    @Override
    public void startWatching() {
        mIsWatching = true;
        if (mRunInShell) {
            new ShellObserverThread().start();
        } else {
            super.startWatching();
        }
    }

    /**
     * Stop watching for events. Some events may be in process, so events may continue to be
     * reported even after this method completes. If monitoring is already stopped, this call has no
     * effect.
     */
    @Override
    public void stopWatching() {
        mIsWatching = false;
        if (mRunInShell) {
            if (mShell != null) {
                mShell.kill();
            }
        } else {
            super.stopWatching();
        }
    }

    private void transferAsset() {
        final String folder;
        switch (Architecture.get()) {
        case ARM_EABI:
            folder = "armeabi";
            break;
        case INTEL_X86:
            folder = "x86";
            break;
        case MIPS:
            folder = "mips";
            break;
        case ARM_EABI_V7A:
        default:
            folder = "armeabi-v7a";
            break;
        }
        final String filename = "inotifywait/" + folder + "/inotifywait";
        mINotifyWait = new AFile(mContext.getFilesDir(), filename);
        if (!mINotifyWait.exists()) {
            AssetUtils.transferAsset(mContext, filename, filename, 0755);
        }
    }

    // ~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~
    // Types
    // ~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~-~

    static class ParsedEvent {

        public final String dirPath;

        public final int event;

        public final String filename;

        public final String value;

        ParsedEvent(String line) {
            Confirm.notNull(line);
            String[] values = line.split(CSV_REGEX);
            Confirm.isLength(values, 3);
            dirPath = values[0];
            value = values[1];
            filename = values[2];
            event = parseMask(value);
        }

        public AFile getFile() {
            return new AFile(dirPath, filename);
        }
    }

    class ShellObserverThread extends Thread {

        public ShellObserverThread() {
            super("ShellObserverThread");
        }

        @Override
        public void run() {
            transferAsset();

            String command = String.format("%s -q -m -c \"%s\"", mINotifyWait.getAbsolutePath(),
                mPath);

            if (mShell == null) {
                mShell = new Shell.Builder().setShell("su")
                        .setOnSTDOUTLineListener(mOnCommandLineListener).addCommand(command).open();
            } else {
                mShell.addCommand(command);
            }

            mShell.waitForIdle();
        }
    }

}
