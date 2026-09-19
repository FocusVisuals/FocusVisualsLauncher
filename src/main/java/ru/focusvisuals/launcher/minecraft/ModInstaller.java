package ru.focusvisuals.launcher.minecraft;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

public final class ModInstaller {
    private static final String MODRINTH_API = "https://api.modrinth.com/v2/project/";
    private static final String FOCUSVISUALS_DOWNLOAD_URL =
            "https://raw.githubusercontent.com/MarselGumarov/Focusvisualmod/main/FocusVisuals-1.1.jar";
    private static final Pattern DOWNLOAD_URL = Pattern.compile("\"url\"\\s*:\\s*\"([^\"]+\\.jar)\"");
    private static final Pattern VERSION = Pattern.compile("\"version_number\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern MOD_VERSION = Pattern.compile("\"version\"\\s*:\\s*\"([^\"]+)\"");
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private ModInstaller() {}

    public static void install(Path gameDir, String minecraftVersion) throws Exception {
        Path mods = gameDir.resolve("mods");
        Files.createDirectories(mods);
        Path focusVisuals = downloadUrl(FOCUSVISUALS_DOWNLOAD_URL, mods, "FocusVisuals-1.1.jar");
        removeInstalledFocusVisuals(mods, focusVisuals);
        repairFocusVisuals(focusVisuals);
        downloadModrinth("sodium", minecraftVersion, mods);
        downloadModrinth("fabric-api", minecraftVersion, mods);
    }

    public static UpdateStatus checkFocusVisualsUpdate(Path gameDir, String minecraftVersion) throws Exception {
        Path installed = findFocusVisuals(gameDir.resolve("mods"));
        if (installed == null) return new UpdateStatus(false, false, "FocusVisuals is not installed");
        String installedVersion = installedVersion(installed);
        return new UpdateStatus(!"1.1".equals(installedVersion), true,
                "GitHub FocusVisuals version: 1.1 (installed: " + installedVersion + ")");
    }

    public record UpdateStatus(boolean updateAvailable, boolean sourceAvailable, String message) {}

    private static Path findFocusVisuals(Path mods) throws IOException {
        if (!Files.isDirectory(mods)) return null;
        try (var files = Files.list(mods)) {
            return files.filter(path -> path.getFileName().toString().toLowerCase().contains("focusvisual"))
                    .filter(path -> path.toString().toLowerCase().endsWith(".jar")).findFirst().orElse(null);
        }
    }

    private static String installedVersion(Path jar) throws IOException {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry entry = zip.getEntry("fabric.mod.json");
            if (entry == null) throw new IOException("FocusVisuals JAR has no fabric.mod.json");
            String json = new String(zip.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8);
            Matcher matcher = MOD_VERSION.matcher(json);
            if (!matcher.find()) throw new IOException("FocusVisuals fabric.mod.json has no version");
            return matcher.group(1);
        }
    }

    private static void removeInstalledFocusVisuals(Path mods, Path keep) throws IOException {
        try (var files = Files.list(mods)) {
            for (Path file : files
                    .filter(path -> !path.equals(keep))
                    .filter(path -> path.getFileName().toString().toLowerCase().contains("focusvisual"))
                    .filter(path -> path.toString().toLowerCase().endsWith(".jar")).toList()) {
                Files.deleteIfExists(file);
            }
        }
    }

    private static void repairFocusVisuals(Path jar) throws IOException {
        if (!Files.isRegularFile(jar)) return;
        Path temp = jar.resolveSibling(jar.getFileName() + ".repair");
        try (ZipFile input = new ZipFile(jar.toFile());
             ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(temp))) {
            boolean hasConfigManager = input.getEntry("aethereal/ConfigManager.class") != null;
            var entries = input.entries();
            while (entries.hasMoreElements()) {
                ZipEntry source = entries.nextElement();
                output.putNextEntry(new ZipEntry(source.getName()));
                byte[] data = input.getInputStream(source).readAllBytes();
                if (!hasConfigManager && source.getName().equals("focusvisuals.mixins.json")) {
                    String json = new String(data, StandardCharsets.UTF_8)
                            .replace("\"ChatScreenMixin\",", "");
                    data = json.getBytes(StandardCharsets.UTF_8);
                }
                output.write(data);
                output.closeEntry();
            }
        }
        Files.move(temp, jar, StandardCopyOption.REPLACE_EXISTING);
    }

    private static Path downloadModrinth(String project, String minecraftVersion, Path mods) throws Exception {
        String query = "?game_versions=%5B%22" + minecraftVersion + "%22%5D&loaders=%5B%22fabric%22%5D";
        HttpResponse<String> response = HTTP.send(
                HttpRequest.newBuilder(URI.create(MODRINTH_API + project + "/version" + query))
                        .header("User-Agent", "FocusVisualsLauncher/1.0.0").GET().build(),
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IOException("Modrinth returned HTTP " + response.statusCode() + " for " + project);
        }
        Matcher matcher = DOWNLOAD_URL.matcher(response.body());
        if (!matcher.find()) throw new IOException("No Fabric " + project + " build is available");
        return downloadFile(matcher.group(1).replace("\\/", "/"), mods);
    }

    private static Path downloadUrl(String url, Path mods, String requestedFileName) throws Exception {
        return downloadFile(url, mods, requestedFileName);
    }

    private static Path downloadFile(String url, Path mods) throws Exception {
        String fileName = URLDecoder.decode(url.substring(url.lastIndexOf('/') + 1), StandardCharsets.UTF_8);
        return downloadFile(url, mods, fileName);
    }

    private static Path downloadFile(String url, Path mods, String requestedFileName) throws Exception {
        String fileName = requestedFileName;
        int queryStart = fileName.indexOf('?');
        if (queryStart >= 0) fileName = fileName.substring(0, queryStart);
        Path target = mods.resolve(fileName);
        Path temporary = mods.resolve(fileName + ".download");
        HttpResponse<InputStream> response = HTTP.send(
                HttpRequest.newBuilder(URI.create(url))
                        .header("User-Agent", "FocusVisualsLauncher/1.0.0").GET().build(),
                HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() / 100 != 2) {
            throw new IOException("Modrinth download failed with HTTP " + response.statusCode());
        }
        try (InputStream input = response.body()) {
            Files.copy(input, temporary, StandardCopyOption.REPLACE_EXISTING);
        }
        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        return target;
    }
}
