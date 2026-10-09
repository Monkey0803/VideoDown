package com.videodown.douyin;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import java.util.Arrays;

/** Test fixture control only; grants no access to documents or user storage. */
public final class QuotaExportControlProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }
    @Override public Bundle call(String method, String arg, Bundle extras) {
        final int targetUid;
        try { targetUid = getContext().getPackageManager().getApplicationInfo("com.videodown.douyin.debug", 0).uid; }
        catch (android.content.pm.PackageManager.NameNotFoundException e) { throw new SecurityException("Target missing", e); }
        if (Binder.getCallingUid() != targetUid) throw new SecurityException("Only target test UID may configure fixture");
        if (!Arrays.asList("quota-reset", "quota-unlimited", "quota-stats", "quota-revoke").contains(method))
            throw new IllegalArgumentException("Unknown fixture command");
        long token = Binder.clearCallingIdentity();
        try { return getContext().getContentResolver().call(Uri.parse("content://com.videodown.douyin.test.quotaexport"), method, null, null); }
        finally { Binder.restoreCallingIdentity(token); }
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) { throw new UnsupportedOperationException(); }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
