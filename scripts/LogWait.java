import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

/** Waits until a line of a log file matches a regex: {@code java LogWait.java <file> <regex> <timeoutMillis>}. Prints the first matching line, exit 0; exit 1 on timeout. Scans from the start of the file. */
public final class LogWait {
    public static void main(String[] a) throws Exception {
        Path f = Path.of(a[0]); Pattern p = Pattern.compile(a[1]); long end = System.currentTimeMillis() + Long.parseLong(a[2]);
        while (true) {
            if (Files.exists(f)) for (String line : Files.readAllLines(f, StandardCharsets.ISO_8859_1)) if (p.matcher(line).find()) { System.out.println(line); return; }
            if (System.currentTimeMillis() > end) System.exit(1);
            Thread.sleep(250);
        }
    }
}
