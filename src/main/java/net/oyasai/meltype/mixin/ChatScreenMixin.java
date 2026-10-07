package net.oyasai.meltype.mixin;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.oyasai.meltype.MeltypeClient;
import net.oyasai.meltype.gui.CompositionRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ChatScreen.class)
public abstract class ChatScreenMixin {

    @Shadow
    protected TextFieldWidget chatField;

    @Shadow
    public int width;

    @Shadow
    public int height;

    /**
     * 文字入力イベント（charTyped）のインターセプト
     */
    @Inject(method = "charTyped", at = @At("HEAD"), cancellable = true)
    private void onCharTyped(char chr, int modifiers, CallbackInfoReturnable<Boolean> cir) {
        try {
            String currentText = this.chatField != null ? this.chatField.getText() : "";
            boolean handled = MeltypeClient.INSTANCE.getSession().onCharTyped(
                    chr,
                    currentText,
                    text -> {
                        if (this.chatField != null) {
                            this.chatField.write(text);
                        }
                        return null;
                    }
            );

            if (handled) {
                cir.setReturnValue(true);
            }
        } catch (Throwable ignored) {
            // 例外発生時もゲームを落とさず通常入力にフォールバック
        }
    }

    /**
     * 特殊キー入力イベント（keyPressed: Space, Enter, Backspace等）のインターセプト
     */
    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void onKeyPressed(int keyCode, int scanCode, int modifiers, CallbackInfoReturnable<Boolean> cir) {
        try {
            boolean handled = MeltypeClient.INSTANCE.getSession().onKeyPressed(
                    keyCode,
                    scanCode,
                    modifiers,
                    text -> {
                        if (this.chatField != null) {
                            this.chatField.write(text);
                        }
                        return null;
                    }
            );

            if (handled) {
                cir.setReturnValue(true);
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 描画フェーズ（render）での下線プレビュー・変換候補ウィンドウ描画
     */
    @Inject(method = "render", at = @At("TAIL"))
    private void onRender(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        try {
            CompositionRenderer.INSTANCE.renderChat(
                    context,
                    MeltypeClient.INSTANCE.getSession(),
                    this.width,
                    this.height
            );
        } catch (Throwable ignored) {
        }
    }

    /**
     * チャット画面終了時のセッションリセット
     */
    @Inject(method = "removed", at = @At("HEAD"))
    private void onRemoved(CallbackInfo ci) {
        try {
            MeltypeClient.INSTANCE.getSession().reset();
            MeltypeClient.INSTANCE.getSlashCommandGate().reset();
        } catch (Throwable ignored) {
        }
    }
}
