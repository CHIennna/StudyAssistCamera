package com.example.studyassist

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var statusText: TextView
    private lateinit var toggleRecognitionButton: Button
    private lateinit var retryPermissionButton: Button
    private lateinit var manualScanButton: Button

    private var cameraProvider: ProcessCameraProvider? = null
    private var isRecognitionEnabled = true

    private val requestCameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                updateStatus("摄像头权限已允许，正在启动预览")
                startCamera()
            } else {
                updateStatus("摄像头权限被拒绝，无法显示实时预览")
                Toast.makeText(this, "请允许摄像头权限后再使用识别功能", Toast.LENGTH_LONG).show()
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
            updateStatus("需要摄像头权限才能显示实时预览")
            requestCameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onDestroy() {
        cameraProvider?.unbindAll()
        cameraProvider = null
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
            setPadding(24, 18, 24, 12)
            text = "正在准备摄像头"
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
            text = "暂停识别"
            layoutParams = controlLayoutParams()
        }

        manualScanButton = Button(this).apply {
            text = "手动重新识别"
            layoutParams = controlLayoutParams()
        }

        retryPermissionButton = Button(this).apply {
            text = "重新申请权限"
            isEnabled = false
            layoutParams = controlLayoutParams()
        }

        controls.addView(toggleRecognitionButton)
        controls.addView(manualScanButton)
        controls.addView(retryPermissionButton)
        root.addView(previewView)
        root.addView(statusText)
        root.addView(controls)
        setContentView(root)
    }

    private fun bindControls() {
        toggleRecognitionButton.setOnClickListener {
            isRecognitionEnabled = !isRecognitionEnabled
            toggleRecognitionButton.text = if (isRecognitionEnabled) "暂停识别" else "开始识别"
            updateStatus(
                if (isRecognitionEnabled) {
                    "摄像头预览运行中，OCR 会在下一阶段接入"
                } else {
                    "已暂停识别，摄像头预览仍保持运行"
                },
            )
        }

        manualScanButton.setOnClickListener {
            Toast.makeText(this, "OCR 将在下一阶段接入", Toast.LENGTH_SHORT).show()
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

                    provider.unbindAll()
                    provider.bindToLifecycle(
                        this,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                    )

                    cameraProvider = provider
                    retryPermissionButton.isEnabled = false
                    updateStatus("摄像头预览已启动")
                } catch (error: Exception) {
                    updateStatus("摄像头启动失败：${error.localizedMessage ?: "未知错误"}")
                    Toast.makeText(this, "摄像头启动失败", Toast.LENGTH_LONG).show()
                }
            },
            ContextCompat.getMainExecutor(this),
        )
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun updateStatus(message: String) {
        statusText.text = message
    }

    private fun controlLayoutParams(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            setMargins(0, 8, 0, 0)
        }
    }
}
