package com.vron.client;

import java.util.*;

/** Small JSON reader/writer to keep the launcher dependency-free. */
final class Json {
    private final String s; private int p;
    private Json(String s){this.s=s;}
    static Object parse(String s){ Json j=new Json(s); Object v=j.value(); j.ws(); if(j.p<s.length()) throw new IllegalArgumentException("Trailing JSON at "+j.p); return v; }
    static String stringify(Object o){ StringBuilder b=new StringBuilder(); write(o,b); return b.toString(); }
    @SuppressWarnings("unchecked") static Map<String,Object> obj(Object o){return o instanceof Map?(Map<String,Object>)o:new LinkedHashMap<>();}
    @SuppressWarnings("unchecked") static List<Object> arr(Object o){return o instanceof List?(List<Object>)o:new ArrayList<>();}
    private void ws(){while(p<s.length()&&Character.isWhitespace(s.charAt(p)))p++;}
    private Object value(){ws(); if(p>=s.length())throw new IllegalArgumentException("Unexpected end"); char c=s.charAt(p); if(c=='{')return object(); if(c=='[')return array(); if(c=='"')return string(); if(c=='t'&&word("true"))return true; if(c=='f'&&word("false"))return false; if(c=='n'&&word("null"))return null; return number();}
    private boolean word(String w){if(s.startsWith(w,p)){p+=w.length();return true;}return false;}
    private Map<String,Object> object(){p++; Map<String,Object> m=new LinkedHashMap<>(); ws(); if(take('}'))return m; do{ws();String k=string();ws();need(':');m.put(k,value());ws();if(take('}'))return m;need(',');}while(true);}
    private List<Object> array(){p++;List<Object>a=new ArrayList<>();ws();if(take(']'))return a;do{a.add(value());ws();if(take(']'))return a;need(',');}while(true);}
    private String string(){need('"');StringBuilder b=new StringBuilder();while(p<s.length()){char c=s.charAt(p++);if(c=='"')return b.toString();if(c=='\\'){if(p>=s.length())break;char e=s.charAt(p++);switch(e){case '"','\\','/'->b.append(e);case 'b'->b.append('\b');case 'f'->b.append('\f');case 'n'->b.append('\n');case 'r'->b.append('\r');case 't'->b.append('\t');case 'u'->{if(p+4>s.length())throw new IllegalArgumentException("Bad unicode");b.append((char)Integer.parseInt(s.substring(p,p+4),16));p+=4;}default->throw new IllegalArgumentException("Bad escape");}}else b.append(c);}throw new IllegalArgumentException("Unclosed string");}
    private Number number(){int a=p;if(p<s.length()&&s.charAt(p)=='-')p++;while(p<s.length()&&Character.isDigit(s.charAt(p)))p++;if(p<s.length()&&s.charAt(p)=='.'){p++;while(p<s.length()&&Character.isDigit(s.charAt(p)))p++;}if(p<s.length()&&(s.charAt(p)=='e'||s.charAt(p)=='E')){p++;if(p<s.length()&&(s.charAt(p)=='+'||s.charAt(p)=='-'))p++;while(p<s.length()&&Character.isDigit(s.charAt(p)))p++;}String n=s.substring(a,p);try{if(n.contains(".")||n.contains("e")||n.contains("E"))return Double.parseDouble(n);return Long.parseLong(n);}catch(Exception e){throw new IllegalArgumentException("Bad number: "+n);}}
    private boolean take(char c){if(p<s.length()&&s.charAt(p)==c){p++;return true;}return false;} private void need(char c){ws();if(!take(c))throw new IllegalArgumentException("Expected "+c+" at "+p);}
    private static void write(Object o,StringBuilder b){if(o==null){b.append("null");return;}if(o instanceof String){b.append('"').append(((String)o).replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r")).append('"');return;}if(o instanceof Number||o instanceof Boolean){b.append(o);return;}if(o instanceof Map<?,?>m){b.append('{');boolean f=true;for(var e:m.entrySet()){if(!f)b.append(',');f=false;write(String.valueOf(e.getKey()),b);b.append(':');write(e.getValue(),b);}b.append('}');return;}if(o instanceof Iterable<?>it){b.append('[');boolean f=true;for(Object v:it){if(!f)b.append(',');f=false;write(v,b);}b.append(']');return;}write(o.toString(),b);}
}
