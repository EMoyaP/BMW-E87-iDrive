package com.ug.e87idrive;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;

/** Small read-only provider for sharing the app's own diagnostic report without AndroidX. */
public final class DiagnosticFileProvider extends ContentProvider {
    private File root;

    @Override public boolean onCreate() {
        root = getContext().getExternalFilesDir(null);
        if (root == null) root = getContext().getFilesDir();
        return true;
    }

    @Override public String getType(Uri uri) { return "text/plain"; }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode) && !"rt".equals(mode)) throw new FileNotFoundException("Solo lectura");
        String name = uri.getLastPathSegment();
        if (name == null || name.contains("/") || name.contains("\\") || name.contains("..")) {
            throw new FileNotFoundException("Ruta no válida");
        }
        try {
            File file = new File(root, name).getCanonicalFile();
            File base = root.getCanonicalFile();
            if (!file.getPath().startsWith(base.getPath() + File.separator) || !file.isFile()) {
                throw new FileNotFoundException("Informe no encontrado");
            }
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
        } catch (java.io.IOException e) {
            throw new FileNotFoundException(e.toString());
        }
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sortOrder) {
        String name = uri.getLastPathSegment();
        File file = name == null ? null : new File(root, name);
        MatrixCursor cursor = new MatrixCursor(projection == null ? new String[]{"_display_name", "_size"} : projection);
        if (file != null && file.isFile()) {
            Object[] row = new Object[cursor.getColumnCount()];
            for (int i = 0; i < cursor.getColumnCount(); i++) {
                if ("_display_name".equals(cursor.getColumnName(i))) row[i] = file.getName();
                if ("_size".equals(cursor.getColumnName(i))) row[i] = file.length();
            }
            cursor.addRow(row);
        }
        return cursor;
    }

    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] args) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { return 0; }
}
