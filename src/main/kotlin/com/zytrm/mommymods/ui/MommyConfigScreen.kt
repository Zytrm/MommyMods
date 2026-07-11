package com.zytrm.mommymods.ui

import com.zytrm.mommymods.config.ModConfig
import com.zytrm.mommymods.feature.LouderCatch
import com.zytrm.mommymods.feature.LootingVMessage
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import org.lwjgl.glfw.GLFW
import kotlin.math.roundToInt

class MommyConfigScreen(private val parent: Screen?) : Screen(Component.literal("MommyMods")) {
    private enum class FeatureEntry(
        val title: String,
        val hasOptions: Boolean,
    ) {
        HIDE_LINE("Hide Fishing Line", false),
        LOUDER_CATCH("LouderCatch", true),
        PARTY_HELPER("FishingPartyHelper", true),
        LOOTING_MESSAGE("Looting V Message", true),
        JAWBUS_FINDER("Jawbus Finder", true);

        fun enabled(): Boolean = when (this) {
            HIDE_LINE -> ModConfig.values.hideFishingLine
            LOUDER_CATCH -> ModConfig.values.louderCatch
            PARTY_HELPER -> ModConfig.values.fishingPartyHelper
            LOOTING_MESSAGE -> ModConfig.values.lootingVMessageEnabled
            JAWBUS_FINDER -> ModConfig.values.jawbusFinder
        }

        fun setEnabled(value: Boolean) {
            when (this) {
                HIDE_LINE -> ModConfig.values.hideFishingLine = value
                LOUDER_CATCH -> ModConfig.values.louderCatch = value
                PARTY_HELPER -> ModConfig.values.fishingPartyHelper = value
                LOOTING_MESSAGE -> ModConfig.values.lootingVMessageEnabled = value
                JAWBUS_FINDER -> ModConfig.values.jawbusFinder = value
            }
        }
    }

    private data class FeatureCategory(val title: String, val features: List<FeatureEntry>)
    private data class PanelLayout(val category: FeatureCategory, val x: Int, val y: Int, val width: Int)
    private enum class DraggingSlider { VOLUME, PITCH }

    private val categories = listOf(
        FeatureCategory(
            "FISHING",
            listOf(
                FeatureEntry.HIDE_LINE,
                FeatureEntry.LOUDER_CATCH,
                FeatureEntry.PARTY_HELPER,
                FeatureEntry.LOOTING_MESSAGE,
                FeatureEntry.JAWBUS_FINDER,
            ),
        ),
    )

    private var openFeature: FeatureEntry? = null
    private var windowX = 0
    private var windowY = 0
    private var draggingWindow = false
    private var windowDragX = 0
    private var windowDragY = 0
    private var draggingSlider: DraggingSlider? = null
    private lateinit var lootingMessageBox: EditBox

    private val accent = 0xFFFF4F91.toInt()
    private val accentBright = 0xFFFF8BB7.toInt()
    private val accentMuted = 0xFF9E315B.toInt()
    private val panelBackground = 0xD9100C12.toInt()
    private val rowBackground = 0xC70A080B.toInt()
    private val rowHover = 0xE12A1722.toInt()
    private val rowEnabled = 0xD18F274F.toInt()
    private val windowBackground = 0xF20B080D.toInt()
    private val windowInner = 0xC9161019.toInt()
    private val textColor = 0xFFFFE8F0.toInt()
    private val mutedText = 0xFFCDB2BE.toInt()
    private val disabledText = 0xFF786A71.toInt()

    override fun init() {
        super.init()
        lootingMessageBox = EditBox(font, 0, 0, 200, 16, Component.literal("Jawbus chat message"))
        lootingMessageBox.setMaxLength(256)
        lootingMessageBox.setValue(ModConfig.values.lootingVMessage)
        lootingMessageBox.setResponder { value -> ModConfig.values.lootingVMessage = value }
        lootingMessageBox.visible = false
        addRenderableWidget(lootingMessageBox)
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        drawPanels(graphics, mouseX, mouseY)

        val selected = openFeature
        lootingMessageBox.visible = selected == FeatureEntry.LOOTING_MESSAGE
        if (selected != null) {
            graphics.fill(0, 0, width, height, 0x76000000)
            drawConfigWindow(graphics, selected, mouseX, mouseY)
        }

        super.extractRenderState(graphics, mouseX, mouseY, partialTick)
    }

