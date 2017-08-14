/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.systemui.usb;

import android.annotation.NonNull;
import android.app.Notification;
import android.app.Notification.Action;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.MoveCallback;
import android.content.res.Resources;
import android.os.Bundle;
import android.os.Handler;
import android.os.UserHandle;
import android.os.storage.DiskInfo;
import android.os.storage.StorageEventListener;
import android.os.storage.StorageManager;
import android.os.storage.VolumeInfo;
import android.os.storage.VolumeRecord;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.util.Log;
import android.util.SparseArray;

import com.android.systemui.SystemUI;

import java.util.List;

public class StorageNotification extends SystemUI {
    private static final String TAG = "StorageNotification";

    private static final int PUBLIC_ID = 0x53505542; // SPUB
    private static final int PRIVATE_ID = 0x53505256; // SPRV
    private static final int DISK_ID = 0x5344534b; // SDSK
    private static final int MOVE_ID = 0x534d4f56; // SMOV

    private static final String ACTION_SNOOZE_VOLUME = "com.android.systemui.action.SNOOZE_VOLUME";
    private static final String ACTION_FINISH_WIZARD = "com.android.systemui.action.FINISH_WIZARD";

    // TODO: delay some notifications to avoid bumpy fast operations

    private NotificationManager mNotificationManager;
    private StorageManager mStorageManager;

    private static class MoveInfo {
        public int moveId;
        public Bundle extras;
        public String packageName;
        public String label;
        public String volumeUuid;
    }

    private final SparseArray<MoveInfo> mMoves = new SparseArray<>();

    private final StorageEventListener mListener = new StorageEventListener() {
        @Override
        public void onVolumeStateChanged(VolumeInfo vol, int oldState, int newState) {
            onVolumeStateChangedInternal(vol);
        }

        @Override
        public void onVolumeRecordChanged(VolumeRecord rec) {
            // Avoid kicking notifications when getting early metadata before
            // mounted. If already mounted, we're being kicked because of a
            // nickname or init'ed change.
            final VolumeInfo vol = mStorageManager.findVolumeByUuid(rec.getFsUuid());
            if (vol != null && vol.isMountedReadable()) {
                onVolumeStateChangedInternal(vol);
            }
        }

        @Override
        public void onVolumeForgotten(String fsUuid) {
            // Stop annoying the user
            mNotificationManager.cancelAsUser(fsUuid, PRIVATE_ID, UserHandle.ALL);
        }

        @Override
        public void onDiskScanned(DiskInfo disk, int volumeCount) {
            onDiskScannedInternal(disk, volumeCount);
        }

        @Override
        public void onDiskDestroyed(DiskInfo disk) {
            onDiskDestroyedInternal(disk);
        }
    };

