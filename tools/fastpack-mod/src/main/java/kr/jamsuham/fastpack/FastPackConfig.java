package kr.jamsuham.fastpack;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import net.fabricmc.loader.api.FabricLoader;

/**
 * 설정 (config/fastpack.properties). 게임 안 설정 창(Mod Menu)이나 파일을 직접 고쳐서 바꾼다.
 * 파일은 처음 읽을 때 한 번 읽고, 설정 창에서 바꾸면 바로 저장한다. 파일을 손으로 고쳤으면 게임을 다시 켜면 반영된다.
 */
public final class FastPackConfig {
    private FastPackConfig() {}

    /** 게임을 다시 켜도 지난번 서버 팩을 처음 로딩에 미리 불러오기 (재접속 기능이 켜져 있어야 의미 있음) */
    public static volatile boolean keepAcrossRestart = true;
    /** 재접속할 때 같은 팩이면 다시 적용하지 않기 (나갈 때도 팩을 남겨 둠) */
    public static volatile boolean skipReapply = true;
    /** 처음 받는 팩을 여러 연결로 나눠 받기 */
    public static volatile boolean parallelDownload = true;
    /** 나만 보이는 채팅으로 [FastPack] 알림 보여 주기 */
    public static volatile boolean chatMessages = true;

    private static boolean loaded = false;

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("fastpack.properties");
    }

    /** 처음 한 번 파일을 읽는다 (없으면 기본값으로 만든다) */
    public static synchronized void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        Path f = file();
        Properties p = new Properties();
        try {
            if (Files.isRegularFile(f)) {
                try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) { p.load(r); }
            }
        } catch (IOException | IllegalArgumentException ignored) {
        }
        keepAcrossRestart = bool(p, "keepAcrossRestart", true);
        skipReapply = bool(p, "skipReapply", true);
        parallelDownload = bool(p, "parallelDownload", true);
        chatMessages = bool(p, "chatMessages", true);
        // 새 키가 생겼거나 파일이 없으면 모든 키를 적어 둔다
        if (!p.containsKey("keepAcrossRestart") || !p.containsKey("skipReapply")
                || !p.containsKey("parallelDownload") || !p.containsKey("chatMessages")) {
            save();
        }
    }

    private static boolean bool(Properties p, String key, boolean def) {
        String v = p.getProperty(key);
        if (v == null) return def;
        v = v.trim();
        if (v.equalsIgnoreCase("false")) return false;
        if (v.equalsIgnoreCase("true")) return true;
        return def;
    }

    public static synchronized void save() {
        Properties p = new Properties();
        p.setProperty("keepAcrossRestart", String.valueOf(keepAcrossRestart));
        p.setProperty("skipReapply", String.valueOf(skipReapply));
        p.setProperty("parallelDownload", String.valueOf(parallelDownload));
        p.setProperty("chatMessages", String.valueOf(chatMessages));
        try {
            Files.createDirectories(file().getParent());
            try (Writer w = Files.newBufferedWriter(file(), StandardCharsets.UTF_8)) {
                p.store(w, """
                        FastPack 설정 (게임 안에서는 Mod Menu > FastPack > 설정 에서 바꿀 수 있습니다)
                         keepAcrossRestart: 게임을 다시 켤 때 지난번 서버 리소스팩을 미리 불러와 접속을 빠르게 (게임 켜는 시간은 그만큼 늘어남)
                         skipReapply: 재접속할 때 같은 팩이면 다시 적용하지 않음 (나갈 때도 팩을 남겨 둠)
                         parallelDownload: 처음 받는 팩을 여러 연결로 나눠 받음
                         chatMessages: 나만 보이는 채팅으로 [FastPack] 알림 보여 주기""");
            }
        } catch (IOException ignored) {
        }
    }
}
