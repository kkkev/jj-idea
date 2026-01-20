package `in`.kkkev.jjidea.ui.log

import com.intellij.icons.AllIcons
import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.ListPopup
import com.intellij.ui.ClickListener
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.StartupUiUtil
import com.intellij.util.ui.UIUtil
import java.awt.*
import java.awt.event.*
import javax.swing.*
import javax.swing.border.Border

/**
 * Base class for VCS log filter components.
 *
 * Displays a filter as a clickable component with:
 * - Grey label showing filter name (e.g., "Bookmark")
 * - Value in label color when filter is active (e.g., "Bookmark: master")
 * - Dropdown arrow icon when no filter, X icon when filter is active
 */
abstract class JujutsuFilterComponent(private val displayName: String) : JBPanel<JujutsuFilterComponent>(null) {
    companion object {
        // Base values that will be scaled via JBUI.scale() for HiDPI support
        private const val GAP_BEFORE_ARROW_BASE = 3
        private const val BORDER_SIZE_BASE = 2
        private const val ARC_SIZE_BASE = 10

        // Use these scaled values in the UI
        private fun gapBeforeArrow() = JBUI.scale(GAP_BEFORE_ARROW_BASE)

        private fun borderSize() = BORDER_SIZE_BASE // Border size handled by JBUI.insets()

        private fun arcSize() = ARC_SIZE_BASE // Arc size handled in paintBorder
    }

    private lateinit var nameLabel: JBLabel
    private lateinit var valueLabel: JBLabel
    private lateinit var filterButton: InlineIconButton
    private val changeListeners = mutableListOf<Runnable>()

    /**
     * Initialize the UI components after the subclass is fully constructed.
     * Must be called by subclass or factory method.
     */
    fun initUi(): JComponent {
        // Create labels
        nameLabel = DynamicLabel { if (isValueSelected()) "$displayName: " else displayName }
        valueLabel = DynamicLabel { getCurrentText() }

        // Create button
        filterButton = InlineIconButton(AllIcons.Actions.Close)

        // Setup component
        setDefaultForeground()
        isFocusable = true
        border = wrapBorder(createUnfocusedBorder())
        layout = BoxLayout(this, BoxLayout.X_AXIS)

        // Set vertical alignment for all components to center
        nameLabel.alignmentY = Component.CENTER_ALIGNMENT
        valueLabel.alignmentY = Component.CENTER_ALIGNMENT
        filterButton.alignmentY = Component.CENTER_ALIGNMENT

        // Add components
        add(nameLabel)
        add(valueLabel)
        add(Box.createHorizontalStrut(gapBeforeArrow()))
        add(filterButton)

        // Setup filter button action
        filterButton.setActionListener {
            if (!isEnabled) return@setActionListener
            if (isValueSelected()) {
                resetFilter()
            } else {
                showPopup()
            }
        }

        updateFilterButton()

        // Setup change listener
        installChangeListener {
            setDefaultForeground()
            updateFilterButton()
            valueLabel.revalidate()
            valueLabel.repaint()
        }

        showPopupMenuOnClick()
        showPopupMenuFromKeyboard()
        indicateHovering()
        indicateFocusing()
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)

