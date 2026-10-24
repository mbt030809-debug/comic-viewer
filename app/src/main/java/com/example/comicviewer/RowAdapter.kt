package com.example.comicviewer

import android.content.Context
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.recyclerview.widget.RecyclerView
import coil.ImageLoader
import coil.load
import coil.size.Dimension
import coil.size.Size

/** items: 한 행에 들어갈 이미지 URL (1개 또는 2개). 오른쪽→왼쪽 순서는 호출 측에서 뒤집어 전달 */
class RowAdapter(
    private val loader: ImageLoader,
    val rows: List<List<String>>,
    private val cols: Int,
    private val imgWidthPx: Int,
    private val onLoaded: () -> Unit,
) : RecyclerView.Adapter<RowAdapter.VH>() {

    class VH(val box: LinearLayout, val views: List<ImageView>) : RecyclerView.ViewHolder(box)

    override fun getItemCount() = rows.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val ctx: Context = parent.context
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_HORIZONTAL or Gravity.TOP
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val views = List(cols) {
            ImageView(ctx).apply {
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_CENTER
                layoutParams = LinearLayout.LayoutParams(imgWidthPx, ViewGroup.LayoutParams.WRAP_CONTENT)
                box.addView(this)
            }
        }
        return VH(box, views)
    }

    override fun onBindViewHolder(h: VH, pos: Int) {
        val row = rows[pos]
        h.views.forEachIndexed { i, iv ->
            val url = row.getOrNull(i)
            if (url == null) {
                iv.visibility = if (row.size == 1 && cols == 2) android.view.View.GONE else android.view.View.INVISIBLE
                iv.setImageDrawable(null)
                return@forEachIndexed
            }
            iv.visibility = android.view.View.VISIBLE
            iv.minimumHeight = (imgWidthPx * 1.4f).toInt()
            iv.load(url, loader) {
                size(Size(Dimension.Pixels(imgWidthPx), Dimension.Undefined))
                listener(onSuccess = { _, _ -> iv.minimumHeight = 0; onLoaded() })
            }
        }
    }
}
