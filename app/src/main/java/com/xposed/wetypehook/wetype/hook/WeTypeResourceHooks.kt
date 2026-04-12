package com.xposed.wetypehook.wetype.hook

import android.content.Context
import android.content.res.AssetManager
import android.content.res.ColorStateList
import android.content.res.Resources
import android.content.res.TypedArray
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.View
import android.widget.FrameLayout
import com.xposed.wetypehook.xposed.Log
import com.xposed.wetypehook.xposed.getObjectAs
import com.xposed.wetypehook.xposed.hookAfter
import com.xposed.wetypehook.xposed.hookBefore
import com.xposed.wetypehook.xposed.hookReturnConstant
import com.xposed.wetypehook.xposed.invokeMethodAs
import com.xposed.wetypehook.xposed.loadClassOrNull
import com.xposed.wetypehook.wetype.settings.WeTypeAppearanceColorGroup
import com.xposed.wetypehook.wetype.settings.WeTypeAppearanceColorGroups
import com.xposed.wetypehook.wetype.settings.WeTypeSettings
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.roundToInt

internal object WeTypeResourceHooks {
    private const val CANDIDATE_PINYIN_CONTAINER_ACCESSOR_CLASS =
        "com.tencent.wetype.plugin.hld.candidate.ImeCandidateView\$d2"
    private val SETTING_OPAQUE_BACKGROUND_VIEW_CLASSES = listOf(
        "com.tencent.wetype.plugin.hld.view.settingkeyboard.S10SettingKeyboardTypeView",
        "com.tencent.wetype.plugin.hld.view.settingkeyboard.S10SettingCustomToolbarView"
    )

    private val typedArrayAttributeCache = Collections.synchronizedMap(
        WeakHashMap<TypedArray, IntArray>()
    )
    private val candidatePinyinMarginListeners = Collections.synchronizedMap(
        WeakHashMap<View, View.OnLayoutChangeListener>()
    )

    fun hookFont(
        fontAsset: String,
        moduleFontAsset: String,
        getModuleAssetManager: () -> AssetManager
    ) {
        runCatching {
            Typeface::class.java.getDeclaredMethod(
                "createFromAsset",
                AssetManager::class.java,
                String::class.java
            ).hookBefore { param ->
                if (param.args[1] != fontAsset) return@hookBefore
                param.result = Typeface.createFromAsset(getModuleAssetManager(), moduleFontAsset)
            }
            Log.i("Success: Hook WeType font replacement")
        }.onFailure {
            Log.i("Failed: Hook WeType font replacement")
            Log.i(it)
        }
    }

