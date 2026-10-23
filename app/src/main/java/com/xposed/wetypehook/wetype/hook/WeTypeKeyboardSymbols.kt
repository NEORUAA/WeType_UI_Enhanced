package com.xposed.wetypehook.wetype.hook

import android.content.res.AssetManager
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import com.xposed.wetypehook.wetype.graphics.SfSymbols
import com.xposed.wetypehook.xposed.HookEnvironment
import com.xposed.wetypehook.xposed.Log
import com.xposed.wetypehook.xposed.hookBefore
import com.xposed.wetypehook.xposed.loadClassOrNull
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.WeakHashMap

/** Replaces only key content; the host keeps geometry, state, accessibility and key actions. */
internal object WeTypeKeyboardSymbols {
    private const val KEY_DATA = "com.tencent.wetype.plugin.hld.keyboard.selfdraw.bean.KeyData"
    private const val KEYBOARD = "com.tencent.wetype.plugin.hld.keyboard.selfdraw.S3EnglishQwertyKeyboard"
    private const val EMOJI_KEYBOARD = "com.tencent.wetype.plugin.hld.keyboard.selfdraw.S11EmojiKeyboard"
    private val dotCommaTexts = setOf(",", ".", "，", "。")
    private val hookedContent = mutableSetOf<Method>()
    private val inspectedDrawers = Collections.newSetFromMap(WeakHashMap<Any, Boolean>())
    private val resourceNames = WeakHashMap<Resources, MutableMap<Int, String>>()

    fun hook(getModuleAssets: () -> AssetManager) {
        runCatching {
            val keyboard = loadClassOrNull(KEYBOARD)?.getMethod("getActionButton")?.declaringClass
                ?: error("Self-draw keyboard accessor unavailable")
            val buttonType = keyboard.getMethod("getActionButton").returnType
            val access = ButtonAccess(buttonType, keyboard)
            val drawerType = keyboard.getMethod("getButtonDrawer").returnType
            val addDrawer = drawerType.declaredMethods.single { method ->
                method.returnType == Void.TYPE && method.parameterTypes.size == 1 &&
                    method.parameterTypes[0].name.contains(".selfdraw.drawmethod.")
            }
            val abstractDrawer = addDrawer.parameterTypes.single()
            val drawerMap = drawerType.declaredFields.single { Map::class.java.isAssignableFrom(it.type) }
                .apply { isAccessible = true }
            addDrawer.hookBefore { param ->
                param[0]?.let { installContentHook(it.javaClass, abstractDrawer, access, getModuleAssets) }
            }
            // Existing drawer instances survive a module hot reload. Inspect them once on first use.
            drawerType.declaredMethods.single { method ->
                method.returnType == Void.TYPE && method.parameterTypes.contentEquals(arrayOf(buttonType))
            }.hookBefore { param ->
                synchronized(inspectedDrawers) {
                    if (inspectedDrawers.add(param.thisObject)) {
                        (drawerMap.get(param.thisObject) as? Map<*, *>)?.values?.forEach { drawer ->
                            drawer?.let { installContentHook(it.javaClass, abstractDrawer, access, getModuleAssets) }
                        }
                    }
                }
            }
            Log.i("Success: Hook WeType SF keyboard symbols")
        }.onFailure {
            Log.e("Failed: Hook WeType SF keyboard symbols")
            Log.e(it)
        }
    }

