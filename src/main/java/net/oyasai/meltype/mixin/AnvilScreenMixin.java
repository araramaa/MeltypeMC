package net.oyasai.meltype.mixin;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.AnvilScreen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.oyasai.meltype.MeltypeClient;
import net.oyasai.meltype.gui.CompositionRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(AnvilScreen.class)
public abstract class AnvilScreenMixin {

    @Shadow
    private TextFieldWidget nameField;

    @Shadow
    protected int backgroundWidth;

    @Shadow
    protected int backgroundHeight;

    /**
     * 金床のアイテム名入力欄（nameField）に対する文字入力インターセプト
     */
    @Inject(method = "charTyped", at = @At("HEAD"), cancellable = true)
    private void onCharTyped(char chr, int modifiers, CallbackInfoReturnable<Boolean> cir) {
        if (this.nameField != null && this.nameField.isActive()) {
            String currentText = this.nameField.getText();
            boolean handled = MeltypeClient.INSTANCE.getSession().onCharTyped(
                    chr,
                    currentText,
                    text -> {
                        if (this.nameField != null) {
                            this.nameField.write(text);
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
     * 金床でのキー入力（Space, Enter, Backspace等）のインターセプト
     */
    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void onKeyPressed(int keyCode, int scanCode, int modifiers, CallbackInfoReturnable<Boolean> cir) {
        if (this.nameField != null && this.nameField.isActive()) {
            boolean handled = MeltypeClient.INSTANCE.getSession().onKeyPressed(
                    keyCode,
                    scanCode,
                    modifiers,
                    text -> {
                        if (this.nameField != null) {
                            this.nameField.write(text);
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
     * 金床GUI上での変換候補・プレビュー描画
     */
    @Inject(method = "render", at = @At("TAIL"))
    private void onRender(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        AnvilScreen self = (AnvilScreen) (Object) this;
        CompositionRenderer.INSTANCE.renderAnvil(
                context,
                MeltypeClient.INSTANCE.getSession(),
                self.width,
                self.height,
                this.backgroundWidth,
                this.backgroundHeight
        );
    }

    /**
     * 画面終了時のリセット
     */
    @Inject(method = "removed", at = @At("HEAD"))
    private void onRemoved(CallbackInfo ci) {
        MeltypeClient.INSTANCE.getSession().reset();
    }
}
