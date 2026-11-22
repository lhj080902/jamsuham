package kr.jamsuham.fastpack;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;

/**
 * 모드가 켜졌는지 로그로 남긴다 (진단용).
 * 루나 클라이언트는 자체 로더(ichor)로 Fabric 모드를 돌리는데, 일반 로거 기록이 안 보일 수 있어 System.out 으로도 찍는다.
 */
public class FastPackClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        boolean lunar = FabricLoader.getInstance().isModLoaded("ichor");
        String msg = "[FastPack] 켜짐 (루나 클라이언트: " + (lunar ? "예" : "아니오") + ")";
        System.out.println(msg);
        ParallelDownload.LOG.info(msg);
    }
}