    fun hookXmlDrawables(
        drawableReplacements: Map<String, Int>,
        getModuleResources: (Resources) -> Resources
    ) {
        runCatching {
            Resources::class.java.getMethod("getDrawable", Int::class.javaPrimitiveType)
                .hookAfter { param ->
                    val resources = param.thisObject as? Resources ?: return@hookAfter
                    val resId = param.args[0] as? Int ?: return@hookAfter
                    replaceDrawable(resources, resId, null, drawableReplacements, getModuleResources)
                        ?.also { param.result = it }
                }
            Resources::class.java.getMethod(
                "getDrawable",
                Int::class.javaPrimitiveType,
                Resources.Theme::class.java
            ).hookAfter { param ->
                val resources = param.thisObject as? Resources ?: return@hookAfter
                val resId = param.args[0] as? Int ?: return@hookAfter
                val theme = param.args[1] as? Resources.Theme
                replaceDrawable(resources, resId, theme, drawableReplacements, getModuleResources)
                    ?.also { param.result = it }
            }
            runCatching {
                Resources::class.java.getMethod(
                    "getDrawableForDensity",
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType
                ).hookAfter { param ->
                    val resources = param.thisObject as? Resources ?: return@hookAfter
                    val resId = param.args[0] as? Int ?: return@hookAfter
                    replaceDrawable(resources, resId, null, drawableReplacements, getModuleResources)
                        ?.also { param.result = it }
                }
            }
            runCatching {
                Resources::class.java.getMethod(
                    "getDrawableForDensity",
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Resources.Theme::class.java
                ).hookAfter { param ->
                    val resources = param.thisObject as? Resources ?: return@hookAfter
                    val resId = param.args[0] as? Int ?: return@hookAfter
                    val theme = param.args[2] as? Resources.Theme
                    replaceDrawable(resources, resId, theme, drawableReplacements, getModuleResources)
                        ?.also { param.result = it }
                }
            }
            TypedArray::class.java.getMethod("getDrawable", Int::class.javaPrimitiveType)
                .hookAfter { param ->
                    val typedArray = param.thisObject as? TypedArray ?: return@hookAfter
                    val index = param.args[0] as? Int ?: return@hookAfter
                    val resId = typedArray.getResourceId(index, 0)
                    if (resId == 0) return@hookAfter
                    replaceDrawable(typedArray.resources, resId, null, drawableReplacements, getModuleResources)
                        ?.also { param.result = it }
                }
            Log.i("Success: Hook WeType xml drawables")
        }.onFailure {
            Log.i("Failed: Hook WeType xml drawables")
            Log.i(it)
        }
    }

    fun hookAppearanceColors(staticColorReplacements: Map<String, Int>) {
        runCatching {
            hookThemeStyledAttributes()
            hookColorValueAccess(staticColorReplacements)
            Resources::class.java.getMethod("getColor", Int::class.javaPrimitiveType)
                .hookAfter { param ->
                    val resources = param.thisObject as? Resources ?: return@hookAfter
                    val colorResId = param.args[0] as? Int ?: return@hookAfter
                    param.result = replaceColor(resources, colorResId, param.result as Int, staticColorReplacements)
                }
            Resources::class.java.getMethod(
                "getColor",
                Int::class.javaPrimitiveType,
                Resources.Theme::class.java
            ).hookAfter { param ->
                val resources = param.thisObject as? Resources ?: return@hookAfter
                val colorResId = param.args[0] as? Int ?: return@hookAfter
                param.result = replaceColor(resources, colorResId, param.result as Int, staticColorReplacements)
            }
            Resources::class.java.getMethod("getColorStateList", Int::class.javaPrimitiveType)
                .hookAfter { param ->
                    val resources = param.thisObject as? Resources ?: return@hookAfter
                    val colorResId = param.args[0] as? Int ?: return@hookAfter
                    param.result = replaceColorStateList(
                        resources,
                        colorResId,
                        param.result as ColorStateList,
                        staticColorReplacements
                    )
                }
            Resources::class.java.getMethod(
                "getColorStateList",
                Int::class.javaPrimitiveType,
                Resources.Theme::class.java
            ).hookAfter { param ->
                val resources = param.thisObject as? Resources ?: return@hookAfter
                val colorResId = param.args[0] as? Int ?: return@hookAfter
                param.result = replaceColorStateList(
                    resources,
                    colorResId,
                    param.result as ColorStateList,
                    staticColorReplacements
                )
            }
            TypedArray::class.java.getMethod(
                "getColor",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            ).hookAfter { param ->
                val typedArray = param.thisObject as? TypedArray ?: return@hookAfter
                val index = param.args[0] as? Int ?: return@hookAfter
                val colorResId = typedArray.getResourceId(index, 0)
                if (colorResId != 0) {
                    param.result = replaceColor(
                        typedArray.resources,
                        colorResId,
                        param.result as Int,
                        staticColorReplacements
                    )
                    return@hookAfter
                }
                replaceThemeAttributeColor(typedArray, index)?.also { param.result = it }
            }
            TypedArray::class.java.getMethod("getColorStateList", Int::class.javaPrimitiveType)
                .hookAfter { param ->
                    val typedArray = param.thisObject as? TypedArray ?: return@hookAfter
                    val index = param.args[0] as? Int ?: return@hookAfter
                    val colorResId = typedArray.getResourceId(index, 0)
                    val colorStateList = param.result as? ColorStateList ?: return@hookAfter
                    if (colorResId != 0) {
                        param.result = replaceColorStateList(
                            typedArray.resources,
                            colorResId,
                            colorStateList,
                            staticColorReplacements
                        )
                        return@hookAfter
                    }
                    replaceThemeAttributeColor(typedArray, index)
                        ?.also { param.result = ColorStateList.valueOf(it) }
                }
            Log.i("Success: Hook WeType appearance colors")
        }.onFailure {
            Log.i("Failed: Hook WeType appearance colors")
            Log.i(it)
        }
    }

