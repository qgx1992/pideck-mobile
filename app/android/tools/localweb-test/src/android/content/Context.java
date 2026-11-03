package android.content;
import java.io.File;
public class Context {
    public static File CACHE = new File(System.getProperty("java.io.tmpdir"), "piwebc");
    public Context getApplicationContext() { return this; }
    public File getCacheDir() { return CACHE; }
}
