package com.tvclient.app.ui.browse

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.leanback.widget.Presenter
import coil.dispose
import coil.load
import com.tvclient.app.R
import com.tvclient.app.data.model.Movie

/**
 * Moderne TV-Karte im ABI-TV-Stil (identisch zum neuen TamilTV-Vega-Design):
 * - Abgerundetes 16:10-Cover (196 x 122 dp) mit dezentem Glas-Rand
 * - Bei Fokus: weicher Zoom (1.07x), elektrischer Cyan-Leuchtrahmen (#38BDF8) und Cyan-Titel
 * - Live-TV-Karten tragen ein "LIVE"-Badge oben links und Qualitäts-/Kategorie-Badge unten rechts
 * - Titel und Unterzeile sauber unterhalb der Karte ohne schweren grauen Kasten
 */
class CardPresenter(
    private val cardWidthDp: Int = CARD_WIDTH_DP,
    private val cardHeightDp: Int = CARD_HEIGHT_DP
) : Presenter() {

    private class CardViewHolder(
        root: LinearLayout,
        val imageContainer: FrameLayout,
        val imageView: ImageView,
        val fallbackText: TextView,
        val liveBadge: LinearLayout,
        val cornerBadge: TextView,
        val titleView: TextView,
        val subtitleView: TextView,
        val borderOverlay: View
    ) : ViewHolder(root)

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val context = parent.context
        val d = context.resources.displayMetrics.density
        fun dp(v: Int): Int = (v * d).toInt()
        fun dpF(v: Float): Float = v * d

        val surfaceColor = ContextCompat.getColor(context, R.color.card_background)
        val elevatedColor = ContextCompat.getColor(context, R.color.card_background_selected)
        val accentColor = ContextCompat.getColor(context, R.color.brand_accent)
        val emeraldColor = ContextCompat.getColor(context, R.color.live_emerald)
        val textPrimary = ContextCompat.getColor(context, R.color.text_primary)
        val textMuted = ContextCompat.getColor(context, R.color.text_muted)
        val subtleBorder = ContextCompat.getColor(context, R.color.card_border_subtle)
        val cornerRadius = dpF(11f)

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            isFocusable = true
            isFocusableInTouchMode = true
            clipChildren = false
            clipToPadding = false
            layoutParams = ViewGroup.LayoutParams(dp(cardWidthDp), ViewGroup.LayoutParams.WRAP_CONTENT)
            setPadding(dp(2), dp(4), dp(2), dp(4))
        }

        val imageContainer = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(cardWidthDp - 4), dp(cardHeightDp))
            background = roundedRect(surfaceColor, cornerRadius)
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, cornerRadius)
                }
            }
            clipToOutline = true
        }

        val fallbackText = TextView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            gravity = Gravity.CENTER
            setTextColor(accentColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }

        val imageView = ImageView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            scaleType = ImageView.ScaleType.CENTER_CROP
        }

        // LIVE-Badge oben links
        val liveDot = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(6), dp(6)).apply {
                marginEnd = dp(4)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(emeraldColor)
            }
        }
        val liveLabel = TextView(context).apply {
            text = "LIVE"
            setTextColor(textPrimary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
            setTypeface(null, Typeface.BOLD)
        }
        val liveBadge = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setCornerRadius(dpF(5f))
                setColor(0xD90B0E14.toInt())
                setStroke(dp(1), 0x8010B981.toInt())
            }
            setPadding(dp(6), dp(2), dp(6), dp(2))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START
            ).apply {
                setMargins(dp(7), dp(7), 0, 0)
            }
            addView(liveDot)
            addView(liveLabel)
        }

        // Qualitäts-/Kategorie-Badge unten rechts
        val cornerBadge = TextView(context).apply {
            visibility = View.GONE
            setTextColor(textPrimary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
            setTypeface(null, Typeface.BOLD)
            maxLines = 1
            background = roundedRect(0xD10B0E14.toInt(), dpF(5f))
            setPadding(dp(6), dp(2), dp(6), dp(2))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.END
            ).apply {
                setMargins(0, 0, dp(7), dp(7))
            }
        }

        // Rahmen-Overlay (Cyan bei Fokus, dezentes Glas ohne Fokus)
        val unfocusedBorder = strokeRect(subtleBorder, dp(1), cornerRadius)
        val focusedBorder = strokeRect(accentColor, dp(3), cornerRadius)
        val borderOverlay = View(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            background = unfocusedBorder
        }

        imageContainer.addView(fallbackText)
        imageContainer.addView(imageView)
        imageContainer.addView(liveBadge)
        imageContainer.addView(cornerBadge)
        imageContainer.addView(borderOverlay)

        val titleView = TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(6)
            }
            setTextColor(textPrimary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTypeface(null, Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(2), 0, dp(2), 0)
        }

        val subtitleView = TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(1)
            }
            setTextColor(textMuted)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(2), 0, dp(2), 0)
        }

        root.addView(imageContainer)
        root.addView(titleView)
        root.addView(subtitleView)

        root.setOnFocusChangeListener { v, hasFocus ->
            val targetScale = if (hasFocus) 1.07f else 1.0f
            v.animate()
                .scaleX(targetScale)
                .scaleY(targetScale)
                .setDuration(140)
                .start()
            v.elevation = if (hasFocus) dpF(10f) else 0f
            borderOverlay.background = if (hasFocus) focusedBorder else unfocusedBorder
            imageContainer.background = roundedRect(if (hasFocus) elevatedColor else surfaceColor, cornerRadius)
            titleView.setTextColor(if (hasFocus) accentColor else textPrimary)
        }

        return CardViewHolder(
            root = root,
            imageContainer = imageContainer,
            imageView = imageView,
            fallbackText = fallbackText,
            liveBadge = liveBadge,
            cornerBadge = cornerBadge,
            titleView = titleView,
            subtitleView = subtitleView,
            borderOverlay = borderOverlay
        )
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
        val movie = item as? Movie ?: return
        val holder = viewHolder as CardViewHolder
        val d = holder.view.resources.displayMetrics.density
        val isLogoCard = movie.isChannelFolder || movie.isLiveTv || movie.isAppAction

        holder.titleView.text = movie.title
        val rawSub = movie.subtitleLine
        holder.subtitleView.text = if (movie.isLiveTv) {
            rawSub.replace(Regex("^LIVE\\s*•\\s*", RegexOption.IGNORE_CASE), "")
        } else {
            rawSub
        }
        holder.subtitleView.visibility = if (holder.subtitleView.text.isNullOrBlank()) View.GONE else View.VISIBLE

        holder.liveBadge.visibility = if (movie.isLiveTv) View.VISIBLE else View.GONE

        val badgeText = if (movie.isLiveTv) {
            movie.year?.split("•")?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
        } else {
            null
        }
        if (badgeText != null) {
            holder.cornerBadge.text = badgeText
            holder.cornerBadge.visibility = View.VISIBLE
        } else {
            holder.cornerBadge.visibility = View.GONE
        }

        holder.fallbackText.text = initialsOf(movie.title)
        holder.fallbackText.visibility = View.VISIBLE

        val pad = if (isLogoCard) (16 * d).toInt() else 0
        holder.imageView.apply {
            scaleType = if (isLogoCard) ImageView.ScaleType.FIT_CENTER else ImageView.ScaleType.CENTER_CROP
            setPadding(pad, pad, pad, pad)
        }

        if (movie.thumbnail.isNullOrBlank()) {
            holder.imageView.dispose()
            holder.imageView.setImageDrawable(null)
        } else {
            holder.imageView.load(movie.thumbnail) {
                crossfade(180)
                size((cardWidthDp * d).toInt(), (cardHeightDp * d).toInt())
                listener(
                    onSuccess = { _, _ -> holder.fallbackText.visibility = View.GONE },
                    onError = { _, _ -> holder.fallbackText.visibility = View.VISIBLE }
                )
            }
        }
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        val holder = viewHolder as CardViewHolder
        holder.imageView.dispose()
        holder.imageView.setImageDrawable(null)
    }

    private fun roundedRect(fillColor: Int, radiusPx: Float): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fillColor)
            cornerRadius = radiusPx
        }

    private fun strokeRect(strokeColor: Int, strokeWidthPx: Int, radiusPx: Float): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(Color.TRANSPARENT)
            setStroke(strokeWidthPx, strokeColor)
            cornerRadius = radiusPx
        }

    private fun initialsOf(title: String): String {
        val words = title.split(Regex("[\\s–-]+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return "?"
        return words.take(2).joinToString("") { it.first().uppercase() }
    }

    companion object {
        const val CARD_WIDTH_DP = 196
        const val CARD_HEIGHT_DP = 122
    }
}