    private fun hookColorValueAccess(staticColorReplacements: Map<String, Int>) {
        Resources::class.java.getMethod(
            "getValue",
            Int::class.javaPrimitiveType,
            TypedValue::class.java,
            Boolean::class.javaPrimitiveType
        ).hookAfter { param ->
            val resources = param.thisObject as? Resources ?: return@hookAfter
            val outValue = param.args[1] as? TypedValue ?: return@hookAfter
            replaceTypedValue(resources, outValue, staticColorReplacements)
        }
        runCatching {
            Resources::class.java.getMethod(
                "getValueForDensity",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                TypedValue::class.java,
                Boolean::class.javaPrimitiveType
            ).hookAfter { param ->
                val resources = param.thisObject as? Resources ?: return@hookAfter
                val outValue = param.args[2] as? TypedValue ?: return@hookAfter
                replaceTypedValue(resources, outValue, staticColorReplacements)
            }
        }
        TypedArray::class.java.getMethod(
            "getValue",
            Int::class.javaPrimitiveType,
            TypedValue::class.java
        ).hookAfter { param ->
            if (param.result != true) return@hookAfter
            val typedArray = param.thisObject as? TypedArray ?: return@hookAfter
            val outValue = param.args[1] as? TypedValue ?: return@hookAfter
            replaceTypedValue(typedArray.resources, outValue, staticColorReplacements)
        }
        TypedArray::class.java.getMethod("peekValue", Int::class.javaPrimitiveType)
            .hookAfter { param ->
                val typedArray = param.thisObject as? TypedArray ?: return@hookAfter
                val outValue = param.result as? TypedValue ?: return@hookAfter
                replaceTypedValue(typedArray.resources, outValue, staticColorReplacements)
            }
        runCatching {
            Resources.Theme::class.java.getMethod(
                "resolveAttribute",
                Int::class.javaPrimitiveType,
                TypedValue::class.java,
                Boolean::class.javaPrimitiveType
            ).hookAfter { param ->
                if (param.result != true) return@hookAfter
                val theme = param.thisObject as? Resources.Theme ?: return@hookAfter
                val outValue = param.args[1] as? TypedValue ?: return@hookAfter
                if (replaceTypedValue(theme.resources, outValue, staticColorReplacements)) {
                    return@hookAfter
                }
                replaceThemeAttributeTypedValue(theme.resources, param.args[0] as Int, outValue)
            }
        }
    }

