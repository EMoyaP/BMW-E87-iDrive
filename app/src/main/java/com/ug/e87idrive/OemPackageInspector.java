package com.ug.e87idrive;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.ComponentInfo;
import android.content.pm.FeatureInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.Build;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Read-only inventory of the OEM packages observed on the physical RK3326 unit.
 *
 * <p>This class deliberately does not bind services, query unknown content URIs, send broadcasts or
 * load OEM code. PackageManager metadata and user-requested APK copies are the only operations.</p>
 */
final class OemPackageInspector {
    private static final String[] OBSERVED_PACKAGES = {
            "com.can.activity",
            "com.jancar.services",
            "com.jancar.launcher",
            "com.jancar.radio",
            "com.jancar.bluetooth",
            "com.jancar.btservice",
            "com.jancar.settings",
            "com.autochips.backcarapp",
            "com.suding.speedplay"
    };
    private static final String[] CORE_EXPORT_PACKAGES = {
            "com.can.activity",
            "com.jancar.services",
            "com.jancar.launcher",
            "com.jancar.radio",
            "com.jancar.settings"
    };
    private static final String[] VERIFIED_DISCOVERY_ACTIONS = {
            "com.jancar.services.action.main",
            "com.jancar.services.action.radio"
    };

    static final class ExportArtifact {
        final String packageName;
        final File source;
        final String filename;
        final String mimeType;

        ExportArtifact(String packageName, File source, String filename, String mimeType) {
            this.packageName = packageName;
            this.source = source;
            this.filename = filename;
            this.mimeType = mimeType;
        }
    }

    static final class FullExportPlan {
        final String report;
        final List<ExportArtifact> artifacts;
        final int packageCount;
        final long readableBytes;

        FullExportPlan(String report, List<ExportArtifact> artifacts, int packageCount, long readableBytes) {
            this.report = report;
            this.artifacts = artifacts;
            this.packageCount = packageCount;
            this.readableBytes = readableBytes;
        }
    }

    private final Context context;
    private final PackageManager packageManager;
    private volatile List<String> cachedCanPackages;

    OemPackageInspector(Context context) {
        this.context = context.getApplicationContext();
        packageManager = this.context.getPackageManager();
    }

