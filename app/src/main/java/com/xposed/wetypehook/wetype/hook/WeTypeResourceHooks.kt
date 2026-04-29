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
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import com.xposed.wetypehook.xposed.findMethod
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
    private const val CANDIDATE_SELF_VIEW_CLASS =
        "com.tencent.wetype.plugin.hld.candidate.selfdraw.selfview.d"
    private const val CANDIDATE_WITH_EXTRA_COMPANION_CLASS =
        "com.tencent.wetype.plugin.hld.candidate.b\$a"
    private const val CANDIDATE_ITEM_ROOT_CLASS =
        "com.tencent.wetype.plugin.hld.candidate.selfdraw.scrollview.SelfDrawScrollView\$f"
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
    private val candidateItemRootBaseLeftPaddingPx = Collections.synchronizedMap(
        WeakHashMap<Any, Int>()
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


    private fun <T> resolveResourceIds(
        nameToValueMap: Map<String, T>,
        vararg classNames: String
    ): Map<Int, T> {
        val result = mutableMapOf<Int, T>()
        val unresolvedNames = nameToValueMap.keys.toMutableSet()
        for (className in classNames) {
            if (unresolvedNames.isEmpty()) break
            val clazz = loadClassOrNull(className) ?: continue
            val iterator = unresolvedNames.iterator()
            while (iterator.hasNext()) {
                val name = iterator.next()
                val field = runCatching { clazz.getField(name) }.getOrNull()
                if (field != null) {
                    val id = runCatching { field.getInt(null) }.getOrNull()
                    if (id != null) {
                        result[id] = nameToValueMap[name]!!
                        iterator.remove()
                    }
                }
            }
        }
        if (unresolvedNames.isNotEmpty()) {
            Log.i("Failed to resolve resource IDs for: $unresolvedNames")
        }
        return result
    }

    fun hookXmlDrawables(
        drawableReplacements: Map<String, Int>,
        getModuleResources: (Resources) -> Resources
    ) {
        runCatching {
            val resolvedReplacements = resolveResourceIds(drawableReplacements, "com.tencent.wetype.plugin.hld.r", "com.tencent.wetype.plugin.hld.s")
            Resources::class.java.getMethod("getDrawable", Int::class.javaPrimitiveType)
                .hookAfter { param ->
                    val resources = param.thisObject as? Resources ?: return@hookAfter
                    val resId = param.args[0] as? Int ?: return@hookAfter
                    replaceDrawable(resources, resId, null, resolvedReplacements, getModuleResources)
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
                replaceDrawable(resources, resId, theme, resolvedReplacements, getModuleResources)
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
                    replaceDrawable(resources, resId, null, resolvedReplacements, getModuleResources)
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
                    replaceDrawable(resources, resId, theme, resolvedReplacements, getModuleResources)
                        ?.also { param.result = it }
                }
            }
            TypedArray::class.java.getMethod("getDrawable", Int::class.javaPrimitiveType)
                .hookAfter { param ->
                    val typedArray = param.thisObject as? TypedArray ?: return@hookAfter
                    val index = param.args[0] as? Int ?: return@hookAfter
                    val resId = typedArray.getResourceId(index, 0)
                    if (resId == 0) return@hookAfter
                    replaceDrawable(typedArray.resources, resId, null, resolvedReplacements, getModuleResources)
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
            val resolvedStatic = resolveResourceIds(staticColorReplacements, "com.tencent.wetype.plugin.hld.p", "com.tencent.wetype.plugin.hld.s")
            val dynamicMap = mutableMapOf<String, WeTypeAppearanceColorGroup>()
            WeTypeAppearanceColorGroups.groups.forEach { group ->
                group.colorResourceNames.forEach { name -> dynamicMap[name] = group }
            }
            val resolvedDynamic = resolveResourceIds(dynamicMap, "com.tencent.wetype.plugin.hld.p", "com.tencent.wetype.plugin.hld.s")
            
            val themeAttrMap = mutableMapOf<String, WeTypeAppearanceColorGroup>()
            WeTypeAppearanceColorGroups.groups.forEach { group ->
                group.themeAttributeNames.forEach { name -> themeAttrMap[name] = group }
            }
            val resolvedThemeAttrs = resolveResourceIds(themeAttrMap, "com.tencent.wetype.plugin.hld.o", "com.tencent.wetype.plugin.hld.s")

            hookThemeStyledAttributes()
            hookColorValueAccess(resolvedStatic, resolvedDynamic, resolvedThemeAttrs)
            Resources::class.java.getMethod("getColor", Int::class.javaPrimitiveType)
                .hookAfter { param ->
                    val colorResId = param.args[0] as? Int ?: return@hookAfter
                    param.result = replaceColor(colorResId, param.result as Int, resolvedStatic, resolvedDynamic)
                }
            Resources::class.java.getMethod(
                "getColor",
                Int::class.javaPrimitiveType,
                Resources.Theme::class.java
            ).hookAfter { param ->
                val colorResId = param.args[0] as? Int ?: return@hookAfter
                param.result = replaceColor(colorResId, param.result as Int, resolvedStatic, resolvedDynamic)
            }
            Resources::class.java.getMethod("getColorStateList", Int::class.javaPrimitiveType)
                .hookAfter { param ->
                    val colorResId = param.args[0] as? Int ?: return@hookAfter
                    param.result = replaceColorStateList(
                        colorResId,
                        param.result as ColorStateList,
                        resolvedStatic,
                        resolvedDynamic
                    )
                }
            Resources::class.java.getMethod(
                "getColorStateList",
                Int::class.javaPrimitiveType,
                Resources.Theme::class.java
            ).hookAfter { param ->
                val colorResId = param.args[0] as? Int ?: return@hookAfter
                param.result = replaceColorStateList(
                    colorResId,
                    param.result as ColorStateList,
                    resolvedStatic,
                    resolvedDynamic
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
                        colorResId,
                        param.result as Int,
                        resolvedStatic,
                        resolvedDynamic
                    )
                    return@hookAfter
                }
                replaceThemeAttributeColor(typedArray, index, resolvedThemeAttrs)?.also { param.result = it }
            }
            TypedArray::class.java.getMethod("getColorStateList", Int::class.javaPrimitiveType)
                .hookAfter { param ->
                    val typedArray = param.thisObject as? TypedArray ?: return@hookAfter
                    val index = param.args[0] as? Int ?: return@hookAfter
                    val colorResId = typedArray.getResourceId(index, 0)
                    val colorStateList = param.result as? ColorStateList ?: return@hookAfter
                    if (colorResId != 0) {
                        param.result = replaceColorStateList(
                            colorResId,
                            colorStateList,
                            resolvedStatic,
                            resolvedDynamic
                        )
                        return@hookAfter
                    }
                    replaceThemeAttributeColor(typedArray, index, resolvedThemeAttrs)
                        ?.also { param.result = ColorStateList.valueOf(it) }
                }
            Log.i("Success: Hook WeType appearance colors")
        }.onFailure {
            Log.i("Failed: Hook WeType appearance colors")
            Log.i(it)
        }
    }

    private fun hookColorValueAccess(
        resolvedStatic: Map<Int, Int>,
        resolvedDynamic: Map<Int, WeTypeAppearanceColorGroup>,
        resolvedThemeAttrs: Map<Int, WeTypeAppearanceColorGroup>
    ) {
        Resources::class.java.getMethod(
            "getValue",
            Int::class.javaPrimitiveType,
            TypedValue::class.java,
            Boolean::class.javaPrimitiveType
        ).hookAfter { param ->
            val outValue = param.args[1] as? TypedValue ?: return@hookAfter
            replaceTypedValue(outValue, resolvedStatic, resolvedDynamic)
        }
        runCatching {
            Resources::class.java.getMethod(
                "getValueForDensity",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                TypedValue::class.java,
                Boolean::class.javaPrimitiveType
            ).hookAfter { param ->
                val outValue = param.args[2] as? TypedValue ?: return@hookAfter
                replaceTypedValue(outValue, resolvedStatic, resolvedDynamic)
            }
        }
        TypedArray::class.java.getMethod(
            "getValue",
            Int::class.javaPrimitiveType,
            TypedValue::class.java
        ).hookAfter { param ->
            if (param.result != true) return@hookAfter
            val outValue = param.args[1] as? TypedValue ?: return@hookAfter
            replaceTypedValue(outValue, resolvedStatic, resolvedDynamic)
        }
        TypedArray::class.java.getMethod("peekValue", Int::class.javaPrimitiveType)
            .hookAfter { param ->
                val outValue = param.result as? TypedValue ?: return@hookAfter
                replaceTypedValue(outValue, resolvedStatic, resolvedDynamic)
            }
        runCatching {
            Resources.Theme::class.java.getMethod(
                "resolveAttribute",
                Int::class.javaPrimitiveType,
                TypedValue::class.java,
                Boolean::class.javaPrimitiveType
            ).hookAfter { param ->
                if (param.result != true) return@hookAfter
                val outValue = param.args[1] as? TypedValue ?: return@hookAfter
                if (replaceTypedValue(outValue, resolvedStatic, resolvedDynamic)) {
                    return@hookAfter
                }
                replaceThemeAttributeTypedValue(param.args[0] as Int, outValue, resolvedThemeAttrs)
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

    fun hookCandidateSpecialTextColor() {
        runCatching {
            val candidateWithExtraCompanionClass =
                loadClassOrNull(CANDIDATE_WITH_EXTRA_COMPANION_CLASS)
                    ?: error("Failed to load CandidateWithExtra companion")
            candidateWithExtraCompanionClass.findMethod {
                name == "s" &&
                    parameterTypes.size == 1 &&
                    parameterTypes[0] == Int::class.javaPrimitiveType &&
                    returnType == Int::class.javaPrimitiveType
            }.hookAfter { param ->
                param.result = WeTypeSettings.getAppearanceColorXposed("theme_color")
            }
            Log.i("Success: Hook candidate special text color")
        }.onFailure {
            Log.i("Failed: Hook candidate special text color")
            Log.i(it)
        }
    }

    fun hookCandidateBackgroundCorner() {
        runCatching {
            val candidateViewClass = loadClassOrNull(CANDIDATE_SELF_VIEW_CLASS)
                ?: error("Failed to load candidate self view")
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

    fun hookCandidateBackgroundAlpha() {
        runCatching {
            val candidateViewClass = loadClassOrNull(CANDIDATE_SELF_VIEW_CLASS)
                ?: error("Failed to load candidate self view")
            val colorMethods = candidateViewClass.declaredMethods.filter { method ->
                method.name == "g" &&
                    method.parameterTypes.isEmpty() &&
                    method.returnType == Int::class.javaPrimitiveType
            }
            check(colorMethods.isNotEmpty()) {
                "Failed to find candidate background color method g"
            }
            colorMethods.forEach { method ->
                method.hookAfter { param ->
                    val color = param.result as? Int ?: return@hookAfter
                    if (Color.alpha(color) == 0) return@hookAfter
                    param.result = withForcedAlpha(
                        color,
                        WeTypeSettings.getCandidateBackgroundAlphaXposed()
                    )
                }
            }
            Log.i("Success: Hook candidate background alpha")
        }.onFailure {
            Log.i("Failed: Hook candidate background alpha")
            Log.i(it)
        }
    }

    fun hookCandidateBackgroundLeftMargin() {
        runCatching {
            val candidateItemRootClass = loadClassOrNull(CANDIDATE_ITEM_ROOT_CLASS)
                ?: error("Failed to load candidate item root view")
            val setInsetMethod = candidateItemRootClass.getMethod(
                "b0",
                Integer::class.java,
                Integer::class.java,
                Integer::class.java,
                Integer::class.java
            )
            candidateItemRootClass.getMethod("N").hookBefore { param ->
                val itemRoot = param.thisObject ?: return@hookBefore
                val baseLeftPaddingPx = candidateItemRootBaseLeftPaddingPx.getOrPut(itemRoot) {
                    runCatching { itemRoot.invokeMethodAs<Int>("p") }.getOrDefault(0)
                }
                val itemPosition = runCatching {
                    itemRoot.invokeMethodAs<Int>("o0")
                }.getOrNull() ?: return@hookBefore
                val targetLeftPaddingPx =
                    baseLeftPaddingPx + if (itemPosition == 0) {
                        resolveCandidateBackgroundLeftMarginPx(itemRoot)
                    } else {
                        0
                    }
                val currentLeftPaddingPx = runCatching {
                    itemRoot.invokeMethodAs<Int>("p")
                }.getOrNull() ?: return@hookBefore
                if (currentLeftPaddingPx == targetLeftPaddingPx) return@hookBefore
                setInsetMethod.invoke(itemRoot, targetLeftPaddingPx, null, null, null)
            }
            Log.i("Success: Hook candidate background left margin")
        }.onFailure {
            Log.i("Failed: Hook candidate background left margin")
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

    fun hookKeyboardLogo() {
        runCatching {
            val sClass = loadClassOrNull("com.tencent.wetype.plugin.hld.s") ?: return
            val logoIvId = sClass.getField("logo_iv").getInt(null)

            val replaceLogo = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val imageView = param.thisObject as? android.widget.ImageView ?: return
                    if (imageView.id == logoIvId) {
                        val drawableArg = param.args.getOrNull(0)
                        if (drawableArg is WeTypeIconDrawable) return

                        var alpha = 0.9f
                        if (param.method.name == "setImageResource") {
                            val resId = param.args[0] as Int
                            val resName = runCatching { imageView.resources.getResourceEntryName(resId) }.getOrNull() ?: ""
                            if (resName.contains("dark") || resName == "io" || resName == "j3") {
                                alpha = 0.2f
                            }
                            imageView.setImageDrawable(WeTypeIconDrawable(alpha))
                            param.result = null
                        } else {
                            val uiMode = imageView.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
                            if (uiMode == android.content.res.Configuration.UI_MODE_NIGHT_YES) {
                                alpha = 0.2f
                            }
                            param.args[0] = WeTypeIconDrawable(alpha)
                        }
                    }
                }
            }

            XposedHelpers.findAndHookMethod(
                android.widget.ImageView::class.java,
                "setImageResource",
                Int::class.javaPrimitiveType,
                replaceLogo
            )
            XposedHelpers.findAndHookMethod(
                android.widget.ImageView::class.java,
                "setImageDrawable",
                android.graphics.drawable.Drawable::class.java,
                replaceLogo
            )
            Log.i("Success: Hook WeType keyboard logo")
        }.onFailure {
            Log.i("Failed: Hook WeType keyboard logo")
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

    private fun resolveCandidateBackgroundLeftMarginPx(itemRoot: Any): Int {
        val context = runCatching {
            itemRoot.invokeMethodAs<Context>("k")
        }.getOrNull() ?: return 0
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            WeTypeSettings.getCandidateBackgroundLeftMarginDpXposed().toFloat(),
            context.resources.displayMetrics
        ).roundToInt()
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
        colorResId: Int,
        color: Int,
        staticColorReplacements: Map<Int, Int>,
        dynamicColorReplacements: Map<Int, WeTypeAppearanceColorGroup>
    ): Int {
        staticColorReplacements[colorResId]?.let { return it }
        val group = dynamicColorReplacements[colorResId] ?: return color
        return resolvedGroupColor(group)
    }

    private fun replaceTypedValue(
        typedValue: TypedValue,
        staticColorReplacements: Map<Int, Int>,
        dynamicColorReplacements: Map<Int, WeTypeAppearanceColorGroup>
    ): Boolean {
        val colorResId = typedValue.resourceId.takeIf { it != 0 } ?: return false
        val replacementColor = replaceColor(colorResId, Int.MIN_VALUE, staticColorReplacements, dynamicColorReplacements)
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
        drawableReplacements: Map<Int, Int>,
        getModuleResources: (Resources) -> Resources
    ): Drawable? {
        val replacementResId = drawableReplacements[drawableResId] ?: return null
        val replacementDrawable = getModuleResources(resources).getDrawable(replacementResId, null)
        return replacementDrawable.constantState?.newDrawable(resources, theme)?.mutate()
            ?: replacementDrawable.mutate()
    }

    private fun replaceColorStateList(
        colorResId: Int,
        colorStateList: ColorStateList,
        staticColorReplacements: Map<Int, Int>,
        dynamicColorReplacements: Map<Int, WeTypeAppearanceColorGroup>
    ): ColorStateList {
        val replacedColor = replaceColor(colorResId, colorStateList.defaultColor, staticColorReplacements, dynamicColorReplacements)
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
        attrResId: Int,
        typedValue: TypedValue,
        resolvedThemeAttrs: Map<Int, WeTypeAppearanceColorGroup>
    ): Boolean {
        val group = resolvedThemeAttrs[attrResId] ?: return false
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

    private fun replaceThemeAttributeColor(
        typedArray: TypedArray, 
        index: Int,
        resolvedThemeAttrs: Map<Int, WeTypeAppearanceColorGroup>
    ): Int? {
        val attrIds = typedArrayAttributeCache[typedArray] ?: return null
        val attrResId = attrIds.getOrNull(index) ?: return null
        val group = resolvedThemeAttrs[attrResId] ?: return null
        return resolvedGroupColor(group)
    }

    private fun resolvedGroupColor(group: WeTypeAppearanceColorGroup): Int {
        return WeTypeSettings.getAppearanceColorXposed(group.id)
    }

    private fun withForcedAlpha(color: Int, alpha: Int): Int = Color.argb(
        alpha.coerceIn(0, 255),
        Color.red(color),
        Color.green(color),
        Color.blue(color)
    )
}
