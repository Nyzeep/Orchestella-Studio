package io.github.nyzeep.orchestella.ui.theme

import android.app.Activity
import android.app.Application
import android.app.Dialog
import android.content.Context
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.database.DataSetObserver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckedTextView
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ListAdapter
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.SpinnerAdapter
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.view.WindowCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.navigation.NavigationBarView
import com.google.android.material.navigation.NavigationView
import com.google.android.material.progressindicator.BaseProgressIndicator
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputLayout
import io.github.nyzeep.orchestella.ui.RingProgressView
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import java.util.concurrent.Executors
import io.github.nyzeep.orchestella.ui.theme.ThemeColors.Role as ColorRole

/**
 * Recolors existing Android Views without replacing their layout, corners, or typography.
 * ThemeStore installs the lifecycle bridge; bind() also covers the first Activity immediately.
 * Terminal renderers consume ThemeStore separately; guest surfaces and brand artwork are excluded.
 */
object ThemeApplier {
    enum class Role { AUTO, PRIMARY_ACTION, SECONDARY_ACTION, DANGER_ACTION, NAVIGATION, PRESERVE }

    private val main = Handler(Looper.getMainLooper())
    private val bindings = WeakHashMap<View, ViewBinding>()
    private val roles = WeakHashMap<View, Role>()
    private val dialogs = WeakHashMap<Dialog, RootBinding>()
    private var registration: Registration? = null

    /** Safe to call repeatedly. Uses only application context and existing appearance preferences. */
    @JvmStatic
    fun install(context: Context) {
        if (registration != null) return
        val app = context.applicationContext as? Application ?: return
        registration = Registration(app).also { it.start() }
        if (context is Activity) {
            val reference = WeakReference(context)
            main.post { reference.get()?.takeUnless { it.isDestroyed }?.let(::bind) }
        }
    }

    /** Call before super.onCreate when possible, so light/dark resources inflate consistently. */
    @JvmStatic
    fun prepare(activity: Activity) {
        install(activity)
        if (activity is AppCompatActivity) registration?.store?.applyNightMode(activity)
    }

    /** Call after setContentView and initial View binding. Later Activities are bound automatically. */
    @JvmStatic
    fun bind(activity: Activity) {
        install(activity)
        registration?.bind(activity)
    }

    /** Apply to a new card, navigation group or other subtree after updating its UI state. */
    @JvmStatic
    @JvmOverloads
    fun apply(root: View, theme: AppTheme = ThemeStore(root.context).resolve(root.context)) {
        walk(root, theme, force = true)
    }

