package kr.jamsuham.fastpack.mixin;

import com.google.common.hash.HashCode;
import com.google.common.hash.HashFunction;
import java.net.Proxy;
import java.net.URL;
import java.nio.file.Path;
import java.util.Map;
import kr.jamsuham.fastpack.ParallelDownload;
import net.minecraft.util.HttpUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 바닐라 다운로더(HttpUtil.downloadFile) 맨 앞에서 나눠 받기를 먼저 시도한다.
 * 성공했을 때만 결과를 돌려주고(cancel), 아니면 아무것도 안 하고 바닐라가 그대로 받는다.
 */
@Mixin(HttpUtil.class)
public abstract class HttpUtilMixin {

    @Inject(
            method = "downloadFile(Ljava/nio/file/Path;Ljava/net/URL;Ljava/util/Map;Lcom/google/common/hash/HashFunction;Lcom/google/common/hash/HashCode;ILjava/net/Proxy;Lnet/minecraft/util/HttpUtil$DownloadProgressListener;)Ljava/nio/file/Path;",
            at = @At("HEAD"),
            cancellable = true,
            require = 1)
    private static void fastpack$parallel(Path saveDir, URL url, Map<String, String> headers, HashFunction hashFunction,
                                          HashCode requestedHash, int maxSize, Proxy proxy,
                                          HttpUtil.DownloadProgressListener listener,
                                          CallbackInfoReturnable<Path> cir) {
        Path result = ParallelDownload.tryDownload(saveDir, url, headers, hashFunction, requestedHash, maxSize, proxy,
                new ParallelDownload.Progress() {
                    @Override public void requestStart() { listener.requestStart(); }
                    @Override public void downloadStart(long total) { listener.downloadStart(java.util.OptionalLong.of(total)); }
                    @Override public void downloadedBytes(long bytes) { listener.downloadedBytes(bytes); }
                    @Override public void requestFinished(boolean ok) { listener.requestFinished(ok); }
                });
        if (result != null) {
            cir.setReturnValue(result);
        }
    }
}
