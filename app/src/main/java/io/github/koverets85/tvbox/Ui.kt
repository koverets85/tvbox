package io.github.koverets85.tvbox

import android.content.Context
import android.widget.Button

object Ui {
    /** TV button with a clearly visible focus (blue + slight zoom). */
    fun button(ctx: Context, label: String, pad: Int, onClick: () -> Unit) = Button(ctx).apply {
        text = label
        setOnClickListener { onClick() }
        isAllCaps = false
        textSize = 17f
        setTextColor(0xFFFFFFFF.toInt())
        background = ctx.getDrawable(R.drawable.item_bg)
        stateListAnimator = null
        setPadding(pad, pad / 2, pad, pad / 2)
        setOnFocusChangeListener { v, has ->
            val s = if (has) 1.08f else 1f
            v.animate().scaleX(s).scaleY(s).setDuration(120).start()
        }
    }
}