    private fun drawPanels(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        panelLayouts().forEach { panel ->
            val headerHeight = 22
            val featureHeight = 16
            val totalHeight = headerHeight + panel.category.features.size * featureHeight

            graphics.fill(panel.x, panel.y, panel.x + panel.width, panel.y + totalHeight, panelBackground)
            graphics.fill(panel.x, panel.y, panel.x + panel.width, panel.y + 2, accent)
            graphics.centeredText(font, panel.category.title, panel.x + panel.width / 2, panel.y + 7, textColor)

            panel.category.features.forEachIndexed { index, feature ->
                val rowY = panel.y + headerHeight + index * featureHeight
                val hovered = mouseX in panel.x until (panel.x + panel.width) && mouseY in rowY until (rowY + featureHeight)
                val enabled = feature.enabled()
                val color = when {
                    hovered -> rowHover
                    enabled -> rowEnabled
                    else -> rowBackground
                }
                graphics.fill(panel.x, rowY, panel.x + panel.width, rowY + featureHeight - 1, color)
                graphics.fill(panel.x, rowY, panel.x + 2, rowY + featureHeight, if (enabled) accentBright else accentMuted)
                graphics.centeredText(font, feature.title, panel.x + panel.width / 2, rowY + 4, if (enabled) textColor else mutedText)
            }
        }
    }

    private fun drawConfigWindow(graphics: GuiGraphicsExtractor, feature: FeatureEntry, mouseX: Int, mouseY: Int) {
        val (windowWidth, windowHeight) = windowSize(feature)
        windowX = windowX.coerceIn(4, (width - windowWidth - 4).coerceAtLeast(4))
        windowY = windowY.coerceIn(4, (height - windowHeight - 4).coerceAtLeast(4))

        graphics.fill(windowX - 2, windowY - 2, windowX + windowWidth + 2, windowY + windowHeight + 2, 0xE0000000.toInt())
        graphics.fill(windowX, windowY, windowX + windowWidth, windowY + windowHeight, windowBackground)
        graphics.fill(windowX, windowY, windowX + windowWidth, windowY + 2, accent)
        graphics.fill(windowX + 2, windowY + 2, windowX + windowWidth - 2, windowY + 24, 0xF01A151C.toInt())
        graphics.centeredText(font, feature.title, windowX + windowWidth / 2, windowY + 8, textColor)

        val closeHovered = mouseX in (windowX + windowWidth - 21) until (windowX + windowWidth - 5) &&
            mouseY in (windowY + 4) until (windowY + 20)
        graphics.fill(
            windowX + windowWidth - 21,
            windowY + 4,
            windowX + windowWidth - 5,
            windowY + 20,
            if (closeHovered) accentMuted else 0xFF292329.toInt(),
        )
        graphics.centeredText(font, "x", windowX + windowWidth - 13, windowY + 7, textColor)

        when (feature) {
            FeatureEntry.LOUDER_CATCH -> drawLouderCatchWindow(graphics, windowWidth, mouseX, mouseY)
            FeatureEntry.PARTY_HELPER -> drawPartyHelperWindow(graphics, windowWidth, mouseX, mouseY)
            FeatureEntry.LOOTING_MESSAGE -> drawLootingMessageWindow(graphics, windowWidth, mouseX, mouseY)
            FeatureEntry.JAWBUS_FINDER -> drawJawbusWindow(graphics, windowWidth, mouseX, mouseY)
            FeatureEntry.HIDE_LINE -> Unit
        }
    }

    private fun drawLouderCatchWindow(graphics: GuiGraphicsExtractor, windowWidth: Int, mouseX: Int, mouseY: Int) {
        drawToggleSetting(graphics, windowY + 30, windowWidth, "Enabled", ModConfig.values.louderCatch, true, mouseX, mouseY)
        drawValueSetting(graphics, windowY + 52, windowWidth, "Sound", ModConfig.values.catchSound, mouseX, mouseY)
        drawSliderSetting(graphics, windowY + 74, windowWidth, "Volume", ModConfig.values.catchVolume, 0.1f, 20f, "${"%.1f".format(ModConfig.values.catchVolume)}x")
        drawSliderSetting(graphics, windowY + 96, windowWidth, "Pitch", ModConfig.values.catchPitch, 0.5f, 2f, "${"%.2f".format(ModConfig.values.catchPitch)}x")
        drawActionSetting(graphics, windowY + 118, windowWidth, "Test Sound", "PLAY", mouseX, mouseY)
    }

