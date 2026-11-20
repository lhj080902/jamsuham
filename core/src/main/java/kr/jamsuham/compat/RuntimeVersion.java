package kr.jamsuham.compat;

import java.lang.reflect.Method;

/**
 * 안드로이드에는 {@code Runtime.version()} 이 없어서 ViaVersion 이 시작하자마자 멈춘다.
 * tools/ViaPatcher.java 가 Via jar 안의 호출을 이 클래스로 바꿔 준다.
 */
public final class RuntimeVersion {

    private static final RuntimeVersion INSTANCE = new RuntimeVersion(detect());

    private final int feature;

    private RuntimeVersion(int feature) {
        this.feature = feature;
    }

    public static RuntimeVersion version() {
        return INSTANCE;
    }

    public int feature() {
        return feature;
    }

    private static int detect() {
        // 테스트용: -Djamsuham.forceJavaFeature=8 로 안드로이드 구형 기기 경로를 흉내낸다
        String forced = System.getProperty("jamsuham.forceJavaFeature");
        if (forced != null) {
            try {
                return Integer.parseInt(forced.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        // 일반 JVM: 진짜 값을 쓴다
        try {
            Method m = Runtime.class.getMethod("version");
            Object v = m.invoke(null);
            return (Integer) v.getClass().getMethod("feature").invoke(v);
        } catch (Throwable ignored) {
        }
        // 안드로이드: CRC32C 가 있으면(안드로이드 14+) Java 17 수준, 없으면 8 로 보고
        // ViaVersion 이 직접 구현한 CRC32C 를 쓰게 한다.
        try {
            Class.forName("java.util.zip.CRC32C");
            return 17;
        } catch (Throwable ignored) {
            return 8;
        }
    }
}
