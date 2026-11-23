package kr.jamsuham.fastpack.gui;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/** Mod Menu 의 "설정" 버튼 → FastPack 설정 창. Mod Menu 가 없으면 이 클래스는 불리지 않는다 */
public class ModMenuIntegration implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return FastPackScreen::new;
    }
}
