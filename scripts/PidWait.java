import java.util.concurrent.TimeUnit;

/** Waits for a process to end: {@code java PidWait.java <pid> <millis>}; exit 0 when it is gone (or never existed), 1 when it still runs after the time. */
public final class PidWait {
    public static void main(String[] a) throws Exception {
        var p = ProcessHandle.of(Long.parseLong(a[0]));
        if (p.isEmpty() || !p.get().isAlive()) return;
        try { p.get().onExit().get(Long.parseLong(a[1]), TimeUnit.MILLISECONDS); } catch (java.util.concurrent.TimeoutException e) { System.exit(1); }
    }
}
