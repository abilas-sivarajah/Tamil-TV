package com.tvclient.app.ui.browse

import android.view.ContextThemeWrapper
import android.view.ViewGroup
import android.widget.ImageView
import androidx.core.content.ContextCompat
import androidx.leanback.widget.ImageCardView
import androidx.leanback.widget.Presenter
import coil.dispose
import coil.load
import com.tvclient.app.R
import com.tvclient.app.data.model.Movie

/**
 * Zeichnet eine Poster-Karte. Der Fokus-Effekt (Zoom + farbiger Infobereich)
 * kommt von [ImageCardView] selbst - wir setzen nur die Farben.
 */
class CardPresenter(
    private val cardWidthDp: Int = CARD_WIDTH_DP,
    private val cardHeightDp: Int = CARD_HEIGHT_DP
) : Presenter() {

    private var defaultBackground: Int = 0
    private var selectedBackground: Int = 0

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val context = parent.context
        defaultBackground = ContextCompat.getColor(context, R.color.card_background)
        selectedBackground = ContextCompat.getColor(context, R.color.card_background_selected)

        val cardView = object : ImageCardView(
            ContextThemeWrapper(context, R.style.CardTheme)
        ) {
            override fun setSelected(selected: Boolean) {
                updateInfoBackground(this, selected)
                super.setSelected(selected)
            }
        }

        cardView.isFocusable = true
        cardView.isFocusableInTouchMode = true
        cardView.mainImageView?.scaleType = ImageView.ScaleType.CENTER_CROP
        updateInfoBackground(cardView, false)
        return ViewHolder(cardView)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
        val movie = item as? Movie ?: return
        val cardView = viewHolder.view as ImageCardView
        val density = cardView.resources.displayMetrics.density

        cardView.titleText = movie.title
        cardView.contentText = movie.subtitleLine
        cardView.setMainImageDimensions(
            (cardWidthDp * density).toInt(),
            (cardHeightDp * density).toInt()
        )

        val isLogoCard = movie.isChannelFolder || movie.isLiveTv
        val pad = if (isLogoCard) (16 * density).toInt() else 0
        cardView.mainImageView?.apply {
            scaleType = if (isLogoCard) ImageView.ScaleType.FIT_CENTER else ImageView.ScaleType.CENTER_CROP
            setPadding(pad, pad, pad, pad)
        }

        cardView.mainImageView?.load(movie.thumbnail) {
            crossfade(200)
            placeholder(R.drawable.card_placeholder)
            error(R.drawable.card_placeholder)
            // Auf dem Stick ist RAM knapp: Bilder auf Kartengroesse herunterskalieren
            size((cardWidthDp * density).toInt(), (cardHeightDp * density).toInt())
        }
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        val cardView = viewHolder.view as ImageCardView
        // Referenzen loesen, sonst haelt die Recycling-Queue die Bitmaps fest
        cardView.mainImageView?.dispose()
        cardView.badgeImage = null
        cardView.mainImage = null
    }

    private fun updateInfoBackground(view: ImageCardView, selected: Boolean) {
        view.setInfoAreaBackgroundColor(if (selected) selectedBackground else defaultBackground)
    }

    companion object {
        const val CARD_WIDTH_DP = 180
        const val CARD_HEIGHT_DP = 260
    }
}
