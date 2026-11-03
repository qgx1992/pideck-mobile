package dev.pideck.mobile;

import android.content.Context;
import java.io.File;

public final class LiveRun {
    public static void main(String[] args) throws Exception {
        Context.CACHE = new File(System.getProperty("java.io.tmpdir"), "piweblive");
        Context.CACHE.mkdirs();
        LocalWebServer server = new LocalWebServer(new Context(), args[0], args[1]);
        String origin = server.start();
        System.out.println("LIVE_ORIGIN " + origin);
        Thread.sleep(120_000);
    }
}