    private fun drawPartyHelperWindow(graphics: GuiGraphicsExtractor, windowWidth: Int, mouseX: Int, mouseY: Int) {
        drawToggleSetting(graphics, windowY + 30, windowWidth, "Enabled", ModConfig.values.fishingPartyHelper, true, mouseX, mouseY)
        drawToggleSetting(graphics, windowY + 52, windowWidth, "Auto Kick", ModConfig.values.autoKick, true, mouseX, mouseY)
        drawToggleSetting(graphics, windowY + 74, windowWidth, "No Looting V", ModConfig.values.kickNoLootingV, ModConfig.values.autoKick, mouseX, mouseY)
        drawToggleSetting(graphics, windowY + 96, windowWidth, "Can't Jawbus", ModConfig.values.kickCantJawbus, ModConfig.values.autoKick, mouseX, mouseY)
    }

    private fun drawLootingMessageWindow(graphics: GuiGraphicsExtractor, windowWidth: Int, mouseX: Int, mouseY: Int) {
        drawToggleSetting(
            graphics,
            windowY + 30,
            windowWidth,
            "Enabled",
            ModConfig.values.lootingVMessageEnabled,
            true,
            mouseX,
            mouseY,
        )
        graphics.fill(windowX + 10, windowY + 52, windowX + windowWidth - 10, windowY + 72, windowInner)
        graphics.text(font, "Message", windowX + 16, windowY + 58, textColor, false)
        lootingMessageBox.setX(windowX + 68)
        lootingMessageBox.setY(windowY + 54)
        lootingMessageBox.setWidth(windowWidth - 84)
        drawActionSetting(graphics, windowY + 74, windowWidth, "Test Message", "PREVIEW", mouseX, mouseY)
    }

    private fun drawJawbusWindow(graphics: GuiGraphicsExtractor, windowWidth: Int, mouseX: Int, mouseY: Int) {
        drawToggleSetting(graphics, windowY + 30, windowWidth, "Enabled", ModConfig.values.jawbusFinder, true, mouseX, mouseY)
        drawToggleSetting(
            graphics,
            windowY + 52,
            windowWidth,
            "Death Message Detection",
            ModConfig.values.deathMessageDetection,
            ModConfig.values.jawbusFinder,
            mouseX,
            mouseY,
        )
    }

    private fun drawToggleSetting(
        graphics: GuiGraphicsExtractor,
        y: Int,
        windowWidth: Int,
        label: String,
        value: Boolean,
        active: Boolean,
        mouseX: Int,
        mouseY: Int,
    ) {
        val hovered = active && mouseX in (windowX + 10) until (windowX + windowWidth - 10) && mouseY in y until (y + 20)
        graphics.fill(windowX + 10, y, windowX + windowWidth - 10, y + 20, if (hovered) rowHover else windowInner)
        graphics.text(font, label, windowX + 16, y + 6, if (active) textColor else disabledText, false)
        val switchX = windowX + windowWidth - 45
        graphics.fill(switchX, y + 5, switchX + 24, y + 15, if (value && active) accentMuted else 0xFF3D343A.toInt())
        val knobX = if (value && active) switchX + 14 else switchX + 2
        graphics.fill(knobX, y + 6, knobX + 8, y + 14, if (active) textColor else disabledText)
    }

    private fun drawValueSetting(
        graphics: GuiGraphicsExtractor,
        y: Int,
        windowWidth: Int,
        label: String,
        value: String,
        mouseX: Int,
        mouseY: Int,
    ) {
        val hovered = mouseX in (windowX + 10) until (windowX + windowWidth - 10) && mouseY in y until (y + 20)
        graphics.fill(windowX + 10, y, windowX + windowWidth - 10, y + 20, if (hovered) rowHover else windowInner)
        graphics.text(font, label, windowX + 16, y + 6, textColor, false)
        graphics.text(font, value, windowX + windowWidth - 16 - font.width(value), y + 6, accentBright, false)
    }

    private fun drawSliderSetting(
        graphics: GuiGraphicsExtractor,
        y: Int,
        windowWidth: Int,
        label: String,
        value: Float,
        min: Float,
        max: Float,
        display: String,
    ) {
        graphics.fill(windowX + 10, y, windowX + windowWidth - 10, y + 20, windowInner)
        graphics.text(font, label, windowX + 16, y + 5, textColor, false)
        val sliderX = windowX + 78
        val sliderWidth = windowWidth - 128
        val sliderY = y + 13
        val progress = ((value - min) / (max - min)).coerceIn(0f, 1f)
        graphics.fill(sliderX, sliderY, sliderX + sliderWidth, sliderY + 3, 0xFF44353C.toInt())
        graphics.fill(sliderX, sliderY, sliderX + (sliderWidth * progress).roundToInt(), sliderY + 3, accent)
        val knobX = sliderX + (sliderWidth * progress).roundToInt()
        graphics.fill(knobX - 2, sliderY - 4, knobX + 3, sliderY + 7, textColor)
        graphics.text(font, display, windowX + windowWidth - 16 - font.width(display), y + 5, accentBright, false)
    }

