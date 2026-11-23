package kr.jamsuham.fastpack.mixin;

import kr.jamsuham.fastpack.FastPackLog;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
    /** 진단용: 이 모드의 믹스인이 실제로 게임에 적용되는지 확인 (게임이 켜질 때 한 번 찍힘) */
    @Inject(method = "<init>", at = @At("TAIL"), require = 1)
    private void fastpack$selfTest(CallbackInfo ci) {
        FastPackLog.log("믹스인 적용됨 (게임 클래스에 연결 성공)");
    }

    /** 모아 둔 [FastPack] 알림을 플레이어가 생기면(서버에 들어간 뒤) 나만 보이는 채팅으로 내보낸다 */
    @Inject(method = "tick", at = @At("HEAD"), require = 1)
    private void fastpack$flushChat(CallbackInfo ci) {
        if (!FastPackLog.hasPending()) return;
        Minecraft mc = (Minecraft) (Object) this;
        if (mc.player == null || mc.gui == null) return;
        for (String msg : FastPackLog.drain()) {
            mc.gui.getChat().addMessage(Component.literal("[FastPack] ").withStyle(ChatFormatting.AQUA)
                    .append(Component.literal(msg).withStyle(ChatFormatting.GRAY)));
        }
    }
}
