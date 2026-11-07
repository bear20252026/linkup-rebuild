package com.youloft.watcher.pages.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import com.youloft.watcher.R
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 水印像素合成，原版 `activity_image_preview.xml` 的底左水印卡（时间 36sp 粗体 + 日期/星期两行 +
 * 地址行 + 品牌顶条）从"视图叠加截图"还原为 Canvas 直绘：原版预览页把 TextView 盖在照片上、分享
 * 时截整张 RelativeLayout；拍照直发链路（相机页拍照→合成→发好友）在此一次画完。
 *
 * 比例基准：原版卡片是 411dp 宽屏上的固定 dp 值；这里 scale = 照片宽 / 411，所有 dp/sp 同乘。
 * 前摄小窗（104×136dp 圆角白描边）保留参数位——系统相机单次拍照拿不到前摄帧时传 null。
 */
object WatermarkComposer {

    /** 一次合成的全部输入；时间取拍照时刻，地址由调用方解析（拿不到传 null → 未知地址）。 */
    data class Spec(
        val timeMillis: Long,
        val address: String?,
        val frontThumbnail: Bitmap? = null,
    )

    private const val SCREEN_WIDTH_DP = 411f

    /** 原版常量（dp/sp）：卡边距 16、顶条高 31、logo 21、顶条圆角 16、时间 36 粗体、日期/地址 12。 */
    private const val CARD_MARGIN_DP = 16f
    private const val PILL_HEIGHT_DP = 31f
    private const val LOGO_SIZE_DP = 21f
    private const val CORNER_DP = 16f
    private const val TIME_SP = 36f
    private const val SMALL_SP = 12f
    private const val PADDING_H_DP = 8f
    private const val PADDING_BOTTOM_DP = 8f
    private const val FRONT_W_DP = 104f
    private const val FRONT_H_DP = 136f

    private const val BRAND = 0xFFF6DB7B.toInt() // colorBrand
    private const val TRANSLUCENT = 0x33FFFFFF // color_33ffffff
    private const val FRONT_STROKE_DP = 4f

    /**
     * 返回加好水印的新位图（原位图不动）。照片按 ARGB_8888 可变副本绘制。
     */
    fun compose(context: Context, photo: Bitmap, spec: Spec): Bitmap {
        val scale = photo.width / SCREEN_WIDTH_DP
        val out = photo.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        val dp = { v: Float -> v * scale }

        drawFrontInset(context, canvas, spec.frontThumbnail, scale)
        drawCard(context, canvas, spec, scale, dp)
        return out
    }

    private fun drawFrontInset(context: Context, canvas: Canvas, front: Bitmap?, scale: Float) {
        if (front == null) return
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val rect = RectF(
            16f * scale,
            16f * scale,
            (16f + FRONT_W_DP) * scale,
            (16f + FRONT_H_DP) * scale,
        )
        paint.color = Color.WHITE
        canvas.drawRoundRect(rect, CORNER_DP * scale, CORNER_DP * scale, paint)
        // 居中裁切进圆角矩形（原版 ShapeableImageView centerCrop + 4dp 白描边）。
        val side = minOf(rect.width(), rect.height()).toInt()
        val crop = Bitmap.createScaledBitmap(
            cropCenter(front, side),
            side,
            side,
            true,
        )
        canvas.save()
        canvas.clipRect(rect)
        canvas.drawBitmap(crop, rect.left, rect.top, null)
        canvas.restore()
    }

    private fun cropCenter(src: Bitmap, side: Int): Bitmap {
        val w = minOf(src.width, side)
        val h = minOf(src.height, side)
        return Bitmap.createBitmap(src, (src.width - w) / 2, (src.height - h) / 2, w, h)
    }