    fun hookSelfDrawKeyColors() {
        runCatching {
            val imeButtonClass = loadClassOrNull("com.tencent.wetype.plugin.hld.keyboard.selfdraw.j")
                ?: error("Failed to load ImeButton")

            listOf(
                "i" to { context: Context -> WeTypeSettings.getKeyOpacityXposed(context) },
                "k" to { _: Context -> 0x20 },
                "X" to { context: Context -> (WeTypeSettings.getKeyOpacityXposed(context) + 20).coerceAtMost(255) }
            ).forEach { (methodName, alphaProvider) ->
                imeButtonClass.getMethod(methodName).hookAfter { param ->
                    val color = param.result as? Int ?: return@hookAfter
                    if (color == 0) return@hookAfter
                    val button = param.thisObject ?: return@hookAfter
                    val view = runCatching { button.getObjectAs<android.view.View>("a") }.getOrNull()
                    val context = view?.context ?: return@hookAfter
                    param.result = withForcedAlpha(color, alphaProvider(context))
                }
            }

            imeButtonClass.getMethod("Y").hookReturnConstant(Color.TRANSPARENT)

            Log.i("Success: Hook WeType self-draw key colors")
        }.onFailure {
            Log.i("Failed: Hook WeType self-draw key colors")
            Log.i(it)
        }
    }

    fun hookCandidateBackgroundCorner() {
        runCatching {
            val candidateViewClass = loadClassOrNull(
                "com.tencent.wetype.plugin.hld.candidate.selfdraw.selfview.d"
            ) ?: error("Failed to load candidate self view")
            val cornerField = generateSequence(candidateViewClass as Class<*>?) { it.superclass }
                .mapNotNull { clazz ->
                    runCatching { clazz.getDeclaredField("g") }.getOrNull()
                }
                .firstOrNull()
                ?.also { it.isAccessible = true }
                ?: error("Failed to find candidate corner field g")

            candidateViewClass.declaredMethods
                .filter { it.name == "e" && it.parameterTypes.size == 3 }
                .forEach { method ->
                    method.hookBefore { param ->
                        val radius = WeTypeSettings.getCandidateBackgroundCornerXposed().roundToInt()
                        cornerField.setInt(param.thisObject, radius)
                    }
                }
            Log.i("Success: Hook candidate background corner")
        }.onFailure {
            Log.i("Failed: Hook candidate background corner")
            Log.i(it)
        }
    }

    fun hookCandidatePinyinLeftMargin() {
        runCatching {
            val candidateContainerAccessorClass = loadClassOrNull(CANDIDATE_PINYIN_CONTAINER_ACCESSOR_CLASS)
                ?: error("Failed to load ImeCandidateView\$d2")
            candidateContainerAccessorClass.getMethod("invoke").hookAfter { param ->
                val container = param.result as? FrameLayout ?: return@hookAfter
                applyCandidatePinyinLeftMargin(container)
                ensureCandidatePinyinMarginSync(container)
            }
            Log.i("Success: Hook candidate pinyin left margin")
        }.onFailure {
            Log.i("Failed: Hook candidate pinyin left margin")
            Log.i(it)
        }
    }

    fun hookSettingKeyboardOpaqueBackground() {
        SETTING_OPAQUE_BACKGROUND_VIEW_CLASSES.forEach { className ->
            runCatching {
                val targetClass = loadClassOrNull(className)
                    ?: error("Failed to load $className")
                targetClass.getMethod(
                    "k",
                    Boolean::class.javaPrimitiveType
                ).hookAfter { param ->
                    applyOpaqueSettingKeyboardBackground(param.thisObject)
                }
                runCatching {
                    targetClass.getMethod("onAttachedToWindow").hookAfter { param ->
                        applyOpaqueSettingKeyboardBackground(param.thisObject)
                    }
                }
                Log.i("Success: Hook opaque background for $className")
            }.onFailure {
                Log.i("Failed: Hook opaque background for $className")
                Log.i(it)
            }
        }
    }

    private fun ensureCandidatePinyinMarginSync(view: View) {
        if (candidatePinyinMarginListeners.containsKey(view)) return
        val layoutListener = View.OnLayoutChangeListener { changedView, _, _, _, _, _, _, _, _ ->
            applyCandidatePinyinLeftMargin(changedView)
        }
        val attachStateListener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                applyCandidatePinyinLeftMargin(v)
            }

