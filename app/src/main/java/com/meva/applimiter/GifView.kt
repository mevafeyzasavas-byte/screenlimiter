package com.meva.applimiter

import android.content.Context
import android.graphics.Canvas
import android.graphics.Movie
import android.os.SystemClock
import android.view.View

// Basit, kütüphanesiz GIF oynatıcı (android.graphics.Movie ile).
// GifDrawable/Glide gibi bir bağımlılık eklemeden res/raw'daki .gif dosyalarını canlandırır.
class GifView(context: Context) : View(context) {

    private var movie: Movie? = null
    private var movieStart = 0L

    fun setGifResource(resId: Int) {
        movie = resources.openRawResource(resId).use { Movie.decodeStream(it) }
        movieStart = 0L
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val m = movie
        setMeasuredDimension(
            resolveSize(m?.width() ?: 0, widthMeasureSpec),
            resolveSize(m?.height() ?: 0, heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        val m = movie ?: return
        val now = SystemClock.uptimeMillis()
        if (movieStart == 0L) movieStart = now
        val duration = m.duration().takeIf { it > 0 } ?: 1000
        m.setTime(((now - movieStart) % duration).toInt())

        val mw = m.width().takeIf { it > 0 } ?: return
        val mh = m.height().takeIf { it > 0 } ?: return
        // en-boy oranını koru, kutunun ortasına sığdır
        val scale = minOf(width.toFloat() / mw, height.toFloat() / mh)
        canvas.save()
        canvas.translate((width - mw * scale) / 2f, (height - mh * scale) / 2f)
        canvas.scale(scale, scale)
        m.draw(canvas, 0f, 0f)
        canvas.restore()

        invalidate()
    }
}
