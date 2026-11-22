package kr.jamsuham.fastpack.mixin;

import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.server.DownloadedPackSource;
import net.minecraft.client.resources.server.PackReloadConfig;
import net.minecraft.client.resources.server.ServerPackManager;
import net.minecraft.network.Connection;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.RepositorySource;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 재접속할 때 같은 서버 리소스팩을 다시 적용하지 않는다.
 *
 * 바닐라는 서버에서 나가면 팩을 내리느라 한 번, 다시 들어가면 같은 팩인데도 새로 올리느라 또 한 번 리소스를 전부 다시 읽는다
 * (큰 팩은 수십 초~1분 넘게). 모든 적용은 {@code startReload(Callbacks)} 를 거치므로 여기서:
 * <ul>
 *   <li>나가면서 팩을 내리는 적용 → 건너뛰고 팩을 그대로 둔다 ("남겨 둔 팩")</li>
 *   <li>들어와서 이미 적용돼 있는 것과 똑같은 팩(같은 파일들)을 올리는 적용 → 건너뛴다</li>
 *   <li>다른 팩·다른 서버·싱글 월드 → 바닐라처럼 다시 적용 (남겨 둔 팩은 정리)</li>
 * </ul>
 * 건너뛸 때도 바닐라와 똑같이 적용 성공 콜백을 불러서, 게임 내부 상태와 서버에 보내는 "적용 성공" 응답은 정상으로 처리된다.
 */
@Mixin(DownloadedPackSource.class)
public abstract class DownloadedPackSourceMixin {

    @Shadow @Final Minecraft minecraft;
    @Shadow private RepositorySource packSource;
    @Shadow private PackReloadConfig.Callbacks pendingReload;

    @Shadow private static RepositorySource configureSource(List<Pack> packs) { throw new AssertionError(); }
    @Shadow public abstract void onReloadSuccess();

    /** 지금 실제로 적용돼 있는 서버 팩 파일들 (순서대로). null = 모름(바닐라에 맡김) */
    @Unique private List<String> fastpack$loaded = List.of();
    /** 나갈 때 내리지 않고 남겨 둔 팩이 있는지 */
    @Unique private boolean fastpack$kept = false;
    /** 방금 서버에서 나갔다 (다음 "팩 내리기" 적용을 건너뜀) */
    @Unique private boolean fastpack$leaving = false;
    /** 팩을 남겨 둔 서버 주소 */
    @Unique private SocketAddress fastpack$keptFor = null;
    /** 지금 접속한 서버 주소 */
    @Unique private SocketAddress fastpack$current = null;

    @Unique
    private static List<String> fastpack$key(List<PackReloadConfig.IdAndPath> packs) {
        List<String> key = new ArrayList<>(packs.size());
        for (PackReloadConfig.IdAndPath p : packs) key.add(p.path().toAbsolutePath().normalize().toString());
        return key;
    }

    @Inject(method = "startReload", at = @At("HEAD"), cancellable = true, require = 1)
    private void fastpack$maybeSkip(PackReloadConfig.Callbacks callbacks, CallbackInfo ci) {
        List<String> want;
        try {
            want = fastpack$key(callbacks.packsToLoad());
        } catch (Throwable t) {
            fastpack$loaded = null;
            return; // 모르면 바닐라대로
        }
        boolean leaving = fastpack$leaving;
        fastpack$leaving = false;

        // 1) 서버에서 나가며 팩을 내리는 적용: 건너뛰고 남겨 둔다
        if (leaving && want.isEmpty() && fastpack$loaded != null && !fastpack$loaded.isEmpty()) {
            fastpack$kept = true;
            fastpack$keptFor = fastpack$current;
            System.out.println("[FastPack] 서버에서 나가도 리소스팩을 남겨 둡니다 (같은 서버에 다시 들어가면 바로 적용)");
            fastpack$finishWithoutReload(callbacks, ci);
            return;
        }
        // 2) 이미 적용돼 있는 것과 똑같은 팩: 건너뛴다
        if (!want.isEmpty() && Objects.equals(want, fastpack$loaded)) {
            fastpack$kept = false;
            System.out.println("[FastPack] 같은 리소스팩이 이미 적용돼 있어 다시 적용하지 않습니다");
            fastpack$finishWithoutReload(callbacks, ci);
            return;
        }
        // 3) 그 밖에는 바닐라대로 다시 적용 (남겨 둔 팩도 이걸로 정리됨)
        fastpack$kept = false;
        fastpack$loaded = want;
    }

    /** 리소스를 다시 읽지 않고, 바닐라가 적용을 끝냈을 때와 똑같이 성공 처리 */
    @Unique
    private void fastpack$finishWithoutReload(PackReloadConfig.Callbacks callbacks, CallbackInfo ci) {
        this.pendingReload = callbacks;
        this.onReloadSuccess();
        ci.cancel();
    }

    /** 적용이 실패해서 바닐라가 복구하면 무엇이 적용돼 있는지 모르므로 다음엔 바닐라대로 */
    @Inject(method = {"onRecovery", "onRecoveryFailure"}, at = @At("HEAD"), require = 1)
    private void fastpack$forget(CallbackInfo ci) {
        fastpack$loaded = null;
        fastpack$kept = false;
    }

    @Inject(method = "cleanupAfterDisconnect", at = @At("HEAD"), require = 1)
    private void fastpack$onLeave(CallbackInfo ci) {
        fastpack$leaving = true;
    }

    /** 서버에 들어갈 때: 다른 서버면 남겨 둔 팩을 정리한다 */
    @Inject(method = "configureForServerControl", at = @At("HEAD"), require = 1)
    private void fastpack$onJoinServer(Connection connection, ServerPackManager.PackPromptStatus status, CallbackInfo ci) {
        fastpack$leaving = false;
        SocketAddress addr = null;
        try {
            addr = connection.getRemoteAddress();
        } catch (Throwable ignored) {
        }
        fastpack$current = addr;
        if (fastpack$kept && !Objects.equals(addr, fastpack$keptFor)) {
            System.out.println("[FastPack] 다른 서버라서 남겨 둔 리소스팩을 정리합니다");
            fastpack$dropKept();
        }
    }

    /** 싱글 월드에 들어갈 때: 남겨 둔 서버 팩을 정리한다 */
    @Inject(method = "configureForLocalWorld", at = @At("HEAD"), require = 1)
    private void fastpack$onLocalWorld(CallbackInfo ci) {
        fastpack$leaving = false;
        fastpack$current = null;
        if (fastpack$kept) {
            System.out.println("[FastPack] 싱글 월드라서 남겨 둔 리소스팩을 정리합니다");
            fastpack$dropKept();
        }
    }

    @Unique
    private void fastpack$dropKept() {
        fastpack$kept = false;
        fastpack$keptFor = null;
        fastpack$loaded = List.of();
        this.packSource = configureSource(List.of());
        this.minecraft.reloadResourcePacks();
    }
}
