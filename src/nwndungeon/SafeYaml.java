package nwndungeon;

import org.bukkit.configuration.file.YamlConfiguration;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 配置不得因解析失败变成空表；写入必须检查外部编辑并以临时文件替换。 */
public final class SafeYaml {
    private static final int MAX_BYTES = 8 * 1024 * 1024;
    public static final String MISSING = "missing";

    public static final class Problem extends RuntimeException {
        public Problem(String message) { super(message); }
        public Problem(String message, Throwable cause) { super(message, cause); }
    }

    public record Write(File file, YamlConfiguration yaml, String expectedRevision) { }

    private SafeYaml() { }

    public static YamlConfiguration read(File file) {
        try {
            if (!file.exists()) { return new YamlConfiguration(); }
            return parse(new String(bytes(file.toPath()), StandardCharsets.UTF_8), file.getPath());
        } catch (IOException e) {
            throw new Problem("不能读取配置 " + file.getPath() + "：" + e.getMessage(), e);
        }
    }

    public static YamlConfiguration parse(String text, String source) {
        try {
            LoaderOptions options = new LoaderOptions();
            options.setAllowDuplicateKeys(false);
            options.setMaxAliasesForCollections(50);
            options.setCodePointLimit(MAX_BYTES);
            Object root = new Yaml(new SafeConstructor(options)).load(text);
            if (root != null && !(root instanceof Map<?, ?>)) {
                throw new IllegalArgumentException("文件顶层必须是键值表");
            }
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.options().parseComments(true);
            yaml.loadFromString(text);
            return yaml;
        } catch (Exception e) {
            throw new Problem("配置格式错误 " + source + "：" + e.getMessage(), e);
        }
    }

    public static String revision(File file) {
        try { return file.exists() ? hash(bytes(file.toPath())) : MISSING; }
        catch (IOException e) { throw new Problem("不能检查配置版本：" + file, e); }
    }

    /** 所有文件先验证和准备；逐文件原子替换；中途失败则恢复已替换文件的原内容。 */
    public static void saveAll(List<Write> writes) {
        Map<Path, byte[]> old = new LinkedHashMap<>();
        Map<Path, Path> staged = new LinkedHashMap<>();
        Map<Path, String> writtenRevisions = new LinkedHashMap<>();
        List<Path> committed = new ArrayList<>();
        try {
            for (Write write : writes) {
                Path target = write.file().toPath().toAbsolutePath().normalize();
                if (old.containsKey(target)) { throw new Problem("一次保存中目标文件重复：" + target); }
                String current = revision(target.toFile());
                if (!current.equals(write.expectedRevision())) {
                    throw new Problem("文件已被外部修改，请先重载再保存：" + target.getFileName());
                }
                if (Files.exists(target)) { read(target.toFile()); }
                byte[] output = write.yaml().saveToString().getBytes(StandardCharsets.UTF_8);
                if (output.length > MAX_BYTES) { throw new Problem("配置超过 8 MB：" + target); }
                parse(new String(output, StandardCharsets.UTF_8), target.toString());
                old.put(target, Files.exists(target) ? bytes(target) : null);
                staged.put(target, stage(target, output));
                writtenRevisions.put(target, hash(output));
            }
            for (Map.Entry<Path, Path> entry : staged.entrySet()) {
                Path target = entry.getKey();
                String before = old.get(target) == null ? MISSING : hash(old.get(target));
                if (!revision(target.toFile()).equals(before)) {
                    throw new Problem("保存期间文件被外部修改：" + target.getFileName());
                }
                replace(entry.getValue(), target);
                committed.add(target);
            }
        } catch (Exception failure) {
            for (int i = committed.size() - 1; i >= 0; i--) {
                Path target = committed.get(i);
                try {
                    if (!revision(target.toFile()).equals(writtenRevisions.get(target))) {
                        throw new Problem("回滚前发现外部修改，保留管理员的新内容：" + target.getFileName());
                    }
                    if (old.get(target) == null) { Files.deleteIfExists(target); }
                    else { replace(stage(target, old.get(target)), target); }
                } catch (Exception restore) { failure.addSuppressed(restore); }
            }
            if (failure instanceof Problem problem) { throw problem; }
            throw new Problem("配置保存失败，已尝试恢复原文件：" + failure.getMessage(), failure);
        } finally {
            for (Path temporary : staged.values()) {
                try { Files.deleteIfExists(temporary); }
                catch (IOException ignored) { /* 已替换的文件不在临时路径上。 */ }
            }
        }
    }

    public static void save(File file, YamlConfiguration yaml, String expectedRevision) {
        saveAll(List.of(new Write(file, yaml, expectedRevision)));
    }

    private static Path stage(Path target, byte[] output) throws IOException {
        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), "." + target.getFileName() + "-", ".tmp");
        try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
            ByteBuffer buffer = ByteBuffer.wrap(output);
            while (buffer.hasRemaining()) { channel.write(buffer); }
            channel.force(true);
        } catch (IOException e) {
            Files.deleteIfExists(temporary);
            throw e;
        }
        return temporary;
    }

    private static void replace(Path from, Path to) throws IOException {
        try { Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException e) { Files.move(from, to, StandardCopyOption.REPLACE_EXISTING); }
    }

    private static byte[] bytes(Path path) throws IOException {
        if (Files.size(path) > MAX_BYTES) { throw new IOException("配置超过 8 MB：" + path); }
        return Files.readAllBytes(path);
    }

    private static String hash(byte[] data) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data)); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
