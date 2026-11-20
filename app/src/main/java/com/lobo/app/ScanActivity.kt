package com.lobo.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.activity.result.contract.ActivityResultContracts
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.Result
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.lobo.app.databinding.ActivityScanBinding
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 扫一扫：扫描其它设备的「萝卜快传」主页地址二维码。
 * 解码成功后通过 [EXTRA_SCAN_RESULT] 把原始文本回传给 MainActivity。
 */
class ScanActivity : AppCompatActivity() {

    private lateinit var binding: ActivityScanBinding
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var analysisExecutor: ExecutorService? = null
    private var torchOn = false
    private var decoded = false

    private val qrReader = QRCodeReader()
    private val hints: Map<DecodeHintType, Any> = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        DecodeHintType.TRY_HARDER to true,
        DecodeHintType.CHARACTER_SET to "UTF-8"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScanBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        binding.tvTitle.setText(R.string.scan_title)
        binding.btnClose.setOnClickListener { finish() }
        binding.btnTorch.setOnClickListener { toggleTorch() }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startCamera()
            } else {
                toast(getString(R.string.permission_camera_rationale))
                finish()
            }
        }

    private fun startCamera() {
        binding.tvScanHint.setText(R.string.scan_hint)
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = try {
                future.get()
            } catch (t: Throwable) {
                toast(t.message ?: getString(R.string.scan_connect_failed, ""))
                finish()
                return@addListener
            }
            cameraProvider = provider

            val preview = Preview.Builder().build().also {
                it.surfaceProvider = binding.preview.surfaceProvider
            }

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            val executor = Executors.newSingleThreadExecutor()
            analysisExecutor = executor
            analysis.setAnalyzer(executor) { imageProxy -> analyze(imageProxy) }

            try {
                provider.unbindAll()
                camera = provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis
                )
            } catch (t: Throwable) {
                toast(t.message ?: getString(R.string.scan_connect_failed, ""))
                finish()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyze(imageProxy: ImageProxy) {
        if (decoded) {
            imageProxy.close()
            return
        }
        try {
            val luminance = toLuminance(imageProxy) ?: return
            val bitmap = BinaryBitmap(HybridBinarizer(luminance))
            val result: Result = try {
                qrReader.decode(bitmap, hints)
            } catch (_: NotFoundException) {
                null
            } ?: return
            val text = result.text?.trim().orEmpty()
            if (text.isEmpty()) return
            decoded = true
            runOnUiThread {
                if (isHttpUrl(text)) {
                    binding.progressConnecting.visibility = View.VISIBLE
                    binding.tvScanHint.setText(R.string.scan_connecting)
                    returnResult(text)
                } else {
                    decoded = false
                    toast(getString(R.string.scan_invalid))
                }
            }
        } catch (_: Throwable) {
            // 单帧解码失败忽略，继续扫描
        } finally {
            imageProxy.close()
        }
    }

    /** 把 ImageProxy 的 Y 平面转成 ZXing 的 LuminanceSource（兼容 rowStride / 裁剪区域）。 */
    private fun toLuminance(imageProxy: ImageProxy): PlanarYUVLuminanceSource? {
        val plane = imageProxy.planes.firstOrNull() ?: return null
        val buffer = plane.buffer ?: return null
        val width = imageProxy.width
        val height = imageProxy.height
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        if (width <= 0 || height <= 0) return null

        val rowBytes = rowStride.coerceAtLeast(width * pixelStride)
        val data = ByteArray(width * height)
        val row = ByteArray(rowBytes)
        val start = buffer.position()
        for (y in 0 until height) {
            val offset = start + y * rowStride
            if (offset + rowBytes > buffer.limit()) break
            buffer.position(offset)
            buffer.get(row, 0, rowBytes)
            if (pixelStride == 1) {
                System.arraycopy(row, 0, data, y * width, width)
            } else {
                var src = 0
                var dst = y * width
                for (x in 0 until width) {
                    data[dst++] = row[src]
                    src += pixelStride
                }
            }
        }
        buffer.position(start)
        return PlanarYUVLuminanceSource(data, width, height, 0, 0, width, height, false)
    }

    private fun toast(message: String) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun isHttpUrl(text: String): Boolean {
        return text.startsWith("http://", ignoreCase = true) ||
            text.startsWith("https://", ignoreCase = true)
    }

    private fun returnResult(text: String) {
        setResult(RESULT_OK, Intent().putExtra(EXTRA_SCAN_RESULT, text))
        finish()
    }

    private fun toggleTorch() {
        val control = camera?.cameraControl ?: return
        torchOn = !torchOn
        control.enableTorch(torchOn)
        binding.btnTorch.setImageResource(if (torchOn) R.drawable.ic_flash_on else R.drawable.ic_flash_off)
        toast(getString(if (torchOn) R.string.scan_torch_on else R.string.scan_torch_off))
    }

    override fun onDestroy() {
        analysisExecutor?.shutdownNow()
        analysisExecutor = null
        cameraProvider?.unbindAll()
        cameraProvider = null
        camera = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_SCAN_RESULT = "com.lobo.app.extra.SCAN_RESULT"
    }
}
