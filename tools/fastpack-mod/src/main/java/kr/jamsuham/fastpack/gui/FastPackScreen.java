package kr.jamsuham.fastpack.gui;

import kr.jamsuham.fastpack.FastPackConfig;
import kr.jamsuham.fastpack.FastPackLog;
import kr.jamsuham.fastpack.FastPackState;
import kr.jamsuham.fastpack.PackKeeper;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.CommonComponents;

/** 게임 안 설정 창 (Mod Menu → FastPack → 설정). 바꾸면 바로 config/fastpack.properties 에 저장 */
public class FastPackScreen extends Screen {
    private final Screen parent;
    private Button dropButton;
    private Component status = Component.empty();
    /** 다음 refresh 때 상태 줄에 한 번 보여 줄 문구 */
    private String message = null;

    public FastPackScreen(Screen parent) {
        super(Component.literal("FastPack 설정"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        FastPackConfig.ensureLoaded();
        int w = 260;
        int x = this.width / 2 - w / 2;
        int y = 40;
        int step = 24;

        addRenderableWidget(CycleButton.onOffBuilder(FastPackConfig.skipReapply)
                .create(x, y, w, 20, Component.literal("재접속 때 같은 팩 다시 적용 안 함"), (b, v) -> {
                    FastPackConfig.skipReapply = v;
                    FastPackConfig.save();
                    if (!v) {
                        // 끄면 남겨 둔 팩과 "다음 시작 때 미리 불러오기" 기록도 정리
                        dropKept();
                        FastPackState.clear();
                    }
                    refresh();
                })).setTooltip(Tooltip.create(Component.literal(
                "서버에서 나가도 팩을 내리지 않고, 같은 서버에 다시 들어와 똑같은 팩을 받으면 다시 읽지 않습니다 (가장 효과 큼)")));
        y += step;

        addRenderableWidget(CycleButton.onOffBuilder(FastPackConfig.keepAcrossRestart)
                .create(x, y, w, 20, Component.literal("게임을 다시 켜도 유지"), (b, v) -> {
                    FastPackConfig.keepAcrossRestart = v;
                    FastPackConfig.save();
                    if (!v) FastPackState.clear();
                    refresh();
                })).setTooltip(Tooltip.create(Component.literal(
                "게임을 켤 때 지난번 서버 팩을 미리 불러와 그 서버에 바로 들어갑니다. 게임 켜는 시간은 그만큼 늘어납니다. (위 기능이 켜져 있어야 동작)")));
        y += step;

        addRenderableWidget(CycleButton.onOffBuilder(FastPackConfig.parallelDownload)
                .create(x, y, w, 20, Component.literal("나눠 받기 (여러 연결로 다운로드)"), (b, v) -> {
                    FastPackConfig.parallelDownload = v;
                    FastPackConfig.save();
                })).setTooltip(Tooltip.create(Component.literal(
                "처음 받는 8MB 넘는 팩을 연결 6개로 나눠 동시에 받습니다. 서버가 지원하지 않으면 바닐라로 받습니다.")));
        y += step;

        addRenderableWidget(CycleButton.onOffBuilder(FastPackConfig.chatMessages)
                .create(x, y, w, 20, Component.literal("채팅 알림"), (b, v) -> {
                    FastPackConfig.chatMessages = v;
                    FastPackConfig.save();
                })).setTooltip(Tooltip.create(Component.literal(
                "\"다시 적용하지 않았습니다\", \"나눠 받았습니다\" 같은 알림을 나만 보이는 채팅으로 보여 줍니다.")));
        y += step + 8;

        dropButton = addRenderableWidget(Button.builder(Component.literal("남겨 둔 팩 지금 정리"), b -> {
            if (dropKept()) {
                message = "남겨 둔 팩을 정리했습니다 (리소스를 다시 읽는 중)";
            } else {
                message = "남겨 둔 팩이 없습니다";
            }
            refresh();
        }).bounds(x, y, w, 20).build());
        dropButton.setTooltip(Tooltip.create(Component.literal(
                "서버에서 나간 뒤에도 남아 있는 서버 리소스팩(글꼴·그림)을 지금 내립니다. 다음에 그 서버에 들어가면 다시 적용합니다.")));

        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose())
                .bounds(this.width / 2 - 100, this.height - 28, 200, 20).build());
        refresh();
    }

    /** 남겨 둔 팩이 있으면 정리 */
    private static boolean dropKept() {
        PackKeeper k = PackKeeper.Holder.instance;
        if (k == null) return false;
        try {
            return k.fastpack$dropKeptNow();
        } catch (Throwable t) {
            FastPackLog.log("남겨 둔 팩을 정리하지 못했습니다: " + t);
            return false;
        }
    }

    private void refresh() {
        PackKeeper k = PackKeeper.Holder.instance;
        String kept = k == null ? null : k.fastpack$keptServer();
        if (dropButton != null) dropButton.active = kept != null;
        if (message != null) {
            status = Component.literal(message);
            message = null;
        } else {
            status = Component.literal(kept != null ? "남겨 둔 팩: " + kept : "남겨 둔 팩: 없음");
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        super.render(g, mouseX, mouseY, delta);
        g.drawCenteredString(this.font, this.title, this.width / 2, 15, 0xFFFFFF);
        if (dropButton != null) {
            g.drawCenteredString(this.font, status, this.width / 2, dropButton.getY() + 26, 0xA0A0A0);
        }
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) this.minecraft.setScreen(parent);
    }
}