    private fun installContentHook(
        type: Class<*>,
        abstractDrawer: Class<*>,
        access: ButtonAccess,
        getModuleAssets: () -> AssetManager
    ) {
        val signature = arrayOf(Canvas::class.java, access.type)
        // The shared draw base implements the full pass, leaving just its content method abstract.
        // Discover that remaining contract so an override of the full pass can never be replaced.
        val contentContract = generateSequence(type.superclass as Class<*>?) { it.superclass }
            .takeWhile { it != abstractDrawer && it != Any::class.java }
            .firstNotNullOfOrNull { owner ->
                owner.methods.filter { method ->
                    Modifier.isAbstract(method.modifiers) && method.returnType == Void.TYPE &&
                        method.parameterTypes.contentEquals(signature)
                }.singleOrNull()
            } ?: return
        val content = generateSequence(type as Class<*>?) { it.superclass }
            .takeWhile { it != abstractDrawer && it != Any::class.java }
            .firstNotNullOfOrNull { owner ->
                if (Modifier.isAbstract(owner.modifiers)) return@firstNotNullOfOrNull null
                owner.declaredMethods.filter { method ->
                    method.name == contentContract.name && method.returnType == Void.TYPE &&
                        method.parameterTypes.contentEquals(signature) && Modifier.isPublic(method.modifiers) &&
                        !Modifier.isStatic(method.modifiers)
                }.singleOrNull()
            } ?: return
        synchronized(hookedContent) {
            if (content in hookedContent) return
            val paintFields = generateSequence(content.declaringClass as Class<*>?) { it.superclass }
                .takeWhile { it != abstractDrawer && !Modifier.isAbstract(it.modifiers) }
                .flatMap { it.declaredFields.asSequence() }
                .filter { it.type == Paint::class.java && !Modifier.isStatic(it.modifiers) }
                .onEach { it.isAccessible = true }
                .toList()
            val activeFontDrawer = ThreadLocal<Any?>()
            val prepareText = content.declaringClass.declaredMethods.filter { method ->
                !Modifier.isStatic(method.modifiers) && method.returnType == Void.TYPE &&
                    (method.parameterTypes.contentEquals(arrayOf(access.type)) ||
                        method.parameterTypes.contentEquals(arrayOf(access.type, Boolean::class.javaPrimitiveType!!)))
            }
            HookEnvironment.withHookScope("wetype.keyboard-symbol-content") {
                // Text drawers reapply tracking and artificial outlines while preparing each key.
                // Clear them only inside our override so SF Pro Text keeps its native Regular style.
                prepareText.forEach { prepare ->
                    HookEnvironment.registerHook(prepare, "native-text-spacing") { chain ->
                        val result = chain.proceed()
                        if (activeFontDrawer.get() === chain.thisObject) {
                            paintFields.forEach { field ->
                                (field.get(chain.thisObject) as? Paint)?.let(::applyNativeTextStyle)
                            }
                        }
                        result
                    }
                }
                HookEnvironment.registerHook(content, "content") { chain ->
                    val button = chain.args.getOrNull(1) ?: return@registerHook chain.proceed()
                    val canvas = chain.args.getOrNull(0) as? Canvas ?: return@registerHook chain.proceed()
                    val selection = runCatching { access.appearance(button) }.getOrNull()
                        ?: return@registerHook chain.proceed()
                    if (selection == WeTypeKeyAppearance.Empty) return@registerHook null
                    val assets = runCatching(getModuleAssets).getOrNull()
                        ?: return@registerHook chain.proceed()
                    if (selection == WeTypeKeyAppearance.FontOnly) {
                        if (runCatching { access.drawDotComma(canvas, assets, button) }.getOrDefault(false)) {
                            return@registerHook null
                        }
                        val font = SfSymbols.getTypeface(assets) ?: return@registerHook chain.proceed()
                        val previous = paintFields.mapNotNull { field ->
                            (field.get(chain.thisObject) as? Paint)?.let { paint ->
                                TextPaintState(paint).also {
                                    paint.typeface = font
                                    applyNativeTextStyle(paint)
                                }
                            }
                        }
                        val previousDrawer = activeFontDrawer.get()
                        activeFontDrawer.set(chain.thisObject)
                        try {
                            chain.proceed()
                        } finally {
                            previous.forEach(TextPaintState::restore)
                            if (previousDrawer == null) activeFontDrawer.remove()
                            else activeFontDrawer.set(previousDrawer)
                        }
                    } else {
                        val rendered = runCatching { access.draw(canvas, assets, button, selection) }.getOrDefault(false)
                        if (rendered) null else chain.proceed()
                    }
                }
            }
            hookedContent.add(content)
        }
    }

    private fun applyNativeTextStyle(paint: Paint) {
        paint.letterSpacing = 0f
        paint.style = Paint.Style.FILL
        paint.strokeWidth = 0f
        paint.isFakeBoldText = false
    }

    private class TextPaintState(private val paint: Paint) {
        private val typeface = paint.typeface
        private val letterSpacing = paint.letterSpacing
        private val style = paint.style
        private val strokeWidth = paint.strokeWidth
        private val fakeBold = paint.isFakeBoldText

