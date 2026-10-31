package dev.suttanfix.andesitedownloader;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;


public class Task {

    public static final int WAITING = 0;
    public static final int RUNNING = 1;
    public static final int DONE = 2;
    public static final int FAILED = 3;
    public static final int CANCELLED = 4;

    public final String mcId;
    public final String loader;
    public final String loaderVer;
    public final String instance;

    public volatile int state = WAITING;
    public volatile String stage = "等待中";
    public volatile String error = "";
    public volatile boolean cancelFlag = false;
    public volatile boolean indeterminate = false;
    public volatile double manual = 0d;
    public volatile long finishedAt = 0L;

    public final AtomicInteger filesDone = new AtomicInteger();
    public final AtomicInteger filesTotal = new AtomicInteger();
    public final AtomicLong bytesDone = new AtomicLong();
    public final AtomicLong bytesTotal = new AtomicLong();

    public volatile double speed = 0d;
    private volatile long sampleBytes = 0L;
    private volatile long sampleTime = System.currentTimeMillis();

    public final List<String> log = Collections.synchronizedList(new ArrayList<>());

    public Task(String mcId, String loader, String loaderVer, String instance) {
        this.mcId = mcId;
        this.loader = loader;
        this.loaderVer = loaderVer;
        this.instance = instance;
    }

    public String displayLoader() {
        if (loader == null) return "原版";
        if (loaderVer == null || loaderVer.isEmpty()) return loader;
        return loader + " " + loaderVer;
    }

    public void begin() {
        state = RUNNING;
        sampleTime = System.currentTimeMillis();
        sampleBytes = bytesDone.get();
        log("开始：" + displayLoader() + " / " + mcId);
    }
    
    public void stage(String s) { this.stage = s; }
    

    public void cancel() {
        if (finished()) return;
        cancelFlag = true;
        stage = "正在取消…";
        indeterminate = true;
    }

    public void tick() {
        long now = System.currentTimeMillis();
        long dt = now - sampleTime;
        if (dt >= 600L) {
            long cur = bytesDone.get();
            speed = Math.max(0d, (cur - sampleBytes) * 1000d / dt);
            sampleBytes = cur;
            sampleTime = now;
        }
    }

    public double progress() {
        if (state == DONE) return 1d;
        if (state == CANCELLED) return manual;
        long bt = bytesTotal.get();
        int ft = filesTotal.get();
        double byBytes = bt > 0 ? Math.min(1d, (double) bytesDone.get() / bt) : -1d;
        double byFiles = ft > 0 ? Math.min(1d, (double) filesDone.get() / ft) : -1d;
        if (byBytes >= 0 && byFiles >= 0) return byBytes * 0.6d + byFiles * 0.4d;
        if (byBytes >= 0) return byBytes;
        if (byFiles >= 0) return byFiles;
        return Math.max(0d, Math.min(1d, manual));
    }

    public boolean finished() {
        return state == DONE || state == FAILED || state == CANCELLED;
    }

    public void log(String s) {
        synchronized (log) {
            log.add(s);
            while (log.size() > 300) log.remove(0);
        }
    }

    public static String size(long b) {
        if (b < 1024L) return b + " B";
        if (b < 1024L * 1024L) return String.format("%.1f KB", b / 1024d);
        if (b < 1024L * 1024L * 1024L) return String.format("%.1f MB", b / 1048576d);
        return String.format("%.2f GB", b / 1073741824d);
    }
}
