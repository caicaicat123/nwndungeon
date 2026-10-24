package nwndungeon;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/** 失败发生时才写入，独立于控制台过滤；当前与上一份日志各最多约 1 MB。 */
public final class FailureDiagnostics {
    private FailureDiagnostics() { }

    public static void record(NWNDungeon plugin, String context, String reason, Throwable cause) {
        try { append(plugin.getDataFolder(), context, reason, cause); }
        catch (IOException e) { plugin.getLogger().severe("诊断日志写入失败：" + e.getMessage()); }
    }

    static synchronized void append(File folder, String context, String reason, Throwable cause) throws IOException {
        appendNamed(folder, "failures", context, reason, cause);
    }

    public static void bossHealth(NWNDungeon plugin, String context, String detail) {
        try { appendBossHealth(plugin.getDataFolder(), context, detail); }
        catch (IOException e) { plugin.getLogger().warning("首领血量追踪日志写入失败：" + e.getMessage()); }
    }

    static synchronized void appendBossHealth(File folder, String context, String detail) throws IOException {
        appendNamed(folder, "boss-health", context, detail, null);
    }

    private static void appendNamed(File folder, String name, String context, String reason, Throwable cause) throws IOException {
        var directory = folder.toPath().resolve("diagnostics");
        Files.createDirectories(directory);
        var file = directory.resolve(name + ".log");
        if (Files.exists(file) && Files.size(file) >= 1024 * 1024) {
            Files.move(file, directory.resolve(name + ".previous.log"), StandardCopyOption.REPLACE_EXISTING);
        }
        String trace = "";
        if (cause != null) {
            StringWriter buffer = new StringWriter();
            cause.printStackTrace(new PrintWriter(buffer));
            trace = buffer.toString();
            if (trace.length() > 16_384) { trace = trace.substring(0, 16_384) + "\n[stack truncated]\n"; }
        }
        String entry = "[" + java.time.Instant.now() + "] " + context + "\n原因：" + reason + "\n" + trace + "\n";
        Files.writeString(file, entry, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
}
