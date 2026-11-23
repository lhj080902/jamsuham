package kr.jamsuham.fastpack;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import net.fabricmc.loader.api.FabricLoader;

/**
 * "마지막으로 적용한 서버 팩"을 config 폴더에 저장해 두고, 게임을 다시 켤 때 미리 불러오는 데 쓴다.
 * 끄려면 config/fastpack.properties 의 keepAcrossRestart=false
 */
public final class FastPackState {
    private FastPackState() {}

    public record Entry(UUID id, Path path) {}
    public record Saved(String server, List<Entry> packs) {}

    private static Path dir() {
        return FabricLoader.getInstance().getConfigDir();
    }

    private static Path stateFile() {
        return dir().resolve("fastpack-lastpack.properties");
    }

    /** 설정: 게임을 다시 켜도 지난번 서버 팩을 미리 불러올지 (기본 켜짐). 파일이 없으면 기본값으로 만들어 둔다 */
    public static boolean keepAcrossRestart() {
        Path f = dir().resolve("fastpack.properties");
        Properties p = new Properties();
        try {
            if (Files.isRegularFile(f)) {
                try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) { p.load(r); }
            } else {
                p.setProperty("keepAcrossRestart", "true");
                Files.createDirectories(f.getParent());
                try (Writer w = Files.newBufferedWriter(f, StandardCharsets.UTF_8)) {
                    p.store(w, "FastPack: keepAcrossRestart=true 면 게임을 다시 켤 때 지난번 서버 리소스팩을 미리 불러와 접속을 빠르게 합니다 (게임 켜는 시간은 그만큼 늘어남)");
                }
            }
        } catch (IOException ignored) {
        }
        return !"false".equalsIgnoreCase(p.getProperty("keepAcrossRestart", "true").trim());
    }

    public static Saved load() {
        if (!keepAcrossRestart()) return null;
        Path f = stateFile();
        if (!Files.isRegularFile(f)) return null;
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
            p.load(r);
        } catch (IOException e) {
            return null;
        }
        String server = p.getProperty("server");
        int n;
        try {
            n = Integer.parseInt(p.getProperty("count", "0"));
        } catch (NumberFormatException e) {
            return null;
        }
        if (server == null || n <= 0) return null;
        List<Entry> list = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            String id = p.getProperty("pack." + i + ".id");
            String path = p.getProperty("pack." + i + ".path");
            if (id == null || path == null) return null;
            try {
                list.add(new Entry(UUID.fromString(id), Path.of(path)));
            } catch (Exception e) {
                return null;
            }
        }
        return new Saved(server, list);
    }

    public static void save(Saved s) {
        if (!keepAcrossRestart()) return;
        Properties p = new Properties();
        p.setProperty("server", s.server());
        p.setProperty("count", String.valueOf(s.packs().size()));
        for (int i = 0; i < s.packs().size(); i++) {
            p.setProperty("pack." + i + ".id", s.packs().get(i).id().toString());
            p.setProperty("pack." + i + ".path", s.packs().get(i).path().toAbsolutePath().normalize().toString());
        }
        try {
            Files.createDirectories(stateFile().getParent());
            try (Writer w = Files.newBufferedWriter(stateFile(), StandardCharsets.UTF_8)) {
                p.store(w, "FastPack: 마지막으로 적용한 서버 리소스팩 (자동 생성)");
            }
        } catch (IOException ignored) {
        }
    }

    public static void clear() {
        try {
            Files.deleteIfExists(stateFile());
        } catch (IOException ignored) {
        }
    }
}