            override fun onViewDetachedFromWindow(v: View) {
                candidatePinyinMarginListeners.remove(v)?.also { v.removeOnLayoutChangeListener(it) }
                v.removeOnAttachStateChangeListener(this)
            }
        }
        view.addOnLayoutChangeListener(layoutListener)
        view.addOnAttachStateChangeListener(attachStateListener)
        candidatePinyinMarginListeners[view] = layoutListener
    }

    private fun applyCandidatePinyinLeftMargin(view: View) {
        val startPadding = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            WeTypeSettings.getCandidatePinyinLeftMarginDpXposed().toFloat(),
            view.resources.displayMetrics
        ).roundToInt()
        if (view.paddingStart == startPadding) return
        view.setPaddingRelative(
            startPadding,
            view.paddingTop,
            view.paddingEnd,
            view.paddingBottom
        )
    }

    private fun applyOpaqueSettingKeyboardBackground(target: Any?) {
        target ?: return
        listOf(
            runCatching { target.invokeMethodAs<View>("getSettingBaseGridViewBg") }.getOrNull(),
            runCatching { target.invokeMethodAs<View>("getBelowBgFl") }.getOrNull()
        ).forEach { view ->
            view ?: return@forEach
            forceOpaqueBackground(view)
            view.post { forceOpaqueBackground(view) }
        }
    }

    private fun forceOpaqueBackground(view: View) {
        val background = view.background ?: return
        val mutatedBackground = background.mutate()
        val targetColor = resolveSettingKeyboardBackgroundColor(view)
        when (mutatedBackground) {
            is ColorDrawable -> {
                mutatedBackground.color = targetColor
                view.background = mutatedBackground
            }
            is GradientDrawable -> {
                mutatedBackground.setColor(targetColor)
                mutatedBackground.alpha = 255
                view.background = mutatedBackground
            }
            else -> {
                mutatedBackground.setTint(targetColor)
                mutatedBackground.alpha = 255
                view.background = mutatedBackground
            }
        }
        view.invalidate()
    }

    private fun resolveSettingKeyboardBackgroundColor(view: View): Int {
        val isDarkMode =
            view.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
        return if (isDarkMode) 0xFF262626.toInt() else 0xFFD1D3D8.toInt()
    }

    private fun replaceColor(
        resources: Resources,
        colorResId: Int,
        color: Int,
        staticColorReplacements: Map<String, Int>
    ): Int {
        val resourceType = runCatching { resources.getResourceTypeName(colorResId) }.getOrNull() ?: return color
        if (resourceType != "color") return color
        val colorName = runCatching { resources.getResourceEntryName(colorResId) }.getOrNull() ?: return color
        staticColorReplacements[colorName]?.let { return it }
        val group = WeTypeAppearanceColorGroups.findByColorName(colorName) ?: return color
        return resolvedGroupColor(group)
    }

    private fun replaceTypedValue(
        resources: Resources,
        typedValue: TypedValue,
        staticColorReplacements: Map<String, Int>
    ): Boolean {
        val colorResId = typedValue.resourceId.takeIf { it != 0 } ?: return false
        val replacementColor = replaceColor(resources, colorResId, Int.MIN_VALUE, staticColorReplacements)
        if (replacementColor == Int.MIN_VALUE) return false

        typedValue.type = when (Color.alpha(replacementColor)) {
            0xFF -> TypedValue.TYPE_INT_COLOR_RGB8
            else -> TypedValue.TYPE_INT_COLOR_ARGB8
        }
        typedValue.data = replacementColor
        typedValue.assetCookie = 0
        typedValue.resourceId = 0
        typedValue.string = null
        return true
    }

    private fun replaceDrawable(
        resources: Resources,
        drawableResId: Int,
        theme: Resources.Theme?,
        drawableReplacements: Map<String, Int>,
        getModuleResources: (Resources) -> Resources
    ): Drawable? {
        val drawableName = runCatching { resources.getResourceEntryName(drawableResId) }.getOrNull() ?: return null
        val replacementResId = drawableReplacements[drawableName] ?: return null
        val replacementDrawable = getModuleResources(resources).getDrawable(replacementResId, null)
        return replacementDrawable.constantState?.newDrawable(resources, theme)?.mutate()
            ?: replacementDrawable.mutate()
    }

    private fun replaceColorStateList(
        resources: Resources,
        colorResId: Int,
        colorStateList: ColorStateList,
        staticColorReplacements: Map<String, Int>
    ): ColorStateList {
        val replacedColor = replaceColor(resources, colorResId, colorStateList.defaultColor, staticColorReplacements)
        if (replacedColor == colorStateList.defaultColor) return colorStateList
        return ColorStateList.valueOf(replacedColor)
    }

    private fun hookThemeStyledAttributes() {
        runCatching {
            Resources.Theme::class.java.getMethod("obtainStyledAttributes", IntArray::class.java)
                .hookAfter { param ->
                    val typedArray = param.result as? TypedArray ?: return@hookAfter
                    val attrs = param.args[0] as? IntArray ?: return@hookAfter
                    typedArrayAttributeCache[typedArray] = attrs.copyOf()
                }
        }
        runCatching {
            Resources.Theme::class.java.getMethod(
                "obtainStyledAttributes",
                Int::class.javaPrimitiveType,
                IntArray::class.java
            ).hookAfter { param ->
                val typedArray = param.result as? TypedArray ?: return@hookAfter
                val attrs = param.args[1] as? IntArray ?: return@hookAfter
                typedArrayAttributeCache[typedArray] = attrs.copyOf()
            }
        }
        runCatching {
            Resources.Theme::class.java.getMethod(
                "obtainStyledAttributes",
                android.util.AttributeSet::class.java,
                IntArray::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            ).hookAfter { param ->
                val typedArray = param.result as? TypedArray ?: return@hookAfter
                val attrs = param.args[1] as? IntArray ?: return@hookAfter
                typedArrayAttributeCache[typedArray] = attrs.copyOf()
            }
        }
    }

    private fun replaceThemeAttributeTypedValue(
        resources: Resources,
        attrResId: Int,
        typedValue: TypedValue
    ): Boolean {
        val attrName = runCatching { resources.getResourceEntryName(attrResId) }.getOrNull() ?: return false
        val group = WeTypeAppearanceColorGroups.findByThemeAttributeName(attrName) ?: return false
        val replacementColor = WeTypeSettings.getAppearanceColorXposed(group.id)
        typedValue.type = when (Color.alpha(replacementColor)) {
            0xFF -> TypedValue.TYPE_INT_COLOR_RGB8
            else -> TypedValue.TYPE_INT_COLOR_ARGB8
        }
        typedValue.data = replacementColor
        typedValue.assetCookie = 0
        typedValue.resourceId = 0
        typedValue.string = null
        return true
    }

    private fun replaceThemeAttributeColor(typedArray: TypedArray, index: Int): Int? {
        val attrIds = typedArrayAttributeCache[typedArray] ?: return null
        val attrResId = attrIds.getOrNull(index) ?: return null
        val attrName = runCatching {
            typedArray.resources.getResourceEntryName(attrResId)
        }.getOrNull() ?: return null
        val group = WeTypeAppearanceColorGroups.findByThemeAttributeName(attrName) ?: return null
        return resolvedGroupColor(group)
    }

    private fun resolvedGroupColor(group: WeTypeAppearanceColorGroup): Int {
        val color = WeTypeSettings.getAppearanceColorXposed(group.id)
        if (!group.isKeyColorGroup) return color
        return withForcedAlpha(color, WeTypeSettings.getKeyColorHookAlphaXposed())
    }

    private fun withForcedAlpha(color: Int, alpha: Int): Int = Color.argb(
        alpha.coerceIn(0, 255),
        Color.red(color),
        Color.green(color),
        Color.blue(color)
    )
}
