package kr.jamsuham.fastpack.mixin;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 진단용: 이 모드의 믹스인이 실제로 게임에 적용되는지 확인 (게임이 켜질 때 한 번 찍힘) */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
    @Inject(method = "<init>", at = @At("TAIL"), require = 1)
    private void fastpack$selfTest(CallbackInfo ci) {
        System.out.println("[FastPack] 믹스인 적용됨 (게임 클래스에 연결 성공)");
    }
}
