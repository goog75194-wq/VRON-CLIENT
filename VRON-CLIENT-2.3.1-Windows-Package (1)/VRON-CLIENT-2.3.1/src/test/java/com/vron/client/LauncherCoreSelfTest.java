package com.vron.client;

import java.lang.reflect.Method;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import java.io.*;
import java.net.InetSocketAddress;
import java.security.MessageDigest;
import com.sun.net.httpserver.HttpServer;

/** Dependency-free regression checks for the launcher's local metadata logic. */
public final class LauncherCoreSelfTest {
    private static int passed;
    public static void main(String[] args) throws Exception {
        testJsonRoundTrip();
        Path root=Files.createTempDirectory("vron-launcher-test-");
        try {
            LauncherCore core=new LauncherCore(root);
            testFabricProfileDiscovery(core,root);
            testInheritedLibraries(core,root);
            testArgumentRules(core);
            testNativeExtraction(core,root);
            testVerifiedHttpDownload(core,root);
        } finally { deleteTree(root); }
        System.out.println("VRON self-tests passed: "+passed+"/7");
    }
    private static void testJsonRoundTrip(){
        String source="{\"name\":\"VRON\\nCLIENT\",\"n\":21,\"ok\":true,\"arr\":[1,2.5,null]}";
        Object parsed=Json.parse(source);String encoded=Json.stringify(parsed);Object again=Json.parse(encoded);
        check(Json.obj(again).get("name").equals("VRON\nCLIENT"),"JSON round trip");
    }
    private static void testFabricProfileDiscovery(LauncherCore core,Path root)throws Exception{
        Path d=root.resolve("versions/fabric-loader-test");Files.createDirectories(d);
        Map<String,Object> lib=Map.of("name","net.fabricmc:fabric-loader:0.16.9");
        Map<String,Object> meta=new LinkedHashMap<>();meta.put("id","fabric-loader-test");meta.put("inheritsFrom","1.21.11");meta.put("libraries",List.of(lib));
        Files.writeString(d.resolve("fabric-loader-test.json"),Json.stringify(meta));
        Method m=LauncherCore.class.getDeclaredMethod("findFabricProfile",String.class,String.class);m.setAccessible(true);
        Object actual=m.invoke(core,"1.21.11","0.16.9");check("fabric-loader-test".equals(actual),"Fabric profile discovery");
    }
    private static void testInheritedLibraries(LauncherCore core,Path root)throws Exception{
        Path parent=root.resolve("versions/1.21.11");Path child=root.resolve("versions/custom");Files.createDirectories(parent);Files.createDirectories(child);
        Files.writeString(parent.resolve("1.21.11.json"),Json.stringify(Map.of("libraries",List.of(Map.of("name","org.example:base:1.0")))));
        Files.writeString(child.resolve("custom.json"),Json.stringify(Map.of("inheritsFrom","1.21.11","libraries",List.of(Map.of("name","org.example:child:1.0")))));
        Method m=LauncherCore.class.getDeclaredMethod("effectiveLibraries",Map.class);m.setAccessible(true);
        @SuppressWarnings("unchecked") List<Map<String,Object>> libs=(List<Map<String,Object>>)m.invoke(core,Json.obj(Json.parse(Files.readString(child.resolve("custom.json")))));
        check(libs.size()==2,"Inherited libraries merged");
    }
    private static void testArgumentRules(LauncherCore core)throws Exception{
        Method m=LauncherCore.class.getDeclaredMethod("appendArg",List.class,Object.class,Map.class,Map.class);m.setAccessible(true);
        List<String> out=new ArrayList<>();Map<String,Object> noMatch=Map.of("value","--should-not-appear","rules",List.of(Map.of("action","allow","os",Map.of("name","nonexistent-os"))));
        m.invoke(core,out,noMatch,Map.of(),Map.of());check(out.isEmpty(),"Nonmatching argument rule denied");
        String os=System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")?"windows":System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("mac")?"osx":"linux";
        Map<String,Object> match=Map.of("value","--allowed","rules",List.of(Map.of("action","allow","os",Map.of("name",os))));m.invoke(core,out,match,Map.of(),Map.of());check(out.equals(List.of("--allowed")),"Matching argument rule allowed");
    }
    private static void testNativeExtraction(LauncherCore core,Path root)throws Exception{
        Path jar=root.resolve("native-test.jar"),out=root.resolve("native-out");Files.createDirectories(out);
        try(ZipOutputStream z=new ZipOutputStream(Files.newOutputStream(jar))){entry(z,"native.dll","dll");entry(z,"META-INF/ignored.txt","meta");entry(z,"nested/ignored.bin","nested");}
        Method m=LauncherCore.class.getDeclaredMethod("extractNatives",Path.class,Path.class);m.setAccessible(true);m.invoke(core,jar,out);
        check(Files.readString(out.resolve("native.dll")).equals("dll")&&!Files.exists(out.resolve("ignored.txt"))&&!Files.exists(out.resolve("ignored.bin")),"Native extraction filters metadata and nested entries");
    }
    private static void testVerifiedHttpDownload(LauncherCore core,Path root)throws Exception{
        byte[] payload="VRON-CLIENT-download-check".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/asset",exchange->{exchange.sendResponseHeaders(200,payload.length);try(OutputStream out=exchange.getResponseBody()){out.write(payload);}});server.start();
        try{
            String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(payload));Path target=root.resolve("downloaded/asset.bin");
            Method m=LauncherCore.class.getDeclaredMethod("download",String.class,Path.class,String.class);m.setAccessible(true);
            m.invoke(core,"http://127.0.0.1:"+server.getAddress().getPort()+"/asset",target,hash);
            check(Arrays.equals(payload,Files.readAllBytes(target)),"HTTP download with SHA-1 verification");
        }finally{server.stop(0);}
    }
    private static void entry(ZipOutputStream z,String name,String value)throws IOException{z.putNextEntry(new ZipEntry(name));z.write(value.getBytes());z.closeEntry();}
    private static void check(boolean ok,String label){if(!ok)throw new AssertionError("FAIL: "+label);passed++;System.out.println("PASS: "+label);}
    private static void deleteTree(Path root)throws IOException{if(!Files.exists(root))return;try(var s=Files.walk(root)){for(Path p:s.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}}
}
