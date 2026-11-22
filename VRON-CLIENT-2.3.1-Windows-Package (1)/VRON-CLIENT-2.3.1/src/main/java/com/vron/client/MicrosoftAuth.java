package com.vron.client;

import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/** Microsoft consumer Device Code -> Xbox Live -> XSTS -> Minecraft Services auth. */
final class MicrosoftAuth {
    record Account(String name,String uuid,String accessToken,String xuid){}
    interface Progress { void update(String text); }
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
    private Map<String,Object> post(String url,Map<String,String> form)throws Exception{String body=form.entrySet().stream().map(e->enc(e.getKey())+"="+enc(e.getValue())).reduce((a,b)->a+"&"+b).orElse("");HttpRequest req=HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(40)).header("Content-Type","application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(body)).build();HttpResponse<String> r=http.send(req,HttpResponse.BodyHandlers.ofString());Map<String,Object> j=Json.obj(Json.parse(r.body()));if(r.statusCode()/100!=2)throw new java.io.IOException("OAuth_"+j.getOrDefault("error","")+": "+j.getOrDefault("error_description",r.body()));return j;}
    private Map<String,Object> postJson(String url,Map<String,Object> data)throws Exception{HttpRequest req=HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(40)).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(Json.stringify(data))).build();HttpResponse<String> r=http.send(req,HttpResponse.BodyHandlers.ofString());Map<String,Object> j=Json.obj(Json.parse(r.body()));if(r.statusCode()/100!=2)throw new java.io.IOException("Auth HTTP "+r.statusCode()+": "+j.getOrDefault("Message",j.getOrDefault("error",r.body())));return j;}
    private String enc(String s){return URLEncoder.encode(s,StandardCharsets.UTF_8);}
    Account login(String clientId,Progress progress)throws Exception{
        if(clientId==null||clientId.isBlank()||clientId.equals("YOUR_MICROSOFT_CLIENT_ID"))throw new IllegalArgumentException("Сначала укажи Microsoft OAuth Client ID в настройках лаунчера.");
        Map<String,String> start=new LinkedHashMap<>();start.put("client_id",clientId);start.put("scope","XboxLive.signin offline_access");Map<String,Object> dc=post("https://login.microsoftonline.com/consumers/oauth2/v2.0/devicecode",start);
        String msg=String.valueOf(dc.get("message"));String code=String.valueOf(dc.get("device_code"));String userCode=String.valueOf(dc.get("user_code"));String uri=String.valueOf(dc.getOrDefault("verification_uri","https://microsoft.com/devicelogin"));int interval=((Number)dc.getOrDefault("interval",5L)).intValue();int expires=((Number)dc.getOrDefault("expires_in",900L)).intValue();if(progress!=null)progress.update("Откройте "+uri+" и введите код: "+userCode+"\n"+msg);
        long end=System.currentTimeMillis()+expires*1000L;Map<String,Object> token=null;while(System.currentTimeMillis()<end){Thread.sleep(Math.max(2,interval)*1000L);try{Map<String,String> poll=new LinkedHashMap<>();poll.put("grant_type","urn:ietf:params:oauth:grant-type:device_code");poll.put("client_id",clientId);poll.put("device_code",code);token=post("https://login.microsoftonline.com/consumers/oauth2/v2.0/token",poll);break;}catch(Exception e){String m=e.getMessage();if(m!=null&&m.contains("authorization_pending"))continue;if(m!=null&&m.contains("slow_down")){interval+=5;continue;}throw e;}}
        if(token==null)throw new java.io.IOException("Время Microsoft Device Code истекло.");String ms=str(token.get("access_token"));
        Map<String,Object> xbl=new LinkedHashMap<>();xbl.put("Properties",Map.of("AuthMethod","RPS","SiteName","user.auth.xboxlive.com","RpsTicket","d="+ms));xbl.put("RelyingParty","http://auth.xboxlive.com");xbl.put("TokenType","JWT");Map<String,Object> x=postJson("https://user.auth.xboxlive.com/user/authenticate",xbl);String xblToken=str(x.get("Token"));String uhs=uhs(x);
        Map<String,Object> xs=new LinkedHashMap<>();xs.put("Properties",Map.of("SandboxId","RETAIL","UserTokens",List.of(xblToken)));xs.put("RelyingParty","rp://api.minecraftservices.com/");xs.put("TokenType","JWT");Map<String,Object> xt=postJson("https://xsts.auth.xboxlive.com/xsts/authorize",xs);String xsts=str(xt.get("Token"));String userHash=uhs(xt);if(userHash.isBlank())userHash=uhs;String xuid=xid(xt);
        Map<String,Object> mc=postJson("https://api.minecraftservices.com/authentication/login_with_xbox",Map.of("identityToken","XBL3.0 x="+userHash+";"+xsts));String mcToken=str(mc.get("access_token"));
        Map<String,Object> profile=postJsonGet("https://api.minecraftservices.com/minecraft/profile",mcToken);String name=str(profile.get("name"));String id=str(profile.get("id"));if(name.isBlank()||id.isBlank())throw new java.io.IOException("Microsoft-аккаунт не имеет Minecraft Java Edition профиля или доступ к игре отсутствует.");if(progress!=null)progress.update("Успешный вход: "+name);return new Account(name,id,mcToken,xuid);
    }
    private Map<String,Object> postJsonGet(String url,String bearer)throws Exception{HttpRequest r=HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).header("Authorization","Bearer "+bearer).GET().build();HttpResponse<String> x=http.send(r,HttpResponse.BodyHandlers.ofString());Map<String,Object> j=Json.obj(Json.parse(x.body()));if(x.statusCode()/100!=2)throw new java.io.IOException("Minecraft profile HTTP "+x.statusCode()+": "+x.body());return j;}
    private String uhs(Map<String,Object> response){Object xui=Json.obj(response.get("DisplayClaims")).get("xui");if(xui instanceof List<?> l&&!l.isEmpty())return str(Json.obj(l.get(0)).get("uhs"));return "";}
    private String xid(Map<String,Object> response){Object xui=Json.obj(response.get("DisplayClaims")).get("xui");if(xui instanceof List<?> l&&!l.isEmpty())return str(Json.obj(l.get(0)).get("xid"));return "";}
    private String str(Object o){return o==null?"":String.valueOf(o);}
}
