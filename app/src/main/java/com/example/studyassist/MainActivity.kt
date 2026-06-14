package com.example.studyassist

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : ComponentActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var statusText: TextView
    private lateinit var ocrText: TextView
    private lateinit var toggleRecognitionButton: Button
    private lateinit var retryPermissionButton: Button
    private lateinit var manualScanButton: Button
    private lateinit var clearButton: Button

    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val textRecognizer: TextRecognizer =
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    private var cameraProvider: ProcessCameraProvider? = null
    private var isRecognitionEnabled = true
    private var lastAnalyzedAt = 0L
    private var lastRecognizedText = ""
    private val isOcrRunning = AtomicBoolean(false)

    private val requestCameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                updateStatus("Camera permission granted. Starting preview...")
                startCamera()
            } else {
                updateStatus("Camera permission denied. Preview is unavailable.")
                Toast.makeText(this, "Camera permission is required.", Toast.LENGTH_LONG).show()
                retryPermissionButton.isEnabled = true
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildContentView()
        bindControls()

        if (hasCameraPermission()) {
            startCamera()
        } else {
            updateStatus("Camera permission is required for live preview.")
            requestCameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onDestroy() {
        cameraProvider?.unbindAll()
        cameraProvider = null
        textRecognizer.close()
        cameraExecutor.shutdown()
        super.onDestroy()
    }

    private fun buildContentView() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFF6F7F9.toInt())
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }

        previewView = PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.PERFORMANCE
            scaleType = PreviewView.ScaleType.FILL_CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
        }

        statusText = TextView(this).apply {
            textSize = 15f
            setTextColor(0xFF1F2937.toInt())
            setPadding(24, 16, 24, 8)
            text = "Preparing camera..."
        }

        ocrText = TextView(this).apply {
            textSize = 14f
            setTextColor(0xFF111827.toInt())
            setPadding(24, 12, 24, 12)
            text = "OCR text will appear here."
        }

        val ocrScroll = ScrollView(this).apply {
            setBackgroundColor(0xFFFFFFFF.toInt())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                220,
            )
            addView(ocrText)
        }

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(20, 8, 20, 24)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }

        toggleRecognitionButton = Button(this).apply {
            text = "Pause OCR"
            layoutParams = controlLayoutParams()
        }

        manualScanButton = Button(this).apply {
            text = "Scan once"
            layoutParams = controlLayoutParams()
        }

        clearButton = Button(this).apply {
            text = "Clear text"
            layoutParams = controlLayoutParams()
        }

        retryPermissionButton = Button(this).apply {
            text = "Request permission again"
            isEnabled = false
            layoutParams = controlLayoutParams()
        }

        controls.addView(toggleRecognitionButton)
        controls.addView(manualScanButton)
        controls.addView(clearButton)
        controls.addView(retryPermissionButton)
        root.addView(previewView)
        root.addView(statusText)
        root.addView(ocrScroll)
        root.addView(controls)
        setContentView(root)
    }

    private fun bindControls() {
        toggleRecognitionButton.setOnClickListener {
            isRecognitionEnabled = !isRecognitionEnabled
            toggleRecognitionButton.text = if (isRecognitionEnabled) "Pause OCR" else "Start OCR"
            updateStatus(
                if (isRecognitionEnabled) {
                    "OCR is running with throttled frame analysis."
                } else {
                    "OCR is paused. Camera preview remains active."
                },
            )
        }

        manualScanButton.setOnClickListener {
            lastAnalyzedAt = 0L
            updateStatus("Waiting for the next camera frame to scan...")
        }

        clearButton.setOnClickListener {
            lastRecognizedText = ""
            ocrText.text = "OCR text will appear here."
            updateStatus("OCR result cleared.")
        }

        retryPermissionButton.setOnClickListener {
            retryPermissionButton.isEnabled = false
            requestCameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener(
            {
                try {
                    val provider = cameraProviderFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .also { imageAnalysis ->
                            imageAnalysis.setAnalyzer(cameraExecutor, ::analyzeImage)
                        }

                    provider.unbindAll()
                    provider.bindToLifecycle(
                        this,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis,
                    )

                    cameraProvider = provider
                    retryPermissionButton.isEnabled = false
                    updateStatus("Camera preview and OCR are running.")
                } catch (error: Exception) {
                    updateStatus("Camera failed: ${error.localizedMessage ?: "unknown error"}")
                    Toast.makeText(this, "Failed to start camera.", Toast.LENGTH_LONG).show()
                }
            },
            ContextCompat.getMainExecutor(this),
        )
    }

    private fun analyzeImage(imageProxy: ImageProxy) {
        val now = System.currentTimeMillis()
        val shouldSkip = !isRecognitionEnabled ||
            now - lastAnalyzedAt < OCR_INTERVAL_MS ||
            !isOcrRunning.compareAndSet(false, true)

        if (shouldSkip) {
            imageProxy.close()
            return
        }

        lastAnalyzedAt = now
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            isOcrRunning.set(false)
            imageProxy.close()
            return
        }

        val inputImage = InputImage.fromMediaImage(
            mediaImage,
            imageProxy.imageInfo.rotationDegrees,
        )

        textRecognizer.process(inputImage)
            .addOnSuccessListener { result ->
                val cleanedText = cleanOcrText(result.text)
                if (cleanedText.isBlank()) {
                    updateOcrResult("No clear text detected. Adjust angle or distance.")
                    return@addOnSuccessListener
                }

                if (cleanedText != lastRecognizedText) {
                    lastRecognizedText = cleanedText
                    updateOcrResult(cleanedText)
                } else {
                    updateStatus("OCR text unchanged. Skipping duplicate update.")
                }
            }
            .addOnFailureListener { error ->
                updateStatus("OCR failed: ${error.localizedMessage ?: "unknown error"}")
            }
            .addOnCompleteListener {
                isOcrRunning.set(false)
                imageProxy.close()
            }
    }

    private fun cleanOcrText(rawText: String): String {
        return rawText
            .lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(separator = "\n")
    }

    private fun updateOcrResult(text: String) {
        runOnUiThread {
            ocrText.text = text
            updateStatus("OCR updated.")
        }
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun updateStatus(message: String) {
        runOnUiThread {
            statusText.text = message
        }
    }

    private fun controlLayoutParams(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            setMargins(0, 8, 0, 0)
        }
    }

    private companion object {
        const val OCR_INTERVAL_MS = 1_500L
    }
}