    private fun drawActionSetting(
        graphics: GuiGraphicsExtractor,
        y: Int,
        windowWidth: Int,
        label: String,
        action: String,
        mouseX: Int,
        mouseY: Int,
    ) {
        graphics.fill(windowX + 10, y, windowX + windowWidth - 10, y + 20, windowInner)
        graphics.text(font, label, windowX + 16, y + 6, textColor, false)
        val buttonX = windowX + windowWidth - 62
        val hovered = mouseX in buttonX until (windowX + windowWidth - 16) && mouseY in (y + 3) until (y + 17)
        graphics.fill(buttonX, y + 3, windowX + windowWidth - 16, y + 17, if (hovered) accent else accentMuted)
        graphics.centeredText(font, action, (buttonX + windowX + windowWidth - 16) / 2, y + 6, textColor)
    }

    override fun mouseClicked(event: MouseButtonEvent, isDoubleClick: Boolean): Boolean {
        val mouseX = event.x.toInt()
        val mouseY = event.y.toInt()

        openFeature?.let { feature ->
            val (windowWidth, _) = windowSize(feature)
            if (event.button() == 0 && mouseX in (windowX + windowWidth - 21) until (windowX + windowWidth - 5) &&
                mouseY in (windowY + 4) until (windowY + 20)
            ) {
                closeFeatureWindow()
                return true
            }
            if (event.button() == 0 && mouseX in windowX until (windowX + windowWidth - 23) && mouseY in windowY until (windowY + 24)) {
                draggingWindow = true
                windowDragX = mouseX - windowX
                windowDragY = mouseY - windowY
                return true
            }
            if (event.button() == 0 && handleWindowClick(feature, mouseX, mouseY)) return true
            return super.mouseClicked(event, isDoubleClick)
        }

        featureAt(mouseX, mouseY)?.let { feature ->
            when (event.button()) {
                0 -> {
                    feature.setEnabled(!feature.enabled())
                    ModConfig.save()
                    return true
                }
                1 -> {
                    if (feature.hasOptions) openFeatureWindow(feature)
                    return true
                }
            }
        }
        return super.mouseClicked(event, isDoubleClick)
    }

    private fun handleWindowClick(feature: FeatureEntry, mouseX: Int, mouseY: Int): Boolean {
        when (feature) {
            FeatureEntry.LOUDER_CATCH -> when (mouseY) {
                in (windowY + 30)..(windowY + 49) -> ModConfig.values.louderCatch = !ModConfig.values.louderCatch
                in (windowY + 52)..(windowY + 71) -> cycleSound()
                in (windowY + 74)..(windowY + 93) -> {
                    draggingSlider = DraggingSlider.VOLUME
                    updateSlider(mouseX)
                }
                in (windowY + 96)..(windowY + 115) -> {
                    draggingSlider = DraggingSlider.PITCH
                    updateSlider(mouseX)
                }
                in (windowY + 118)..(windowY + 137) -> LouderCatch.playConfigured()
                else -> return false
            }
            FeatureEntry.PARTY_HELPER -> when (mouseY) {
                in (windowY + 30)..(windowY + 49) -> ModConfig.values.fishingPartyHelper = !ModConfig.values.fishingPartyHelper
                in (windowY + 52)..(windowY + 71) -> ModConfig.values.autoKick = !ModConfig.values.autoKick
                in (windowY + 74)..(windowY + 93) -> if (ModConfig.values.autoKick) {
                    ModConfig.values.kickNoLootingV = !ModConfig.values.kickNoLootingV
                } else return false
                in (windowY + 96)..(windowY + 115) -> if (ModConfig.values.autoKick) {
                    ModConfig.values.kickCantJawbus = !ModConfig.values.kickCantJawbus
                } else return false
                else -> return false
            }
            FeatureEntry.LOOTING_MESSAGE -> when (mouseY) {
                in (windowY + 30)..(windowY + 49) -> {
                    ModConfig.values.lootingVMessageEnabled = !ModConfig.values.lootingVMessageEnabled
                }
                in (windowY + 74)..(windowY + 93) -> LootingVMessage.debugPreview()
                else -> return false
            }
            FeatureEntry.JAWBUS_FINDER -> when (mouseY) {
                in (windowY + 30)..(windowY + 49) -> ModConfig.values.jawbusFinder = !ModConfig.values.jawbusFinder
                in (windowY + 52)..(windowY + 71) -> if (ModConfig.values.jawbusFinder) {
                    ModConfig.values.deathMessageDetection = !ModConfig.values.deathMessageDetection
                } else return false
                else -> return false
            }
            FeatureEntry.HIDE_LINE -> return false
        }
        ModConfig.save()
        return true
    }

