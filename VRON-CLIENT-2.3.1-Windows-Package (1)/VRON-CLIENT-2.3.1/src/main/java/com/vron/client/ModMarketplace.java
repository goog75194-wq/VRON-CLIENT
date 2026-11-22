package com.vron.client;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;

/** Real Modrinth v2 and CurseForge v1 API integration. */
final class ModMarketplace {
    enum Source { MODRINTH, CURSEFORGE }
    record Mod(Source source, String id, String title, String author, String description,
               long downloads, String iconUrl, String websiteUrl) {
        @Override public String toString() { return title + "  •  " + author + "  •  " + downloads + " downloads"; }
    }
    interface Progress { void update(String message); }
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL).build();
    private static final String UA = "VRON-CLIENT/2.3";

    List<Mod> search(Source source, String query, String mc, String loader, String curseKey) throws Exception {
        if (query == null || query.isBlank()) throw new IOException("Введи название мода для поиска.");
        if (source == Source.MODRINTH) {
            String facets = "[[\"project_type:mod\"],[\"versions:" + jsonSafe(mc) + "\"],[\"categories:" + jsonSafe(loader.toLowerCase(Locale.ROOT)) + "\"]]";
            String url = "https://api.modrinth.com/v2/search?query=" + enc(query) + "&facets=" + enc(facets) + "&limit=30&index=relevance";
            Map<String,Object> root = getJson(url, null);
            List<Mod> out = new ArrayList<>();
            for (Object o : Json.arr(root.get("hits"))) {
                Map<String,Object> m = Json.obj(o);
                out.add(new Mod(source, str(m.get("project_id")), str(m.get("title")), str(m.get("author")),
                        str(m.get("description")), num(m.get("downloads")), str(m.get("icon_url")),
                        "https://modrinth.com/mod/" + str(m.get("slug"))));
            }
            return out;
        }
        String key = curseKey == null ? "" : curseKey.trim();
        if (key.isEmpty()) throw new IOException("Для CurseForge нужен личный API Key. Укажи его в настройках лаунчера.");
        int loaderId = switch (loader.toLowerCase(Locale.ROOT)) { case "forge" -> 1; case "fabric" -> 4; case "neoforge" -> 6; default -> 0; };
        String url = "https://api.curseforge.com/v1/mods/search?gameId=432&classId=6&pageSize=30&sortField=2&sortOrder=desc"
                + "&searchFilter=" + enc(query) + "&gameVersion=" + enc(mc) + "&modLoaderType=" + loaderId;
        Map<String,Object> root = getJson(url, key);
        List<Mod> out = new ArrayList<>();
        for (Object o : Json.arr(root.get("data"))) {
            Map<String,Object> m = Json.obj(o); Map<String,Object> links = Json.obj(m.get("links"));
            out.add(new Mod(source, str(m.get("id")), str(m.get("name")), authors(m.get("authors")),
                    str(m.get("summary")), num(m.get("downloadCount")), str(Json.obj(m.get("logo")).get("url")),
                    str(links.get("websiteUrl"))));
        }
        return out;
    }

    Path install(Mod mod, Path modsDir, String mc, String loader, String curseKey, Progress progress) throws Exception {
        Files.createDirectories(modsDir);
        Path installed;
        if (mod.source() == Source.MODRINTH) {
            Set<String> installedIds = new HashSet<>();
            installed = installModrinthVersion(mod.id(), null, modsDir, mc, loader, installedIds, progress);
        } else installed = installCurseForge(mod, modsDir, mc, loader, curseKey, progress);
        if (installed != null) rememberInstalled(mod, installed, modsDir, mc, loader);
        return installed;
    }

    private void rememberInstalled(Mod mod, Path file, Path dir, String mc, String loader) throws Exception {
        Path index = dir.resolve(".vron-installed-mods.json");
        List<Object> entries = Files.isRegularFile(index) ? Json.arr(Json.parse(Files.readString(index))) : new ArrayList<>();
        entries.removeIf(o -> mod.id().equals(str(Json.obj(o).get("id"))));
        Map<String,Object> row = new LinkedHashMap<>(); row.put("source", mod.source().name()); row.put("id", mod.id());
        row.put("title", mod.title()); row.put("file", file.getFileName().toString()); row.put("minecraft", mc); row.put("loader", loader);
        entries.add(row); Files.writeString(index, Json.stringify(entries), StandardCharsets.UTF_8);
    }

    /** Checks the launcher-managed installed mods and replaces them with the newest compatible file. */
    int updateInstalled(Path modsDir, String mc, String loader, String curseKey, Progress progress) throws Exception {
        Path index = modsDir.resolve(".vron-installed-mods.json"); if (!Files.isRegularFile(index)) return 0;
        List<Object> entries = Json.arr(Json.parse(Files.readString(index))); int updated = 0;
        for (Object obj : entries) {
            Map<String,Object> row = Json.obj(obj); String id = str(row.get("id"));
            if (!mc.equals(str(row.get("minecraft"))) || !loader.equalsIgnoreCase(str(row.get("loader")))) continue;
            Source source = Source.valueOf(str(row.get("source"))); String oldName = str(row.get("file"));
            String url = null, name = null, hash = null;
            if (source == Source.MODRINTH) {
                String endpoint = "https://api.modrinth.com/v2/project/" + enc(id) + "/version?loaders=" + enc("[\""+loader.toLowerCase(Locale.ROOT)+"\"]") + "&game_versions=" + enc("[\""+mc+"\"]");
                List<Object> vs = Json.arr(getJsonArray(endpoint)); if (vs.isEmpty()) continue;
                Map<String,Object> v = Json.obj(vs.get(0)); List<Object> fs = Json.arr(v.get("files")); if (fs.isEmpty()) continue;
                Map<String,Object> f = fs.stream().map(Json::obj).filter(x -> Boolean.TRUE.equals(x.get("primary"))).findFirst().orElse(Json.obj(fs.get(0)));
                url=str(f.get("url")); name=safeFile(str(f.get("filename"))); Map<String,Object> hashes=Json.obj(f.get("hashes"));
                hash=!str(hashes.get("sha512")).isBlank()?"SHA-512:"+str(hashes.get("sha512")):"SHA-1:"+str(hashes.get("sha1"));
            } else {
                if (curseKey == null || curseKey.isBlank()) continue;
                int loaderId=switch(loader.toLowerCase(Locale.ROOT)){case "forge"->1;case "fabric"->4;case "neoforge"->6;default->0;};
                List<Object> fs=Json.arr(getJson("https://api.curseforge.com/v1/mods/"+enc(id)+"/files?gameVersion="+enc(mc)+"&modLoaderType="+loaderId+"&pageSize=1&index=0",curseKey).get("data"));
                if(fs.isEmpty())continue;Map<String,Object> f=Json.obj(fs.get(0));url=str(f.get("downloadUrl"));name=safeFile(str(f.get("fileName")));
                if(url.isBlank()){url=str(getJson("https://api.curseforge.com/v1/mods/"+enc(id)+"/files/"+enc(str(f.get("id")))+"/download-url",curseKey).get("data"));}
            }
            if(url==null||url.isBlank()||name==null||name.isBlank())continue;
            Path temp=modsDir.resolve(name+".update");progress.update("Проверка обновления: "+str(row.get("title")));
            download(url,temp,hash);Path old=oldName.isBlank()?null:modsDir.resolve(oldName);
            if(old!=null&&Files.isRegularFile(old)&&digest(old,"SHA-256").equals(digest(temp,"SHA-256"))){Files.deleteIfExists(temp);continue;}
            Path target=modsDir.resolve(name);Files.move(temp,target,StandardCopyOption.REPLACE_EXISTING);
            if(old!=null&&!old.equals(target))Files.deleteIfExists(old);row.put("file",name);updated++;
        }
        Files.writeString(index,Json.stringify(entries),StandardCharsets.UTF_8);return updated;
    }

    private Path installModrinthVersion(String projectId, String versionId, Path dir, String mc, String loader,
                                        Set<String> installed, Progress p) throws Exception {
        String identity = versionId == null ? projectId : versionId;
        if (!installed.add(identity)) return null;
        Map<String,Object> version;
        if (versionId != null) version = getJson("https://api.modrinth.com/v2/version/" + enc(versionId), null);
        else {
            String url = "https://api.modrinth.com/v2/project/" + enc(projectId) + "/version?loaders="
                    + enc("[\"" + loader.toLowerCase(Locale.ROOT) + "\"]") + "&game_versions=" + enc("[\"" + mc + "\"]");
            List<Object> versions = Json.arr(getJsonArray(url));
            if (versions.isEmpty()) throw new IOException("У мода нет версии для Minecraft " + mc + " / " + loader);
            version = Json.obj(versions.get(0));
        }
        // Install required project/version dependencies first.
        for (Object depObj : Json.arr(version.get("dependencies"))) {
            Map<String,Object> dep = Json.obj(depObj);
            if (!"required".equals(str(dep.get("dependency_type")))) continue;
            String depVersion = str(dep.get("version_id")); String depProject = str(dep.get("project_id"));
            if (!depVersion.isBlank()) installModrinthVersion(depProject, depVersion, dir, mc, loader, installed, p);
            else if (!depProject.isBlank()) installModrinthVersion(depProject, null, dir, mc, loader, installed, p);
        }
        List<Object> files = Json.arr(version.get("files"));
        if (files.isEmpty()) throw new IOException("Modrinth не предоставил файл для выбранной версии");
        Map<String,Object> file = files.stream().map(Json::obj).filter(f -> Boolean.TRUE.equals(f.get("primary"))).findFirst().orElse(Json.obj(files.get(0)));
        String fileName = safeFile(str(file.get("filename")));
        String url = str(file.get("url")); if (url.isBlank()) throw new IOException("В Modrinth версии отсутствует URL файла");
        String sha512 = str(Json.obj(file.get("hashes")).get("sha512"));
        String sha1 = str(Json.obj(file.get("hashes")).get("sha1"));
        Path target = uniqueTarget(dir, fileName); p.update("Загрузка Modrinth: " + fileName);
        download(url, target, sha512.isBlank() ? "SHA-1:" + sha1 : "SHA-512:" + sha512);
        return target;
    }

    private Path installCurseForge(Mod mod, Path dir, String mc, String loader, String key, Progress p) throws Exception {
        if (key == null || key.isBlank()) throw new IOException("Не задан CurseForge API Key");
        int loaderId = switch (loader.toLowerCase(Locale.ROOT)) { case "forge" -> 1; case "fabric" -> 4; case "neoforge" -> 6; default -> 0; };
        String filesUrl = "https://api.curseforge.com/v1/mods/" + enc(mod.id()) + "/files?gameVersion=" + enc(mc)
                + "&modLoaderType=" + loaderId + "&pageSize=50&index=0";
        List<Object> files = Json.arr(getJson(filesUrl, key).get("data"));
        if (files.isEmpty()) throw new IOException("CurseForge не нашёл совместимый файл для " + mc + " / " + loader);
        Map<String,Object> file = Json.obj(files.get(0));
        if (!Boolean.TRUE.equals(file.get("isAvailable"))) throw new IOException("Файл CurseForge недоступен для загрузки");
        String downloadUrl = str(file.get("downloadUrl"));
        if (downloadUrl.isBlank()) {
            Map<String,Object> result = getJson("https://api.curseforge.com/v1/mods/" + enc(mod.id()) + "/files/" + enc(str(file.get("id"))) + "/download-url", key);
            downloadUrl = str(result.get("data"));
        }
        if (downloadUrl.isBlank()) throw new IOException("CurseForge не вернул прямую ссылку на файл");
        String name = safeFile(str(file.get("fileName"))); Path target = uniqueTarget(dir, name);
        p.update("Загрузка CurseForge: " + name);
        download(downloadUrl, target, null);
        return target;
    }

    private Object getJsonArray(String url) throws Exception {
        HttpRequest req = request(url, null).GET().build(); HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
        check(r.statusCode(), r.body()); return Json.parse(r.body());
    }
    private Map<String,Object> getJson(String url, String key) throws Exception {
        HttpRequest req = request(url, key).GET().build(); HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
        check(r.statusCode(), r.body()); return Json.obj(Json.parse(r.body()));
    }
    private HttpRequest.Builder request(String url, String key) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60)).header("User-Agent", UA).header("Accept", "application/json");
        if (key != null && !key.isBlank()) b.header("x-api-key", key); return b;
    }
    private void check(int code, String body) throws IOException { if (code < 200 || code >= 300) throw new IOException("Marketplace HTTP " + code + ": " + body.substring(0, Math.min(500, body.length()))); }
    private void download(String url, Path target, String expected) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(4)).header("User-Agent", UA).GET().build();
        HttpResponse<Path> r = http.send(req, HttpResponse.BodyHandlers.ofFile(target));
        if (r.statusCode() < 200 || r.statusCode() >= 300) { Files.deleteIfExists(target); throw new IOException("Ошибка загрузки файла: HTTP " + r.statusCode()); }
        if (Files.size(target) < 4) { Files.deleteIfExists(target); throw new IOException("Скачанный файл слишком мал и не похож на JAR"); }
        if (expected != null && !expected.endsWith(":")) {
            String[] parts = expected.split(":", 2); String actual = digest(target, parts[0]);
            if (!actual.equalsIgnoreCase(parts[1])) { Files.deleteIfExists(target); throw new IOException("Контрольная сумма мода не совпала: " + target.getFileName()); }
        }
    }
    private String digest(Path path, String algorithm) throws Exception { MessageDigest md = MessageDigest.getInstance(algorithm); try(var in=Files.newInputStream(path)){byte[] b=new byte[65536];for(int n;(n=in.read(b))>0;)md.update(b,0,n);}return HexFormat.of().formatHex(md.digest()); }
    private Path uniqueTarget(Path dir, String name) { Path p=dir.resolve(name); if(!Files.exists(p))return p; String base=name.endsWith(".jar")?name.substring(0,name.length()-4):name; return dir.resolve(base+"-"+System.currentTimeMillis()+".jar"); }
    private String safeFile(String n) throws IOException { String s=Path.of(n.isBlank()?"mod.jar":n).getFileName().toString().replaceAll("[^a-zA-Z0-9._+() -]","_"); if(!s.toLowerCase(Locale.ROOT).endsWith(".jar"))throw new IOException("Загружаемый файл не является JAR-модом: "+s); return s; }
    private String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
    private String jsonSafe(String s) { return s.replace("\\", "").replace("\"", ""); }
    private String authors(Object value) { List<String> names=new ArrayList<>(); for(Object a:Json.arr(value)){String n=str(Json.obj(a).get("name"));if(!n.isBlank())names.add(n);} return names.isEmpty()?"CurseForge author":String.join(", ",names); }
    private String str(Object o) { return o == null ? "" : String.valueOf(o); }
    private long num(Object o) { return o instanceof Number n ? n.longValue() : 0; }
}
