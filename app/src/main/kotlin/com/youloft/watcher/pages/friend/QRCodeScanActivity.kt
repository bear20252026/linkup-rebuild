package com.youloft.watcher.pages.friend

import android.Manifest
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.youloft.watcher.R
import com.youloft.watcher.ext.onIntervalClick
import com.youloft.watcher.scan.QrDecoder
import com.youloft.watcher.view.ScanMaskDrawable
import java.util.concurrent.Executors

/**
 * Rebuilt from `QRCodeScanActivity`. The original subclasses `com.king.zxing:zxing-lite`'s
 * `BarcodeCameraScanActivity`; this build does the same job on CameraX + `com.google.zxing:core`,
 * because the library's shipped version cannot be recovered from the APK and the 1.6.6 layout already
 * places an `androidx.camera.view.PreviewView` — the trade-off is written up in `report/24`.
 *
 * Kept exactly as recovered: the result contract (`SCAN_RESULT` + `RESULT_OK` + finish), QR-only decoding
 * over the whole frame, analysis stopped on the first hit, the 2000 ms reversing scan line, the
 * punched-out dim mask over `fl_qr_scan`, the gallery branch with its API-29 permission split and the
 * 800×800 down-sampling rule, and vibrate-on-hit (a boolean the recovered config flips to `true`, with
 * `VIBRATE` in the shipped manifest).
 */
class QRCodeScanActivity : AppCompatActivity() {

    /** CameraX binds through a future; the seam exists so the screen can be driven without a camera. */
    fun interface CameraBinder {
        fun bind(activity: QRCodeScanActivity, previewView: PreviewView, analysis: ImageAnalysis)
    }

    var cameraBinder: CameraBinder = CameraXBinder()

    private var camera: Camera? = null

    private var analysis: ImageAnalysis? = null

    private val analysisExecutor = Executors.newSingleThreadExecutor()

    private var flashOn = false

    private var finishedWithResult = false

    private val analyzer = ImageAnalysis.Analyzer { image -> analyse(image) }