    String buildScreenSummary() {
        boolean canPackage = installed("com.can.activity");
        boolean jancarServices = installed("com.jancar.services");
        boolean radio = installed("com.jancar.radio");
        StringBuilder out = new StringBuilder(700);
        out.append("UNIDAD Y PUENTES OEM\n");
        out.append("Rockchip ").append(Build.MODEL).append(" · ")
                .append(Build.SUPPORTED_ABIS.length == 0 ? "ABI desconocida" : Build.SUPPORTED_ABIS[0])
                .append(" · API ").append(Build.VERSION.SDK_INT).append('\n');
        out.append(platformAssessment(Build.VERSION.SDK_INT, Build.VERSION.RELEASE)).append("\n\n");
        out.append(status(canPackage)).append("  Proveedor CAN instalado");
        if (canPackage) out.append(" · contrato aún no identificado");
        out.append('\n');
        out.append(status(jancarServices)).append("  Servicios Jancar instalados");
        if (jancarServices) out.append(" · CarService localizado por metadatos");
        out.append('\n');
        out.append(status(radio)).append("  Radio Jancar instalada").append('\n');
        out.append(status(packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)))
                .append("  Android Automotive público").append("\n\n");
        out.append("Las capturas anteriores no expusieron puertas, freno, cinturón ni temperatura por APIs públicas.\n");
        out.append("USB DEBUG puede exportar metadatos y APK OEM para identificar el contrato real sin ejecutarlo.");
        return out.toString();
    }

    String buildReport() {
        StringBuilder out = new StringBuilder(12_000);
        out.append("INVENTARIO OEM DIRIGIDO · SOLO METADATOS\n");
        out.append(platformAssessment(Build.VERSION.SDK_INT, Build.VERSION.RELEASE)).append('\n');
        out.append("No se enlazan servicios, no se consultan URI desconocidas y no se carga código OEM.\n");
        Set<String> packages = new LinkedHashSet<>();
        Collections.addAll(packages, OBSERVED_PACKAGES);
        packages.addAll(discoverCanRelatedPackages());
        for (String packageName : packages) appendPackage(out, packageName);
        out.append("\nAPK RELACIONADOS CON CAN/VEHÍCULO SELECCIONADOS PARA EXPORTACIÓN\n");
        for (String packageName : discoverCanRelatedPackages()) out.append(packageName).append('\n');
        appendVerifiedActionResolution(out);
        return out.toString();
    }

    List<ExportArtifact> exportArtifacts() {
        List<ExportArtifact> artifacts = new ArrayList<>();
        for (String packageName : discoverCanRelatedPackages()) {
            PackageInfo info = packageInfo(packageName);
            if (info == null || info.applicationInfo == null) continue;
            addArtifact(artifacts, packageName, info.applicationInfo.sourceDir, "base");
            String[] splits = info.applicationInfo.splitSourceDirs;
            if (splits != null) {
                for (int i = 0; i < splits.length; i++) addArtifact(artifacts, packageName, splits[i], "split_" + (i + 1));
            }
        }
        return Collections.unmodifiableList(artifacts);
    }

    FullExportPlan buildFullExportPlan() {
        List<PackageInfo> packages = allInstalledPackages();
        List<ExportArtifact> artifacts = new ArrayList<>();
        StringBuilder out = new StringBuilder(Math.max(64_000, packages.size() * 1_200));
        appendFirmwareProfile(out);
        out.append("\nAPLICACIONES INSTALADAS Y COMPONENTES\n");
        for (PackageInfo info : packages) {
            appendPackageInfo(out, info.packageName, info);
            addPackageArtifacts(artifacts, info);
        }
        appendUpdateDiscovery(out, packages);
        appendReadableFirmwareFiles(out, artifacts);
        long bytes = 0L;
        for (ExportArtifact artifact : artifacts) bytes += Math.max(0L, artifact.source.length());
        out.append("\nRESUMEN DE COPIA\nPaquetes visibles=").append(packages.size())
                .append("\nAPK/splits legibles=").append(artifacts.size())
                .append("\nBytes candidatos=").append(bytes).append('\n');
        out.append("No contiene datos privados de aplicaciones, cuentas, bases de datos, particiones, MCU ni firmware Hiworld.\n");
        return new FullExportPlan(out.toString(), Collections.unmodifiableList(artifacts), packages.size(), bytes);
    }

    private Set<String> discoverCanRelatedPackages() {
        List<String> cached = cachedCanPackages;
        if (cached != null) return new LinkedHashSet<>(cached);
        Set<String> selected = new LinkedHashSet<>();
        Collections.addAll(selected, CORE_EXPORT_PACKAGES);
        int flags = PackageManager.GET_ACTIVITIES | PackageManager.GET_SERVICES
                | PackageManager.GET_RECEIVERS | PackageManager.GET_PROVIDERS | PackageManager.GET_META_DATA;
        List<PackageInfo> installed;
        try { installed = packageManager.getInstalledPackages(flags); }
        catch (RuntimeException error) { return selected; }
        for (PackageInfo info : installed) {
            if (info == null || info.packageName == null || context.getPackageName().equals(info.packageName)) continue;
            if (containsCanEvidence(info.packageName)
                    || componentsContainCanEvidence(info.activities)
                    || componentsContainCanEvidence(info.receivers)
                    || componentsContainCanEvidence(info.services)
                    || componentsContainCanEvidence(info.providers)) {
                selected.add(info.packageName);
            }
        }
        List<String> present = new ArrayList<>();
        for (String packageName : selected) if (packageInfo(packageName) != null) present.add(packageName);
        cachedCanPackages = Collections.unmodifiableList(present);
        return new LinkedHashSet<>(present);
    }

    private static boolean componentsContainCanEvidence(ComponentInfo[] components) {
        if (components == null) return false;
        for (ComponentInfo component : components) {
            if (component != null && containsCanEvidence(component.name)) return true;
        }
        return false;
    }

    private static boolean containsCanEvidence(String value) {
        if (value == null) return false;
        String lower = value.toLowerCase(Locale.ROOT);
        String[] tokens = {"canbus", "canbox", "carservice", "vehicle", "cluster", "backcar", "mcu"};
        for (String token : tokens) if (lower.contains(token)) return true;
        return false;
    }

    static String platformAssessment(int sdk, String release) {
        String expected = expectedAndroid(sdk);
        String shown = release == null || release.trim().isEmpty() ? "desconocido" : release.trim();
        if (expected == null) return "Android declarado " + shown + " · API efectiva " + sdk;
        boolean consistent = shown.equals(expected) || shown.startsWith(expected + ".");
        if (consistent) return "Android " + shown + " · API efectiva " + sdk;
        return "Firmware declara Android " + shown + ", pero API " + sdk + " corresponde a Android " + expected
                + " · tratar compatibilidad como API " + sdk;
    }

    private void appendPackage(StringBuilder out, String packageName) {
        PackageInfo info = packageInfo(packageName);
        out.append("\n- ").append(packageName);
        if (info == null) {
            out.append(" · NO INSTALADO\n");
            return;
        }
        ApplicationInfo app = info.applicationInfo;
        out.append(" · INSTALADO");
        out.append(" · versión=").append(info.versionName == null ? "?" : info.versionName);
        out.append(" (").append(longVersionCode(info)).append(')');
        if (app != null) {
            out.append(" · uid=").append(app.uid);
            out.append(" · sistema=").append((app.flags & ApplicationInfo.FLAG_SYSTEM) != 0);
            File source = new File(app.sourceDir == null ? "" : app.sourceDir);
            out.append(" · apk=").append(source.isFile() ? formatMiB(source.length()) : "no legible");
        }
        out.append('\n');
        appendComponents(out, "activity", info.activities);
        appendComponents(out, "receiver", info.receivers);
        appendComponents(out, "service", info.services);
        if (info.providers != null) {
            for (ProviderInfo provider : info.providers) {
                out.append("  provider: ").append(provider.name)
                        .append(" · authority=").append(value(provider.authority))
                        .append(" · exported=").append(provider.exported)
                        .append(" · enabled=").append(provider.enabled)
                        .append(" · readPermission=").append(value(provider.readPermission))
                        .append(" · writePermission=").append(value(provider.writePermission))
                        .append(" · grantUriPermissions=").append(provider.grantUriPermissions)
                        .append('\n');
            }
        }
    }

    private void appendComponents(StringBuilder out, String kind, ComponentInfo[] components) {
        if (components == null) return;
        for (ComponentInfo component : components) {
            out.append("  ").append(kind).append(": ").append(component.name)
                    .append(" · exported=").append(component.exported)
                    .append(" · enabled=").append(component.enabled)
                    .append(" · process=").append(value(component.processName));
            String permission = null;
            if (component instanceof ActivityInfo) permission = ((ActivityInfo) component).permission;
            else if (component instanceof ServiceInfo) permission = ((ServiceInfo) component).permission;
            if (permission != null) out.append(" · permission=").append(permission);
            out.append('\n');
        }
    }

    private void appendVerifiedActionResolution(StringBuilder out) {
        out.append("\nACCIONES JANCAR PUBLICADAS EN CÓDIGO ABIERTO · RESOLUCIÓN LOCAL SIN EJECUCIÓN\n");
        for (String action : VERIFIED_DISCOVERY_ACTIONS) {
            Intent intent = new Intent(action).setPackage("com.jancar.services");
            List<ResolveInfo> services;
            try { services = packageManager.queryIntentServices(intent, 0); }
            catch (Exception error) { services = Collections.emptyList(); }
            out.append(action).append(" -> ");
            if (services.isEmpty()) out.append("no resuelta en esta unidad");
            else {
                for (int i = 0; i < services.size(); i++) {
                    if (i > 0) out.append(" | ");
                    ServiceInfo service = services.get(i).serviceInfo;
                    out.append(service == null ? "(sin detalle)" : service.packageName + "/" + service.name);
                }
            }
            out.append('\n');
        }
    }

    private PackageInfo packageInfo(String packageName) {
        int flags = PackageManager.GET_ACTIVITIES | PackageManager.GET_SERVICES
                | PackageManager.GET_RECEIVERS | PackageManager.GET_PROVIDERS | PackageManager.GET_META_DATA
                | PackageManager.GET_PERMISSIONS;
        if (Build.VERSION.SDK_INT >= 24) flags |= PackageManager.MATCH_DISABLED_COMPONENTS;
        try { return packageManager.getPackageInfo(packageName, flags); }
        catch (PackageManager.NameNotFoundException ignored) { return null; }
        catch (RuntimeException ignored) { return null; }
    }

    private boolean installed(String packageName) { return packageInfo(packageName) != null; }

    private List<PackageInfo> allInstalledPackages() {
        int flags = PackageManager.GET_ACTIVITIES | PackageManager.GET_SERVICES
                | PackageManager.GET_RECEIVERS | PackageManager.GET_PROVIDERS | PackageManager.GET_META_DATA
                | PackageManager.GET_PERMISSIONS;
        if (Build.VERSION.SDK_INT >= 24) flags |= PackageManager.MATCH_DISABLED_COMPONENTS;
        List<PackageInfo> packages;
        try { packages = new ArrayList<>(packageManager.getInstalledPackages(flags)); }
        catch (RuntimeException error) { return new ArrayList<>(); }
        Collections.sort(packages, (left, right) -> left.packageName.compareToIgnoreCase(right.packageName));
        return packages;
    }

    private void appendFirmwareProfile(StringBuilder out) {
        out.append("INVENTARIO COMPLETO DE LA UNIDAD · SOLO LECTURA\n");
        out.append("Generado sin root, ADB, ejecución de APK ni acceso a datos privados.\n\n");
        appendBuild(out, "BRAND", Build.BRAND);
        appendBuild(out, "MANUFACTURER", Build.MANUFACTURER);
        appendBuild(out, "MODEL", Build.MODEL);
        appendBuild(out, "DEVICE", Build.DEVICE);
        appendBuild(out, "PRODUCT", Build.PRODUCT);
        appendBuild(out, "BOARD", Build.BOARD);
        appendBuild(out, "HARDWARE", Build.HARDWARE);
        appendBuild(out, "BOOTLOADER", Build.BOOTLOADER);
        appendBuild(out, "ID", Build.ID);
        appendBuild(out, "DISPLAY", Build.DISPLAY);
        appendBuild(out, "FINGERPRINT", Build.FINGERPRINT);
        appendBuild(out, "TYPE", Build.TYPE);
        appendBuild(out, "TAGS", Build.TAGS);
        appendBuild(out, "VERSION.RELEASE", Build.VERSION.RELEASE);
        appendBuild(out, "VERSION.SDK_INT", String.valueOf(Build.VERSION.SDK_INT));
        appendBuild(out, "VERSION.INCREMENTAL", Build.VERSION.INCREMENTAL);
        appendBuild(out, "VERSION.SECURITY_PATCH", Build.VERSION.SECURITY_PATCH);
        appendBuild(out, "SUPPORTED_ABIS", java.util.Arrays.toString(Build.SUPPORTED_ABIS));
        appendBuild(out, "kernel/os.version", System.getProperty("os.version", "(desconocido)"));
        out.append(platformAssessment(Build.VERSION.SDK_INT, Build.VERSION.RELEASE)).append('\n');

        out.append("\nCARACTERÍSTICAS ANDROID\n");
        FeatureInfo[] features;
        try { features = packageManager.getSystemAvailableFeatures(); }
        catch (RuntimeException error) { features = null; }
        if (features != null) {
            List<String> names = new ArrayList<>();
            for (FeatureInfo feature : features) {
                if (feature == null) continue;
                names.add(feature.name == null ? "gles=" + feature.getGlEsVersion() : feature.name);
            }
            Collections.sort(names, String.CASE_INSENSITIVE_ORDER);
            for (String name : names) out.append(name).append('\n');
        }
        out.append("\nBIBLIOTECAS COMPARTIDAS\n");
        String[] libraries = packageManager.getSystemSharedLibraryNames();
        if (libraries != null) {
            java.util.Arrays.sort(libraries, String.CASE_INSENSITIVE_ORDER);
            for (String library : libraries) out.append(library).append('\n');
        }
    }

    private void appendPackageInfo(StringBuilder out, String packageName, PackageInfo info) {
        out.append("\n- ").append(packageName);
        ApplicationInfo app = info.applicationInfo;
        CharSequence label = null;
        if (app != null) {
            try { label = packageManager.getApplicationLabel(app); } catch (RuntimeException ignored) {}
        }
        if (label != null) out.append(" [").append(compact(label.toString())).append(']');
        out.append(" · INSTALADO · versión=").append(info.versionName == null ? "?" : compact(info.versionName));
        out.append(" (").append(longVersionCode(info)).append(')');
        if (app != null) {
            out.append(" · uid=").append(app.uid)
                    .append(" · sistema=").append((app.flags & ApplicationInfo.FLAG_SYSTEM) != 0)
                    .append(" · enabled=").append(app.enabled);
            File source = new File(app.sourceDir == null ? "" : app.sourceDir);
            out.append(" · apk=").append(source.isFile() ? formatMiB(source.length()) : "no legible");
        }
        out.append('\n');
        if (info.requestedPermissions != null) {
            out.append("  permisos solicitados: ");
            for (int i = 0; i < info.requestedPermissions.length; i++) {
                if (i > 0) out.append(" | ");
                out.append(info.requestedPermissions[i]);
                if (info.requestedPermissionsFlags != null && i < info.requestedPermissionsFlags.length
                        && (info.requestedPermissionsFlags[i] & PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0) {
                    out.append(" [concedido]");
                }
            }
            out.append('\n');
        }
        appendComponents(out, "activity", info.activities);
        appendComponents(out, "receiver", info.receivers);
        appendComponents(out, "service", info.services);
        if (info.providers != null) {
            for (ProviderInfo provider : info.providers) {
                out.append("  provider: ").append(provider.name)
                        .append(" · authority=").append(value(provider.authority))
                        .append(" · exported=").append(provider.exported)
                        .append(" · enabled=").append(provider.enabled)
                        .append(" · readPermission=").append(value(provider.readPermission))
                        .append(" · writePermission=").append(value(provider.writePermission))
                        .append(" · grantUriPermissions=").append(provider.grantUriPermissions)
                        .append('\n');
            }
        }
    }

    private static void addPackageArtifacts(List<ExportArtifact> output, PackageInfo info) {
        if (info == null || info.applicationInfo == null) return;
        addArtifact(output, info.packageName, info.applicationInfo.sourceDir, "base");
        String[] splits = info.applicationInfo.splitSourceDirs;
        if (splits != null) {
            for (int i = 0; i < splits.length; i++) addArtifact(output, info.packageName, splits[i], "split_" + (i + 1));
        }
    }

    private static void appendBuild(StringBuilder out, String key, String value) {
        out.append("Build.").append(key).append('=').append(value == null ? "(null)" : compact(value)).append('\n');
    }

    private static String compact(String value) {
        return value == null ? "" : value.replace('\n', ' ').replace('\r', ' ').trim();
    }

    private static void addArtifact(List<ExportArtifact> output, String packageName, String path, String suffix) {
        if (path == null || path.trim().isEmpty()) return;
        File source = new File(path);
        if (!source.isFile() || !source.canRead()) return;
        String safePackage = packageName.replaceAll("[^A-Za-z0-9._-]", "_");
        output.add(new ExportArtifact(packageName, source, "oem_" + safePackage + "_" + suffix + ".apk",
                "application/vnd.android.package-archive"));
    }

    private void appendUpdateDiscovery(StringBuilder out, List<PackageInfo> packages) {
        out.append("\nACTUALIZACIÓN DE SISTEMA/FIRMWARE · DESCUBRIMIENTO SIN EJECUCIÓN\n");
        for (PackageInfo info : packages) {
            StringBuilder evidence = new StringBuilder(info.packageName);
            appendComponentNames(evidence, info.activities);
            appendComponentNames(evidence, info.receivers);
            appendComponentNames(evidence, info.services);
            appendComponentNames(evidence, info.providers);
            String lower = evidence.toString().toLowerCase(Locale.ROOT);
            if (lower.contains("ota") || lower.contains("update") || lower.contains("upgrade")
                    || lower.contains("firmware")) {
                out.append(info.packageName).append(" -> ").append(compact(evidence.toString())).append('\n');
            }
        }
        Intent settings = new Intent("android.settings.SYSTEM_UPDATE_SETTINGS");
        List<ResolveInfo> handlers;
        try { handlers = packageManager.queryIntentActivities(settings, 0); }
        catch (RuntimeException error) { handlers = Collections.emptyList(); }
        out.append("android.settings.SYSTEM_UPDATE_SETTINGS -> ");
        if (handlers.isEmpty()) out.append("sin actividad pública");
        else {
            for (int i = 0; i < handlers.size(); i++) {
                if (i > 0) out.append(" | ");
                ActivityInfo activity = handlers.get(i).activityInfo;
                out.append(activity == null ? "(sin detalle)" : activity.packageName + "/" + activity.name);
            }
        }
        out.append('\n');
    }

    private static void appendComponentNames(StringBuilder out, ComponentInfo[] components) {
        if (components == null) return;
        for (ComponentInfo component : components) {
            if (component != null && component.name != null) out.append(' ').append(component.name);
        }
    }

    private static void appendReadableFirmwareFiles(StringBuilder out, List<ExportArtifact> artifacts) {
        out.append("\nARCHIVOS PÚBLICOS DE IDENTIFICACIÓN DE FIRMWARE\n");
        addFirmwareFile(out, artifacts, "/system/build.prop", "firmware_system_build.prop", "text/plain");
        addFirmwareFile(out, artifacts, "/vendor/build.prop", "firmware_vendor_build.prop", "text/plain");
        addFirmwareFile(out, artifacts, "/product/build.prop", "firmware_product_build.prop", "text/plain");
        addFirmwareFile(out, artifacts, "/odm/build.prop", "firmware_odm_build.prop", "text/plain");
        addFirmwareFile(out, artifacts, "/system_ext/build.prop", "firmware_system_ext_build.prop", "text/plain");
        addFirmwareFile(out, artifacts, "/system/etc/prop.default", "firmware_system_prop.default", "text/plain");
        addFirmwareFile(out, artifacts, "/system/etc/security/otacerts.zip", "firmware_otacerts.zip", "application/zip");
        out.append("No se intentan leer boot, recovery, super, vendor raw, MCU ni memoria interna Hiworld.\n");
    }

    private static void addFirmwareFile(StringBuilder out, List<ExportArtifact> artifacts, String path,
                                        String filename, String mimeType) {
        File source = new File(path);
        if (!source.isFile() || !source.canRead() || source.length() <= 0L) {
            out.append(path).append(" -> no legible por una APK normal\n");
            return;
        }
        artifacts.add(new ExportArtifact("firmware.public", source, filename, mimeType));
        out.append(path).append(" -> legible · ").append(source.length()).append(" bytes\n");
    }

    private static String status(boolean available) { return available ? "[DETECTADO]" : "[NO]"; }

    private static String value(String value) { return value == null || value.isEmpty() ? "(ninguno)" : value; }

    private static String formatMiB(long bytes) {
        return String.format(Locale.ROOT, "%.1f MiB", bytes / 1_048_576.0);
    }

    @SuppressWarnings("deprecation")
    private static long longVersionCode(PackageInfo info) {
        return Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
    }

    private static String expectedAndroid(int sdk) {
        switch (sdk) {
            case 23: return "6";
            case 24:
            case 25: return "7";
            case 26:
            case 27: return "8";
            case 28: return "9";
            case 29: return "10";
            case 30: return "11";
            case 31:
            case 32: return "12";
            case 33: return "13";
            case 34: return "14";
            case 35: return "15";
            default: return null;
        }
    }
}
