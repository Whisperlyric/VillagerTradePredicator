package com.villagertradepredicator.client.gui;

import java.util.OptionalLong;
import java.util.function.Consumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Manual world-seed entry, styled after the vanilla experiment-confirmation dialog:
 * a warning, an editable value, and an explicit "I know what I'm doing" gate.
 *
 * <p>Used when a server's world seed is needed but not derivable (most multiplayer
 * setups; only some proxy configurations expose it). Manual edits are normally only
 * correct after a world rollback or when the proxy address changed to a different
 * backend — hence the wording and the confirmation gate.</p>
 */
public final class SeedConfirmScreen extends Screen {
    private static final Component TITLE = Component.literal("手动输入世界种子");
    private static final Component WARNING_1 =
            Component.literal("在你真的了解自己在做什么时，请不要手动编辑这一数值！");
    private static final Component WARNING_2 =
            Component.literal("这一数值的手动更改通常发生在回档或更换端口对应的服务器时");
    private static final Component CONFIRM_LABEL = Component.literal("我知道我在做什么");
    private static final Component CANCEL_LABEL = Component.literal("取消");

    private final Screen parent;
    private final String initialSeed;
    private final Consumer<Long> onConfirmed;
    private EditBox seedBox;
    private Button confirmButton;

    public SeedConfirmScreen(Screen parent, String initialSeed, Consumer<Long> onConfirmed) {
        super(TITLE);
        this.parent = parent;
        this.initialSeed = initialSeed == null ? "" : initialSeed;
        this.onConfirmed = onConfirmed;
    }

    /** Opens this screen over the current one. */
    public static void open(Screen parent, String currentSeed, Consumer<Long> onConfirmed) {
        Minecraft.getInstance().setScreenAndShow(new SeedConfirmScreen(parent, currentSeed, onConfirmed));
    }

    @Override
    protected void init() {
        seedBox = new EditBox(font, this.width / 2 - 100, this.height / 2 - 10, 200, 20,
                Component.literal("世界种子"));
        seedBox.setValue(initialSeed);
        seedBox.setHint(Component.literal("十进制或 0x 十六进制"));
        addRenderableWidget(seedBox);

        confirmButton = Button.builder(CONFIRM_LABEL, b -> confirm())
                .bounds(this.width / 2 - 154, this.height / 2 + 24, 150, 20)
                .build();
        addRenderableWidget(confirmButton);
        addRenderableWidget(Button.builder(CANCEL_LABEL, b -> onClose())
                .bounds(this.width / 2 + 4, this.height / 2 + 24, 150, 20)
                .build());
        validate();
    }

    @Override
    public void tick() {
        super.tick();
        validate();
    }

    private void validate() {
        confirmButton.active = parseSeed().isPresent();
    }

    private OptionalLong parseSeed() {
        String text = seedBox.getValue().trim();
        if (text.isEmpty()) {
            return OptionalLong.empty();
        }
        try {
            return OptionalLong.of(Long.decode(text));
        } catch (NumberFormatException e) {
            return OptionalLong.empty();
        }
    }

    private void confirm() {
        OptionalLong seed = parseSeed();
        if (seed.isPresent()) {
            onConfirmed.accept(seed.getAsLong());
            onClose();
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        graphics.centeredText(font, TITLE, this.width / 2, this.height / 2 - 78, 0xFFFFFFFF);
        // 8-digit ARGB — high-version renderers drop colors with fewer channels
        graphics.centeredText(font, WARNING_1, this.width / 2, this.height / 2 - 52, 0xFFFF5555);
        graphics.centeredText(font, WARNING_2, this.width / 2, this.height / 2 - 38, 0xFFFFFFFF);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreenAndShow(parent);
    }
}
