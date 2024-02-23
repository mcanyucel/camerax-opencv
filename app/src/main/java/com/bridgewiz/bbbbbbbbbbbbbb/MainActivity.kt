package com.bridgewiz.bbbbbbbbbbbbbb

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.media.Image
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.Surface
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.core.content.ContextCompat
import androidx.navigation.findNavController
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.navigateUp
import com.bridgewiz.bbbbbbbbbbbbbb.databinding.ActivityMainBinding
import org.opencv.android.Utils
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

typealias LumaListener = (luma: Double) -> Unit
typealias OpencvListener = (message: String, bitmap: Bitmap) -> Unit

class MainActivity : AppCompatActivity() {


    private lateinit var binding: ActivityMainBinding

    private var imageCapture: ImageCapture? = null

    private lateinit var cameraExecutor: ExecutorService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Request camera permissions
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            requestPermissions()
        }

        binding.imageCaptureButton.setOnClickListener { takePhoto() }
        binding.videoCaptureButton.setOnClickListener {
            Toast.makeText(this, "Video feature is not implemented.", Toast.LENGTH_SHORT).show() }

        cameraExecutor = Executors.newSingleThreadExecutor()

        System.loadLibrary("opencv_java4")
    }

    private fun takePhoto() {
        val imageCapture = imageCapture ?: return

        val name = SimpleDateFormat(FILENAME_FORMAT, Locale.US)
            .format(System.currentTimeMillis())

        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CameraX-Image")
        }

        val outputOptions = ImageCapture.OutputFileOptions
            .Builder(contentResolver,
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                contentValues)
            .build()

        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed: ${exception.message}", exception)
                }

                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    val msg = "Photo capture succeeded: ${outputFileResults.savedUri}"
                    Toast.makeText(baseContext, msg, Toast.LENGTH_SHORT).show()
                    Log.d(TAG, "onImageSaved: $msg")
                }
            }
        )
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            // Preview
            val preview = Preview.Builder()
                .build()
                .also { it.setSurfaceProvider(binding.viewFinder.surfaceProvider) }

            // capture
            imageCapture = ImageCapture.Builder().build()

            val opencvAnalyzer = ImageAnalysis.Builder()
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor, LightnessAnalyzer { _, bitmap ->
                        // Log.d(TAG, message)
                        runOnUiThread {
                            // Rotate the bitmap based on the device orientation
                            try {
                                val rotation = when (binding.viewFinder.display.rotation) {
                                    Surface.ROTATION_0 -> 90
                                    Surface.ROTATION_90 -> 0
                                    Surface.ROTATION_180 -> 270
                                    Surface.ROTATION_270 -> 180
                                    else -> 0
                                }

                                if (rotation == 0) {
                                    binding.opencvImage.setImageBitmap(bitmap)
                                } else {
                                    val matrix = android.graphics.Matrix()
                                    matrix.postRotate(rotation.toFloat())
                                    val rotatedBitmap = Bitmap.createBitmap(
                                        bitmap,
                                        0,
                                        0,
                                        bitmap.width,
                                        bitmap.height,
                                        matrix,
                                        true
                                    )
                                    binding.opencvImage.setImageBitmap(rotatedBitmap)
                                }
                            } catch (ex: Exception) {
                                binding.opencvImage.setImageBitmap(bitmap)
                            }
                        }
                    })
                }

            // back camera as default
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()

                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageCapture, opencvAnalyzer
                )
            } catch (ex: Exception) {
                Log.e(TAG, "use case binding failed", ex)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun requestPermissions() {
        activityResultLauncher.launch(REQUIRED_PERMISSIONS)
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(
            baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    private val activityResultLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            var permissionGranted = true
            permissions.entries.forEach {
                if (it.key in REQUIRED_PERMISSIONS && !it.value)
                    permissionGranted = false
            }
            if (!permissionGranted) {
                Toast.makeText(baseContext, "Permission denied", Toast.LENGTH_SHORT)
                    .show()
            } else {
                startCamera()
            }
        }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    companion object {
        private const val TAG = "CameraX"
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
        private val REQUIRED_PERMISSIONS =
            mutableListOf(
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO
            )
                .toTypedArray()
    }

    private class LightnessAnalyzer(private val listener: OpencvListener) : ImageAnalysis.Analyzer {
        fun Image.yuvToRgba(): Mat {
            val rgbaMat = Mat()

            if (format == ImageFormat.YUV_420_888 && planes.size == 3){
                val chromaPixelStride = planes[1].pixelStride

                if (chromaPixelStride == 2) // chroma channels are interleaved
                {
                    assert(planes[0].pixelStride == 1)
                    assert(planes[2].pixelStride == 2)
                    val yPlane = planes[0].buffer
                    val uvPlane1 = planes[1].buffer
                    val uvPlane2 = planes[2].buffer

                    val yMat = Mat(height, width, CvType.CV_8UC1, yPlane)
                    val uvMat1 = Mat(height / 2, width / 2, CvType.CV_8UC2, uvPlane1)
                    val uvMat2 = Mat(height / 2, width / 2, CvType.CV_8UC2, uvPlane2)
                    val addrDiff = uvMat2.dataAddr() - uvMat1.dataAddr()

                    if (addrDiff > 0) {
                        assert(addrDiff == 1L)
                        Imgproc.cvtColorTwoPlane(yMat,uvMat1, rgbaMat, Imgproc.COLOR_YUV2RGBA_NV12)
                    } else {
                        assert(addrDiff == -1L)
                        Imgproc.cvtColorTwoPlane(yMat, uvMat2, rgbaMat, Imgproc.COLOR_YUV2RGBA_NV21)
                    }
                }
                else // chroma channels are not interleaved
                {
                    val yuvBytes = ByteArray(width * (height + height / 2))
                    val yPlane = planes[0].buffer
                    val uPlane = planes[1].buffer
                    val vPlane = planes[2].buffer

                    yPlane.get(yuvBytes, 0, width * height)

                    val chromaRowStride = planes[1].rowStride
                    val chromaRowPadding = chromaRowStride - width / 2

                    var offset = width * height

                    if (chromaRowPadding == 0) {
                        // When the row stride of the chroma channels equals their width, we can copy
                        // the entire channels in one go
                        uPlane.get(yuvBytes, offset, width * height / 4)
                        offset += width * height / 4
                        vPlane.get(yuvBytes, offset, width * height / 4)
                    } else {
                        // When not equal, we need to copy the channels row by row
                        for (i in 0 until height / 2) {
                            uPlane.get(yuvBytes, offset, width / 2)
                            offset += width / 2
                            if (i < height / 2 - 1) {
                                uPlane.position(uPlane.position() + chromaRowPadding)
                            }
                        }
                        for (i in 0 until height / 2) {
                            vPlane.get(yuvBytes, offset, width / 2)
                            offset += width / 2
                            if (i < height / 2 - 1) {
                                vPlane.position(vPlane.position() + chromaRowPadding)
                            }
                        }
                    }

                    val yuvMat = Mat(height + height / 2, width, CvType.CV_8UC1)
                    yuvMat.put(0,0,yuvBytes)
                    Imgproc.cvtColor(yuvMat, rgbaMat, Imgproc.COLOR_YUV2RGBA_I420, 4)
                }
            }
            return rgbaMat
        }

        @OptIn(ExperimentalGetImage::class) override fun analyze(image: ImageProxy) {
            image.image?.let {
                if (it.format == ImageFormat.YUV_420_888 && it.planes.size == 3) {
                    val rgbMat = it.yuvToRgba()
                    val buf = Mat()
                    Imgproc.cvtColor(rgbMat, buf, Imgproc.COLOR_RGBA2GRAY)
                    val bmp = Bitmap.createBitmap(buf.cols(), buf.rows(), Bitmap.Config.ARGB_8888)
                    val message = "Frame"

                    Utils.matToBitmap(buf, bmp)

                    listener(message, bmp)
                }
            }

            image.close()
        }
    }
}