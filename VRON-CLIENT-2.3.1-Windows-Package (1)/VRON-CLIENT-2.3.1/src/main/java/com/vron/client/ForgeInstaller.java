package com.vron.client;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.regex.*;
import java.util.concurrent.TimeUnit;

/** Downloads official Forge installer artifacts from the Forge Maven repository and runs client installation. */
final class ForgeInstaller {
    interface Progress { void update(String message); }
    private static final String MAVEN = "https://maven.minecraftforge.net/net/minecraftforge/forge/";
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).followRedirects(HttpClient.Redirect.NORMAL).build();

    List<String> versions(String mc) throws Exception {
        String xml = get(MAVEN + "maven-metadata.xml");
        Matcher m = Pattern.compile("<version>\\s*" + Pattern.quote(mc) + "-([^<]+)\\s*</version>").matcher(xml);
        LinkedHashSet<String> found = new LinkedHashSet<>();
        while (m.find()) found.add(m.group(1).trim());
        List<String> result = new ArrayList<>(found);
        Collections.reverse(result); // Maven metadata normally lists oldest first.
        if (result.isEmpty()) throw new IOException("Forge не публикует версии для Minecraft " + mc);
        return result;
    }

    String install(String mc, String forgeVersion, Path gameDir, Progress progress) throws Exception {
        Files.createDirectories(gameDir);
        String coordinate = mc + "-" + forgeVersion;
        String artifact = "forge-" + coordinate + "-installer.jar";
        String url = MAVEN + coordinate + "/" + artifact;
        Path cache = gameDir.resolve("launcher-cache"); Files.createDirectories(cache);
        Path installer = cache.resolve(artifact);
        progress.update("Загрузка Forge Installer " + forgeVersion);
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(3)).header("User-Agent", "VRON-CLIENT/2.3").GET().build();
        HttpResponse<Path> response = http.send(request, HttpResponse.BodyHandlers.ofFile(installer));
        if (response.statusCode() < 200 || response.statusCode() >= 300) { Files.deleteIfExists(installer); throw new IOException("Forge Maven HTTP " + response.statusCode()); }
        if (Files.size(installer) < 100_000) throw new IOException("Forge installer повреждён или неполный: " + Files.size(installer) + " bytes");
        Path java = Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java");
        progress.update("Установка Forge " + coordinate + " в выбранную сборку");
        Process process = new ProcessBuilder(java.toString(), "-jar", installer.toString(), "--installClient", gameDir.toString())
                .directory(gameDir.toFile()).redirectErrorStream(true).start();
        StringBuilder output = new StringBuilder();
        Thread reader = new Thread(() -> { try (BufferedReader br = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) { for (String line; (line = br.readLine()) != null;) { synchronized (output) { output.append(line).append('\n'); } progress.update("Forge: " + line); } } catch (IOException ignored) {} }, "vron-forge-installer-log");
        reader.setDaemon(true); reader.start();
        if (!process.waitFor(15, TimeUnit.MINUTES)) { process.destroyForcibly(); throw new IOException("Forge installer превысил лимит 15 минут"); }
        reader.join(3000);
        if (process.exitValue() != 0) throw new IOException("Forge installer завершился с кодом " + process.exitValue() + ": " + output);
        String profile = findProfile(gameDir, mc, forgeVersion);
        if (profile == null) throw new IOException("Forge installer завершился, но профиль Forge не найден. Вывод: " + output);
        progress.update("Forge установлен: " + profile);
        return profile;
    }

    private String findProfile(Path gameDir, String mc, String forge) throws Exception {
        Path versions = gameDir.resolve("versions"); if (!Files.isDirectory(versions)) return null;
        try (var dirs = Files.list(versions)) {
            for (Path dir : dirs.filter(Files::isDirectory).toList()) {
                Path json = dir.resolve(dir.getFileName() + ".json"); if (!Files.isRegularFile(json)) continue;
                try {
                    Map<String,Object> meta = Json.obj(Json.parse(Files.readString(json)));
                    String id = String.valueOf(meta.getOrDefault("id", ""));
                    String parent = String.valueOf(meta.getOrDefault("inheritsFrom", ""));
                    if (!mc.equals(parent) && !id.contains(mc)) continue;
                    for (Object lib : Json.arr(meta.get("libraries"))) {
                        String name = String.valueOf(Json.obj(lib).getOrDefault("name", ""));
                        if (name.startsWith("net.minecraftforge:forge:")) {
                            if (name.contains(mc + "-" + forge) || id.contains(mc + "-" + forge)) return dir.getFileName().toString();
                        }
                    }
                } catch (Exception ignored) {}
            }
        }
        return null;
    }
    private String get(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60)).header("User-Agent", "VRON-CLIENT/2.3").GET().build();
        HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() < 200 || r.statusCode() >= 300) throw new IOException("Forge metadata HTTP " + r.statusCode()); return r.body();
    }
    private boolean isWindows() { return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"); }
}
