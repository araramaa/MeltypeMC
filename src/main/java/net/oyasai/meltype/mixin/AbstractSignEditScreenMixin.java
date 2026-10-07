package net.oyasai.meltype.mixin;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.AbstractSignEditScreen;
import net.minecraft.client.util.SelectionManager;
import net.oyasai.meltype.MeltypeClient;
import net.oyasai.meltype.gui.CompositionRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(AbstractSignEditScreen.class)
public abstract class AbstractSignEditScreenMixin {

    @Shadow
    private SelectionManager selectionManager;

    @Shadow
    public int width;

    @Shadow
    public int height;

    /**
     * 看板編集画面での文字入力インターセプト
     */
    @Inject(method = "charTyped", at = @At("HEAD"), cancellable = true)
    private void onCharTyped(char chr, int modifiers, CallbackInfoReturnable<Boolean> cir) {
        if (this.selectionManager != null) {
            boolean handled = MeltypeClient.INSTANCE.getSession().onCharTyped(
                    chr,
                    "",
                    text -> {
                        if (this.selectionManager != null) {
                            this.selectionManager.insert(text);
                        }
                        return null;
                    }
            );

            if (handled) {
                cir.setReturnValue(true);
            }
        }
    }

    /**
     * 看板編集画面での特殊キー入力インターセプト（Space, Enter, Backspace等）
     */
    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void onKeyPressed(int keyCode, int scanCode, int modifiers, CallbackInfoReturnable<Boolean> cir) {
        if (this.selectionManager != null) {
            boolean handled = MeltypeClient.INSTANCE.getSession().onKeyPressed(
                    keyCode,
                    scanCode,
                    modifiers,
                    text -> {
                        if (this.selectionManager != null) {
                            this.selectionManager.insert(text);
                        }
                        return null;
                    }
            );

            if (handled) {
                cir.setReturnValue(true);
            }
        }
    }

    /**
     * 看板画面下部でのプレビュー・変換候補描画
     */
    @Inject(method = "render", at = @At("TAIL"))
    private void onRender(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        CompositionRenderer.INSTANCE.renderSign(
                context,
                MeltypeClient.INSTANCE.getSession(),
                this.width,
                this.height
        );
    }

    /**
     * 看板画面終了時のリセット
     */
    @Inject(method = "removed", at = @At("HEAD"))
    private void onRemoved(CallbackInfo ci) {
        MeltypeClient.INSTANCE.getSession().reset();
    }
}