    override fun mouseDragged(event: MouseButtonEvent, deltaX: Double, deltaY: Double): Boolean {
        if (draggingWindow) {
            val feature = openFeature ?: return true
            val (windowWidth, windowHeight) = windowSize(feature)
            windowX = (event.x.toInt() - windowDragX).coerceIn(4, (width - windowWidth - 4).coerceAtLeast(4))
            windowY = (event.y.toInt() - windowDragY).coerceIn(4, (height - windowHeight - 4).coerceAtLeast(4))
            return true
        }
        if (draggingSlider != null) {
            updateSlider(event.x.toInt())
            return true
        }
        return super.mouseDragged(event, deltaX, deltaY)
    }

    override fun mouseReleased(event: MouseButtonEvent): Boolean {
        if (draggingWindow || draggingSlider != null) {
            draggingWindow = false
            draggingSlider = null
            ModConfig.save()
            return true
        }
        return super.mouseReleased(event)
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        if (event.key == GLFW.GLFW_KEY_ESCAPE && openFeature != null) {
            closeFeatureWindow()
            return true
        }
        return super.keyPressed(event)
    }

    private fun openFeatureWindow(feature: FeatureEntry) {
        openFeature = feature
        val (windowWidth, windowHeight) = windowSize(feature)
        windowX = (width - windowWidth) / 2
        windowY = (height - windowHeight) / 2
    }

    private fun closeFeatureWindow() {
        draggingWindow = false
        draggingSlider = null
        lootingMessageBox.visible = false
        openFeature = null
        ModConfig.save()
    }

    private fun cycleSound() {
        val choices = LouderCatch.choices
        val current = choices.indexOfFirst { it.label == ModConfig.values.catchSound }.coerceAtLeast(0)
        ModConfig.values.catchSound = choices[(current + 1) % choices.size].label
    }

    private fun updateSlider(mouseX: Int) {
        val feature = openFeature ?: return
        val (windowWidth, _) = windowSize(feature)
        val sliderX = windowX + 78
        val sliderWidth = windowWidth - 128
        val progress = ((mouseX - sliderX) / sliderWidth.toFloat()).coerceIn(0f, 1f)
        when (draggingSlider) {
            DraggingSlider.VOLUME -> ModConfig.values.catchVolume = ((0.1f + progress * 19.9f) * 10).roundToInt() / 10f
            DraggingSlider.PITCH -> ModConfig.values.catchPitch = ((0.5f + progress * 1.5f) * 20).roundToInt() / 20f
            null -> Unit
        }
    }

    private fun featureAt(mouseX: Int, mouseY: Int): FeatureEntry? {
        panelLayouts().forEach { panel ->
            panel.category.features.forEachIndexed { index, feature ->
                val y = panel.y + 22 + index * 16
                if (mouseX in panel.x until (panel.x + panel.width) && mouseY in y until (y + 16)) return feature
            }
        }
        return null
    }

    private fun panelLayouts(): List<PanelLayout> {
        val panelWidth = 110
        val gap = 10
        val startX = 10
        val top = 10
        return categories.mapIndexed { index, category ->
            PanelLayout(category, startX + index * (panelWidth + gap), top, panelWidth)
        }
    }

    private fun windowSize(feature: FeatureEntry): Pair<Int, Int> = when (feature) {
        FeatureEntry.LOUDER_CATCH -> 230 to 142
        FeatureEntry.PARTY_HELPER -> 230 to 120
        FeatureEntry.LOOTING_MESSAGE -> 300 to 98
        FeatureEntry.JAWBUS_FINDER -> 230 to 76
        FeatureEntry.HIDE_LINE -> 210 to 70
    }

    override fun onClose() {
        ModConfig.save()
        super.onClose()
    }

    override fun isPauseScreen(): Boolean = false
    override fun isInGameUi(): Boolean = true
}