        fun restore() {
            paint.typeface = typeface
            paint.letterSpacing = letterSpacing
            paint.style = style
            paint.strokeWidth = strokeWidth
            paint.isFakeBoldText = fakeBold
        }
    }

    private class ButtonAccess(val type: Class<*>, keyboard: Class<*>) {
        private val properties = propertySignatures(type)
        private val keyData = type.methods.single { it.parameterTypes.isEmpty() && it.returnType.name == KEY_DATA }
        private val keyId = keyData.returnType.getMethod("getId")
        private val parent = type.methods.single { it.parameterTypes.isEmpty() && it.returnType == keyboard }
        private val drawRect = property("drawRect", Rect::class.java)
        private val drawerType = property("drawerType", String::class.java)
        private val mainText = property("mainText", String::class.java)
        private val mainTextSize = property("mainTextSize", Int::class.javaPrimitiveType!!)
        private val floatText = property("floatText", String::class.java)
        private val floatTextSize = property("floatTextSize", Int::class.javaPrimitiveType!!)
        private val floatTextColor = property("floatTextColor", Int::class.javaPrimitiveType!!)
        private val status = property("status", String::class.java)
        private val visible = property("isContentVisible", Boolean::class.javaPrimitiveType!!)
        private val image = property("imgSrc", Int::class.javaPrimitiveType!!)
        private val textColor = property("mainTextColor", Int::class.javaPrimitiveType!!)
        private val textPressedColor = property("mainTextColorPressed", Int::class.javaPrimitiveType!!)
        private val iconColor = property("iconColor", Int::class.javaPrimitiveType!!)
        private val iconPressedColor = property("iconPressedColor", Int::class.javaPrimitiveType!!)
        private val iconHeight = runCatching { property("iconHeight", Int::class.javaPrimitiveType!!) }.getOrNull()
        private val dotCommaPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
        private val mainInk = Rect()
        private val floatInk = Rect()

        private fun property(name: String, result: Class<*>): PropertyReader {
            val signature = properties[name] ?: error("Keyboard property missing: $name")
            val method = signature.getter?.let { methodName ->
                type.declaredMethods.singleOrNull {
                    it.name == methodName && it.parameterTypes.isEmpty() && it.returnType == result
                }
            }
            if (method != null) return PropertyReader(method.apply { isAccessible = true }, null)
            // R8 removes some getters while retaining their backing fields and Kotlin metadata.
            val field = signature.field?.let { fieldName ->
                type.declaredFields.singleOrNull { it.name == fieldName && it.type == result }
            } ?: error("Keyboard property storage missing: $name")
            return PropertyReader(null, field.apply { isAccessible = true })
        }

        fun appearance(button: Any): WeTypeKeyAppearance? {
            if (visible.invoke(button) != true) return null
            val view = parent.invoke(button) as? View ?: return null
            val resourceId = image.invoke(button) as Int
            val imageName = synchronized(resourceNames) {
                resourceNames.getOrPut(view.resources) { mutableMapOf() }.getOrPut(resourceId) {
                    if (resourceId == 0) "" else runCatching { view.resources.getResourceEntryName(resourceId) }.getOrDefault("")
                }
            }
            val appearance = resolveWeTypeKeyAppearance(
                keyId.invoke(keyData.invoke(button)) as String,
                drawerType.invoke(button) as String,
                mainText.invoke(button) as String,
                imageName
            )
            // A host hint may appear during voice input or a long press; let it keep its layout.
            if (appearance != WeTypeKeyAppearance.FontOnly && (floatText.invoke(button) as String).isNotEmpty()) return null
            return appearance
        }

