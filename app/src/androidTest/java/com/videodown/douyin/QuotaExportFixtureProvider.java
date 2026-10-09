package com.videodown.douyin;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.ParcelFileDescriptor;
import android.os.ProxyFileDescriptorCallback;
import android.os.storage.StorageManager;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsProvider;
import android.system.ErrnoException;
import android.system.OsConstants;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;

/** Test-only provider: real OS proxy FD returns ENOSPC without filling device storage. */
public final class QuotaExportFixtureProvider extends DocumentsProvider {
    private volatile long quota = 4096;
    private volatile long bytesWritten;
    private volatile int enospcCount;
    private HandlerThread writer;
    private final String[] columns = {Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME,
        Document.COLUMN_MIME_TYPE, Document.COLUMN_FLAGS, Document.COLUMN_SIZE};
    private File directory() { File dir = new File(getContext().getFilesDir(), "quota-export"); dir.mkdirs(); return dir; }
    private File path(String id) {
        if ("root".equals(id)) return directory();
        if (id.isEmpty() || id.contains("/") || id.contains("\\") || id.equals(".") || id.equals(".."))
            throw new IllegalArgumentException("Invalid document id");
        return new File(directory(), id);
    }
    @Override public boolean onCreate() {
        writer = new HandlerThread("test-export-quota"); writer.start(); return true;
    }
    @Override public Cursor queryRoots(String[] projection) {
        MatrixCursor result = new MatrixCursor(projection != null ? projection : new String[] {
            Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_FLAGS,
            Root.COLUMN_MIME_TYPES, Root.COLUMN_AVAILABLE_BYTES});
        result.newRow().add(Root.COLUMN_ROOT_ID, "root").add(Root.COLUMN_DOCUMENT_ID, "root")
            .add(Root.COLUMN_TITLE, "P2 空间配额测试").add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_CREATE | Root.FLAG_LOCAL_ONLY | Root.FLAG_SUPPORTS_IS_CHILD)
            .add(Root.COLUMN_MIME_TYPES, "*/*").add(Root.COLUMN_AVAILABLE_BYTES, Math.min(quota, directory().getUsableSpace()));
        return result;
    }
    private void row(MatrixCursor cursor, File file) {
        boolean root = file.equals(directory());
        cursor.newRow().add(Document.COLUMN_DOCUMENT_ID, root ? "root" : file.getName())
            .add(Document.COLUMN_DISPLAY_NAME, root ? "P2 空间配额测试" : file.getName())
            .add(Document.COLUMN_MIME_TYPE, root ? Document.MIME_TYPE_DIR : "video/mp4")
            .add(Document.COLUMN_FLAGS, root ? Document.FLAG_DIR_SUPPORTS_CREATE : Document.FLAG_SUPPORTS_WRITE | Document.FLAG_SUPPORTS_DELETE)
            .add(Document.COLUMN_SIZE, file.length());
    }
    @Override public Cursor queryDocument(String id, String[] projection) {
        MatrixCursor result = new MatrixCursor(projection != null ? projection : columns);
        File file = path(id); if (file.exists()) row(result, file); return result;
    }
    @Override public Cursor queryChildDocuments(String id, String[] projection, String sortOrder) {
        MatrixCursor result = new MatrixCursor(projection != null ? projection : columns);
        File[] files = path(id).listFiles(); if (files != null) for (File file : files) row(result, file); return result;
    }
    @Override public String createDocument(String parent, String mime, String name) throws FileNotFoundException {
        if (!"root".equals(parent)) throw new FileNotFoundException("Invalid parent");
        try { if (!path(name).createNewFile()) throw new FileNotFoundException("Collision"); }
        catch (IOException e) { throw new FileNotFoundException(e.getMessage()); }
        return name;
    }
    @Override public void deleteDocument(String id) throws FileNotFoundException {
        if ("root".equals(id) || !path(id).delete()) throw new FileNotFoundException("Cannot delete document");
    }
    @Override public boolean isChildDocument(String parent, String id) {
        return "root".equals(parent) && !"root".equals(id) && path(id).isFile();
    }
    @Override public ParcelFileDescriptor openDocument(String id, String mode, CancellationSignal signal) throws FileNotFoundException {
        File file = path(id);
        if (!mode.contains("w")) return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
        final RandomAccessFile output;
        try { output = new RandomAccessFile(file, "rw"); output.setLength(0); }
        catch (IOException e) { throw new FileNotFoundException(e.getMessage()); }
        try {
            return getContext().getSystemService(StorageManager.class).openProxyFileDescriptor(
                ParcelFileDescriptor.MODE_WRITE_ONLY, new ProxyFileDescriptorCallback() {
                    @Override public long onGetSize() throws ErrnoException {
                        try { return output.length(); } catch (IOException e) { throw new ErrnoException("size", OsConstants.EIO, e); }
                    }
                    @Override public int onWrite(long offset, int size, byte[] data) throws ErrnoException {
                        if (offset >= quota) { enospcCount++; throw new ErrnoException("test quota write", OsConstants.ENOSPC); }
                        int accepted = (int) Math.min((long) size, quota - offset);
                        try { output.seek(offset); output.write(data, 0, accepted); bytesWritten += accepted; return accepted; }
                        catch (IOException e) { throw new ErrnoException("write", OsConstants.EIO, e); }
                    }
                    @Override public void onFsync() throws ErrnoException {
                        try { output.getFD().sync(); } catch (IOException e) { throw new ErrnoException("sync", OsConstants.EIO, e); }
                    }
                    @Override public void onRelease() { try { output.close(); } catch (IOException ignored) { } }
                }, new Handler(writer.getLooper()));
        } catch (IOException e) {
            try { output.close(); } catch (IOException ignored) { }
            throw new FileNotFoundException(e.getMessage());
        }
    }
    @Override public Bundle call(String method, String arg, Bundle extras) {
        if ("quota-revoke".equals(method)) {
            android.net.Uri tree = android.provider.DocumentsContract.buildTreeDocumentUri("com.videodown.douyin.test.quotaexport", "root");
            android.net.Uri children = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(tree, "root");
            final int targetUid;
            try { targetUid = getContext().getPackageManager().getApplicationInfo("com.videodown.douyin.debug", 0).uid; }
            catch (android.content.pm.PackageManager.NameNotFoundException e) { throw new IllegalStateException(e); }
            Bundle result = new Bundle();
            result.putInt("providerUid", android.os.Process.myUid()); result.putInt("callerUid", android.os.Binder.getCallingUid());
            result.putInt("targetUid", targetUid); result.putString("providerPackage", getContext().getPackageName());
            result.putInt("beforeTree", getContext().checkUriPermission(tree, -1, targetUid, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION));
            result.putInt("beforeChildren", getContext().checkUriPermission(children, -1, targetUid, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION));
            int flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION | android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
            getContext().revokeUriPermission("com.videodown.douyin.debug", tree, flags);
            // This test-only root has no prior recipient grants; revoke its transient owners too.
            getContext().revokeUriPermission(tree, flags);
            result.putInt("afterTree", getContext().checkUriPermission(tree, -1, targetUid, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION));
            result.putInt("afterChildren", getContext().checkUriPermission(children, -1, targetUid, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION));
            return result;
        }
        if ("quota-stats".equals(method)) { Bundle result = new Bundle(); result.putLong("bytesWritten", bytesWritten); result.putInt("enospcCount", enospcCount); return result; }
        if ("quota-unlimited".equals(method)) { quota = 64L * 1024 * 1024; return new Bundle(); }
        if ("quota-reset".equals(method)) {
            File[] files = directory().listFiles(); if (files != null) for (File file : files) file.delete();
            quota = 4096; bytesWritten = 0; enospcCount = 0; return new Bundle();
        }
        return super.call(method, arg, extras);
    }
}