    private fun drawCard(
        context: Context,
        canvas: Canvas,
        spec: Spec,
        scale: Float,
        dp: (Float) -> Float,
    ) {
        val timeText = formatTime(spec.timeMillis)
        val dateText = formatDate(spec.timeMillis)
        val weekText = formatWeek(spec.timeMillis)
        val addressText = spec.address ?: context.getString(R.string.unknown_address)

        val timePaint = textPaint(TIME_SP * scale, bold = true)
        val smallPaint = textPaint(SMALL_SP * scale, bold = false)

        val timeWidth = timePaint.measureText(timeText)
        val dateWidth = smallPaint.measureText(dateText)
        val weekWidth = smallPaint.measureText(weekText)
        val addressWidth = smallPaint.measureText(addressText)
        val columnGap = 4f * scale

        val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = BRAND }
        val veilPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = TRANSLUCENT }

        // 底块宽 = max(时间+日期列, 地址) + 两侧内边距；顶条宽随底块（原版 alignEnd 对齐）。
        val bodyWidth = maxOf(
            timeWidth + columnGap + maxOf(dateWidth, weekWidth),
            addressWidth.coerceAtMost(dp(180f)), // 原版地址单行省略
        ) + dp(PADDING_H_DP) * 2
        val bodyHeight =
            timePaint.fontSpacing + smallPaint.fontSpacing * 0.5f + smallPaint.fontSpacing + dp(PADDING_BOTTOM_DP)

        val cardLeft = dp(CARD_MARGIN_DP)
        val cardBottom = canvas.height - dp(CARD_MARGIN_DP)
        val pillTop = cardBottom - dp(PILL_HEIGHT_DP) - bodyHeight
        val bodyTop = pillTop + dp(PILL_HEIGHT_DP)

        val cardWidth = bodyWidth
        val radius = dp(CORNER_DP)
        val pill = RectF(cardLeft, pillTop, cardLeft + cardWidth, pillTop + dp(PILL_HEIGHT_DP))
        val body = RectF(cardLeft, bodyTop, cardLeft + cardWidth, bodyTop + bodyHeight)
        canvas.drawRoundRect(pill, radius, radius, pillPaint)
        // 顶条只保留上圆角：下半用底块色盖回再整体画底块。
        canvas.drawRect(
            pill.left,
            pill.top + dp(PILL_HEIGHT_DP) / 2,
            pill.right,
            pill.bottom,
            pillPaint,
        )
        canvas.drawRoundRect(body, radius, radius, veilPaint)
        canvas.drawRect(body.left, body.top, body.right, body.top + bodyHeight / 2, veilPaint)

        drawBrandRow(context, canvas, pill, scale)

        val textLeft = cardLeft + dp(PADDING_H_DP)
        val baselineTime = bodyTop + timePaint.fontSpacing - timePaint.fontMetrics.bottom
        canvas.drawText(timeText, textLeft, baselineTime, timePaint)
        val columnLeft = textLeft + timeWidth + columnGap
        val dateTop = bodyTop + timePaint.fontSpacing * 0.5f
        canvas.drawText(dateText, columnLeft, dateTop + smallPaint.fontSpacing - smallPaint.fontMetrics.bottom, smallPaint)
        canvas.drawText(
            weekText,
            columnLeft,
            dateTop + smallPaint.fontSpacing * 1.5f - smallPaint.fontMetrics.bottom,
            smallPaint,
        )
        val addressBaseline = bodyTop + timePaint.fontSpacing + smallPaint.fontSpacing - smallPaint.fontMetrics.bottom
        canvas.drawText(addressText, textLeft, addressBaseline, smallPaint)
    }

    /** 顶条：logo（21dp 圆角 + 白描边）+ 字标图（高 23dp）。 */
    private fun drawBrandRow(context: Context, canvas: Canvas, pill: RectF, scale: Float) {
        val logo = android.graphics.BitmapFactory.decodeResource(context.resources, R.mipmap.ic_launcher)
        val name = android.graphics.BitmapFactory.decodeResource(context.resources, R.drawable.ic_camera_app_name)
        val center = pill.centerY()
        val logoSize = LOGO_SIZE_DP * scale
        val logoLeft = pill.left + dp(PADDING_H_DP, scale)
        if (logo != null) {
            val dst = RectF(logoLeft, center - logoSize / 2, logoLeft + logoSize, center + logoSize / 2)
            canvas.drawRoundRect(dst, 6f * scale, 6f * scale, Paint().apply { color = Color.WHITE })
            canvas.drawBitmap(logo, null, RectF(dst.left + 1f * scale, dst.top + 1f * scale, dst.right - 1f * scale, dst.bottom - 1f * scale), Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        }
        if (name != null) {
            val h = 23f * scale
            val w = h / name.height * name.width
            val left = logoLeft + logoSize + 4f * scale
            canvas.drawBitmap(name, null, RectF(left, center - h / 2, left + w, center + h / 2), Paint(Paint.FILTER_BITMAP_FLAG))
        }
    }

    private fun dp(v: Float, scale: Float) = v * scale

    private fun textPaint(sizePx: Float, bold: Boolean) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sizePx
        color = Color.WHITE
        typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }

    fun formatTime(millis: Long): String = SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(millis))

    fun formatDate(millis: Long): String = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date(millis)) // ag.a.f1746q

    fun formatWeek(millis: Long): String {
        val names = arrayOf("周日", "周一", "周二", "周三", "周四", "周五", "周六")
        return names[Calendar.getInstance(Locale.CHINA).apply { timeInMillis = millis }.get(Calendar.DAY_OF_WEEK) - 1]
    }
}