        @Synchronized
        fun drawDotComma(canvas: Canvas, assets: AssetManager, button: Any): Boolean {
            if (drawerType.invoke(button) != "dotComma") return false
            val main = mainText.invoke(button) as String
            val hint = floatText.invoke(button) as String
            if (main !in dotCommaTexts || hint !in dotCommaTexts) return false
            val rect = drawRect.invoke(button) as Rect
            val mainSize = (mainTextSize.invoke(button) as Int).toFloat()
            val hintSize = (floatTextSize.invoke(button) as Int).toFloat()
            if (rect.isEmpty || mainSize <= 0f || hintSize <= 0f) return false
            val paint = dotCommaPaint
            paint.typeface = SfSymbols.getTypeface(assets) ?: return false
            paint.textSize = mainSize
            paint.getTextBounds(main, 0, main.length, mainInk)
            val mainMetrics = paint.fontMetrics
            // Preserve DotCommaDrawer's two baselines, then center their combined visible ink.
            val mainBaseline = -(mainMetrics.descent - mainMetrics.ascent) / 3f +
                mainMetrics.leading - mainMetrics.ascent
            paint.textSize = hintSize
            paint.getTextBounds(hint, 0, hint.length, floatInk)
            if (mainInk.isEmpty || floatInk.isEmpty) return false
            val hintMetrics = paint.fontMetrics
            val hintBaseline = (hintMetrics.descent - hintMetrics.ascent) / 2f -
                (hintMetrics.leading - hintMetrics.ascent)
            val top = minOf(mainBaseline + mainInk.top, hintBaseline + floatInk.top)
            val bottom = maxOf(mainBaseline + mainInk.bottom, hintBaseline + floatInk.bottom)
            val verticalOffset = rect.exactCenterY() - (top + bottom) / 2f
            paint.textSize = mainSize
            paint.color = (if (status.invoke(button) == "press") textPressedColor else textColor).invoke(button) as Int
            canvas.drawText(main, rect.exactCenterX() - mainInk.exactCenterX(), mainBaseline + verticalOffset, paint)
            paint.textSize = hintSize
            paint.color = floatTextColor.invoke(button) as Int
            canvas.drawText(hint, rect.exactCenterX() - floatInk.exactCenterX(), hintBaseline + verticalOffset, paint)
            return true
        }

        fun draw(canvas: Canvas, assets: AssetManager, button: Any, appearance: WeTypeKeyAppearance): Boolean {
            val rect = drawRect.invoke(button) as Rect
            if (rect.isEmpty) return false
            val isImage = drawerType.invoke(button) == "imageType"
            val pressed = status.invoke(button) == "press"
            val colorMethod = when {
                isImage && pressed -> iconPressedColor
                isImage -> iconColor
                pressed -> textPressedColor
                else -> textColor
            }
            val selectedColor = colorMethod.invoke(button) as Int
            // A zero tint keeps the original back drawable's intrinsic color in the host.
            val color = if (isImage && appearance is WeTypeKeyAppearance.Symbol &&
                appearance.name == "arrow.left" && selectedColor == 0
            ) {
                (if (pressed) textPressedColor else textColor).invoke(button) as Int
            } else selectedColor
            val bounds = RectF(rect)
            return when (appearance) {
                is WeTypeKeyAppearance.Symbol -> {
                    // Scale the design's symbols together without independently fitting their ink.
                    val referenceSize = when (appearance.name) {
                        "shift", "shift.fill", "capslock.fill" -> 22f
                        "delete.left" -> 20f
                        else -> 19f
                    }
                    val size = symbolReferenceHeight(button, appearance.name, rect.height()) * referenceSize / 43f * 0.85f
                    SfSymbols.drawSymbol(canvas, assets, appearance.name, bounds,
                        size, color, weight = 400, fitFraction = 1f)
                }
                is WeTypeKeyAppearance.Text -> {
                    val size = rect.height() * 18f / 43f
                    SfSymbols.drawText(canvas, assets, appearance.value, bounds, size, color)
                }
                WeTypeKeyAppearance.Empty -> true
                WeTypeKeyAppearance.FontOnly -> false
            }
        }

        private fun symbolReferenceHeight(button: Any, symbol: String, keyHeight: Int): Float {
            val view = parent.invoke(button) as? View ?: return keyHeight.toFloat()
            if (view.javaClass.name != EMOJI_KEYBOARD) return keyHeight.toFloat()
            val id = (keyId.invoke(keyData.invoke(button)) as String).removeSuffix("_copy")
            if (!((id == "return" && symbol == "arrow.left") ||
                    (id == "delete" && symbol == "delete.left"))) return keyHeight.toFloat()
            val scaledIconHeight = (iconHeight?.invoke(button) as? Int)?.takeIf { it > 0 }
                ?: return keyHeight.toFloat()
            // Host design units: a standard key is 130/92 high and its icon box is 64 high.
            // Use the live icon scale so the compact emoji footer does not shrink these actions.
            val standardKeyHeight = if (view.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) 92f else 130f
            return scaledIconHeight * standardKeyHeight / 64f
        }
    }

