package com.example.studyassist

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
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
    private lateinit var cancelAiButton: Button
    private lateinit var copyButton: Button
    private lateinit var clearButton: Button

    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val latinTextRecognizer: TextRecognizer =
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val chineseTextRecognizer: TextRecognizer =
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
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
        cancelAiRequest(showToast = false)
        cameraProvider?.unbindAll()
        cameraProvider = null
        latinTextRecognizer.close()
        chineseTextRecognizer.close()
        cameraExecutor.shutdown()
        super.onDestroy()
    }

    private fun buildContentView() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFF3F6FA.toInt())
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
                dp(280),
            )
        }

        statusText = sectionText("Preparing camera...", textSize = 15f)
        ocrText = sectionText("OCR text will appear here.")
        translationText = sectionText("Chinese translation will appear here.")
        analysisText = sectionText("DeepSeek study analysis will appear here.")

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 10, 16, 18)
        }
        val contentScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
            addView(content)
        }

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(0, 6, 0, 0)
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

        cancelAiButton = Button(this).apply {
            text = "Cancel AI request"
            isEnabled = false
            layoutParams = controlLayoutParams()
        }

        copyButton = Button(this).apply {
            text = "Copy results"
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
        controls.addView(cancelAiButton)
        controls.addView(copyButton)
        controls.addView(clearButton)
        controls.addView(retryPermissionButton)

        root.addView(previewView)
        content.addView(statusText)
        content.addView(labeledSection("OCR Text", ocrText, minHeight = 130))
        content.addView(labeledSection("Translation", translationText, minHeight = 110))
        content.addView(labeledSection("Study Analysis", analysisText, minHeight = 220))
        content.addView(controls)
        root.addView(contentScroll)
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

        cancelAiButton.setOnClickListener {
            cancelAiRequest(showToast = true)
        }

        copyButton.setOnClickListener {
            copyResultsToClipboard()
        }

        clearButton.setOnClickListener {
            cancelAiRequest(showToast = false)
            lastRecognizedText = ""
            lastAiRequestedText = ""
            ocrText.text = "OCR text will appear here."
            translationText.text = "Chinese translation will appear here."
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

        recognizeLatinThenChinese(inputImage, imageProxy)
    }

    private fun recognizeLatinThenChinese(inputImage: InputImage, imageProxy: ImageProxy) {
        var latinText = ""
        var chineseText = ""
        var latinError: Exception? = null
        var chineseError: Exception? = null

        latinTextRecognizer.process(inputImage)
            .addOnSuccessListener { result: Text ->
                latinText = result.text
            }
            .addOnFailureListener { error ->
                latinError = error
            }
            .addOnCompleteListener {
                chineseTextRecognizer.process(inputImage)
                    .addOnSuccessListener { result: Text ->
                        chineseText = result.text
                    }
                    .addOnFailureListener { error ->
                        chineseError = error
                    }
                    .addOnCompleteListener {
                        finishOcrProcessing(
                            imageProxy = imageProxy,
                            latinText = latinText,
                            chineseText = chineseText,
                            latinError = latinError,
                            chineseError = chineseError,
                        )
                    }
            }
    }

    private fun finishOcrProcessing(
        imageProxy: ImageProxy,
        latinText: String,
        chineseText: String,
        latinError: Exception?,
        chineseError: Exception?,
    ) {
        try {
            val cleanedText = mergeOcrText(latinText, chineseText)
            if (cleanedText.isBlank()) {
                val errorText = listOfNotNull(latinError, chineseError)
                    .joinToString("; ") { it.localizedMessage ?: "unknown OCR error" }
                updateOcrResult(
                    if (errorText.isBlank()) {
                        "No clear text detected. Adjust angle or distance."
                    } else {
                        "OCR failed or found no text: $errorText"
                    },
                )
                return
            }

            if (isSimilarText(cleanedText, lastRecognizedText)) {
                updateStatus("OCR text is similar to the previous result. Skipping duplicate.")
                return
            }

            lastRecognizedText = cleanedText
            updateOcrResult(cleanedText)
            handleRecognizedText(cleanedText)
        } finally {
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
        if (isSimilarText(text, lastAiRequestedText) && now - lastAiRequestedAt < AI_COOLDOWN_MS) {
            updateStatus("DeepSeek request skipped by duplicate cooldown.")
            return
        }

        lastAiRequestedText = text
        lastAiRequestedAt = now
        aiRequestVersion += 1
        val requestVersion = aiRequestVersion

        currentAiCall?.cancel()
        cancelAiButton.isEnabled = true
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
            .put("response_format", JSONObject().put("type", "json_object"))
            .put("thinking", JSONObject().put("type", "disabled"))
            .put("temperature", 0.2)
            .put("max_tokens", 1800)
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
                    cancelAiButton.isEnabled = false
                    updateStatus("Network request failed.")
                    analysisText.text = "DeepSeek request failed: ${e.localizedMessage ?: "network error"}"
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val responseText = response.use { it.body?.string().orEmpty() }
                runOnUiThread {
                    if (requestVersion != aiRequestVersion) return@runOnUiThread
                    cancelAiButton.isEnabled = false
                    if (!response.isSuccessful) {
                        updateStatus("DeepSeek returned HTTP ${response.code}.")
                        analysisText.text = "DeepSeek error ${response.code}: $responseText"
                        return@runOnUiThread
                    }

                    val result = parseDeepSeekResult(responseText)
                    translationText.text = result.translation
                    analysisText.text = result.analysis
                    updateStatus(result.status)
                }
            }
        })
    }

    private fun cancelAiRequest(showToast: Boolean) {
        aiRequestVersion += 1
        currentAiCall?.cancel()
        currentAiCall = null
        cancelAiButton.isEnabled = false
        updateStatus("AI request canceled.")
        if (showToast) {
            Toast.makeText(this, "AI request canceled.", Toast.LENGTH_SHORT).show()
        }
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
            CHINESE_QUESTION_KEYWORDS.any { text.contains(it) } ||
            OPTION_PATTERN.containsMatchIn(text) ||
            MATH_PATTERN.containsMatchIn(text)

        return if (looksLikeQuestion) ContentKind.QUESTION else ContentKind.OTHER
    }

    private fun buildStudyPrompt(ocrText: String): String {
        return """
            Return one valid JSON object only. Do not wrap it in Markdown.
            Required JSON keys:
            {
              "content_status": "complete | incomplete | uncertain",
              "chinese_translation": "Chinese translation or a short note if translation is not needed",
              "study_analysis": "Study explanation in Simplified Chinese with numbered sections",
              "suspicious_ocr": "OCR positions that may be wrong, or empty string"
            }

            You are a study tutoring assistant. The text below was recognized by OCR from learning material or a question image.
            First judge whether the content is complete and whether it looks like a question.
            If the text is incomplete, tell the user to retake the image or adjust the angle.
            If the content is in English, translate it into Chinese first.
            If it is a question, include these sections in study_analysis:
            1. Question understanding
            2. Related knowledge points
            3. Solving idea
            4. Detailed steps
            5. Final answer
            6. Common mistakes
            7. How to recognize similar question types

            Requirements:
            - Do not only give the answer.
            - Explain for students with weak foundations.
            - If OCR may be wrong, point out suspicious positions.
            - If conditions are insufficient, do not invent missing content.
            - Respond in Simplified Chinese inside JSON string values.

            OCR text:
            $ocrText
        """.trimIndent()
    }

    private fun parseDeepSeekResult(responseText: String): StudyResult {
        return try {
            val content = JSONObject(responseText)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
                .trim()

            val resultObject = JSONObject(content)
            val status = resultObject.optString("content_status", "DeepSeek response updated.")
            val translation = resultObject.optString("chinese_translation", "No translation returned.")
            val analysis = resultObject.optString("study_analysis", content)
            val suspicious = resultObject.optString("suspicious_ocr", "")
            StudyResult(
                translation = translation,
                analysis = buildString {
                    append(analysis.ifBlank { content })
                    if (suspicious.isNotBlank()) {
                        append("\n\nOCR notes:\n")
                        append(suspicious)
                    }
                },
                status = "DeepSeek response updated: $status",
            )
        } catch (error: Exception) {
            StudyResult(
                translation = "DeepSeek response received, but JSON parsing failed.",
                analysis = "Failed to parse DeepSeek response: ${error.localizedMessage ?: "unknown error"}\n\n$responseText",
                status = "DeepSeek response parse failed.",
            )
        }
    }

    private fun mergeOcrText(latinText: String, chineseText: String): String {
        val latinClean = cleanOcrText(latinText)
        val chineseClean = cleanOcrText(chineseText)
        if (latinClean.isBlank()) return chineseClean
        if (chineseClean.isBlank()) return latinClean
        if (latinClean == chineseClean) return latinClean

        val mergedLines = mutableListOf<String>()
        (chineseClean.lines() + latinClean.lines())
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { line ->
                if (mergedLines.none { existing -> isSimilarText(existing, line) }) {
                    mergedLines.add(line)
                }
            }

        return mergedLines.joinToString("\n")
    }

    private fun cleanOcrText(rawText: String): String {
        return rawText
            .lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(separator = "\n")
    }

    private fun isSimilarText(newText: String, oldText: String): Boolean {
        if (newText.isBlank() || oldText.isBlank()) return false
        if (newText == oldText) return true
        val a = normalizeForSimilarity(newText)
        val b = normalizeForSimilarity(oldText)
        if (a == b) return true
        val shorter = minOf(a.length, b.length)
        val longer = maxOf(a.length, b.length)
        if (longer == 0 || shorter.toFloat() / longer < 0.75f) return false
        val common = a.toSet().intersect(b.toSet()).size
        val total = a.toSet().union(b.toSet()).size.coerceAtLeast(1)
        return common.toFloat() / total >= 0.88f
    }

    private fun normalizeForSimilarity(text: String): String {
        return text.lowercase()
            .filterNot { it.isWhitespace() }
            .filterNot { it in ",.;:!?()[]{}" }
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

    private fun copyResultsToClipboard() {
        val resultText = buildString {
            append("OCR Text:\n")
            append(ocrText.text)
            append("\n\nTranslation:\n")
            append(translationText.text)
            append("\n\nStudy Analysis:\n")
            append(analysisText.text)
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Study Assist Result", resultText))
        Toast.makeText(this, "Results copied.", Toast.LENGTH_SHORT).show()
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

    private fun labeledSection(title: String, textView: TextView, minHeight: Int): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 8, 0, 0)
            addView(sectionLabel(title))
            addView(
                ScrollView(this@MainActivity).apply {
                    setBackgroundColor(0xFFFFFFFF.toInt())
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(minHeight),
                    ).apply {
                        setMargins(0, 4, 0, 0)
                    }
                    addView(textView)
                },
            )
        }
    }

    private fun sectionLabel(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 13f
            setTextColor(0xFF4B5563.toInt())
            setPadding(2, 0, 0, 0)
        }
    }

    private fun sectionText(text: String, textSize: Float = 14f): TextView {
        return TextView(this).apply {
            this.text = text
            this.textSize = textSize
            setTextColor(0xFF111827.toInt())
            setPadding(16, 12, 16, 12)
            setLineSpacing(0f, 1.08f)
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

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private data class StudyResult(
        val translation: String,
        val analysis: String,
        val status: String,
    )

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
        val CHINESE_QUESTION_KEYWORDS = listOf(
            "\u6C42",
            "\u8BA1\u7B97",
            "\u9009\u62E9",
            "\u8BC1\u660E",
            "\u7B54\u6848",
            "\u89E3\u6790",
            "\u4E0B\u5217",
            "\u9898",
        )
        const val SYSTEM_PROMPT =
            "You help students learn from OCR text. You do not help with cheating or rule-breaking."
    }
}
