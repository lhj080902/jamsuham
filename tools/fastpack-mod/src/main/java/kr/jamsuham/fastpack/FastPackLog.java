package kr.jamsuham.fastpack;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 로그 + 나만 보이는 채팅 알림.
 * 팩 처리는 접속 설정 단계(아직 채팅 창이 없음)나 다운로드 스레드에서 일어나서 바로 채팅에 띄우면 안 보인다 →
 * 큐에 모아 두었다가 MinecraftMixin 이 매 틱 플레이어가 생겼을 때 꺼내 채팅에 넣는다.
 * 루나 클라이언트는 SLF4J 로그가 안 보여서 System.out 으로도 찍는다.
 */
public final class FastPackLog {
    private FastPackLog() {}

    public record Pending(String text, long at) {}

    /** 이보다 오래된 알림은 버린다 (나가 있는 동안 쌓인 것이 다음 접속 때 엉뚱하게 뜨지 않게) */
    private static final long MAX_AGE_MS = 3 * 60_000L;
    private static final int MAX_QUEUE = 20;
    private static final ConcurrentLinkedQueue<Pending> QUEUE = new ConcurrentLinkedQueue<>();

    /** 로그만 */
    public static void log(String msg) {
        String line = "[FastPack] " + msg;
        System.out.println(line);
        ParallelDownload.LOG.info(line);
    }

    /** 로그 + (설정이 켜져 있으면) 채팅 알림 */
    public static void chat(String msg) {
        log(msg);
        if (!FastPackConfig.chatMessages) return;
        QUEUE.add(new Pending(msg, System.currentTimeMillis()));
        while (QUEUE.size() > MAX_QUEUE) QUEUE.poll();
    }

    /** 내보낼 알림을 꺼낸다 (오래된 것은 버림). 메인 스레드에서 부른다 */
    public static List<String> drain() {
        List<String> out = new ArrayList<>();
        long now = System.currentTimeMillis();
        Pending p;
        while ((p = QUEUE.poll()) != null) {
            if (now - p.at() <= MAX_AGE_MS) out.add(p.text());
        }
        return out;
    }

    public static boolean hasPending() {
        return !QUEUE.isEmpty();
    }
}