    private val galleryLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        onGalleryResult(result)
    }

    private val galleryPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) openGalleryPicker()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_qr_code_scan)
        hideSystemBars()

        findViewById<ImageView>(R.id.iv_close).onIntervalClick { finish() }
        findViewById<ImageView>(R.id.iv_gallery).onIntervalClick { requestGalleryAccess() }
        val flash = findViewById<ImageView>(R.id.iv_flash)
        flash.onIntervalClick { toggleFlash(flash) }

        val frame = findViewById<FrameLayout>(R.id.fl_qr_scan)
        frame.post {
            findViewById<FrameLayout>(R.id.fl_mask).background = ScanMaskDrawable(
                ColorDrawable(Color.argb(MASK_ALPHA, 0, 0, 0)),
                frame.left,
                frame.top,
                frame.right,
                frame.bottom,
            )
        }
        val line = findViewById<ImageView>(R.id.iv_line_scan)
        line.post { startScanLine(line) }

        bindCamera()
    }

    override fun onResume() {
        super.onResume()
        // The recovered screen turned decoding back on in `onResume` and off in `onPause`.
        if (!finishedWithResult) analysis?.setAnalyzer(analysisExecutor, analyzer)
    }

    override fun onPause() {
        super.onPause()
        analysis?.clearAnalyzer()
    }

    override fun onDestroy() {
        analysisExecutor.shutdown()
        super.onDestroy()
    }

    private fun bindCamera() {
        val frameAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
        analysis = frameAnalysis
        cameraBinder.bind(this, findViewById(R.id.previewView), frameAnalysis)
    }

    /** The provider hands the camera back once its future resolves; the flash toggle needs it. */
    internal fun attachCamera(camera: Camera) {
        this.camera = camera
    }

    internal fun analyse(image: ImageProxy) {
        try {
            if (finishedWithResult) return
            val text = QrDecoder.decode(image) ?: return
            analysis?.clearAnalyzer()
            ContextCompat.getMainExecutor(this).execute { deliverResult(text) }
        } finally {
            image.close()
        }
    }

    /** One hit ends the session: the recovered class stopped analysing before it returned. */
    internal fun deliverResult(text: String) {
        if (finishedWithResult) return
        finishedWithResult = true
        analysis?.clearAnalyzer()
        vibrate()
        setResult(RESULT_OK, Intent().putExtra(EXTRA_SCAN_RESULT, text))
        finish()
    }

    /** `W()` — a plain `ACTION_PICK` on images, not the in-app selector screen. */
    private fun requestGalleryAccess() {
        galleryPermissionLauncher.launch(galleryPermission())
    }

    private fun openGalleryPicker() {
        runCatching { galleryLauncher.launch(Intent(Intent.ACTION_PICK).apply { type = IMAGE_MIME_TYPE }) }
    }

    internal fun onGalleryResult(result: ActivityResult) {
        val data = result.data?.data ?: return
        if (result.resultCode != RESULT_OK) return
        val bitmap = runCatching { decodeSampled(data) }.getOrNull() ?: return
        val text = QrDecoder.decode(bitmap)
        bitmap.recycle()
        if (text != null) deliverResult(text)
    }

    /**
     * The recovered two-pass decode: measure with `inJustDecodeBounds`, pick the sample size against
     * 800×800, then decode for real.
     */
    private fun decodeSampled(uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val options = BitmapFactory.Options().apply {
            inSampleSize = QrDecoder.sampleSizeFor(bounds.outWidth, bounds.outHeight, GALLERY_SIZE, GALLERY_SIZE)
            inJustDecodeBounds = false
        }
        return contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
    }

    /** `S()` — from wherever the line sits now down to the frame's bottom, reversing forever. */
    private fun startScanLine(line: ImageView) {
        val parentHeight = (line.parent as? ViewGroup)?.height ?: return
        ValueAnimator.ofFloat(line.translationY, parentHeight.toFloat()).apply {
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            duration = SCAN_LINE_DURATION_MILLIS
            addUpdateListener { animator -> line.translationY = animator.animatedValue as Float }
            start()
        }
    }

    private fun toggleFlash(flash: ImageView) {
        flashOn = !flashOn
        runCatching { camera?.cameraControl?.enableTorch(flashOn) }
        flash.setImageResource(if (flashOn) R.drawable.ic_flash_on else R.drawable.ic_flash_off)
    }

    private fun vibrate() {
        val vibrator = getSystemService(Vibrator::class.java) ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(VIBRATE_MILLIS, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(VIBRATE_MILLIS)
            }
        }
    }

    /**
     * The recovered chain was `ImmersionBar…hideNavigationBar().fitsSystemWindows(R.id.rl_title_bar)`;
     * `WindowInsetsControllerCompat` is the platform equivalent we can depend on directly.
     */
    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView)?.apply {
            hide(WindowInsetsCompat.Type.navigationBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.rl_title_bar)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, view.paddingBottom)
            insets
        }
    }

    companion object {
        /** The key the recovered class exposed as `QRCodeScanActivity.f64728i` / `f63935i`. */
        const val EXTRA_SCAN_RESULT = "SCAN_RESULT"

        /** `SelectMimeType.SYSTEM_IMAGE`, the type the original put on the picker Intent. */
        const val IMAGE_MIME_TYPE = "image/*"

        const val GALLERY_SIZE = 800
        const val SCAN_LINE_DURATION_MILLIS = 2000L
        const val MASK_ALPHA = 153
        const val VIBRATE_MILLIS = 60L

        /** The original's `SDK_INT >= 29` split between the two read-media permissions. */
        internal fun galleryPermission(): String = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        fun intent(context: Context): Intent = Intent(context, QRCodeScanActivity::class.java)
    }

    class CameraXBinder : CameraBinder {
        override fun bind(activity: QRCodeScanActivity, previewView: PreviewView, analysis: ImageAnalysis) {
            val future = ProcessCameraProvider.getInstance(activity)
            future.addListener(
                {
                    runCatching {
                        val provider = future.get()
                        val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                        provider.unbindAll()
                        activity.attachCamera(
                            provider.bindToLifecycle(
                                activity,
                                CameraSelector.DEFAULT_BACK_CAMERA,
                                preview,
                                analysis,
                            ),
                        )
                    }
                },
                ContextCompat.getMainExecutor(activity),
            )
        }
    }
}