        return this
    }

    /**
     * Get the current display text for the filter value.
     * Returns empty string when no filter is active.
     */
    protected abstract fun getCurrentText(): String

    /**
     * Check if a value is currently selected/filtered.
     */
    protected abstract fun isValueSelected(): Boolean

    /**
     * Create the action group for the popup menu.
     */
    protected abstract fun createActionGroup(): ActionGroup

    /**
     * Reset the filter to its default state.
     */
    protected abstract fun doResetFilter()

    /**
     * Add a change listener that will be notified when the filter changes.
     */
    fun addChangeListener(listener: Runnable) {
        changeListeners.add(listener)
    }

    /**
     * Notify all change listeners.
     */
    protected fun notifyFilterChanged() {
        changeListeners.forEach { it.run() }
    }

    private fun installChangeListener(onChange: Runnable) {
        addChangeListener(onChange)
    }

    private fun updateFilterButton() {
        val selected = isValueSelected() && isEnabled
        filterButton.isEnabled = isEnabled
        filterButton.setIcon(if (selected) AllIcons.Actions.Close else AllIcons.General.ArrowDown)
        filterButton.setHoveredIcon(if (selected) AllIcons.Actions.CloseHovered else AllIcons.General.ArrowDown)
        filterButton.isFocusable = selected
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        setDefaultForeground()
        updateFilterButton()
    }

    private fun setDefaultForeground() {
        setForeground(false)
    }

    private fun setOnHoverForeground() {
        setForeground(true)
    }

    private fun setForeground(isHovered: Boolean) {
        val isEnabled = isEnabled
        if (isEnabled && isHovered) {
            nameLabel.foreground =
                if (StartupUiUtil.isUnderDarcula) UIUtil.getLabelForeground() else UIUtil.getTextAreaForeground()
            valueLabel.foreground =
                if (StartupUiUtil.isUnderDarcula) UIUtil.getLabelForeground() else UIUtil.getTextFieldForeground()
        } else {
            nameLabel.foreground =
                if (isEnabled) UIUtil.getLabelInfoForeground() else UIUtil.getLabelDisabledForeground()
            valueLabel.foreground = if (isEnabled) UIUtil.getLabelForeground() else UIUtil.getLabelDisabledForeground()
        }
    }

    private fun resetFilter() {
        doResetFilter()
    }

    private fun showPopup() {
        val popup = createPopupMenu()
        popup.showUnderneathOf(this)
    }

    private fun createPopupMenu(): ListPopup = JBPopupFactory.getInstance().createActionGroupPopup(
        null,
        createActionGroup(),
        DataManager.getInstance().getDataContext(this),
        JBPopupFactory.ActionSelectionAid.SPEEDSEARCH,
        false
    )

    private fun createFocusedBorder(): Border =
        FilledRoundedBorder(UIUtil.getFocusedBorderColor(), arcSize(), borderSize(), false)

    private fun createUnfocusedBorder(): Border = JBUI.Borders.empty(borderSize())

    private fun wrapBorder(outerBorder: Border): Border =
        BorderFactory.createCompoundBorder(outerBorder, JBUI.Borders.empty(2))

    private fun indicateFocusing() {
        addFocusListener(
            object : FocusAdapter() {
                override fun focusGained(e: FocusEvent) {
                    if (!isEnabled) return
                    border = wrapBorder(createFocusedBorder())
                }

                override fun focusLost(e: FocusEvent) {
                    border = wrapBorder(createUnfocusedBorder())
                }
            }
        )
    }

    private fun showPopupMenuFromKeyboard() {
        addKeyListener(
            object : KeyAdapter() {
                override fun keyPressed(e: KeyEvent) {
                    if (!isEnabled) return
                    if (e.keyCode == KeyEvent.VK_ENTER || e.keyCode == KeyEvent.VK_DOWN) {
                        showPopup()
                    }
                    if (e.keyCode == KeyEvent.VK_DELETE) {
                        resetFilter()
                    }
                }
            }
        )
    }

    private fun showPopupMenuOnClick() {
        val clickListener = object : ClickListener() {
            override fun onClick(
                event: MouseEvent,
                clickCount: Int
            ): Boolean {
                if (!isEnabled) return false
                if (UIUtil.isCloseClick(event, MouseEvent.MOUSE_RELEASED)) {
                    resetFilter()
                } else {
                    showPopup()
                }
                return true
            }
        }
        clickListener.installOn(this)
        clickListener.installOn(valueLabel)
        clickListener.installOn(nameLabel)
    }

    private fun indicateHovering() {
        val mouseAdapter = object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) {
                setOnHoverForeground()
            }

            override fun mouseExited(e: MouseEvent) {
                setDefaultForeground()
            }
        }
        addMouseListener(mouseAdapter)
        filterButton.addMouseListener(mouseAdapter)
        nameLabel.addMouseListener(mouseAdapter)
        valueLabel.addMouseListener(mouseAdapter)
    }

    /**
     * Dynamic label that computes text on demand.
     *
     * Note: getText() can be called during superclass initialization before textSupplier is assigned.
     */
    private inner class DynamicLabel(
        private val textSupplier: () -> String
    ) : JBLabel("") {
        override fun getText(): String {
            // Check for null - can be called during superclass initialization
            @Suppress("SENSELESS_COMPARISON")
            return if (textSupplier == null) "" else textSupplier()
        }

        override fun getMinimumSize(): Dimension {
            val size = super.getMinimumSize()
            size.width = minOf(size.width, JBUI.scale(70))
            return size
        }
    }

    /**
     * Filled rounded border for focus indication.
     */
    private class FilledRoundedBorder(
        private val color: Color,
        private val arcSize: Int,
        private val thickness: Int,
        private val thinBorder: Boolean
    ) : Border {
        override fun paintBorder(c: Component, g: Graphics, x: Int, y: Int, width: Int, height: Int) {
            val g2d = g.create() as Graphics2D
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2d.color = color

            val t = JBUI.scale(if (thinBorder) 1 else thickness)
            val arc = JBUI.scale(arcSize)

            // Draw outer rounded rectangle
            g2d.drawRoundRect(x, y, width - 1, height - 1, arc, arc)

            g2d.dispose()
        }

        override fun getBorderInsets(c: Component): Insets = JBUI.insets(thickness)

        override fun isBorderOpaque(): Boolean = false
    }

    /**
     * Inline icon button for dropdown/close icon.
     */
    private class InlineIconButton(icon: Icon) : JComponent() {
        private var icon: Icon = icon
        private var hoveredIcon: Icon? = null
        private var isHovered = false
        private var actionListener: ActionListener? = null

        init {
            val size = Dimension(icon.iconWidth, icon.iconHeight)
            preferredSize = size
            minimumSize = size
            maximumSize = size

            addMouseListener(
                object : MouseAdapter() {
                    override fun mouseEntered(e: MouseEvent) {
                        isHovered = true
                        repaint()
                    }

                    override fun mouseExited(e: MouseEvent) {
                        isHovered = false
                        repaint()
                    }

                    override fun mouseClicked(e: MouseEvent) {
                        if (isEnabled) {
                            actionListener?.actionPerformed(
                                ActionEvent(this@InlineIconButton, ActionEvent.ACTION_PERFORMED, "click")
                            )
                        }
                    }
                }
            )
        }

        fun setIcon(icon: Icon) {
            this.icon = icon
            val size = Dimension(icon.iconWidth, icon.iconHeight)
            preferredSize = size
            minimumSize = size
            maximumSize = size
            repaint()
        }

        fun setHoveredIcon(icon: Icon) {
            this.hoveredIcon = icon
            repaint()
        }

        fun setActionListener(listener: (ActionEvent) -> Unit) {
            this.actionListener = ActionListener(listener)
        }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val iconToPaint = if (isHovered && hoveredIcon != null) hoveredIcon!! else icon
            // Center the icon vertically within the component bounds
            val x = 0
            val y = (height - iconToPaint.iconHeight) / 2
            iconToPaint.paintIcon(this, g, x, y)
        }
    }
}