    private final BroadcastReceiver mSnoozeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // TODO: kick this onto background thread
            final String fsUuid = intent.getStringExtra(VolumeRecord.EXTRA_FS_UUID);
            mStorageManager.setVolumeSnoozed(fsUuid, true);
        }
    };

    private final BroadcastReceiver mFinishReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // When finishing the adoption wizard, clean up any notifications
            // for moving primary storage
            mNotificationManager.cancelAsUser(null, MOVE_ID, UserHandle.ALL);
        }
    };

    private final MoveCallback mMoveCallback = new MoveCallback() {
        @Override
        public void onCreated(int moveId, Bundle extras) {
            final MoveInfo move = new MoveInfo();
            move.moveId = moveId;
            move.extras = extras;
            if (extras != null) {
                move.packageName = extras.getString(Intent.EXTRA_PACKAGE_NAME);
                move.label = extras.getString(Intent.EXTRA_TITLE);
                move.volumeUuid = extras.getString(VolumeRecord.EXTRA_FS_UUID);
            }
            mMoves.put(moveId, move);
        }

        @Override
        public void onStatusChanged(int moveId, int status, long estMillis) {
            final MoveInfo move = mMoves.get(moveId);
            if (move == null) {
                Log.w(TAG, "Ignoring unknown move " + moveId);
                return;
            }

            if (PackageManager.isMoveStatusFinished(status)) {
                onMoveFinished(move, status);
            } else {
                /* SPRD: add for primary storage @{ */
                final VolumeInfo vol = mStorageManager.findVolumeByQualifiedUuid(move.volumeUuid);
                if (vol == null) {
                    return;
                }
                /* @} */
                onMoveProgress(move, status, estMillis);
            }
        }
    };

    @Override
    public void start() {
        mNotificationManager = mContext.getSystemService(NotificationManager.class);

        mStorageManager = mContext.getSystemService(StorageManager.class);
        mStorageManager.registerListener(mListener);

        mContext.registerReceiver(mSnoozeReceiver, new IntentFilter(ACTION_SNOOZE_VOLUME),
                android.Manifest.permission.MOUNT_UNMOUNT_FILESYSTEMS, null);
        mContext.registerReceiver(mFinishReceiver, new IntentFilter(ACTION_FINISH_WIZARD),
                android.Manifest.permission.MOUNT_UNMOUNT_FILESYSTEMS, null);

        // Kick current state into place
        final List<DiskInfo> disks = mStorageManager.getDisks();
        for (DiskInfo disk : disks) {
            onDiskScannedInternal(disk, disk.volumeCount);
        }

        final List<VolumeInfo> vols = mStorageManager.getVolumes();
        for (VolumeInfo vol : vols) {
            onVolumeStateChangedInternal(vol);
        }

        mContext.getPackageManager().registerMoveCallback(mMoveCallback, new Handler());

        updateMissingPrivateVolumes();
    }

    private void updateMissingPrivateVolumes() {
        final List<VolumeRecord> recs = mStorageManager.getVolumeRecords();
        for (VolumeRecord rec : recs) {
            if (rec.getType() != VolumeInfo.TYPE_PRIVATE) continue;

            final String fsUuid = rec.getFsUuid();
            final VolumeInfo info = mStorageManager.findVolumeByUuid(fsUuid);
            if ((info != null && info.isMountedWritable()) || rec.isSnoozed()) {
                // Yay, private volume is here, or user snoozed
                mNotificationManager.cancelAsUser(fsUuid, PRIVATE_ID, UserHandle.ALL);

            } else {
                // Boo, annoy the user to reinsert the private volume
                final CharSequence title = mContext.getString(Resources.getSystem().getIdentifier("ext_media_missing_title", "string","android"),
                        rec.getNickname());
                final CharSequence text = mContext.getString(Resources.getSystem().getIdentifier("ext_media_missing_message", "string","android"));

                Notification.Builder builder = new Notification.Builder(mContext)
                        .setSmallIcon(Resources.getSystem().getIdentifier("ic_sd_card_48dp", "drawable","android"))
                        .setColor(mContext.getColor(Resources.getSystem().getIdentifier("system_notification_accent_color", "color","android")))
                        .setContentTitle(title)
                        .setContentText(text)
                        .setContentIntent(buildForgetPendingIntent(rec))
                        .setStyle(new Notification.BigTextStyle().bigText(text))
                        .setVisibility(Notification.VISIBILITY_PUBLIC)
                        .setLocalOnly(true)
                        .setCategory(Notification.CATEGORY_SYSTEM)
                        .setDeleteIntent(buildSnoozeIntent(fsUuid));
                SystemUI.overrideNotificationAppName(mContext, builder);

                mNotificationManager.notifyAsUser(fsUuid, PRIVATE_ID, builder
                        .build(), UserHandle.ALL);
            }
        }
    }

    private void onDiskScannedInternal(DiskInfo disk, int volumeCount) {
        if (volumeCount == 0 && disk.size > 0) {
            // No supported volumes found, give user option to format
            final CharSequence title = mContext.getString(
                    Resources.getSystem().getIdentifier("ext_media_unsupported_notification_title", "string","android"), disk.getDescription());
            final CharSequence text = mContext.getString(
                    Resources.getSystem().getIdentifier("ext_media_unsupported_notification_message", "string","android"), disk.getDescription());

            Notification.Builder builder = new Notification.Builder(mContext)
                    .setSmallIcon(getSmallIcon(disk, VolumeInfo.STATE_UNMOUNTABLE))
                    .setColor(mContext.getColor(Resources.getSystem().getIdentifier("system_notification_accent_color", "color","android")))
                    .setContentTitle(title)
                    .setContentText(text)
                    .setContentIntent(buildInitPendingIntent(disk))
                    .setStyle(new Notification.BigTextStyle().bigText(text))
                    .setVisibility(Notification.VISIBILITY_PUBLIC)
                    .setLocalOnly(true)
                    .setCategory(Notification.CATEGORY_ERROR);
            SystemUI.overrideNotificationAppName(mContext, builder);

            mNotificationManager.notifyAsUser(disk.getId(), DISK_ID, builder.build(),
                    UserHandle.ALL);

        } else {
            // Yay, we have volumes!
            mNotificationManager.cancelAsUser(disk.getId(), DISK_ID, UserHandle.ALL);
        }
    }

    /**
     * Remove all notifications for a disk when it goes away.
     *
     * @param disk The disk that went away.
     */
    private void onDiskDestroyedInternal(@NonNull DiskInfo disk) {
        mNotificationManager.cancelAsUser(disk.getId(), DISK_ID, UserHandle.ALL);
    }

    private void onVolumeStateChangedInternal(VolumeInfo vol) {
        switch (vol.getType()) {
            case VolumeInfo.TYPE_PRIVATE:
                onPrivateVolumeStateChangedInternal(vol);
                break;
            case VolumeInfo.TYPE_PUBLIC:
                onPublicVolumeStateChangedInternal(vol);
                break;
        }
    }

    private void onPrivateVolumeStateChangedInternal(VolumeInfo vol) {
        Log.d(TAG, "Notifying about private volume: " + vol.toString());

        updateMissingPrivateVolumes();
    }

    private void onPublicVolumeStateChangedInternal(VolumeInfo vol) {
        Log.d(TAG, "Notifying about public volume: " + vol.toString());

        final Notification notif;
        switch (vol.getState()) {
            case VolumeInfo.STATE_UNMOUNTED:
                notif = onVolumeUnmounted(vol);
                break;
            case VolumeInfo.STATE_CHECKING:
                notif = onVolumeChecking(vol);
                break;
            case VolumeInfo.STATE_MOUNTED:
            case VolumeInfo.STATE_MOUNTED_READ_ONLY:
                notif = onVolumeMounted(vol);
                break;
            case VolumeInfo.STATE_FORMATTING:
                notif = onVolumeFormatting(vol);
                break;
            case VolumeInfo.STATE_EJECTING:
                notif = onVolumeEjecting(vol);
                break;
            case VolumeInfo.STATE_UNMOUNTABLE:
                notif = onVolumeUnmountable(vol);
                break;
            case VolumeInfo.STATE_REMOVED:
                notif = onVolumeRemoved(vol);
                break;
            case VolumeInfo.STATE_BAD_REMOVAL:
                notif = onVolumeBadRemoval(vol);
                break;
            default:
                notif = null;
                break;
        }

        if (notif != null) {
            mNotificationManager.notifyAsUser(vol.getId(), PUBLIC_ID, notif, UserHandle.ALL);
        } else {
            mNotificationManager.cancelAsUser(vol.getId(), PUBLIC_ID, UserHandle.ALL);
        }
    }

    private Notification onVolumeUnmounted(VolumeInfo vol) {
        // Ignored
        return null;
    }

    private Notification onVolumeChecking(VolumeInfo vol) {
        final DiskInfo disk = vol.getDisk();
        final CharSequence title = mContext.getString(
                Resources.getSystem().getIdentifier("ext_media_checking_notification_title", "string","android"), disk.getDescription());
        final CharSequence text = mContext.getString(
                Resources.getSystem().getIdentifier("ext_media_checking_notification_message", "string","android"), disk.getDescription());

        return buildNotificationBuilder(vol, title, text)
                .setCategory(Notification.CATEGORY_PROGRESS)
                .setPriority(Notification.PRIORITY_LOW)
                .setOngoing(true)
                .build();
    }

    private Notification onVolumeMounted(VolumeInfo vol) {
        final VolumeRecord rec = mStorageManager.findRecordByUuid(vol.getFsUuid());
        final DiskInfo disk = vol.getDisk();

        // Don't annoy when user dismissed in past.  (But make sure the disk is adoptable; we
        // used to allow snoozing non-adoptable disks too.)
        if (rec.isSnoozed() && disk.isAdoptable()) {
            return null;
        }

        if (disk.isAdoptable() && !rec.isInited()) {
            final CharSequence title = disk.getDescription();
            final CharSequence text = mContext.getString(
                    Resources.getSystem().getIdentifier("ext_media_new_notification_message", "string","android"), disk.getDescription());

            final PendingIntent initIntent = buildInitPendingIntent(vol);
            return buildNotificationBuilder(vol, title, text)
                    .addAction(new Action(Resources.getSystem().getIdentifier("ic_settings_24dp", "drawable","android"),
                            mContext.getString(Resources.getSystem().getIdentifier("ext_media_init_action", "string","android")), initIntent))
                    .addAction(new Action(Resources.getSystem().getIdentifier("ic_eject_24dp", "drawable","android"),
                            mContext.getString(Resources.getSystem().getIdentifier("ext_media_unmount_action", "string","android")),
                            buildUnmountPendingIntent(vol)))
                    .setContentIntent(initIntent)
                    .setDeleteIntent(buildSnoozeIntent(vol.getFsUuid()))
                    .setCategory(Notification.CATEGORY_SYSTEM)
                    .build();

        } else {
            final CharSequence title = disk.getDescription();
            final CharSequence text = mContext.getString(
                    Resources.getSystem().getIdentifier("ext_media_ready_notification_message", "string","android"), disk.getDescription());

            final PendingIntent browseIntent = buildBrowsePendingIntent(vol);
            final Notification.Builder builder = buildNotificationBuilder(vol, title, text)
                    .addAction(new Action(Resources.getSystem().getIdentifier("ic_folder_24dp", "drawable","android"),
                            mContext.getString(Resources.getSystem().getIdentifier("ext_media_browse_action", "string","android")),
                            browseIntent))
                    .addAction(new Action(Resources.getSystem().getIdentifier("ic_eject_24dp", "drawable","android"),
                            mContext.getString(Resources.getSystem().getIdentifier("ext_media_unmount_action", "string","android")),
                            buildUnmountPendingIntent(vol)))
                    .setContentIntent(browseIntent)
                    .setCategory(Notification.CATEGORY_SYSTEM)
                    .setPriority(Notification.PRIORITY_LOW);
            // Non-adoptable disks can't be snoozed.
            if (disk.isAdoptable()) {
                builder.setDeleteIntent(buildSnoozeIntent(vol.getFsUuid()));
            }

            return builder.build();
        }
    }

    private Notification onVolumeFormatting(VolumeInfo vol) {
        // Ignored
        return null;
    }

    private Notification onVolumeEjecting(VolumeInfo vol) {
        final DiskInfo disk = vol.getDisk();
        final CharSequence title = mContext.getString(
                Resources.getSystem().getIdentifier("ext_media_unmounting_notification_title", "string","android"), disk.getDescription());
        final CharSequence text = mContext.getString(
                Resources.getSystem().getIdentifier("ext_media_unmounting_notification_message", "string","android"), disk.getDescription());

        return buildNotificationBuilder(vol, title, text)
                .setCategory(Notification.CATEGORY_PROGRESS)
                .setPriority(Notification.PRIORITY_LOW)
                .setOngoing(true)
                .build();
    }

    private Notification onVolumeUnmountable(VolumeInfo vol) {
        final DiskInfo disk = vol.getDisk();
        final CharSequence title = mContext.getString(
                Resources.getSystem().getIdentifier("ext_media_unmountable_notification_title", "string","android"), disk.getDescription());
        final CharSequence text = mContext.getString(
                Resources.getSystem().getIdentifier("ext_media_unmountable_notification_message", "string","android"), disk.getDescription());

        return buildNotificationBuilder(vol, title, text)
                .setContentIntent(buildInitPendingIntent(vol))
                .setCategory(Notification.CATEGORY_ERROR)
                .build();
    }

    private Notification onVolumeRemoved(VolumeInfo vol) {
        if (!vol.isPrimary()) {
            // Ignore non-primary media
            return null;
        }

        final DiskInfo disk = vol.getDisk();
        final CharSequence title = mContext.getString(
                Resources.getSystem().getIdentifier("ext_media_nomedia_notification_title", "string","android"), disk.getDescription());
        final CharSequence text = mContext.getString(
                Resources.getSystem().getIdentifier("ext_media_nomedia_notification_message", "string","android"), disk.getDescription());

        return buildNotificationBuilder(vol, title, text)
                .setCategory(Notification.CATEGORY_ERROR)
                .build();
    }

    private Notification onVolumeBadRemoval(VolumeInfo vol) {
        if (!vol.isPrimary()) {
            // Ignore non-primary media
            return null;
        }

        final DiskInfo disk = vol.getDisk();
        final CharSequence title = mContext.getString(
                Resources.getSystem().getIdentifier("ext_media_badremoval_notification_title", "string","android"), disk.getDescription());
        final CharSequence text = mContext.getString(
                Resources.getSystem().getIdentifier("ext_media_badremoval_notification_message", "string","android"), disk.getDescription());

        return buildNotificationBuilder(vol, title, text)
                .setCategory(Notification.CATEGORY_ERROR)
                .build();
    }

    private void onMoveProgress(MoveInfo move, int status, long estMillis) {
        final CharSequence title;
        if (!TextUtils.isEmpty(move.label)) {
            title = mContext.getString(Resources.getSystem().getIdentifier("ext_media_move_specific_title", "string","android"), move.label);
        } else {
            title = mContext.getString(Resources.getSystem().getIdentifier("ext_media_move_title", "string","android"));
        }

        final CharSequence text;
        if (estMillis < 0) {
            text = null;
        } else {
            text = DateUtils.formatDuration(estMillis);
        }

        final PendingIntent intent;
        if (move.packageName != null) {
            intent = buildWizardMovePendingIntent(move);
        } else {
            intent = buildWizardMigratePendingIntent(move);
        }

        Notification.Builder builder = new Notification.Builder(mContext)
                .setSmallIcon(Resources.getSystem().getIdentifier("ic_sd_card_48dp", "drawable","android"))
                .setColor(mContext.getColor(Resources.getSystem().getIdentifier("system_notification_accent_color", "color","android")))
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(intent)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setLocalOnly(true)
                .setCategory(Notification.CATEGORY_PROGRESS)
                .setPriority(Notification.PRIORITY_LOW)
                .setProgress(100, status, false)
                .setOngoing(true);
        SystemUI.overrideNotificationAppName(mContext, builder);

        mNotificationManager.notifyAsUser(move.packageName, MOVE_ID,
                builder.build(), UserHandle.ALL);
    }

    private void onMoveFinished(MoveInfo move, int status) {
        if (move.packageName != null) {
            // We currently ignore finished app moves; just clear the last
            // published progress
            mNotificationManager.cancelAsUser(move.packageName, MOVE_ID, UserHandle.ALL);
            return;
        }

        final VolumeInfo privateVol = mContext.getPackageManager().getPrimaryStorageCurrentVolume();
        final String descrip = mStorageManager.getBestVolumeDescription(privateVol);

        final CharSequence title;
        final CharSequence text;
        if (status == PackageManager.MOVE_SUCCEEDED) {
            title = mContext.getString(Resources.getSystem().getIdentifier("ext_media_move_success_title", "string","android"));
            text = mContext.getString(Resources.getSystem().getIdentifier("ext_media_move_success_message", "string","android"), descrip);
        } else {
            title = mContext.getString(Resources.getSystem().getIdentifier("ext_media_move_failure_title", "string","android"));
            text = mContext.getString(Resources.getSystem().getIdentifier("ext_media_move_failure_message", "string","android"));
        }

        // Jump back into the wizard flow if we moved to a real disk
        final PendingIntent intent;
        if (privateVol != null && privateVol.getDisk() != null) {
            intent = buildWizardReadyPendingIntent(privateVol.getDisk());
        } else if (privateVol != null) {
            intent = buildVolumeSettingsPendingIntent(privateVol);
        } else {
            intent = null;
        }

        Notification.Builder builder = new Notification.Builder(mContext)
                .setSmallIcon(Resources.getSystem().getIdentifier("ic_sd_card_48dp", "drawable","android"))
                .setColor(mContext.getColor(Resources.getSystem().getIdentifier("system_notification_accent_color", "color","android")))
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(intent)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setLocalOnly(true)
                .setCategory(Notification.CATEGORY_SYSTEM)
                .setPriority(Notification.PRIORITY_LOW)
                .setAutoCancel(true);
        SystemUI.overrideNotificationAppName(mContext, builder);

        mNotificationManager.notifyAsUser(move.packageName, MOVE_ID, builder.build(),
                UserHandle.ALL);
    }

    private int getSmallIcon(DiskInfo disk, int state) {
        if (disk.isSd()) {
            switch (state) {
                case VolumeInfo.STATE_CHECKING:
                case VolumeInfo.STATE_EJECTING:
                    return Resources.getSystem().getIdentifier("ic_sd_card_48dp", "drawable","android");
                default:
                    return Resources.getSystem().getIdentifier("ic_sd_card_48dp", "drawable","android");
            }
        } else if (disk.isUsb()) {
            return Resources.getSystem().getIdentifier("ic_usb_48dp", "drawable","android");
        } else {
            return Resources.getSystem().getIdentifier("ic_sd_card_48dp", "drawable","android");
        }
    }

    private Notification.Builder buildNotificationBuilder(VolumeInfo vol, CharSequence title,
            CharSequence text) {
        Notification.Builder builder = new Notification.Builder(mContext)
                .setSmallIcon(getSmallIcon(vol.getDisk(), vol.getState()))
                .setColor(mContext.getColor(Resources.getSystem().getIdentifier("system_notification_accent_color", "color","android")))
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setLocalOnly(true);
        overrideNotificationAppName(mContext, builder);
        return builder;
    }

    private PendingIntent buildInitPendingIntent(DiskInfo disk) {
        final Intent intent = new Intent();
        intent.setClassName("com.android.settings",
                "com.android.settings.deviceinfo.StorageWizardInit");
        intent.putExtra(DiskInfo.EXTRA_DISK_ID, disk.getId());

        final int requestKey = disk.getId().hashCode();
        return PendingIntent.getActivityAsUser(mContext, requestKey, intent,
                PendingIntent.FLAG_CANCEL_CURRENT, null, UserHandle.CURRENT);
    }

    private PendingIntent buildInitPendingIntent(VolumeInfo vol) {
        final Intent intent = new Intent();
        intent.setClassName("com.android.settings",
                "com.android.settings.deviceinfo.StorageWizardInit");
        intent.putExtra(VolumeInfo.EXTRA_VOLUME_ID, vol.getId());

        final int requestKey = vol.getId().hashCode();
        return PendingIntent.getActivityAsUser(mContext, requestKey, intent,
                PendingIntent.FLAG_CANCEL_CURRENT, null, UserHandle.CURRENT);
    }

    private PendingIntent buildUnmountPendingIntent(VolumeInfo vol) {
        final Intent intent = new Intent();
        intent.setClassName("com.android.settings",
                "com.android.settings.deviceinfo.StorageUnmountReceiver");
        intent.putExtra(VolumeInfo.EXTRA_VOLUME_ID, vol.getId());

        final int requestKey = vol.getId().hashCode();
        return PendingIntent.getBroadcastAsUser(mContext, requestKey, intent,
                PendingIntent.FLAG_CANCEL_CURRENT, UserHandle.CURRENT);
    }

    private PendingIntent buildBrowsePendingIntent(VolumeInfo vol) {
        Intent intent = new Intent();
        final int requestKey = vol.getId().hashCode();
        intent.setClass(mContext,BrowseReceiver.class);
        intent.putExtra("intent",vol.buildBrowseIntent());
        intent.putExtra("volumeinfo",vol);
        return PendingIntent.getBroadcastAsUser(mContext, requestKey, intent,
                PendingIntent.FLAG_CANCEL_CURRENT, UserHandle.CURRENT);

//        Intent intent = vol.buildBrowseIntent();
//        final int requestKey = vol.getId().hashCode();
//        return PendingIntent.getActivity(mContext, requestKey, intent,
//                PendingIntent.FLAG_CANCEL_CURRENT, null);
    }

    private PendingIntent buildVolumeSettingsPendingIntent(VolumeInfo vol) {
        final Intent intent = new Intent();
        switch (vol.getType()) {
            case VolumeInfo.TYPE_PRIVATE:
                intent.setClassName("com.android.settings",
                        "com.android.settings.Settings$PrivateVolumeSettingsActivity");
                break;
            case VolumeInfo.TYPE_PUBLIC:
                intent.setClassName("com.android.settings",
                        "com.android.settings.Settings$PublicVolumeSettingsActivity");
                break;
            default:
                return null;
        }
        intent.putExtra(VolumeInfo.EXTRA_VOLUME_ID, vol.getId());

        final int requestKey = vol.getId().hashCode();
        return PendingIntent.getActivityAsUser(mContext, requestKey, intent,
                PendingIntent.FLAG_CANCEL_CURRENT, null, UserHandle.CURRENT);
    }

    private PendingIntent buildSnoozeIntent(String fsUuid) {
        final Intent intent = new Intent(ACTION_SNOOZE_VOLUME);
        intent.putExtra(VolumeRecord.EXTRA_FS_UUID, fsUuid);

        final int requestKey = fsUuid.hashCode();
        return PendingIntent.getBroadcastAsUser(mContext, requestKey, intent,
                PendingIntent.FLAG_CANCEL_CURRENT, UserHandle.CURRENT);
    }

    private PendingIntent buildForgetPendingIntent(VolumeRecord rec) {
        final Intent intent = new Intent();
        intent.setClassName("com.android.settings",
                "com.android.settings.Settings$PrivateVolumeForgetActivity");
        intent.putExtra(VolumeRecord.EXTRA_FS_UUID, rec.getFsUuid());

        final int requestKey = rec.getFsUuid().hashCode();
        return PendingIntent.getActivityAsUser(mContext, requestKey, intent,
                PendingIntent.FLAG_CANCEL_CURRENT, null, UserHandle.CURRENT);
    }

    private PendingIntent buildWizardMigratePendingIntent(MoveInfo move) {
        final Intent intent = new Intent();
        intent.setClassName("com.android.settings",
                "com.android.settings.deviceinfo.StorageWizardMigrateProgress");
        intent.putExtra(PackageManager.EXTRA_MOVE_ID, move.moveId);

        final VolumeInfo vol = mStorageManager.findVolumeByQualifiedUuid(move.volumeUuid);
        if (vol != null) {
            intent.putExtra(VolumeInfo.EXTRA_VOLUME_ID, vol.getId());
        }
        return PendingIntent.getActivityAsUser(mContext, move.moveId, intent,
                PendingIntent.FLAG_CANCEL_CURRENT, null, UserHandle.CURRENT);
    }

    private PendingIntent buildWizardMovePendingIntent(MoveInfo move) {
        final Intent intent = new Intent();
        intent.setClassName("com.android.settings",
                "com.android.settings.deviceinfo.StorageWizardMoveProgress");
        intent.putExtra(PackageManager.EXTRA_MOVE_ID, move.moveId);

        return PendingIntent.getActivityAsUser(mContext, move.moveId, intent,
                PendingIntent.FLAG_CANCEL_CURRENT, null, UserHandle.CURRENT);
    }

    private PendingIntent buildWizardReadyPendingIntent(DiskInfo disk) {
        final Intent intent = new Intent();
        intent.setClassName("com.android.settings",
                "com.android.settings.deviceinfo.StorageWizardReady");
        intent.putExtra(DiskInfo.EXTRA_DISK_ID, disk.getId());

        final int requestKey = disk.getId().hashCode();
        return PendingIntent.getActivityAsUser(mContext, requestKey, intent,
                PendingIntent.FLAG_CANCEL_CURRENT, null, UserHandle.CURRENT);
    }
}