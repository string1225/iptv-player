package com.string.iptv.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.widget.TextView

object TvStyle {
    val background = Color.rgb(8, 13, 19)
    val panel = Color.rgb(16, 25, 35)
    val muted = Color.rgb(150, 164, 180)
    val accent = Color.rgb(182, 239, 112)
    fun Context.dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    fun shape(color: Int, radius: Float = 12f, stroke: Int? = null): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
        if (stroke != null) setStroke(2, stroke)
    }

    fun focusBackground(): StateListDrawable = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), shape(accent))
        addState(intArrayOf(android.R.attr.state_pressed), shape(accent))
        addState(intArrayOf(android.R.attr.state_selected), shape(Color.rgb(37, 52, 63), stroke = Color.rgb(83, 110, 75)))
        addState(intArrayOf(), shape(Color.TRANSPARENT))
    }

    fun focusText(): ColorStateList = ColorStateList(
        arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf(android.R.attr.state_pressed), intArrayOf()),
        intArrayOf(background, background, Color.WHITE),
    )

    fun text(context: Context, value: String, size: Float = 16f, color: Int = Color.WHITE, bold: Boolean = false): TextView = TextView(context).apply {
        text = value
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        gravity = Gravity.CENTER_VERTICAL
    }

    fun button(context: Context, value: String, onClick: () -> Unit): TextView = text(context, value, 15f).apply {
        isFocusable = true
        isFocusableInTouchMode = true
        isClickable = true
        gravity = Gravity.CENTER
        setPadding(context.dp(18), context.dp(10), context.dp(18), context.dp(10))
        background = focusBackground()
        setTextColor(focusText())
        setOnClickListener { onClick() }
    }
}
