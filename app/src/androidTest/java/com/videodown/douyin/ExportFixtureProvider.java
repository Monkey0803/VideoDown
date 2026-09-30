package com.videodown.douyin;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsProvider;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Arrays;

/** Test APK only; Java keeps this separate provider process independent of target Kotlin runtime. */
public class ExportFixtureProvider extends DocumentsProvider {
    private File directory() { File file = new File(getContext().getFilesDir(), "export-fixture"); file.mkdirs(); return file; }
    private File failMarker() { return new File(getContext().getFilesDir(), "export-fail"); }
    @Override public boolean onCreate() { return true; }
    @Override public Cursor queryRoots(String[] projection) {
        String[] columns = projection != null ? projection : new String[] {Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_FLAGS, Root.COLUMN_MIME_TYPES, Root.COLUMN_AVAILABLE_BYTES};
        MatrixCursor result = new MatrixCursor(columns);
        result.newRow().add(Root.COLUMN_ROOT_ID, "root").add(Root.COLUMN_DOCUMENT_ID, "root")
            .add(Root.COLUMN_TITLE, "P2 导出测试").add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_CREATE | Root.FLAG_LOCAL_ONLY)
            .add(Root.COLUMN_MIME_TYPES, "*/*").add(Root.COLUMN_AVAILABLE_BYTES, directory().getUsableSpace());
        return result;
    }
    private File path(String id) {
        if ("root".equals(id)) return directory();
        if (id.isEmpty() || id.contains("/") || id.contains("\\") || id.equals(".") || id.equals("..")) throw new IllegalArgumentException("invalid document");
        return new File(directory(), id);
    }
    private final String[] defaults = {Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_FLAGS, Document.COLUMN_SIZE};
    private void row(MatrixCursor cursor, File file) {
        boolean root = file.equals(directory());
        cursor.newRow().add(Document.COLUMN_DOCUMENT_ID, root ? "root" : file.getName())
            .add(Document.COLUMN_DISPLAY_NAME, root ? "P2 导出测试" : file.getName())
            .add(Document.COLUMN_MIME_TYPE, file.isDirectory() ? Document.MIME_TYPE_DIR : "video/mp4")
            .add(Document.COLUMN_FLAGS, file.isDirectory() ? Document.FLAG_DIR_SUPPORTS_CREATE : Document.FLAG_SUPPORTS_WRITE | Document.FLAG_SUPPORTS_DELETE)
            .add(Document.COLUMN_SIZE, file.length());
    }
    @Override public Cursor queryDocument(String id, String[] projection) {
        MatrixCursor result = new MatrixCursor(projection != null ? projection : defaults); if (path(id).exists()) row(result, path(id)); return result;
    }
    @Override public Cursor queryChildDocuments(String id, String[] projection, String sortOrder) {
        MatrixCursor result = new MatrixCursor(projection != null ? projection : defaults);
        File[] files = path(id).listFiles();
        if (files != null) { Arrays.sort(files); for (File file : files) row(result, file); }
        return result;
    }
    @Override public String createDocument(String parent, String mime, String name) throws FileNotFoundException {
        if (!parent.equals("root")) throw new FileNotFoundException("invalid parent");
        File file = path(name);
        try { if (!file.createNewFile()) throw new FileNotFoundException("collision"); }
        catch (IOException error) { throw new FileNotFoundException(error.getMessage()); }
        return file.getName();
    }
    @Override public void deleteDocument(String id) throws FileNotFoundException { if (!path(id).delete()) throw new FileNotFoundException("cannot delete"); }
    @Override public ParcelFileDescriptor openDocument(String id, String mode, CancellationSignal signal) throws FileNotFoundException {
        if (failMarker().exists() && mode.contains("w")) throw new FileNotFoundException("injected write failure");
        return ParcelFileDescriptor.open(path(id), ParcelFileDescriptor.parseMode(mode));
    }
    @Override public boolean isChildDocument(String parent, String id) { return parent.equals("root") && !id.equals("root") && path(id).exists(); }
    @Override public Bundle call(String method, String arg, Bundle extras) {
        if (method.equals("fixture-reset")) {
            File[] files = directory().listFiles(); if (files != null) for (File file : files) file.delete();
            failMarker().delete(); return new Bundle();
        }
        if (method.equals("fixture-fail")) {
            try { failMarker().createNewFile(); } catch (IOException error) { throw new IllegalStateException(error); }
            return new Bundle();
        }
        return super.call(method, arg, extras);
    }
}
