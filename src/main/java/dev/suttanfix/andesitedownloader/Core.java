package dev.suttanfix.andesitedownloader;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;


public class Core {

    public static final String APP_NAME = "安山岩下载器";
    public static final String APP_VERSION = "1.0.0";


    private static final String MIRROR = "https://bmclapi2.bangbang93.com";

    private static final String MANIFEST = "https://launchermeta.mojang.com/mc/game/version_manifest_v2.json";
    private static final String UA = "AndesiteDownloader/" + APP_VERSION;

    public static final String OS;
    public static final String ARCH;

    static {
        String n = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        OS = n.contains("win") ? "windows" : (n.contains("mac") ? "osx" : "linux");
        String a = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        ARCH = (a.contains("aarch64") || a.contains("arm64")) ? "arm64" : "x64";
    }



    public File baseDir;
    public File gameDir;
    public int threads = 8;
    public boolean mirror = true;
    public boolean checkSha1 = true;


    public final List<Task> tasks = new CopyOnWriteArrayList<>();

    private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "andesite-worker");
        t.setDaemon(true);
        return t;
    });

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private JsonObject manifest;
    private final Map<String, String> versionUrlCache = new HashMap<>();



    public void init() {
        baseDir = computeBaseDir();
        gameDir = new File(baseDir, ".minecraft");

        File cfg = new File(baseDir, "config.json");
        if (cfg.isFile()) {
            try {
                String text = new String(Files.readAllBytes(cfg.toPath()), StandardCharsets.UTF_8);
                JsonObject o = JsonParser.parseString(text).getAsJsonObject();
                if (o.has("gameDir")) gameDir = new File(o.get("gameDir").getAsString());
                if (o.has("threads")) threads = Math.max(1, Math.min(32, o.get("threads").getAsInt()));
                if (o.has("mirror")) mirror = o.get("mirror").getAsBoolean();
                if (o.has("checkSha1")) checkSha1 = o.get("checkSha1").getAsBoolean();
            } catch (Exception ignored) {
              
            }
        }

        if (!gameDir.isDirectory() && !gameDir.mkdirs()) {
            gameDir = new File(System.getProperty("user.dir"), ".minecraft");
            gameDir.mkdirs();
        }
    }

    public void save() {
        try {
            JsonObject o = new JsonObject();
            o.addProperty("gameDir", gameDir.getAbsolutePath());
            o.addProperty("threads", threads);
            o.addProperty("mirror", mirror);
            o.addProperty("checkSha1", checkSha1);
            Files.write(new File(baseDir, "config.json").toPath(),
                    o.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    private static File computeBaseDir() {
        try {
            URI uri = Core.class.getProtectionDomain().getCodeSource().getLocation().toURI();
            File f = new File(uri);
            if (f.isFile()) return f.getParentFile();   
            return f;                                   
        } catch (Exception e) {
            return new File(System.getProperty("user.dir"));
        }
    }

  

    private String[] candidates(String url) {
        if (!mirror) return new String[]{url};

        String m = url
                .replace("https://launchermeta.mojang.com", MIRROR)
                .replace("https://piston-meta.mojang.com", MIRROR)
                .replace("https://launcher.mojang.com", MIRROR)
                .replace("https://resources.download.minecraft.net", MIRROR + "/assets")
                .replace("https://libraries.minecraft.net", MIRROR + "/maven")
                .replace("https://files.minecraftforge.net/maven", MIRROR + "/maven")
                .replace("https://maven.minecraftforge.net", MIRROR + "/maven");

        if (m.equals(url)) return new String[]{url};
        return new String[]{m, url};
    }


    public byte[] fetchBytes(String url) {
        return fetchBytes(url, null);
    }


    private byte[] fetchBytes(String url, Task t) {
        Exception last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            for (String u : candidates(url)) {
                checkCancel(t);
                try {
                    HttpRequest req = HttpRequest.newBuilder(URI.create(u))
                            .timeout(Duration.ofSeconds(30))
                            .header("User-Agent", UA)
                            .GET()
                            .build();
                    HttpResponse<byte[]> r = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
                    if (r.statusCode() >= 200 && r.statusCode() < 300) return r.body();
                    last = new IOException("HTTP " + r.statusCode() + " @" + u);
                } catch (Exception e) {
                    last = e;
                }
            }
            sleep(400L * (attempt + 1));
        }
        throw new RuntimeException("请求失败：" + url
                + (last == null ? "" : "（" + last.getMessage() + "）"));
    }

    public String fetchText(String url) {
        return new String(fetchBytes(url, null), StandardCharsets.UTF_8);
    }

    private String fetchText(String url, Task t) {
        return new String(fetchBytes(url, t), StandardCharsets.UTF_8);
    }

  
    private static void checkCancel(Task t) {
        if (t != null && t.cancelFlag) throw new RuntimeException("已取消");
    }


    public JsonObject fetchJson(String url) {
        return JsonParser.parseString(fetchText(url)).getAsJsonObject();
    }

 

    public synchronized List<String[]> loadVersions() {
        if (manifest == null) {
            manifest = fetchJson(MANIFEST);
            versionUrlCache.clear();
            for (JsonElement e : manifest.getAsJsonArray("versions")) {
                JsonObject v = e.getAsJsonObject();
                versionUrlCache.put(v.get("id").getAsString(), v.get("url").getAsString());
            }
        }

        List<String[]> out = new ArrayList<>();
        for (JsonElement e : manifest.getAsJsonArray("versions")) {
            JsonObject v = e.getAsJsonObject();
            out.add(new String[]{
                    v.get("id").getAsString(),
                    v.get("type").getAsString(),
                    v.get("releaseTime").getAsString(),
                    v.get("url").getAsString()
            });
        }
        return out;
    }


    public synchronized String versionRaw(String id) {
        if (manifest == null) loadVersions();
        String url = versionUrlCache.get(id);
        if (url == null) throw new RuntimeException("版本列表中没有 " + id);
        return fetchText(url);
    }

 


    public List<String> listFabric(String mc) {
        List<String> out = new ArrayList<>();
        JsonArray arr = JsonParser.parseString(
                fetchText("https://meta.fabricmc.net/v2/versions/loader/" + mc)).getAsJsonArray();
        for (JsonElement e : arr) {
            String v = e.getAsJsonObject().getAsJsonObject("loader").get("version").getAsString();
            if (!out.contains(v)) out.add(v);
        }
        return out;
    }


    public List<String> listForge(String mc) {
        List<String> out = new ArrayList<>();
        String xml = fetchText("https://maven.minecraftforge.net/net/minecraftforge/forge/maven-metadata.xml");
        Matcher m = Pattern.compile("<version>([^<]+)</version>").matcher(xml);
        while (m.find()) {
            String v = m.group(1).trim();
            if (v.startsWith(mc + "-") && v.length() > mc.length() + 1) {
                out.add(v.substring(mc.length() + 1));
            }
        }
        return out;
    }


    public List<String> listNeoForge(String mc) {
        List<String> out = new ArrayList<>();
        if (!mc.startsWith("1.")) return out;
        String prefix = mc.substring(2) + ".";

        String xml;
        try {
            xml = fetchText("https://maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml");
        } catch (Exception e) {
            return out;
        }

        Matcher m = Pattern.compile("<version>([^<]+)</version>").matcher(xml);
        while (m.find()) {
            String v = m.group(1).trim();
            if (v.startsWith(prefix)) out.add(v);
        }
        return out;
    }




    public Task install(String mc, String loader, String loaderVer) {
        boolean vanilla = loader == null || "原版".equals(loader);
        String instance = instanceName(mc, loader, loaderVer);
        Task t = new Task(mc, vanilla ? null : loader, vanilla ? null : loaderVer, instance);
        tasks.add(t);
        pool.submit(() -> runInstall(t));
        return t;
    }


    public static String instanceName(String mc, String loader, String loaderVer) {
        if (loader == null || "原版".equals(loader)) return mc;
        switch (loader) {
            case "Fabric":
                return "fabric-loader-" + loaderVer + "-" + mc;
            case "Forge":
                return mc + "-forge-" + loaderVer;
            case "NeoForge":
                return mc + "-neoforge-" + loaderVer;
            default:
                return mc + "-" + loader.toLowerCase(Locale.ROOT) + "-" + loaderVer;
        }
    }


    private void runInstall(Task t) {
        t.begin();
        try {
            checkCancel(t);                                        

            String raw = versionRaw(t.mcId);
            checkCancel(t);                                   
            JsonObject vj = JsonParser.parseString(raw).getAsJsonObject();

            t.stage("下载原版 " + t.mcId);
            installVanilla(t.mcId, raw, vj, t);

            if ("Fabric".equals(t.loader)) {
                installFabric(t.mcId, t.loaderVer, t);
            } else if ("Forge".equals(t.loader)) {
                installForge(t.mcId, t.loaderVer, false, t);
            } else if ("NeoForge".equals(t.loader)) {
                installForge(t.mcId, t.loaderVer, true, t);
            }

            t.state = Task.DONE;
            t.stage = "已完成";
            t.indeterminate = false;
            t.finishedAt = System.currentTimeMillis();            
            t.log("安装完成 → " + new File(gameDir, "versions/" + t.instance).getAbsolutePath());
        } catch (Exception e) {
            if (t.cancelFlag) {
                t.state = Task.CANCELLED;
                t.stage = "已取消";
            } else {
                t.state = Task.FAILED;
                t.stage = "失败";
                t.error = e.getMessage() == null ? e.toString() : e.getMessage();
                t.log("错误：" + t.error);
            }
            t.indeterminate = false;
            t.finishedAt = System.currentTimeMillis();            
        }
    }
    

    private void installVanilla(String mc, String raw, JsonObject vj, Task t) throws Exception {
        File vdir = new File(gameDir, "versions/" + mc);
        if (!vdir.isDirectory() && !vdir.mkdirs()) throw new RuntimeException("无法创建目录 " + vdir);
        if (new File(vdir, mc + ".json").isFile()) t.log("检测到已有版本目录，将增量补全（已存在文件自动跳过）");

        Files.write(new File(vdir, mc + ".json").toPath(), raw.getBytes(StandardCharsets.UTF_8));

        File nativesDir = new File(vdir, "natives");
        if (!nativesDir.isDirectory()) nativesDir.mkdirs();

        List<Object[]> list = new ArrayList<>();
        List<File> nativeJars = new ArrayList<>();

        
        JsonObject downloads = vj.has("downloads") && !vj.get("downloads").isJsonNull()
                ? vj.getAsJsonObject("downloads") : null;
        if (downloads != null && downloads.has("client") && !downloads.get("client").isJsonNull()) {
            JsonObject c = downloads.getAsJsonObject("client");
            list.add(entry(str(c, "url"), new File(vdir, mc + ".jar"), str(c, "sha1"), num(c, "size")));
        }

    
        if (vj.has("logging") && !vj.get("logging").isJsonNull()) {
            JsonObject lg = vj.getAsJsonObject("logging");
            if (lg.has("client") && !lg.get("client").isJsonNull()) {
                JsonObject f = lg.getAsJsonObject("client").has("file")
                        ? lg.getAsJsonObject("client").getAsJsonObject("file") : null;
                if (f != null && f.has("url") && !f.get("url").isJsonNull()) {
                    list.add(entry(str(f, "url"),
                            new File(gameDir, "assets/log_configs/" + str(f, "id")),
                            str(f, "sha1"), num(f, "size")));
                }
            }
        }

    
        if (vj.has("libraries") && !vj.get("libraries").isJsonNull()) {
            for (JsonElement e : vj.getAsJsonArray("libraries")) {
                JsonObject lib = e.getAsJsonObject();
                if (!rulesAllow(lib)) continue;
                addLibrary(list, nativeJars, lib);
            }
        }

       
        if (vj.has("assetIndex") && !vj.get("assetIndex").isJsonNull()) {
            t.stage("读取资源索引");
            JsonObject ai = vj.getAsJsonObject("assetIndex");
            String indexId = str(ai, "id");
            String indexText = fetchText(str(ai, "url"), t);

            File indexFile = new File(gameDir, "assets/indexes/" + indexId + ".json");
            File indexParent = indexFile.getParentFile();
            if (indexParent != null && !indexParent.isDirectory()) indexParent.mkdirs();
            Files.write(indexFile.toPath(), indexText.getBytes(StandardCharsets.UTF_8));

            JsonObject idx = JsonParser.parseString(indexText).getAsJsonObject();
            if (idx.has("objects") && !idx.get("objects").isJsonNull()) {
                for (Map.Entry<String, JsonElement> en : idx.getAsJsonObject("objects").entrySet()) {
                    if (en.getValue() == null || !en.getValue().isJsonObject()) continue;
                    JsonObject o = en.getValue().getAsJsonObject();
                    String hash = str(o, "hash");
                    if (hash == null || hash.length() < 2) continue;
                    String sub = hash.substring(0, 2);
                    list.add(entry("https://resources.download.minecraft.net/" + sub + "/" + hash,
                            new File(gameDir, "assets/objects/" + sub + "/" + hash),
                            hash, num(o, "size")));
                }
            }
        }

       
        t.stage("下载原版文件");
        transfer(list, t);

       
        if (!nativeJars.isEmpty()) {
            t.stage("解压本地库");
            for (File jar : nativeJars) extractNatives(jar, nativesDir);
        }

        t.log("原版 " + mc + " 就绪：" + list.size() + " 个文件");
    }


    private void addLibrary(List<Object[]> out, List<File> nativeJars, JsonObject lib) {
        JsonObject downloads = lib.has("downloads") && !lib.get("downloads").isJsonNull()
                ? lib.getAsJsonObject("downloads") : null;


        if (downloads != null && downloads.has("artifact") && !downloads.get("artifact").isJsonNull()) {
            JsonObject a = downloads.getAsJsonObject("artifact");
            String path = str(a, "path");
            if (path != null && a.has("url") && !a.get("url").isJsonNull()) {
                out.add(entry(str(a, "url"), new File(gameDir, "libraries/" + path),
                        str(a, "sha1"), num(a, "size")));
            }
        } else if (lib.has("name") && !lib.get("name").isJsonNull()) {
            String path = mavenPath(lib.get("name").getAsString());
            String repo = lib.has("url") && !lib.get("url").isJsonNull()
                    ? lib.get("url").getAsString()
                    : "https://libraries.minecraft.net/";
            if (!repo.endsWith("/")) repo += "/";
            out.add(entry(repo + path, new File(gameDir, "libraries/" + path), null, 0L));
        }


        if (lib.has("natives") && !lib.get("natives").isJsonNull()
                && downloads != null && downloads.has("classifiers")
                && !downloads.get("classifiers").isJsonNull()) {
            JsonObject natives = lib.getAsJsonObject("natives");
            if (natives.has(OS) && !natives.get(OS).isJsonNull()) {
                String key = natives.get(OS).getAsString().replace("${arch}", "64");
                JsonObject cls = downloads.getAsJsonObject("classifiers");
                if (cls.has(key) && !cls.get(key).isJsonNull()) {
                    JsonObject a = cls.getAsJsonObject(key);
                    String path = str(a, "path");
                    if (path != null && str(a, "url") != null) {
                        File target = new File(gameDir, "libraries/" + path);
                        out.add(entry(str(a, "url"), target, str(a, "sha1"), num(a, "size")));
                        nativeJars.add(target);
                    }
                }
            }
        }
    }


    private boolean rulesAllow(JsonObject lib) {
        if (!lib.has("rules") || lib.get("rules").isJsonNull()) return true;

        boolean allow = false;
        for (JsonElement e : lib.getAsJsonArray("rules")) {
            if (!e.isJsonObject()) continue;
            JsonObject rule = e.getAsJsonObject();
            boolean match = true;

            if (rule.has("os") && !rule.get("os").isJsonNull()) {
                JsonObject os = rule.getAsJsonObject("os");
                if (os.has("name") && !OS.equals(os.get("name").getAsString())) match = false;

                if (os.has("arch")) {
                    String want = os.get("arch").getAsString();
                    if ("x86".equals(want) && "x64".equals(ARCH)) match = false;
                    if ("arm64".equals(want) && !"arm64".equals(ARCH)) match = false;
                }

                if (os.has("version")) {
                    String regex = os.get("version").getAsString();
                    String ver = System.getProperty("os.version", "");
                    try {
                        if (!Pattern.compile(regex).matcher(ver).find()) match = false;
                    } catch (Exception ignored) {
                        match = false;   
                    }
                }
            }


            if (rule.has("features")) match = false;

            if (match) {
                String action = rule.has("action") ? rule.get("action").getAsString() : "allow";
                allow = "allow".equals(action);
            }
        }
        return allow;
    }


    private static String mavenPath(String name) {
        String ext = "jar";
        String coord = name;

        int at = coord.indexOf('@');
        if (at >= 0) {
            ext = coord.substring(at + 1);
            coord = coord.substring(0, at);
        }

        String[] p = coord.split(":");
        if (p.length < 3) return coord.replace(':', '/') + "." + ext;

        String group = p[0];
        String artifact = p[1];
        String version = p[2];
        String classifier = p.length > 3 ? "-" + p[3] : "";

        return group.replace('.', '/') + "/" + artifact + "/" + version + "/"
                + artifact + "-" + version + classifier + "." + ext;
    }


    private void extractNatives(File jar, File dir) {
        if (jar == null || !jar.isFile()) return;

        try (ZipFile zip = new ZipFile(jar)) {
            Enumeration<? extends ZipEntry> en = zip.entries();
            while (en.hasMoreElements()) {
                ZipEntry entry = en.nextElement();
                String name = entry.getName();

                if (entry.isDirectory()) continue;
                if (name.startsWith("META-INF/")) continue;
                if (name.endsWith(".class")) continue;
                if (name.contains("/")) continue;  

                File out = new File(dir, name);
                try (InputStream in = zip.getInputStream(entry);
                     OutputStream os = new BufferedOutputStream(new FileOutputStream(out))) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                }
            }
        } catch (Exception e) {
            
        }
    }


    private void installFabric(String mc, String loader, Task t) throws Exception {
        t.stage("获取 Fabric 元数据");
        String id = "fabric-loader-" + loader + "-" + mc;
        String json = fetchText("https://meta.fabricmc.net/v2/versions/loader/"
                + mc + "/" + loader + "/profile/json", t);
        JsonObject profile = JsonParser.parseString(json).getAsJsonObject();

        File vdir = new File(gameDir, "versions/" + id);
        if (!vdir.isDirectory() && !vdir.mkdirs()) throw new RuntimeException("无法创建目录 " + vdir);
        Files.write(new File(vdir, id + ".json").toPath(), json.getBytes(StandardCharsets.UTF_8));

        List<Object[]> list = new ArrayList<>();
        if (profile.has("libraries") && !profile.get("libraries").isJsonNull()) {
            for (JsonElement e : profile.getAsJsonArray("libraries")) {
                if (!e.isJsonObject()) continue;
                JsonObject lib = e.getAsJsonObject();
                if (!lib.has("name") || lib.get("name").isJsonNull()) continue;
                if (!rulesAllow(lib)) continue;

                String path = mavenPath(lib.get("name").getAsString());
                String repo = lib.has("url") && !lib.get("url").isJsonNull()
                        ? lib.get("url").getAsString()
                        : "https://maven.fabricmc.net/";
                if (!repo.endsWith("/")) repo += "/";
                list.add(entry(repo + path, new File(gameDir, "libraries/" + path), null, 0L));
            }
        }

        t.stage("下载 Fabric 组件");
        transfer(list, t);
        t.log("Fabric " + loader + " 就绪：" + list.size() + " 个组件");
    }
    

    private void installForge(String mc, String ver, boolean neo, Task t) throws Exception {
        String full = neo ? ver : mc + "-" + ver;
        String url = neo
                ? "https://maven.neoforged.net/releases/net/neoforged/neoforge/" + ver
                  + "/neoforge-" + ver + "-installer.jar"
                : "https://maven.minecraftforge.net/net/minecraftforge/forge/" + full
                  + "/forge-" + full + "-installer.jar";


        t.stage("下载安装器");
        File installer = File.createTempFile("andesite-installer", ".jar");
        List<Object[]> one = new ArrayList<>();
        one.add(entry(url, installer, null, 0L));
        transfer(one, t);


        t.stage("准备安装环境");
        File sandbox = Files.createTempDirectory("andesite-forge").toFile();
        File smc = new File(sandbox, ".minecraft");
        if (!smc.isDirectory() && !smc.mkdirs()) throw new RuntimeException("无法创建沙盒目录 " + smc);


        Files.write(new File(smc, "launcher_profiles.json").toPath(),
                ("{\"profiles\":{},\"selectedProfile\":\"\",\"clientToken\":\"\","
                 + "\"authenticationDatabase\":{},\"launcherVersion\":{\"name\":\"Andesite\",\"format\":21}}")
                        .getBytes(StandardCharsets.UTF_8));


        File vanilla = new File(gameDir, "versions/" + mc);
        if (vanilla.isDirectory()) copyTree(vanilla, new File(smc, "versions/" + mc));


        String javaBin = new File(new File(System.getProperty("java.home"), "bin"),
                "windows".equals(OS) ? "java.exe" : "java").getAbsolutePath();

        ProcessBuilder pb = new ProcessBuilder(javaBin,
                "-Duser.home=" + sandbox.getAbsolutePath(),
                "-jar", installer.getAbsolutePath(),
                "--installClient");
        pb.directory(sandbox);
        pb.redirectErrorStream(true);
        if ("windows".equals(OS)) pb.environment().put("APPDATA", sandbox.getAbsolutePath());
        pb.environment().put("HOME", sandbox.getAbsolutePath());

        t.stage("运行安装器");
        t.indeterminate = true;
        t.manual = 0d;

        Process p = pb.start();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (t.cancelFlag) {
                    p.destroyForcibly();
                    throw new RuntimeException("已取消");
                }
                String s = line.trim();
                if (!s.isEmpty()) {
                    t.log(s);
                    if (s.length() <= 70) t.manual = Math.min(0.95d, t.manual + 0.01d);
                }
            }
        }

        int code = p.waitFor();
        t.indeterminate = false;
        if (code != 0) throw new RuntimeException("安装器退出码 " + code + "，请查看任务日志");


        t.stage("合并安装结果");
        int copied = 0;
        File sandboxVersions = new File(smc, "versions");
        File[] dirs = sandboxVersions.listFiles();
        if (dirs != null) {
            for (File d : dirs) {
                if (!d.isDirectory() || d.getName().equals(mc)) continue;
                copyTree(d, new File(gameDir, "versions/" + d.getName()));
                copied++;
            }
        }
        File sandboxLibs = new File(smc, "libraries");
        if (sandboxLibs.isDirectory()) copyTree(sandboxLibs, new File(gameDir, "libraries"));

        deleteTree(sandbox);
        installer.delete();

        if (copied == 0) throw new RuntimeException("安装器未产出新版本目录，请查看任务日志");
        t.log("安装器产出 " + copied + " 个版本目录");
    }




    private static Object[] entry(String url, File file, String sha1, long size) {
        return new Object[]{url, file, sha1, size};
    }


    private void transfer(List<Object[]> entries, Task t) throws Exception {
        List<Object[]> pending = new ArrayList<>();
        long totalBytes = 0L;
        long doneBytes = 0L;
        int totalFiles = 0;
        int doneFiles = 0;

        for (Object[] e : entries) {
            File f = (File) e[1];
            String sha = (String) e[2];
            long sz = (Long) e[3];

            totalFiles++;
            totalBytes += Math.max(0L, sz);

            boolean ok = false;
            if (f.isFile() && f.length() > 0) {
                ok = sha == null || !checkSha1 || sha.equalsIgnoreCase(sha1(f));
            }
            if (ok) {
                doneFiles++;
                doneBytes += f.length();
            } else {
                pending.add(e);
            }
        }

        t.filesTotal.set(totalFiles);
        t.filesDone.set(doneFiles);
        t.bytesTotal.set(totalBytes);
        t.bytesDone.set(doneBytes);

        if (t.cancelFlag) throw new RuntimeException("已取消");
        if (pending.isEmpty()) return;

        ExecutorService ex = Executors.newFixedThreadPool(
                Math.max(1, Math.min(32, threads)));
        List<Future<?>> futures = new ArrayList<>(pending.size());
        final Exception[] failure = new Exception[1];

        for (Object[] e : pending) {
            futures.add(ex.submit(() -> {
                try {
                    if (!t.cancelFlag) downloadOne(e, t);
                } catch (Exception err) {
                    synchronized (failure) {
                        if (failure[0] == null) failure[0] = err;
                    }
                }
                return null;
            }));
        }
        ex.shutdown();

        for (Future<?> f : futures) {
            try {
                f.get();
            } catch (Exception ignored) {
            }
        }

        synchronized (failure) {
            if (failure[0] != null) {
                if (t.cancelFlag) throw new RuntimeException("已取消");
                throw new RuntimeException(failure[0].getMessage(), failure[0]);
            }
        }
        if (t.cancelFlag) throw new RuntimeException("已取消");
    }


    private void downloadOne(Object[] e, Task t) throws Exception {
        final String url = (String) e[0];
        final File dest = (File) e[1];
        final String sha = (String) e[2];

        File dir = dest.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
            throw new RuntimeException("无法创建目录 " + dir);
        }

        final File part = new File(dir, dest.getName() + ".part");
        Exception last = null;

        for (int attempt = 0; attempt < 3; attempt++) {
            for (String u : candidates(url)) {
                if (t.cancelFlag) throw new RuntimeException("已取消");

                long counted = 0L;
                try {
                    long offset = part.isFile() ? part.length() : 0L;

                    HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(u))
                            .timeout(Duration.ofSeconds(60))
                            .header("User-Agent", UA)
                            .GET();
                    if (offset > 0) rb.header("Range", "bytes=" + offset + "-");

                    HttpResponse<InputStream> r = http.send(rb.build(),
                            HttpResponse.BodyHandlers.ofInputStream());

                    if (r.statusCode() / 100 != 2) {
                        last = new IOException("HTTP " + r.statusCode());
                        continue;
                    }

                    boolean append = offset > 0 && r.statusCode() == 206;
                    if (append) {
                        counted = offset;
                        t.bytesDone.addAndGet(offset);
                    } else if (part.isFile()) {
                        part.delete();
                    }

                    try (InputStream in = r.body();
                         OutputStream out = new BufferedOutputStream(
                                 new FileOutputStream(part, append), 65536)) {
                        byte[] buf = new byte[65536];
                        int n;
                        while ((n = in.read(buf)) > 0) {
                            if (t.cancelFlag) throw new RuntimeException("已取消");
                            out.write(buf, 0, n);
                            counted += n;
                            t.bytesDone.addAndGet(n);
                        }
                    }

                    if (sha != null && checkSha1 && !sha.equalsIgnoreCase(sha1(part))) {
                        t.bytesDone.addAndGet(-counted);
                        counted = 0L;
                        part.delete();
                        last = new IOException("SHA1 校验不一致");
                        continue;
                    }

                    Files.move(part.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    t.filesDone.incrementAndGet();
                    return;
                } catch (RuntimeException re) {
                    t.bytesDone.addAndGet(-counted);
                    if ("已取消".equals(re.getMessage())) throw re;
                    last = re;
                } catch (Exception ex) {
                    t.bytesDone.addAndGet(-counted);
                    last = ex;
                }
            }
            sleep(300L * (attempt + 1));
        }

        throw new RuntimeException("下载失败 " + dest.getName()
                + (last == null ? "" : "：" + last.getMessage()));
    }


    public static String sha1(File f) {
        try (InputStream in = Files.newInputStream(f.toPath())) {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            byte[] d = md.digest();
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }


    private static void copyTree(File src, File dst) throws IOException {
        if (src == null || !src.exists()) return;

        var s = src.toPath();
        var d = dst.toPath();

        if (!Files.isDirectory(s)) {
            Files.createDirectories(d.getParent());
            Files.copy(s, d, StandardCopyOption.REPLACE_EXISTING);
            return;
        }

        Files.createDirectories(d);
        try (var st = Files.walk(s)) {
            for (var p : (Iterable<java.nio.file.Path>) st::iterator) {
                var target = d.resolve(s.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }


    private static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        try (var st = Files.walk(f.toPath())) {
            st.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        } catch (Exception ignored) {
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String str(JsonObject o, String k) {
        return o != null && o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : null;
    }

    private static long num(JsonObject o, String k) {
        try {
            return o != null && o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsLong() : 0L;
        } catch (Exception e) {
            return 0L;
        }
    }
}