    /** Call after show(); Dialog windows are separate from the Activity content tree. */
    @JvmStatic
    fun apply(dialog: Dialog) {
        val theme = ThemeStore(dialog.context).resolve(dialog.context)
        dialog.window?.let { window ->
            // Sheets need transparent window space outside their themed content and existing scrim.
            window.setBackgroundDrawable(ColorDrawable(if (dialog is BottomSheetDialog) Color.TRANSPARENT else theme.surface))
            val root = window.decorView as? ViewGroup ?: return@let
            val binding = dialogs[dialog]?.takeIf { it.root.get() === root } ?: RootBinding(root, theme).also {
                dialogs[dialog] = it
                // ListView choice rows are often inflated after show(); catch those on the first layout.
                val layout = ViewTreeObserver.OnGlobalLayoutListener { it.root.get()?.let { root -> walk(root, it.theme, false) } }
                it.listener = layout
                root.viewTreeObserver.addOnGlobalLayoutListener(layout)
                root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(view: View) = Unit
                    override fun onViewDetachedFromWindow(view: View) {
                        if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnGlobalLayoutListener(layout)
                        view.removeOnAttachStateChangeListener(this)
                    }
                })
            }
            binding.theme = theme
            walk(root, theme, force = true)
        }
    }

    /** An explicit role takes precedence over legacy color inference. PRESERVE excludes a subtree. */
    @JvmStatic
    fun setRole(view: View, role: Role) {
        if (roles[view] == role) return
        roles[view] = role
        bindings.remove(view)
    }

    private class ViewBinding(
        var textRole: ColorRole,
        var backgroundRole: ColorRole,
        var buttonRole: Role,
    ) {
        var theme: AppTheme? = null
        var background = WeakReference<Drawable>(null)
        var backgroundColor: Int? = null
        var textColor: Int? = null
        var selected = false
        var enabled = true
        var checked = false
        var containerFill: Int? = null
    }

    private class RootBinding(root: ViewGroup, var theme: AppTheme) {
        val root = WeakReference(root)
        var wallpaper: String? = null
        var applied = false
        var cornerRadius = -1
        var listener: ViewTreeObserver.OnGlobalLayoutListener? = null
    }

    private class Registration(val app: Application) : Application.ActivityLifecycleCallbacks {
        val prefs = app.getSharedPreferences(ThemeStore.PREFS_NAME, Context.MODE_PRIVATE)
        val store = ThemeStore(prefs, app)
        val activities = WeakHashMap<Activity, RootBinding>()
        private var imageUri: String? = null
        private var image: Bitmap? = null
        private var imageGeneration = 0
        private val imageExecutor by lazy { Executors.newSingleThreadExecutor() }
        // SharedPreferences retains weak listener references; this owner retains it strongly.
        private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            main.post {
                activities.keys.toList().filterNot { it.isDestroyed }.forEach(::bind)
                dialogs.keys.toList().filter { it.isShowing }.forEach { apply(it) }
            }
        }

        fun start() {
            app.registerActivityLifecycleCallbacks(this)
            prefs.registerOnSharedPreferenceChangeListener(listener)
        }

        fun bind(activity: Activity) {
            if (activity.isDestroyed) return
            // Live changes only repaint: changing the delegate here would recreate in-flight editors/wizards.
            val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
            val theme = store.resolve(app)
            val binding = activities[activity]?.takeIf { it.root.get() === content } ?: RootBinding(content, theme).also {
                activities.remove(activity)?.let(::detach)
                activities[activity] = it
                val weakRoot = it.root
                val layoutListener = ViewTreeObserver.OnGlobalLayoutListener {
                    weakRoot.get()?.let { root -> walk(root, it.theme, force = false) }
                }
                it.listener = layoutListener
                content.viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
            }
            val changed = binding.theme != theme
            binding.theme = theme
            val radius = store.cornerRadiusDp()
            val radiusChanged = binding.cornerRadius != radius
            binding.cornerRadius = radius
            walk(content, theme, force = changed || radiusChanged)
            val terminal = activity.javaClass.simpleName == "TerminalActivity"
            val background = if (terminal) theme.terminalBackground else theme.background
            activity.window.setBackgroundDrawable(ColorDrawable(background))
            val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
            controller.isAppearanceLightStatusBars = !theme.isDark
            controller.isAppearanceLightNavigationBars = !theme.isDark
            activity.window.statusBarColor = background
            activity.window.navigationBarColor = background
            val page = content.getChildAt(0) ?: return
            // Wallpaper belongs behind app chrome, never over terminal or guest pixels.
            val uri = if (terminal || activity.javaClass.simpleName == "DesktopActivity") null else store.backgroundUri()
            if (!binding.applied || changed || binding.wallpaper != uri || page.background == null) {
                binding.applied = true
                binding.wallpaper = uri
                page.setBackgroundColor(background)
                bindings[page]?.let {
                    it.backgroundRole = ColorRole.BACKGROUND
                    it.background = WeakReference(page.background)
                    it.backgroundColor = background
                }
                if (uri != null) requestImage(uri)
            }
            if (uri != null && imageUri == uri && image != null && page.background !is LayerDrawable) {
                setWallpaper(page, image!!, theme)
            }
        }

        private fun requestImage(uri: String) {
            if (uri == imageUri) return // One attempt per selection; revoked access does not retry in a layout loop.
            imageUri = uri
            image = null
            val generation = ++imageGeneration
            if (!uri.startsWith("content://")) return
            imageExecutor.execute {
                val decoded = try {
                    val parsed = Uri.parse(uri)
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    app.contentResolver.openInputStream(parsed)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) null else {
                        var sample = 1
                        while (bounds.outWidth / sample > 2048 || bounds.outHeight / sample > 2048) sample *= 2
                        val options = BitmapFactory.Options().apply { inSampleSize = sample }
                        app.contentResolver.openInputStream(parsed)?.use { BitmapFactory.decodeStream(it, null, options) }
                    }
                } catch (_: Exception) {
                    null // Keep the user's saved selection; fall back to the theme's solid background.
                } catch (_: OutOfMemoryError) {
                    null
                }
                main.post {
                    if (generation == imageGeneration) {
                        image = decoded
                        activities.keys.toList().filterNot { it.isDestroyed }.forEach(::bind)
                    }
                }
            }
        }

        private fun detach(binding: RootBinding) {
            binding.root.get()?.viewTreeObserver?.takeIf { it.isAlive }?.let { observer ->
                binding.listener?.let(observer::removeOnGlobalLayoutListener)
            }
        }

        override fun onActivityCreated(activity: Activity, state: Bundle?) = bind(activity)
        override fun onActivityStarted(activity: Activity) = bind(activity)
        override fun onActivityResumed(activity: Activity) = bind(activity)
        override fun onActivityDestroyed(activity: Activity) { activities.remove(activity)?.let(::detach) }
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
    }

    private fun setWallpaper(page: View, bitmap: Bitmap, theme: AppTheme) {
        val image = BitmapDrawable(page.resources, bitmap).apply { gravity = Gravity.FILL }
        page.background = LayerDrawable(arrayOf(image, ColorDrawable((theme.background and 0x00FFFFFF) or 0xB0000000.toInt())))
        // The root is an intentional composite, not a legacy solid to remap on the next layout.
        bindings[page]?.let { it.background = WeakReference(page.background); it.backgroundColor = null }
    }

    private fun walk(view: View, theme: AppTheme, force: Boolean, containerFill: Int? = null) {
        val name = resourceName(view)
        if (roles[view] == Role.PRESERVE || view is SurfaceView || view is TextureView ||
            view.javaClass.simpleName.contains("TerminalView") || name.contains("wayland_surface")) return
        val currentBackground = backgroundColor(view)
        val currentText = (view as? TextView)?.currentTextColor
        val checked = (view as? CompoundButton)?.isChecked ?: (view as? MaterialButton)?.isChecked ?: false
        val selected = view.isSelected || checked
        val state = bindings.getOrPut(view) {
            ViewBinding(
                inferTextRole(view, currentText ?: theme.text, theme),
                inferBackgroundRole(currentBackground ?: theme.surface, theme),
                roles[view]?.takeUnless { it == Role.AUTO } ?: when {
                    listOf("delete", "force_stop", "emergency_stop").any(name::contains) -> Role.DANGER_ACTION
                    view is Button && (currentBackground == Color.TRANSPARENT ||
                        (view is MaterialButton && view.strokeWidth > 0)) -> Role.SECONDARY_ACTION
                    else -> Role.PRIMARY_ACTION
                },
            )
        }
        val backgroundChanged = state.background.get() !== view.background || state.backgroundColor != currentBackground
        if (state.theme == null || backgroundChanged) {
            state.backgroundRole = inferBackgroundRole(currentBackground ?: theme.surface, theme)
        }
        if (force || state.theme != theme || backgroundChanged || state.textColor != currentText ||
            state.selected != selected || state.enabled != view.isEnabled || state.checked != checked ||
            state.containerFill != containerFill) {
            colorView(view, name, theme, state, containerFill)
            state.theme = theme
            state.background = WeakReference(view.background)
            state.backgroundColor = backgroundColor(view)
            state.textColor = (view as? TextView)?.currentTextColor
            state.selected = selected
            state.enabled = view.isEnabled
            state.checked = checked
            state.containerFill = containerFill
        }
        // Material navigation manages its own checked state and internal item backgrounds.
        if (view is NavigationBarView || view is NavigationView) return
        if (view is ViewGroup) {
            val childFill = when {
                roles[view] == Role.NAVIGATION -> if (selected) theme.primary else theme.surface
                currentBackground != null && Color.alpha(currentBackground) > 200 &&
                    state.backgroundRole in setOf(ColorRole.PRIMARY, ColorRole.ACCENT, ColorRole.TEXT) ->
                    ThemeColors.color(theme, state.backgroundRole)
                else -> containerFill
            }
            for (index in 0 until view.childCount) walk(view.getChildAt(index), theme, force, childFill)
        }
    }

    private fun colorView(view: View, name: String, theme: AppTheme, binding: ViewBinding, containerFill: Int?) {
        val outline = ThemeColors.color(theme, ColorRole.OUTLINE)
        val muted = ThemeColors.color(theme, ColorRole.MUTED)
        val disabled = ThemeColors.blend(theme.surface, theme.text, 0.45f)
        val actionColor = if (binding.buttonRole == Role.DANGER_ACTION) 0xFFEF4444.toInt() else theme.primary
        val primaryInk = ThemeColors.onColor(actionColor)
        val normalInk = ThemeColors.readable(theme.text, containerFill ?: theme.surface)
        when (view) {
            is MaterialCardView -> {
                view.setCardBackgroundColor(theme.surface)
                view.strokeColor = outline
                // Corner radius is the one deliberate geometry override: 0 keeps the XML look.
                val radiusPx = cornerRadiusPx(view)
                if (radiusPx > 0f) {
                    view.shapeAppearanceModel = view.shapeAppearanceModel.toBuilder()
                        .setAllCornerSizes(radiusPx).build()
                }
            }
            is MaterialButton -> {
                val secondary = binding.buttonRole == Role.SECONDARY_ACTION
                val fill = if (secondary) theme.surface else actionColor
                val ink = if (secondary) normalInk else primaryInk
                view.backgroundTintList = states(ThemeColors.blend(theme.surface, fill, 0.3f), actionColor, fill)
                view.setTextColor(states(disabled, primaryInk, ink))
                view.iconTint = states(disabled, primaryInk, ink)
                view.strokeColor = states(outline, actionColor, if (secondary) outline else fill)
                view.rippleColor = ColorStateList.valueOf((theme.text and 0x00FFFFFF) or 0x24000000)
                val radiusPx = cornerRadiusPx(view)
                if (radiusPx > 0f) view.cornerRadius = radiusPx.toInt()
            }
            is MaterialToolbar -> {
                view.setBackgroundColor(theme.background)
                view.setTitleTextColor(normalInk)
                view.setSubtitleTextColor(muted)
                view.navigationIcon = view.navigationIcon?.mutate()?.apply { setTint(normalInk) }
                view.overflowIcon = view.overflowIcon?.mutate()?.apply { setTint(normalInk) }
                for (index in 0 until view.menu.size()) {
                    val item = view.menu.getItem(index)
                    item.icon = item.icon?.mutate()?.apply { setTint(normalInk) }
                }
            }
            is NavigationBarView -> {
                view.setBackgroundColor(theme.surface)
                view.itemIconTintList = states(disabled, ThemeColors.readable(theme.primary, theme.surface), muted)
                view.itemTextColor = view.itemIconTintList
            }
            is NavigationView -> {
                view.setBackgroundColor(theme.surface)
                view.itemIconTintList = states(disabled, ThemeColors.readable(theme.primary, theme.surface), normalInk)
                view.itemTextColor = view.itemIconTintList
            }
            is TextInputLayout -> {
                view.boxBackgroundColor = theme.surface
                view.boxStrokeColor = theme.primary
                view.defaultHintTextColor = ColorStateList.valueOf(muted)
                view.hintTextColor = ColorStateList.valueOf(ThemeColors.readable(theme.primary, theme.surface))
            }
            is Slider -> {
                view.thumbTintList = ColorStateList.valueOf(theme.primary)
                view.trackActiveTintList = ColorStateList.valueOf(theme.primary)
                view.trackInactiveTintList = ColorStateList.valueOf(outline)
                view.haloTintList = ColorStateList.valueOf((theme.primary and 0x00FFFFFF) or 0x33000000)
            }
            is RingProgressView -> view.configure(theme.primary, normalInk, muted, view.resources.displayMetrics.density)
            else -> {
                if (isWallpaperContentWrapper(view, name)) {
                    // root_coordinator alone owns page color/wallpaper. Keep theming the wrapper's children.
                    view.backgroundTintList = null
                    view.setBackgroundColor(Color.TRANSPARENT)
                } else if (!isStatus(name)) {
                    recolorBackground(view, binding.backgroundRole, theme, outline)
                }
            }
        }
        if (view is TextView && view !is MaterialButton && !isStatus(name)) {
            val backgroundRole = binding.backgroundRole
            val filled = view.background != null && backgroundColor(view)?.let { Color.alpha(it) > 200 } == true &&
                backgroundRole in setOf(ColorRole.PRIMARY, ColorRole.ACCENT, ColorRole.TEXT)
            val foreground = when {
                filled -> ThemeColors.onColor(ThemeColors.color(theme, backgroundRole))
                view is Button && view !is CompoundButton -> if (binding.buttonRole == Role.SECONDARY_ACTION) normalInk else primaryInk
                else -> ThemeColors.readable(ThemeColors.color(theme, binding.textRole), containerFill ?: theme.surface)
            }
            view.setTextColor(states(disabled, foreground, foreground))
            view.setHintTextColor(muted)
            view.highlightColor = (theme.primary and 0x00FFFFFF) or 0x50000000
            view.compoundDrawableTintList = ColorStateList.valueOf(foreground)
            if (view is Button && view !is CompoundButton) {
                val fill = if (binding.buttonRole == Role.SECONDARY_ACTION) theme.surface else actionColor
                view.backgroundTintList = states(outline, actionColor, fill)
            }
        }
        if (view is CheckedTextView) view.checkMarkTintList = states(disabled, theme.primary, muted)
        if (view is EditText) view.backgroundTintList = states(outline, theme.primary, outline)
        if (view is CompoundButton) {
            view.buttonTintList = states(disabled, theme.primary, muted)
            if (view is SwitchCompat) {
                view.thumbTintList = states(disabled, primaryInk, normalInk)
                view.trackTintList = states(outline, theme.primary, outline)
            }
        }
        if (view is ProgressBar) {
            if (view is BaseProgressIndicator<*>) {
                view.setIndicatorColor(theme.primary)
                view.trackColor = outline
            } else {
                view.progressTintList = ColorStateList.valueOf(theme.primary)
                view.progressBackgroundTintList = ColorStateList.valueOf(outline)
                view.indeterminateTintList = ColorStateList.valueOf(theme.primary)
            }
            if (view is SeekBar) view.thumbTintList = ColorStateList.valueOf(theme.primary)
        }
        if (view is ImageView && !isArtwork(name) && (view is ImageButton || view.imageTintList != null)) {
            view.imageTintList = states(disabled, normalInk, normalInk)
        }
        if (view is Spinner) {
            view.setPopupBackgroundDrawable(ColorDrawable(theme.surface))
            val adapter = view.adapter
            if (adapter != null && adapter !is ThemeSpinnerAdapter) {
                val selection = view.selectedItemPosition
                view.adapter = ThemeSpinnerAdapter(adapter, view.context)
                if (selection >= 0) view.setSelection(selection, false)
            }
        }
        if (roles[view] == Role.NAVIGATION) {
            view.backgroundTintList = states(outline, theme.primary, theme.surface)
            if (view is TextView) view.setTextColor(states(disabled, primaryInk, muted))
        }
    }

    private fun recolorBackground(view: View, role: ColorRole, theme: AppTheme, outline: Int) {
        val drawable = view.background?.mutate() ?: return
        fun update(background: Drawable) {
            when (background) {
                is RippleDrawable -> {
                    background.setColor(ColorStateList.valueOf((theme.primary and 0x00FFFFFF) or 0x30000000))
                    for (index in 0 until background.numberOfLayers) update(background.getDrawable(index))
                }
                is ColorDrawable -> if (Color.alpha(background.color) != 0) background.color = ThemeColors.color(theme, role)
                is GradientDrawable -> {
                    val fill = background.color?.defaultColor
                    if (fill != null && Color.alpha(fill) != 0) background.setColor(ThemeColors.color(theme, role))
                    background.colors?.let { colors ->
                        background.colors = colors.map { ThemeColors.color(theme, inferBackgroundRole(it, theme)) }.toIntArray()
                    }
                    background.setStroke((view.resources.displayMetrics.density * 1.5f).toInt().coerceAtLeast(1), outline)
                    val radiusPx = cornerRadiusPx(view)
                    if (radiusPx > 0f) background.cornerRadius = radiusPx
                }
                is MaterialShapeDrawable -> {
                    background.fillColor = ColorStateList.valueOf(ThemeColors.color(theme, role))
                    background.strokeColor = ColorStateList.valueOf(outline)
                }
                // Includes custom image composites: keep the picked bitmap intact.
                is LayerDrawable -> if ((0 until background.numberOfLayers).none { background.getDrawable(it) is BitmapDrawable }) {
                    for (index in 0 until background.numberOfLayers) update(background.getDrawable(index))
                }
            }
        }
        update(drawable)
        view.backgroundTintList?.let { view.backgroundTintList = ColorStateList.valueOf(ThemeColors.color(theme, role)) }
    }

    /** Corner-radius token in pixels; zero means "keep the current geometry untouched". */
    private fun cornerRadiusPx(view: View): Float =
        ThemeStore(view.context).cornerRadiusDp() * view.resources.displayMetrics.density

    private fun backgroundColor(view: View): Int? = view.backgroundTintList?.defaultColor ?: when (val drawable = view.background) {        is ColorDrawable -> drawable.color
        is GradientDrawable -> drawable.color?.defaultColor
        is MaterialShapeDrawable -> drawable.fillColor?.defaultColor
        else -> null
    }

    private fun inferBackgroundRole(color: Int, theme: AppTheme): ColorRole = when {
        color == Color.TRANSPARENT -> ColorRole.SURFACE
        color == theme.background -> ColorRole.BACKGROUND
        color == theme.surface || color == theme.surfaceTranslucent -> ColorRole.SURFACE
        color == theme.primary -> ColorRole.PRIMARY
        color == theme.accent -> ColorRole.ACCENT
        // Legacy XML surface hexes (e.g. #16171D) collide with light themes' text ink;
        // the explicit surface table must win over the text-ink exact match below or
        // light-mode cold starts paint dashboard cards with dark ink (session 17).
        ThemeColors.isLegacySurfaceHex(color) -> ColorRole.SURFACE
        color == theme.text -> ColorRole.TEXT
        else -> ThemeColors.backgroundRole(color)
    }

    private fun inferTextRole(view: View, color: Int, theme: AppTheme): ColorRole {
        when (color) {
            theme.text -> return ColorRole.TEXT
            theme.textDim -> return ColorRole.MUTED
            theme.primary -> return ColorRole.PRIMARY
            theme.accent -> return ColorRole.ACCENT
        }
        val attrs = listOf(
            com.google.android.material.R.attr.colorOnSurface to ColorRole.TEXT,
            com.google.android.material.R.attr.colorOnSurfaceVariant to ColorRole.MUTED,
            com.google.android.material.R.attr.colorOutline to ColorRole.MUTED,
            com.google.android.material.R.attr.colorPrimary to ColorRole.PRIMARY,
            com.google.android.material.R.attr.colorSecondary to ColorRole.ACCENT,
        )
        val value = TypedValue()
        for ((attr, role) in attrs) {
            if (view.context.theme.resolveAttribute(attr, value, true) && value.type in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT && value.data == color) return role
        }
        return ThemeColors.textRole(color)
    }

    private fun states(disabled: Int, active: Int, normal: Int) = ColorStateList(
        arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf(android.R.attr.state_checked),
            intArrayOf(android.R.attr.state_selected), intArrayOf()),
        intArrayOf(disabled, active, active, normal),
    )

    private fun resourceName(view: View): String = if (view.id == View.NO_ID) "" else try {
        view.resources.getResourceEntryName(view.id)
    } catch (_: android.content.res.Resources.NotFoundException) { "" }

    private fun isWallpaperContentWrapper(view: View, name: String): Boolean {
        if (name != "root_container") return false
        var ancestor = view.parent as? View
        while (ancestor != null) {
            if (ThemeColors.isWallpaperContentWrapper(name, resourceName(ancestor))) return true
            ancestor = ancestor.parent as? View
        }
        return false
    }

    private fun isArtwork(name: String) = listOf("distro", "logo", "brand", "qr", "screenshot", "preview_image").any(name::contains)
    private fun isStatus(name: String) = listOf(
        "status_badge", "avf_badge", "badge_cfg_zone", "ram_safety_zone", "storage_warning",
        "txt_storage_warning", "net_test_result", "download_status", "runtime_badge", "fps_ping",
    ).any(name::contains)

    /** Applies both selected rows and popup rows without replacing custom distro artwork/layouts. */
    private class ThemeSpinnerAdapter(private val source: SpinnerAdapter, private val context: Context) : BaseAdapter() {
        init {
            source.registerDataSetObserver(object : DataSetObserver() {
                override fun onChanged() = notifyDataSetChanged()
                override fun onInvalidated() = notifyDataSetInvalidated()
            })
        }
        override fun getCount() = source.count
        override fun getItem(position: Int): Any? = source.getItem(position)
        override fun getItemId(position: Int) = source.getItemId(position)
        override fun hasStableIds() = source.hasStableIds()
        override fun getItemViewType(position: Int) = source.getItemViewType(position)
        override fun getViewTypeCount() = source.viewTypeCount
        override fun isEnabled(position: Int) = (source as? ListAdapter)?.isEnabled(position) ?: true
        override fun areAllItemsEnabled() = (source as? ListAdapter)?.areAllItemsEnabled() ?: true
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
            source.getView(position, convertView, parent).also { apply(it, ThemeStore(context).resolve(context)) }
        override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View =
            source.getDropDownView(position, convertView, parent).also {
                val theme = ThemeStore(context).resolve(context)
                apply(it, theme)
                it.setBackgroundColor(theme.surface)
            }
    }
}
