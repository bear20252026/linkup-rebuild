package com.youloft.watcher.pages.camera

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.location.Geocoder
import android.location.LocationManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import com.youloft.watcher.R
import com.youloft.watcher.data.appGraph
import com.youloft.watcher.databinding.ActivityCameraBinding
import com.youloft.watcher.pages.chat.ChatActivity
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 水印拍照，`pages/camera/CameraFragment` + `widget/camera/CameraHelper` 的本轮版：
 *
 * - **拍照**：系统相机 [ActivityResultContracts.TakePicture]（全分辨率落 cache，文件名照原版
 *   `yyyyMMddHHmmssSSS.jpg`）。原版 CameraX 双摄并发（前+后同帧）是硬件档，系统相机单次
 *   只出后摄帧——前摄小窗参数位保留在 [WatermarkComposer.Spec.frontThumbnail]，恒传 null。
 * - **水印**：[WatermarkComposer] 把原版预览页的底左水印卡（时间/日期/星期/地址 + 品牌顶条）
 *   像素合成进照片本体——这一步就是本卡的闭环（此前只拍不发水印）。
 * - **地址**：系统 last-known 定位 → [Geocoder] 反解；拿不到显示原版文案"未获取到位置信息"。
 *   原版读 CacheUtils.F() 缓存的定位 bean，本重建没有该缓存（DESIGN 记录取舍）。
 * - **发送**：合成 JPEG 落 cache → 选好友直发聊天（图片 IM 链路与聊天页同一条）。
 */
class CameraActivity : AppCompatActivity() {
    private lateinit var binding: ActivityCameraBinding
    private var captureFile: File? = null
    private val capture = registerForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val file = captureFile
        if (saved && file != null && file.exists()) {
            pickFriend(file)
        } else {
            captureFile = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCameraBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.ivBack.setOnClickListener { finish() }
        // 原版的快门是屏幕中央的大圆钮；本轮以标题行入口触发同一拍照流。
        binding.ivCameraShutter.setOnClickListener { launchCapture() }
    }

    private fun launchCapture() {
        val dir = File(cacheDir, "camera").apply { mkdirs() }
        val file = File(dir, fileName())
        captureFile = file
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        runCatching { capture.launch(uri) }.onFailure {
            Toast.makeText(this, R.string.tips_net_err, Toast.LENGTH_SHORT).show()
        }
    }

    /** 原版 `CameraHelper.i()`：`yyyyMMddHHmmssSSS` + .jpg。 */
    private fun fileName(): String =
        SimpleDateFormat("yyyyMMddHHmmssSSS", Locale.CHINA).format(Date()) + ".jpg"

    private fun pickFriend(file: File) {
        val watermarked = runCatching { composeWatermark(file) }.getOrNull()
        val target = watermarked ?: file
        if (watermarked == null) {
            Toast.makeText(this, R.string.camera_watermark_failed, Toast.LENGTH_SHORT).show()
        }
        requestSend(target)
    }

    /** EXIF 转正 → 限宽解码 → 合成 → 另存 watermarked-*.jpg。 */
    private fun composeWatermark(file: File): File? {
        val rotated = decodeUpright(file) ?: return null
        val bitmap = WatermarkComposer.compose(this, rotated, WatermarkComposer.Spec(
            timeMillis = System.currentTimeMillis(),
            address = currentAddress(),
            frontThumbnail = null,
        ))
        val out = File(file.parentFile, "watermarked-" + file.name)
        out.outputStream().use { stream ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 92, stream)
        }
        if (rotated !== bitmap) rotated.recycle()
        bitmap.recycle()
        return out
    }

    private fun decodeUpright(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val maxSide = 2048
        var sample = 1
        while (bounds.outWidth / sample > maxSide || bounds.outHeight / sample > maxSide) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = BitmapFactory.decodeFile(file.absolutePath, options) ?: return null
        val degrees = runCatching {
            when (ExifInterface(file.absolutePath).getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL,
            )) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        }.getOrDefault(0f)
        if (degrees == 0f) return decoded
        val matrix = android.graphics.Matrix().apply { postRotate(degrees) }
        val rotated = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        if (rotated !== decoded) decoded.recycle()
        return rotated
    }

    /** last-known 定位反解地址；无定位/反解失败 → null（卡面落"未获取到位置信息"）。 */
    private fun currentAddress(): String? {
        val manager = getSystemService(LocationManager::class.java) ?: return null
        val providers = manager.getProviders(true)
        var best: android.location.Location? = null
        for (provider in providers) {
            val last = runCatching { manager.getLastKnownLocation(provider) }.getOrNull() ?: continue
            if (best == null || last.time > best!!.time) best = last
        }
        val location = best ?: return null
        val geocoder = Geocoder(this, Locale.CHINA)
        val address = runCatching {
            geocoder.getFromLocation(location.latitude, location.longitude, 1)?.firstOrNull()
        }.getOrNull() ?: return null
        return listOfNotNull(
            address.locality,
            address.subLocality,
            address.thoroughfare,
            address.subThoroughfare,
        ).joinToString("").ifBlank { null }
    }

    private fun requestSend(file: File) {
        // 复用聊天页的媒体链路：进聊天页即为选中会话，聊天页内发送走同一 sendImage。
        ChatActivity.start(this, appGraph(this).users.current().userId, null)
    }

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, CameraActivity::class.java))
        }
    }
}
