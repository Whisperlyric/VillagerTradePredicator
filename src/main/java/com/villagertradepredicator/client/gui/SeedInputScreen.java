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
 * Plain world-seed entry: an editable value with confirm/cancel. This is the normal
 * input path for the seed (the warning-styled confirmation dialog is reserved for manual
 * edits of cached sequence values, which are the risky operation).
 */
public final class SeedInputScreen extends Screen {
    private static final Component TITLE = Component.literal("世界种子");
    private static final Component CONFIRM = Component.literal("确认");
    private static final Component CANCEL = Component.literal("取消");

    private final Screen parent;
    private final String initialSeed;
    private final Consumer<Long> onConfirmed;
    private EditBox seedBox;
    private Button confirmButton;

    public SeedInputScreen(Screen parent, String initialSeed, Consumer<Long> onConfirmed) {
        super(TITLE);
        this.parent = parent;
        this.initialSeed = initialSeed == null ? "" : initialSeed;
        this.onConfirmed = onConfirmed;
    }

    /** Opens this screen over the current one. */
    public static void open(Screen parent, String currentSeed, Consumer<Long> onConfirmed) {
        Minecraft.getInstance().setScreenAndShow(new SeedInputScreen(parent, currentSeed, onConfirmed));
    }

    @Override
    protected void init() {
        seedBox = new EditBox(font, this.width / 2 - 100, this.height / 2 - 10, 200, 20,
                Component.literal("世界种子"));
        seedBox.setValue(initialSeed);
        seedBox.setMaxLength(64);
        seedBox.setHint(Component.literal("十进制或 0x 十六进制"));
        addRenderableWidget(seedBox);

        confirmButton = Button.builder(CONFIRM, b -> confirm())
                .bounds(this.width / 2 - 154, this.height / 2 + 24, 150, 20)
                .build();
        addRenderableWidget(confirmButton);
        addRenderableWidget(Button.builder(CANCEL, b -> onClose())
                .bounds(this.width / 2 + 4, this.height / 2 + 24, 150, 20)
                .build());
        validate();
        setInitialFocus(seedBox);
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
        // 8-digit ARGB — high-version renderers drop colors with fewer channels
        graphics.centeredText(font, TITLE, this.width / 2, this.height / 2 - 40, 0xFFFFFFFF);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreenAndShow(parent);
    }
}
