package com.vron.client;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.zip.*;

/** Real Mojang/Fabric installer and game process integration. */
final class LauncherCore {
    static final String MANIFEST="https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
    final Path gameDir; final HttpClient http;
    LauncherCore(Path gameDir){this.gameDir=gameDir;this.http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(25)).followRedirects(HttpClient.Redirect.NORMAL).build();}
    interface Progress { void update(String message); }
    private void say(Progress p,String s){if(p!=null)p.update(s);}
    private String get(String url)throws Exception{return request(url).body();}
    private HttpResponse<String> request(String url)throws Exception{
        HttpResponse<String> r=http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(3)).header("User-Agent","VRON-CLIENT/2.2 (Java launcher)").GET().build(),HttpResponse.BodyHandlers.ofString());
        if(r.statusCode()/100!=2)throw new IOException("HTTP "+r.statusCode()+" при запросе "+url+": "+r.body());
        return r;
    }
    private Map<String,Object> getJson(String url)throws Exception{HttpResponse<String> r=request(url);if(r.statusCode()/100!=2)throw new IOException("HTTP "+r.statusCode()+" for "+url);return Json.obj(Json.parse(r.body()));}
    private void download(String url,Path dest,String expectedSha1)throws Exception{
        if(Files.isRegularFile(dest)&&(expectedSha1==null||expectedSha1.isBlank()||expectedSha1.equalsIgnoreCase(sha1(dest))))return;
        Files.createDirectories(dest.getParent());Path tmp=dest.resolveSibling(dest.getFileName()+".part");
        Exception last=null;
        for(int attempt=1;attempt<=3;attempt++){
            try{
                HttpResponse<InputStream> r=http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(10)).header("User-Agent","VRON-CLIENT/2.2").GET().build(),HttpResponse.BodyHandlers.ofInputStream());
                if(r.statusCode()/100!=2){r.body().close();throw new IOException("Download HTTP "+r.statusCode()+": "+url);}
                try(InputStream in=r.body();OutputStream out=Files.newOutputStream(tmp,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING)){in.transferTo(out);}
                if(expectedSha1!=null&&!expectedSha1.isBlank()&&!expectedSha1.equalsIgnoreCase(sha1(tmp)))throw new IOException("SHA-1 mismatch: "+url);
                try{Files.move(tmp,dest,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}catch(AtomicMoveNotSupportedException e){Files.move(tmp,dest,StandardCopyOption.REPLACE_EXISTING);}
                return;
            }catch(Exception e){last=e;Files.deleteIfExists(tmp);if(attempt<3)try{Thread.sleep(700L*attempt);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw interrupted;}}
        }
        throw new IOException("Не удалось скачать файл после 3 попыток: "+url,last);
    }
    private String sha1(Path p)throws Exception{MessageDigest md=MessageDigest.getInstance("SHA-1");try(InputStream in=Files.newInputStream(p)){byte[] b=new byte[65536];for(int n;(n=in.read(b))>0;)md.update(b,0,n);}return HexFormat.of().formatHex(md.digest());}
    Map<String,Object> manifest()throws Exception{return getJson(MANIFEST);}
    Map<String,Object> versionInfo(String id)throws Exception{
        Map<String,Object> m=manifest();for(Object o:Json.arr(m.get("versions"))){Map<String,Object> v=Json.obj(o);if(id.equals(v.get("id")))return getJson(String.valueOf(v.get("url")));}throw new IOException("Версия отсутствует в официальном каталоге Mojang: "+id);
    }
    void install(String id,Progress p)throws Exception{
        Files.createDirectories(gameDir);say(p,"Проверка метаданных Minecraft "+id);
        Path local=gameDir.resolve("versions").resolve(id).resolve(id+".json");Map<String,Object> meta=Files.isRegularFile(local)?Json.obj(Json.parse(Files.readString(local))):versionInfo(id);
        installMetadata(meta,p,new HashSet<>());ensureRuntime(runtimeMetadata(meta),p);say(p,"Установка Minecraft "+id+" завершена");
    }
    private Map<String,Object> runtimeMetadata(Map<String,Object> meta)throws Exception{
        if(meta.get("javaVersion")!=null)return meta;String parent=str(meta.get("inheritsFrom"));if(parent.isBlank())return meta;
        Path f=gameDir.resolve("versions").resolve(parent).resolve(parent+".json");return Files.isRegularFile(f)?runtimeMetadata(Json.obj(Json.parse(Files.readString(f)))):meta;
    }
    private void installMetadata(Map<String,Object> meta,Progress p,Set<String> seen)throws Exception{
        String id=String.valueOf(meta.get("id"));if(!seen.add(id))throw new IOException("Циклическое наследование версии: "+id);
        Object parent=meta.get("inheritsFrom");if(parent!=null){Path parentJson=gameDir.resolve("versions").resolve(String.valueOf(parent)).resolve(String.valueOf(parent)+".json");if(!Files.exists(parentJson))installMetadata(versionInfo(String.valueOf(parent)),p,seen);}
        Path vdir=gameDir.resolve("versions").resolve(id);Files.createDirectories(vdir);Path json=vdir.resolve(id+".json");Files.writeString(json,Json.stringify(meta),StandardCharsets.UTF_8);
        Map<String,Object> downloads=Json.obj(meta.get("downloads"));Map<String,Object> client=Json.obj(downloads.get("client"));if(client.get("url")!=null){say(p,"Загрузка клиента "+id);download(String.valueOf(client.get("url")),vdir.resolve(id+".jar"),str(client.get("sha1")));}else if(parent==null)throw new IOException("В метаданных нет client download для "+id);
        List<Map<String,Object>> libs=effectiveLibraries(meta);int total=libs.size(),done=0;for(Map<String,Object> lib:libs){done++;say(p,"Библиотеки "+done+" / "+total);downloadLibrary(lib);}
        Map<String,Object> ai=Json.obj(meta.get("assetIndex"));if(ai.get("url")!=null){String aid=String.valueOf(ai.get("id"));Path idx=gameDir.resolve("assets/indexes").resolve(aid+".json");download(String.valueOf(ai.get("url")),idx,str(ai.get("sha1")));Map<String,Object> index=Json.obj(Json.parse(Files.readString(idx)));Map<String,Object> objects=Json.obj(index.get("objects"));downloadAssets(objects,p);materializeLegacyAssets(index,aid,objects,p);}
        Map<String,Object> logging=Json.obj(meta.get("logging"));Map<String,Object> clientLog=Json.obj(logging.get("client"));Map<String,Object> file=Json.obj(clientLog.get("file"));if(file.get("url")!=null)download(str(file.get("url")),gameDir.resolve("assets/log_configs").resolve(str(file.get("id"))),str(file.get("sha1")));
    }
    private List<Map<String,Object>> effectiveLibraries(Map<String,Object> meta)throws Exception{
        List<Map<String,Object>> out=new ArrayList<>();Object parent=meta.get("inheritsFrom");if(parent!=null){Path f=gameDir.resolve("versions").resolve(str(parent)).resolve(str(parent)+".json");if(Files.exists(f)){Map<String,Object> pm=Json.obj(Json.parse(Files.readString(f)));out.addAll(effectiveLibraries(pm));}}
        for(Object o:Json.arr(meta.get("libraries"))){Map<String,Object> lib=Json.obj(o);if(allowed(lib))out.add(lib);}return out;
    }
    private boolean allowed(Map<String,Object> lib){Object rules=lib.get("rules");if(!(rules instanceof List<?>))return true;boolean allow=false;for(Object ro:Json.arr(rules)){Map<String,Object> r=Json.obj(ro);if(ruleMatch(Json.obj(r.get("os")),Json.obj(r.get("features"))))allow="allow".equals(r.get("action"));}return allow;}
    private boolean ruleMatch(Map<String,Object> os,Map<String,Object> features){String name=System.getProperty("os.name","").toLowerCase(Locale.ROOT);String osName=name.contains("win")?"windows":name.contains("mac")?"osx":"linux";if(os.get("name")!=null&&!osName.equals(os.get("name")))return false;if(os.get("arch")!=null){String actual=System.getProperty("os.arch","").toLowerCase(Locale.ROOT),wanted=str(os.get("arch")).toLowerCase(Locale.ROOT);if(wanted.equals("x86_64")&&!(actual.equals("amd64")||actual.equals("x86_64")))return false;if(wanted.equals("x86")&&!(actual.equals("x86")||actual.equals("i386")||actual.equals("i686")))return false;if(!wanted.equals("x86_64")&&!wanted.equals("x86")&&!actual.matches(wanted))return false;}if(os.get("version")!=null&&!System.getProperty("os.version").matches(str(os.get("version"))))return false;Map<String,Boolean> supported=Map.of("is_demo_user",false,"has_custom_resolution",false,"has_quick_plays_support",false,"is_quick_play_singleplayer",false,"is_quick_play_multiplayer",false,"is_quick_play_realms",false);for(String k:features.keySet()){Boolean actual=supported.get(k);if(actual==null||!Objects.equals(actual,features.get(k)))return false;}return true;}
    private void downloadLibrary(Map<String,Object> lib)throws Exception{Map<String,Object> d=Json.obj(lib.get("downloads"));Map<String,Object> a=Json.obj(d.get("artifact"));if(a.get("url")!=null){String path=str(a.get("path"));download(str(a.get("url")),gameDir.resolve("libraries").resolve(path),str(a.get("sha1")));}else if(lib.get("name")!=null){String[] c=str(lib.get("name")).split(":");if(c.length>=3){String group=c[0].replace('.','/'),artifact=c[1],version=c[2],classifier=c.length>3?"-"+c[3]:"";String ext="jar";if(version.contains("@")){String[] vv=version.split("@",2);version=vv[0];ext=vv[1];}String path=group+"/"+artifact+"/"+version+"/"+artifact+"-"+version+classifier+"."+ext;String base=str(lib.getOrDefault("url","https://libraries.minecraft.net/"));if(!base.endsWith("/"))base+="/";download(base+path,gameDir.resolve("libraries").resolve(path),null);}}
        Map<String,Object> natives=Json.obj(lib.get("natives"));String os=System.getProperty("os.name","").toLowerCase(Locale.ROOT);String key=os.contains("win")?"windows":os.contains("mac")?"osx":"linux";Object classifier=natives.get(key);if(classifier!=null){String cl=str(classifier).replace("${arch}",System.getProperty("os.arch").contains("64")?"64":"32");Map<String,Object> c=Json.obj(Json.obj(d.get("classifiers")).get(cl));Path jar=null;if(c.get("url")!=null){jar=gameDir.resolve("libraries").resolve(str(c.get("path")));download(str(c.get("url")),jar,str(c.get("sha1")));}else if(lib.get("name")!=null){String[] parts=str(lib.get("name")).split(":");if(parts.length>=3){String group=parts[0].replace('.','/'),artifact=parts[1],version=parts[2];String path=group+"/"+artifact+"/"+version+"/"+artifact+"-"+version+"-"+cl+".jar";String base=str(lib.getOrDefault("url","https://libraries.minecraft.net/"));if(!base.endsWith("/"))base+="/";jar=gameDir.resolve("libraries").resolve(path);download(base+path,jar,null);}}if(jar!=null){Path nativesDir=gameDir.resolve("versions/natives").resolve(str(lib.getOrDefault("name","natives")).replaceAll("[^a-zA-Z0-9._-]","_"));Files.createDirectories(nativesDir);extractNatives(jar,nativesDir);}}}
    private Path libraryArtifactPath(Map<String,Object> lib){Map<String,Object> artifact=Json.obj(Json.obj(lib.get("downloads")).get("artifact"));if(artifact.get("path")!=null)return gameDir.resolve("libraries").resolve(str(artifact.get("path")));String name=str(lib.get("name"));if(name.isBlank())return null;String[] c=name.split(":");if(c.length<3)return null;String group=c[0].replace('.','/'),artifactName=c[1],version=c[2],classifier=c.length>3?"-"+c[3]:"",ext="jar";if(version.contains("@")){String[] vv=version.split("@",2);version=vv[0];ext=vv[1];}return gameDir.resolve("libraries").resolve(group+"/"+artifactName+"/"+version+"/"+artifactName+"-"+version+classifier+"."+ext);}
    private Path nativeDirectory(Map<String,Object> lib){
        Map<String,Object> natives=Json.obj(lib.get("natives"));String os=System.getProperty("os.name","").toLowerCase(Locale.ROOT);String key=os.contains("win")?"windows":os.contains("mac")?"osx":"linux";Object classifier=natives.get(key);if(classifier==null)return null;
        String cl=str(classifier).replace("${arch}",System.getProperty("os.arch","").contains("64")?"64":"32");return gameDir.resolve("versions/natives").resolve(str(lib.getOrDefault("name","natives")).replaceAll("[^a-zA-Z0-9._-]","_"));
    }
    private void extractNatives(Path jar,Path out)throws Exception{try(ZipInputStream z=new ZipInputStream(Files.newInputStream(jar))){for(ZipEntry e;(e=z.getNextEntry())!=null;){String n=e.getName();if(e.isDirectory()||n.startsWith("META-INF/")||n.contains("/"))continue;Path dest=out.resolve(Path.of(n).getFileName()).normalize();if(!dest.startsWith(out))throw new IOException("Unsafe native archive entry: "+n);Files.copy(z,dest,StandardCopyOption.REPLACE_EXISTING);}}}
    private void downloadAssets(Map<String,Object> objects,Progress p)throws Exception{
        ExecutorService pool=Executors.newFixedThreadPool(8);List<Future<?>> jobs=new ArrayList<>();int total=objects.size();java.util.concurrent.atomic.AtomicInteger done=new java.util.concurrent.atomic.AtomicInteger();
        try{for(Object ov:objects.values()){Map<String,Object> ob=Json.obj(ov);String hash=str(ob.get("hash"));if(hash.length()<2)continue;jobs.add(pool.submit(()->{try{Path dest=gameDir.resolve("assets/objects").resolve(hash.substring(0,2)).resolve(hash);download("https://resources.download.minecraft.net/"+hash.substring(0,2)+"/"+hash,dest,hash);int n=done.incrementAndGet();if(n%250==0)say(p,"Ресурсы игры: "+n+" / "+total);}catch(Exception e){throw new CompletionException(e);}}));}for(Future<?> f:jobs)f.get();}finally{pool.shutdownNow();}
    }
    private void materializeLegacyAssets(Map<String,Object> index,String assetId,Map<String,Object> objects,Progress p)throws Exception{
        if(Boolean.TRUE.equals(index.get("virtual"))){Path virtual=gameDir.resolve("assets/virtual").resolve(assetId);Files.createDirectories(virtual);for(var e:objects.entrySet()){Map<String,Object> ob=Json.obj(e.getValue());String hash=str(ob.get("hash"));if(hash.length()<2)continue;Path src=gameDir.resolve("assets/objects").resolve(hash.substring(0,2)).resolve(hash);Path dst=virtual.resolve(e.getKey()).normalize();if(!dst.startsWith(virtual))throw new IOException("Unsafe virtual asset path: "+e.getKey());if(Files.isRegularFile(src)){Files.createDirectories(dst.getParent());if(!Files.exists(dst))Files.copy(src,dst);}}}
        if(Boolean.TRUE.equals(index.get("map_to_resources"))){Path resources=gameDir.resolve("resources");Files.createDirectories(resources);for(var e:objects.entrySet()){Map<String,Object> ob=Json.obj(e.getValue());String hash=str(ob.get("hash"));if(hash.length()<2)continue;Path src=gameDir.resolve("assets/objects").resolve(hash.substring(0,2)).resolve(hash);Path dst=resources.resolve(e.getKey()).normalize();if(!dst.startsWith(resources))throw new IOException("Unsafe resource path: "+e.getKey());if(Files.isRegularFile(src)){Files.createDirectories(dst.getParent());if(!Files.exists(dst))Files.copy(src,dst);}}}
    }
    private String runtimePlatform(){String os=System.getProperty("os.name","").toLowerCase(Locale.ROOT),arch=System.getProperty("os.arch","").toLowerCase(Locale.ROOT);if(os.contains("win"))return arch.contains("aarch64")||arch.contains("arm64")?"windows-arm64":arch.contains("86")&&!arch.contains("64")?"windows-x86":"windows-x64";if(os.contains("mac"))return arch.contains("aarch64")||arch.contains("arm64")?"mac-os-arm64":"mac-os";if(arch.contains("aarch64")||arch.contains("arm64"))return "linux-arm64";return arch.contains("86")&&!arch.contains("64")?"linux-i386":"linux";}
    private void ensureRuntime(Map<String,Object> meta,Progress p)throws Exception{
        Map<String,Object> jv=Json.obj(meta.get("javaVersion"));String component=str(jv.getOrDefault("component","jre-legacy"));String platform=runtimePlatform();Map<String,Object> all=getJson("https://piston-meta.mojang.com/v1/products/java-runtime/2ec0cc96c44e5a76b9c8b7c39df7210883d12871/all.json");List<Object> releases=Json.arr(Json.obj(all.get(platform)).get(component));if(releases.isEmpty()&&component.equals("jre-legacy")){component="jre-legacy";releases=Json.arr(Json.obj(all.get(platform)).get(component));}if(releases.isEmpty()){say(p,"Для " + platform + " нет отдельного Mojang runtime " + component + "; будет использована установленная Java.");return;}
        Map<String,Object> chosen=Json.obj(releases.get(0)),manifestInfo=Json.obj(chosen.get("manifest"));String release=str(Json.obj(chosen.get("version")).get("name"));Path root=gameDir.resolve("runtime").resolve(component).resolve(platform).resolve(release);Path marker=root.resolve(".vron-runtime-ready");if(Files.exists(marker))return;say(p,"Загрузка Java Runtime "+release+" ("+component+")");Map<String,Object> manifest=getJson(str(manifestInfo.get("url")));Map<String,Object> files=Json.obj(manifest.get("files"));ExecutorService pool=Executors.newFixedThreadPool(8);List<Future<?>> tasks=new ArrayList<>();Map<Path,String> links=new LinkedHashMap<>();try{for(var e:files.entrySet()){String rel=e.getKey();Map<String,Object> f=Json.obj(e.getValue());Path dest=root.resolve(rel).normalize();if(!dest.startsWith(root))throw new IOException("Unsafe runtime path: "+rel);String type=str(f.get("type"));if(type.equals("directory")){Files.createDirectories(dest);continue;}if(type.equals("link")){links.put(dest,str(f.get("target")));continue;}Map<String,Object> raw=Json.obj(Json.obj(f.get("downloads")).get("raw"));if(raw.get("url")==null)continue;tasks.add(pool.submit(()->{try{download(str(raw.get("url")),dest,str(raw.get("sha1")));if(Boolean.TRUE.equals(f.get("executable")))dest.toFile().setExecutable(true,false);}catch(Exception ex){throw new CompletionException(ex);}}));}for(Future<?> t:tasks)t.get();}finally{pool.shutdownNow();}for(var link:links.entrySet()){Path dest=link.getKey(),target=root.resolve(link.getValue()).normalize();Files.createDirectories(dest.getParent());try{Files.createSymbolicLink(dest,target);}catch(Exception ignored){if(Files.isRegularFile(target))Files.copy(target,dest,StandardCopyOption.REPLACE_EXISTING);}}Files.createDirectories(root);Files.writeString(marker,release,StandardCharsets.UTF_8);say(p,"Java Runtime установлен: "+release);
    }
    private Path runtimeExecutable(Map<String,Object> meta)throws Exception{
        Map<String,Object> jv=Json.obj(meta.get("javaVersion"));String component=str(jv.getOrDefault("component","jre-legacy"));Path dir=gameDir.resolve("runtime").resolve(component).resolve(runtimePlatform());if(Files.isDirectory(dir)){try(var walk=Files.walk(dir,7)){Optional<Path> found=walk.filter(Files::isRegularFile).filter(x->{String n=x.getFileName().toString().toLowerCase(Locale.ROOT);return isWindows()?n.equals("java.exe"):n.equals("java");}).filter(x->x.toString().contains("/bin/")||x.toString().contains("\\bin\\")).findFirst();if(found.isPresent())return found.get();}}return Path.of(System.getProperty("java.home"),"bin",isWindows()?"java.exe":"java");
    }
    private String str(Object o){return o==null?"":String.valueOf(o);}

    Process launch(String id,String player,int ram,String accessToken,String uuid,Progress p)throws Exception{return launch(id,player,ram,accessToken,uuid,"", "",p);}
    Process launch(String id,String player,int ram,String accessToken,String uuid,String xuid,String clientId,Progress p)throws Exception{
        Path jf=gameDir.resolve("versions").resolve(id).resolve(id+".json");if(!Files.exists(jf))throw new IOException("Версия не установлена: "+id);
        Map<String,Object> meta=Json.obj(Json.parse(Files.readString(jf)));String parent=str(meta.get("inheritsFrom"));Map<String,Object> base=meta;if(!parent.isBlank()){Path pf=gameDir.resolve("versions").resolve(parent).resolve(parent+".json");if(Files.exists(pf))base=Json.obj(Json.parse(Files.readString(pf)));}
        List<Map<String,Object>> libs=effectiveLibraries(meta);LinkedHashSet<String> cp=new LinkedHashSet<>();LinkedHashSet<String> nativeDirs=new LinkedHashSet<>();for(Map<String,Object> lib:libs){Path artifact=libraryArtifactPath(lib);if(artifact!=null){if(!Files.isRegularFile(artifact))throw new IOException("Не найдена библиотека "+artifact+". Повтори установку версии.");cp.add(artifact.toString());}Path nd=nativeDirectory(lib);if(nd!=null&&Files.isDirectory(nd))nativeDirs.add(nd.toString());}
        Path client=gameDir.resolve("versions").resolve(parent.isBlank()?id:parent).resolve((parent.isBlank()?id:parent)+".jar");if(!Files.exists(client))client=gameDir.resolve("versions").resolve(id).resolve(id+".jar");if(!Files.exists(client))throw new IOException("Не найден client JAR: "+client);cp.add(client.toString());
        String os=System.getProperty("os.name","").toLowerCase(Locale.ROOT);String natives=String.join(File.pathSeparator,nativeDirs);String sep=File.pathSeparator;
        Map<String,String> vars=new HashMap<>();vars.put("${auth_player_name}",player);vars.put("${version_name}",id);vars.put("${game_directory}",gameDir.toString());vars.put("${assets_root}",gameDir.resolve("assets").toString());String assetId=str(Json.obj(meta.get("assetIndex")).getOrDefault("id",Json.obj(base.get("assetIndex")).get("id")));vars.put("${game_assets}",gameDir.resolve("assets/virtual").resolve(assetId).toString());vars.put("${assets_index_name}",assetId);vars.put("${auth_uuid}",uuid==null||uuid.isBlank()?UUID.nameUUIDFromBytes(("OfflinePlayer:"+player).getBytes(StandardCharsets.UTF_8)).toString().replace("-",""):uuid);vars.put("${auth_access_token}",accessToken==null?"0":accessToken);vars.put("${auth_session}",accessToken==null?"0":accessToken);vars.put("${auth_xuid}",xuid==null?"":xuid);vars.put("${clientid}",clientId==null?"":clientId);vars.put("${user_type}",accessToken==null?"legacy":"msa");vars.put("${version_type}",str(meta.getOrDefault("type",base.getOrDefault("type","release"))));vars.put("${natives_directory}",natives);vars.put("${launcher_name}","VRON CLIENT");vars.put("${launcher_version}","2.2");vars.put("${classpath}",String.join(sep,cp));vars.put("${classpath_separator}",sep);vars.put("${library_directory}",gameDir.resolve("libraries").toString());vars.put("${resolution_width}","1280");vars.put("${resolution_height}","720");
        List<String> args=new ArrayList<>();String java=runtimeExecutable(meta.get("javaVersion")==null?base:meta).toString();args.add(java);if(os.contains("mac"))args.add("-XstartOnFirstThread");args.add("-Xms512M");args.add("-Xmx"+ram+"M");args.add("-Djava.library.path="+natives);args.add("-Dorg.lwjgl.librarypath="+natives);args.add("-Dminecraft.launcher.brand=VRON CLIENT");args.add("-Dminecraft.launcher.version=2.2");
        Object jvm=Json.obj(meta.get("arguments")).get("jvm");if(jvm==null)jvm=Json.obj(base.get("arguments")).get("jvm");if(jvm instanceof List<?>){for(Object a:Json.arr(jvm))appendArg(args,a,vars,meta);}else{args.add("-cp");args.add(String.join(sep,cp));}
        String main=str(meta.getOrDefault("mainClass",base.get("mainClass")));if(main.isBlank())throw new IOException("В метаданных отсутствует mainClass");args.add(main);
        Object game=Json.obj(meta.get("arguments")).get("game");if(game==null)game=Json.obj(base.get("arguments")).get("game");if(game instanceof List<?>)for(Object a:Json.arr(game))appendArg(args,a,vars,meta);else{String legacy=str(meta.getOrDefault("minecraftArguments",base.get("minecraftArguments")));for(String a:legacy.split(" "))if(!a.isBlank())args.add(sub(a,vars));}
        say(p,"Запуск процесса Minecraft "+id);ProcessBuilder pb=new ProcessBuilder(args).directory(gameDir.toFile()).redirectErrorStream(true);Process proc=pb.start();Thread logThread=new Thread(()->{try(BufferedReader r=new BufferedReader(new InputStreamReader(proc.getInputStream(),StandardCharsets.UTF_8))){for(String line;(line=r.readLine())!=null;)say(p,"[GAME] "+line);}catch(Exception ignored){}});logThread.setDaemon(true);logThread.start();return proc;
    }
    private void appendArg(List<String> out,Object value,Map<String,String> vars,Map<String,Object> meta){if(value instanceof String){out.add(sub((String)value,vars));return;}Map<String,Object> o=Json.obj(value);List<Object> rules=Json.arr(o.get("rules"));boolean ok=rules.isEmpty();for(Object r:rules){Map<String,Object> rr=Json.obj(r);if(ruleMatch(Json.obj(rr.get("os")),Json.obj(rr.get("features"))))ok="allow".equals(rr.get("action"));}if(!ok)return;Object v=o.get("value");if(v instanceof List<?>)for(Object x:Json.arr(v))out.add(sub(str(x),vars));else if(v!=null)out.add(sub(str(v),vars));}
    private String sub(String s,Map<String,String> vars){for(var e:vars.entrySet())s=s.replace(e.getKey(),e.getValue());return s;}

    static List<String> fabricLoaderVersions(String mc)throws Exception{HttpClient c=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();String u="https://meta.fabricmc.net/v2/versions/loader/"+URLEncoder.encode(mc,StandardCharsets.UTF_8);HttpResponse<String> r=c.send(HttpRequest.newBuilder(URI.create(u)).timeout(Duration.ofSeconds(40)).header("User-Agent","VRON-CLIENT/2.2").GET().build(),HttpResponse.BodyHandlers.ofString());if(r.statusCode()/100!=2)throw new IOException("Fabric Metadata HTTP "+r.statusCode()+": "+r.body());List<String> versions=new ArrayList<>();for(Object o:Json.arr(Json.parse(r.body()))){String v=String.valueOf(Json.obj(Json.obj(o).get("loader")).get("version"));if(!v.isBlank())versions.add(v);}if(versions.isEmpty())throw new IOException("Для Minecraft "+mc+" нет доступного Fabric Loader");return versions;}
    static String latestFabricLoader(String mc)throws Exception{return fabricLoaderVersions(mc).get(0);}
    String installFabric(String mc,String loader,Progress p)throws Exception{
        String v=loader==null||loader.isBlank()?latestFabricLoader(mc):loader;
        List<Object> arr=Json.arr(Json.parse(get("https://meta.fabricmc.net/v2/versions/installer")));
        if(arr.isEmpty())throw new IOException("Fabric Installer API вернул пустой список");
        String iv=str(Json.obj(arr.get(0)).get("version"));if(iv.isBlank())throw new IOException("Fabric API не вернул версию Installer");
        String url="https://maven.fabricmc.net/net/fabricmc/fabric-installer/"+iv+"/fabric-installer-"+iv+".jar";
        Path installer=gameDir.resolve("launcher-cache/fabric-installer-"+iv+".jar");say(p,"Загрузка Fabric Installer "+iv);download(url,installer,null);
        say(p,"Установка Fabric Loader "+v+" для Minecraft "+mc);
        Path java=Path.of(System.getProperty("java.home"),"bin",isWindows()?"java.exe":"java");
        Process pr=new ProcessBuilder(java.toString(),"-jar",installer.toString(),"client","-dir",gameDir.toString(),"-mcversion",mc,"-loader",v).redirectErrorStream(true).start();
        StringBuilder output=new StringBuilder();Thread reader=new Thread(()->{try(BufferedReader r=new BufferedReader(new InputStreamReader(pr.getInputStream(),StandardCharsets.UTF_8))){for(String line;(line=r.readLine())!=null;)output.append(line).append('\n');}catch(Exception ignored){}});reader.setDaemon(true);reader.start();
        if(!pr.waitFor(10,TimeUnit.MINUTES)){pr.destroyForcibly();throw new IOException("Fabric Installer превысил лимит ожидания 10 минут");}reader.join(3000);
        if(pr.exitValue()!=0)throw new IOException("Fabric installer завершился с кодом "+pr.exitValue()+": "+output);
        String profile=findFabricProfile(mc,v);if(profile==null)throw new IOException("Fabric installer завершился, но профиль Fabric не найден. Вывод: "+output);
        say(p,"Fabric установлен: "+profile);return profile;
    }
    private String findFabricProfile(String mc,String loader)throws Exception{
        Path versions=gameDir.resolve("versions");if(!Files.isDirectory(versions))return null;
        try(var dirs=Files.list(versions)){
            for(Path dir:dirs.filter(Files::isDirectory).toList()){
                Path json=dir.resolve(dir.getFileName()+".json");if(!Files.isRegularFile(json))continue;
                try{Map<String,Object> meta=Json.obj(Json.parse(Files.readString(json)));if(!mc.equals(str(meta.get("inheritsFrom"))))continue;
                    for(Object item:Json.arr(meta.get("libraries"))){String name=str(Json.obj(item).get("name"));if(name.startsWith("net.fabricmc:fabric-loader:")){String found=name.substring("net.fabricmc:fabric-loader:".length()).split(":")[0];if(loader.equals(found))return dir.getFileName().toString();}}
                }catch(Exception ignored){}
            }
        }return null;
    }
    private boolean isWindows(){return System.getProperty("os.name","").toLowerCase(Locale.ROOT).contains("win");}
}
