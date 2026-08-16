package com.ug.e87idrive;

import android.annotation.SuppressLint;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.DocumentsContract;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Writes explicitly requested, passive diagnostic sessions to a user-approved USB directory. */
public final class UsbDiagnosticRecorder {
    private static final String PREFS = "usb_diagnostic";
    private static final String KEY_TREE_URI = "tree_uri";
    private static final long MAX_OEM_FILE_BYTES = 100L * 1024L * 1024L;
    private static final long MAX_OEM_TOTAL_BYTES = 250L * 1024L * 1024L;
    private static final long MAX_FULL_FILE_BYTES = 2L * 1024L * 1024L * 1024L;
    private static final long MAX_FULL_TOTAL_BYTES = 16L * 1024L * 1024L * 1024L;

    public interface Callback {
        void complete(boolean success, String message);
    }

    private final Context context;
    private final SharedPreferences preferences;
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "e87-usb-diagnostic");
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    });
    private volatile Uri activeDocument;
    private volatile String activeFilename;
    private volatile String lastError = "";

    public UsbDiagnosticRecorder(Context context) {
        this.context = context.getApplicationContext();
        preferences = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public Intent directoryPickerIntent() {
        Intent intent = null;
        if (Build.VERSION.SDK_INT >= 29) {
            StorageVolume removable = writableRemovableVolume();
            if (removable != null) intent = removable.createOpenDocumentTreeIntent();
        }
        if (intent == null) intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        return intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
    }

    public boolean removableVolumeMounted() {
        return writableRemovableVolume() != null;
    }

    public String removableVolumeDescription() {
        if (Build.VERSION.SDK_INT < 24) return "Selector de almacenamiento disponible";
        StorageVolume volume = writableRemovableVolume();
        if (volume == null) return "No se detecta una memoria USB montada y escribible";
        try { return volume.getDescription(context); }
        catch (Exception ignored) { return "Memoria USB detectada"; }
    }

    public boolean hasDirectoryPermission() {
        String stored = preferences.getString(KEY_TREE_URI, null);
        if (stored == null || stored.isEmpty()) return false;
        Uri target = Uri.parse(stored);
        for (android.content.UriPermission permission : context.getContentResolver().getPersistedUriPermissions()) {
            if (target.equals(permission.getUri()) && permission.isWritePermission()) return true;
        }
        return false;
    }

    public String directorySummary() {
        if (!hasDirectoryPermission()) return removableVolumeDescription() + " · carpeta sin autorizar";
        String folder = selectedFolderName();
        StorageVolume removable = writableRemovableVolume();
        if (removable == null) return "Carpeta autorizada: " + folder + " · USB física no detectada ahora";
        String description;
        try { description = Build.VERSION.SDK_INT >= 24 ? removable.getDescription(context) : "Memoria USB"; }
        catch (Exception ignored) { description = "Memoria USB"; }
        return description + " · carpeta autorizada: " + folder;
    }

    @SuppressLint("WrongConstant") // The returned grant is explicitly masked to the two accepted URI mode flags.
    public boolean acceptDirectoryResult(Intent data) {
        if (data == null || data.getData() == null) return false;
        Uri uri = data.getData();
        int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        try {
            context.getContentResolver().takePersistableUriPermission(uri, flags);
            preferences.edit().putString(KEY_TREE_URI, uri.toString()).apply();
            lastError = "";
            return true;
        } catch (Exception error) {
            lastError = shortError(error);
            return false;
        }
    }

    public void forgetDirectory() {
        String stored = preferences.getString(KEY_TREE_URI, null);
        if (stored != null) {
            try {
                context.getContentResolver().releasePersistableUriPermission(Uri.parse(stored),
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } catch (Exception ignored) {}
        }
        preferences.edit().remove(KEY_TREE_URI).apply();
    }

    public void startSession(String label, String report, Callback callback) {
        activeDocument = null;
        activeFilename = "e87_" + sanitize(label) + "_"
                + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(new Date()) + ".txt";
        writeRecovery(report);
        io.execute(() -> {
            try {
                Uri directory = configuredDirectory();
                if (directory == null) throw new IllegalStateException("No hay una carpeta USB autorizada");
                activeDocument = createDocument(directory, activeFilename);
                if (activeDocument == null) throw new IllegalStateException("El proveedor USB no permitió crear el archivo");
                writeDocument(activeDocument, report);
                lastError = "";
                notifyCallback(callback, true, "Captura USB iniciada: " + activeFilename);
            } catch (Exception error) {
                lastError = shortError(error);
                notifyCallback(callback, false, "No se pudo iniciar la captura USB: " + lastError);
            }
        });
    }

    public void updateSession(String report) {
        writeRecovery(report);
        io.execute(() -> {
            Uri document = activeDocument;
            if (document == null) return;
            try {
                writeDocument(document, report);
                lastError = "";
            } catch (Exception error) {
                lastError = shortError(error);
            }
        });
    }

    public void finishSession(String report, Callback callback) {
        writeRecovery(report);
        io.execute(() -> {
            Uri document = activeDocument;
            try {
                if (document == null) throw new IllegalStateException(lastError.isEmpty()
                        ? "No se creó el archivo USB" : lastError);
                writeDocument(document, report);
                lastError = "";
                notifyCallback(callback, true, "Diagnóstico terminado en " + activeFilename);
            } catch (Exception error) {
                lastError = shortError(error);
                notifyCallback(callback, false, "USB no disponible; queda copia de recuperación interna: " + lastError);
            } finally {
                activeDocument = null;
                activeFilename = null;
            }
        });
    }

    public void saveReport(String label, String report, Callback callback) {
        writeRecovery(report);
        String filename = "e87_" + sanitize(label) + "_"
                + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(new Date()) + ".txt";
        io.execute(() -> {
            try {
                Uri directory = configuredDirectory();
                if (directory == null) throw new IllegalStateException("No hay una carpeta USB autorizada");
                Uri document = createDocument(directory, filename);
                if (document == null) throw new IllegalStateException("El proveedor USB no permitió crear el archivo");
                writeDocument(document, report);
                lastError = "";
                notifyCallback(callback, true, "Informe guardado en USB: " + filename);
            } catch (Exception error) {
                lastError = shortError(error);
                notifyCallback(callback, false, "No se pudo guardar en USB: " + lastError);
            }
        });
    }

    /**
     * Copies a narrow, caller-provided OEM artifact allowlist to the user-approved directory.
     * No package is executed, loaded or modified. Limits keep the operation suitable for this head unit.
     */
    public void exportOemBundle(String report, List<OemPackageInspector.ExportArtifact> artifacts,
                                Callback callback) {
        exportPackageBundle("oem", "OEM", report, artifacts, MAX_OEM_FILE_BYTES,
                MAX_OEM_TOTAL_BYTES, callback);
    }

    public void exportFullPackageBundle(String report, List<OemPackageInspector.ExportArtifact> artifacts,
                                        Callback callback) {
        exportPackageBundle("firmware", "Inventario completo", report, artifacts, MAX_FULL_FILE_BYTES,
                MAX_FULL_TOTAL_BYTES, callback);
    }

    private void exportPackageBundle(String prefix, String displayName, String report,
                                     List<OemPackageInspector.ExportArtifact> artifacts,
                                     long maxFileBytes, long maxTotalBytes, Callback callback) {
        writeRecovery(report);
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(new Date());
        io.execute(() -> {
            int copied = 0;
            int skipped = 0;
            long totalBytes = 0L;
            StringBuilder details = new StringBuilder();
            try {
                Uri directory = configuredDirectory();
                if (directory == null) throw new IllegalStateException("No hay una carpeta USB autorizada");
                String reportName = "e87_" + prefix + "_inventory_" + timestamp + ".txt";
                Uri reportDocument = createDocument(directory, "text/plain", reportName);
                if (reportDocument == null) throw new IllegalStateException("No se pudo crear el inventario OEM");
                writeDocument(reportDocument, report);
                for (OemPackageInspector.ExportArtifact artifact : artifacts) {
                    File source = artifact.source;
                    long length = source.length();
                    if (!source.isFile() || !source.canRead() || length <= 0
                            || length > maxFileBytes || totalBytes + length > maxTotalBytes) {
                        skipped++;
                        details.append("OMITIDO ").append(artifact.packageName).append(" · ")
                                .append(length).append(" bytes\n");
                        continue;
                    }
                    String filename = timestamp + "_" + artifact.filename;
                    Uri target = null;
                    try {
                        target = createDocument(directory, artifact.mimeType, filename);
                        if (target == null) throw new IllegalStateException("el proveedor no creó el archivo");
                        copyDocument(source, target);
                        copied++;
                        totalBytes += length;
                        details.append("COPIADO ").append(filename).append(" · ").append(length).append(" bytes\n");
                    } catch (Exception fileError) {
                        skipped++;
                        details.append("OMITIDO ").append(filename).append(" · ")
                                .append(shortError(fileError)).append('\n');
                        if (target != null) {
                            try { DocumentsContract.deleteDocument(context.getContentResolver(), target); }
                            catch (Exception ignored) {}
                        }
                    }
                }
                String resultName = "e87_" + prefix + "_export_result_" + timestamp + ".txt";
                Uri resultDocument = createDocument(directory, "text/plain", resultName);
                if (resultDocument != null) {
                    writeDocument(resultDocument, displayName.toUpperCase(Locale.ROOT) + " · EXPORTACIÓN PASIVA\nArchivos copiados=" + copied
                            + " · omitidos=" + skipped + " · bytes=" + totalBytes + "\n\n" + details);
                }
                lastError = "";
                notifyCallback(callback, true, displayName + " exportado: inventario + " + copied + " archivos"
                        + (skipped == 0 ? "" : " · " + skipped + " omitidos"));
            } catch (Exception error) {
                lastError = shortError(error);
                notifyCallback(callback, false, "No se pudo completar " + displayName.toLowerCase(Locale.ROOT)
                        + ": " + lastError);
            }
        });
    }

    public String lastError() { return lastError; }

    public String recoveryReport() {
        File recovery = new File(context.getFilesDir(), "e87_usb_diagnostic_recovery.txt");
        if (!recovery.isFile()) return "";
        StringBuilder text = new StringBuilder((int) Math.min(recovery.length(), 128_000));
        char[] buffer = new char[4_096];
        try (FileReader reader = new FileReader(recovery)) {
            int count;
            while ((count = reader.read(buffer)) >= 0 && text.length() < 512_000) {
                text.append(buffer, 0, count);
            }
            return text.toString();
        } catch (Exception error) {
            return "";
        }
    }

    public void close() { io.shutdown(); }

    private StorageVolume writableRemovableVolume() {
        if (Build.VERSION.SDK_INT < 24) return null;
        StorageManager manager = (StorageManager) context.getSystemService(Context.STORAGE_SERVICE);
        if (manager == null) return null;
        List<StorageVolume> volumes;
        try { volumes = manager.getStorageVolumes(); }
        catch (Exception ignored) { return null; }
        for (StorageVolume volume : volumes) {
            if (!volume.isRemovable()) continue;
            if (Environment.MEDIA_MOUNTED.equals(volume.getState())) return volume;
        }
        return null;
    }

    private Uri configuredDirectory() {
        if (!hasDirectoryPermission()) return null;
        String stored = preferences.getString(KEY_TREE_URI, null);
        return stored == null ? null : Uri.parse(stored);
    }

    private String selectedFolderName() {
        String stored = preferences.getString(KEY_TREE_URI, null);
        if (stored == null) return "(desconocida)";
        try {
            String id = DocumentsContract.getTreeDocumentId(Uri.parse(stored));
            int separator = Math.max(id.lastIndexOf(':'), id.lastIndexOf('/'));
            String name = separator >= 0 ? id.substring(separator + 1) : id;
            return name.isEmpty() ? "raíz del volumen" : name;
        } catch (Exception ignored) {
            return "carpeta seleccionada";
        }
    }

    private Uri createDocument(Uri treeUri, String filename) throws Exception {
        return createDocument(treeUri, "text/plain", filename);
    }

    private Uri createDocument(Uri treeUri, String mimeType, String filename) throws Exception {
        String documentId = DocumentsContract.getTreeDocumentId(treeUri);
        Uri parent = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId);
        return DocumentsContract.createDocument(context.getContentResolver(), parent, mimeType, filename);
    }

    private void writeDocument(Uri document, String report) throws Exception {
        ContentResolver resolver = context.getContentResolver();
        try (OutputStream stream = resolver.openOutputStream(document, "wt")) {
            if (stream == null) throw new IllegalStateException("No se pudo abrir el archivo para escritura");
            try (OutputStreamWriter writer = new OutputStreamWriter(stream, StandardCharsets.UTF_8)) {
                writer.write(report);
                writer.flush();
            }
        }
    }

    private void copyDocument(File source, Uri document) throws Exception {
        ContentResolver resolver = context.getContentResolver();
        byte[] buffer = new byte[64 * 1024];
        try (FileInputStream input = new FileInputStream(source);
             OutputStream output = resolver.openOutputStream(document, "w")) {
            if (output == null) throw new IllegalStateException("No se pudo abrir el destino USB");
            int count;
            while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
            output.flush();
        }
    }

    private void writeRecovery(String report) {
        io.execute(() -> {
            File recovery = new File(context.getFilesDir(), "e87_usb_diagnostic_recovery.txt");
            try (FileWriter writer = new FileWriter(recovery, false)) { writer.write(report); }
            catch (Exception ignored) {}
        });
    }

    private void notifyCallback(Callback callback, boolean success, String message) {
        if (callback != null) callback.complete(success, message);
    }

    private static String sanitize(String value) {
        String normalized = value == null ? "diagnostico"
                : Normalizer.normalize(value, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        String safe = normalized.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
        if (safe.isEmpty()) safe = "diagnostico";
        return safe.length() > 40 ? safe.substring(0, 40) : safe;
    }

    private static String shortError(Throwable error) {
        String message = error.getMessage();
        if (message == null || message.trim().isEmpty()) return error.getClass().getSimpleName();
        return error.getClass().getSimpleName() + ": " + message.replace('\n', ' ');
    }
}