    private class PropertyReader(private val method: Method?, private val field: Field?) {
        fun invoke(instance: Any): Any? = if (method != null) method.invoke(instance) else field!!.get(instance)
    }

    internal data class PropertySignature(val field: String?, val getter: String?)

    private fun propertySignatures(type: Class<*>): Map<String, PropertySignature> {
        val metadata = type.declaredAnnotations.firstOrNull { it.annotationClass.java.name == "kotlin.Metadata" }
            ?: error("Keyboard metadata missing")
        @Suppress("UNCHECKED_CAST")
        val strings = metadata.annotationClass.java.getMethod("d2").invoke(metadata) as Array<String>
        @Suppress("UNCHECKED_CAST")
        val data = metadata.annotationClass.java.getMethod("d1").invoke(metadata) as Array<String>
        return decodePropertySignatures(data, strings)
    }

    internal fun decodePropertySignatures(data: Array<String>, strings: Array<String>): Map<String, PropertySignature> {
        val encoded = data.joinToString("")
        // Kotlin's current UTF-8 metadata encoding stores one byte per UTF-16 code unit.
        check(encoded.length <= 1_048_576 && strings.size <= 65_536 && encoded.firstOrNull() == '\u0000') {
            "Unsupported keyboard metadata encoding"
        }
        val bytes = ByteArray(encoded.length - 1) { encoded[it + 1].code.toByte() }
        val reader = MetadataReader(bytes)
        val directNames = buildList {
            MetadataReader(reader.message()).fields().filter { it.number == 1 }.forEach { record ->
                val fields = MetadataReader(record.bytes!!).fields()
                val range = fields.firstOrNull { it.number == 1 }?.value ?: 1
                check(range in 1..strings.size && range <= strings.size - size) { "Invalid keyboard name table" }
                val direct = fields.all { it.number == 1 || (it.number == 3 && it.value == 0) }
                repeat(range) { add(direct) }
            }
        }
        fun nameAt(index: Int): String {
            check(index in strings.indices && directNames.getOrNull(index) == true) {
                "Unsupported transformed keyboard member name"
            }
            return strings[index]
        }
        return reader.fields().filter { it.number == 10 }.associate { property ->
            val fields = MetadataReader(property.bytes!!).fields()
            val name = nameAt(fields.single { it.number == 2 }.value!!)
            val signature = fields.firstOrNull { it.number == 100 }?.bytes
                ?.let { MetadataReader(it).fields() }.orEmpty()
            fun memberName(number: Int, default: String? = null): String? {
                val member = signature.firstOrNull { it.number == number }?.bytes ?: return null
                val index = MetadataReader(member).fields().firstOrNull { it.number == 1 }?.value
                return index?.let(::nameAt) ?: default
            }
            name to PropertySignature(memberName(1, name), memberName(3))
        }
    }

    private data class MetadataField(val number: Int, val value: Int? = null, val bytes: ByteArray? = null)

    /** Reads only the protobuf wire types used by Kotlin class/JVM-property metadata. */
    private class MetadataReader(private val bytes: ByteArray) {
        private var position = 0

        private fun integer(): Int {
            var result = 0
            for (shift in 0..28 step 7) {
                check(position < bytes.size) { "Truncated keyboard metadata" }
                val byte = bytes[position++].toInt() and 0xff
                result = result or ((byte and 0x7f) shl shift)
                if (byte and 0x80 == 0) return result
            }
            error("Invalid keyboard metadata integer")
        }

        fun message(): ByteArray {
            val size = integer()
            check(size >= 0 && size <= bytes.size - position) { "Invalid keyboard metadata message" }
            return bytes.copyOfRange(position, position + size).also { position += size }
        }

        fun fields(): List<MetadataField> = buildList {
            while (position < bytes.size) {
                val tag = integer()
                check(tag ushr 3 != 0) { "Invalid keyboard metadata field" }
                when (tag and 7) {
                    0 -> add(MetadataField(tag ushr 3, value = integer()))
                    2 -> add(MetadataField(tag ushr 3, bytes = message()))
                    1 -> position += 8
                    5 -> position += 4
                    else -> error("Unsupported keyboard metadata wire type")
                }
                check(position <= bytes.size) { "Truncated keyboard metadata field" }
            }
        }
    }
}
