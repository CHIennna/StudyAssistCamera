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
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var statusText: TextView
    private lateinit var ocrText: TextView
    private lateinit var translationText: TextView
    private lateinit var analysisText: TextView
    private lateinit var toggleRecognitionButton: Button
    private lateinit var retryPermissionButton: Button
    private lateinit var manualScanButton: Button
    private lateinit var clearButton: Button

    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val textRecognizer: TextRecognizer =
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private var cameraProvider: ProcessCameraProvider? = null
    private var currentAiCall: Call? = null
    private var isRecognitionEnabled = true
    private var lastAnalyzedAt = 0L
    private var lastRecognizedText = ""
    private var lastAiRequestedText = ""
    private var lastAiRequestedAt = 0L
    private var aiRequestVersion = 0
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
        currentAiCall?.cancel()
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

        statusText = sectionText("Preparing camera...", textSize = 15f)
        ocrText = sectionText("OCR text will appear here.")
        translationText = sectionText("Translation or language hint will appear here.")
        analysisText = sectionText("DeepSeek study analysis will appear here.")

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
            text = "Clear results"
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
        root.addView(scrollSection(ocrText, height = 190))
        root.addView(scrollSection(translationText, height = 150))
        root.addView(scrollSection(analysisText, height = 240))
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
            currentAiCall?.cancel()
            lastRecognizedText = ""
            lastAiRequestedText = ""
            ocrText.text = "OCR text will appear here."
            translationText.text = "Translation or language hint will appear here."
            analysisText.text = "DeepSeek study analysis will appear here."
            updateStatus("Results cleared.")
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
                    handleRecognizedText(cleanedText)
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

    private fun handleRecognizedText(text: String) {
        when (classifyText(text)) {
            ContentKind.TOO_SHORT -> {
                updateTranslation("Text is too short or incomplete. Adjust angle and scan again.")
                updateAnalysis("Waiting for a clearer OCR result before calling DeepSeek.")
            }
            ContentKind.ENGLISH -> {
                updateTranslation("English text detected. Requesting Chinese translation...")
                maybeRequestAi(text)
            }
            ContentKind.QUESTION -> {
                updateTranslation("Question-like content detected. Requesting study analysis...")
                maybeRequestAi(text)
            }
            ContentKind.OTHER -> {
                updateTranslation("Text detected, but it does not look like English text or a complete question yet.")
                updateAnalysis("Keep the page steady or use Scan once when the full content is visible.")
            }
        }
    }

    private fun maybeRequestAi(text: String) {
        val apiKey = BuildConfig.DEEPSEEK_API_KEY.trim()
        if (apiKey.isEmpty()) {
            updateAnalysis(
                "DeepSeek API key is missing. Add DEEPSEEK_API_KEY=your_key to local.properties " +
                    "or set the DEEPSEEK_API_KEY environment variable, then sync Gradle.",
            )
            updateStatus("DeepSeek API key missing.")
            return
        }

        val now = System.currentTimeMillis()
        if (text == lastAiRequestedText && now - lastAiRequestedAt < AI_COOLDOWN_MS) {
            updateStatus("DeepSeek request skipped by duplicate cooldown.")
            return
        }

        lastAiRequestedText = text
        lastAiRequestedAt = now
        aiRequestVersion += 1
        val requestVersion = aiRequestVersion

        currentAiCall?.cancel()
        updateAnalysis("Loading DeepSeek study response...")
        updateStatus("Calling DeepSeek...")

        val requestBody = JSONObject()
            .put("model", DEEPSEEK_MODEL)
            .put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
                    .put(JSONObject().put("role", "user").put("content", buildStudyPrompt(text))),
            )
            .put("stream", false)
            .toString()
            .toRequestBody(JSON_MEDIA_TYPE)

        val request = Request.Builder()
            .url(DEEPSEEK_CHAT_URL)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(requestBody)
            .build()

        val call = httpClient.newCall(request)
        currentAiCall = call
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (call.isCanceled()) return
                runOnUiThread {
                    if (requestVersion != aiRequestVersion) return@runOnUiThread
                    updateStatus("Network request failed.")
                    analysisText.text = "DeepSeek request failed: ${e.localizedMessage ?: "network error"}"
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val responseText = response.use { it.body?.string().orEmpty() }
                runOnUiThread {
                    if (requestVersion != aiRequestVersion) return@runOnUiThread
                    if (!response.isSuccessful) {
                        updateStatus("DeepSeek returned HTTP ${response.code}.")
                        analysisText.text = "DeepSeek error ${response.code}: $responseText"
                        return@runOnUiThread
                    }

                    val content = parseDeepSeekContent(responseText)
                    analysisText.text = content
                    translationText.text = extractTranslationHint(content)
                    updateStatus("DeepSeek response updated.")
                }
            }
        })
    }

    private fun classifyText(text: String): ContentKind {
        val compact = text.filterNot { it.isWhitespace() }
        if (compact.length < MIN_USEFUL_TEXT_LENGTH) return ContentKind.TOO_SHORT

        val letters = compact.count { it in 'A'..'Z' || it in 'a'..'z' }
        val englishRatio = letters.toFloat() / compact.length.coerceAtLeast(1)
        if (letters >= MIN_ENGLISH_LETTERS && englishRatio >= ENGLISH_RATIO_THRESHOLD) {
            return ContentKind.ENGLISH
        }

        val lower = text.lowercase()
        val looksLikeQuestion = text.contains("?") ||
            text.contains("\uFF1F") ||
            text.contains("=") ||
            QUESTION_KEYWORDS.any { lower.contains(it) } ||
            OPTION_PATTERN.containsMatchIn(text) ||
            MATH_PATTERN.containsMatchIn(text)

        return if (looksLikeQuestion) ContentKind.QUESTION else ContentKind.OTHER
    }

    private fun buildStudyPrompt(ocrText: String): String {
        return """
            You are a study tutoring assistant. The following text was recognized by OCR from learning material or a question image.
            First judge whether the content is complete and whether it looks like a question.

            If the text is incomplete, tell the user to retake the image or adjust the angle.
            If the content is in English, translate it into Chinese first.
            If it is a question, answer with this structure:

            1. Chinese translation if needed
            2. Question understanding
            3. Related knowledge points
            4. Solving idea
            5. Detailed steps
            6. Final answer
            7. Common mistakes
            8. How to recognize similar question types

            Requirements:
            - Do not only give the answer.
            - Explain for students with weak foundations.
            - If OCR may be wrong, point out suspicious positions.
            - If conditions are insufficient, do not invent missing content.
            - Respond in Simplified Chinese.

            OCR text:
            $ocrText
        """.trimIndent()
    }

    private fun parseDeepSeekContent(responseText: String): String {
        return try {
            JSONObject(responseText)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
                .trim()
                .ifEmpty { "DeepSeek returned an empty response." }
        } catch (error: Exception) {
            "Failed to parse DeepSeek response: ${error.localizedMessage ?: "unknown error"}\n\n$responseText"
        }
    }

    private fun extractTranslationHint(content: String): String {
        val firstLines = content.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .take(6)
            .joinToString("\n")

        return firstLines.ifBlank { "DeepSeek response received." }
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

    private fun updateTranslation(text: String) {
        runOnUiThread {
            translationText.text = text
        }
    }

    private fun updateAnalysis(text: String) {
        runOnUiThread {
            analysisText.text = text
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

    private fun scrollSection(textView: TextView, height: Int): ScrollView {
        return ScrollView(this).apply {
            setBackgroundColor(0xFFFFFFFF.toInt())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                height,
            ).apply {
                setMargins(0, 6, 0, 0)
            }
            addView(textView)
        }
    }

    private fun sectionText(text: String, textSize: Float = 14f): TextView {
        return TextView(this).apply {
            this.text = text
            this.textSize = textSize
            setTextColor(0xFF111827.toInt())
            setPadding(24, 12, 24, 12)
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

    private enum class ContentKind {
        TOO_SHORT,
        ENGLISH,
        QUESTION,
        OTHER,
    }

    private companion object {
        const val OCR_INTERVAL_MS = 1_500L
        const val AI_COOLDOWN_MS = 20_000L
        const val MIN_USEFUL_TEXT_LENGTH = 12
        const val MIN_ENGLISH_LETTERS = 8
        const val ENGLISH_RATIO_THRESHOLD = 0.45f
        const val DEEPSEEK_CHAT_URL = "https://api.deepseek.com/chat/completions"
        const val DEEPSEEK_MODEL = "deepseek-v4-flash"
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        val OPTION_PATTERN = Regex("""(?im)(^|\n)\s*[A-D][\).: ]""")
        val MATH_PATTERN = Regex("""[0-9]\s*[+\-*/=^]\s*[0-9a-zA-Z(]""")
        val QUESTION_KEYWORDS = listOf(
            "solve",
            "calculate",
            "choose",
            "find",
            "prove",
            "answer",
            "question",
            "what is",
            "which",
            "why",
        )
        const val SYSTEM_PROMPT =
            "You help students learn from OCR text. You do not help with cheating or rule-breaking."
    }
}
