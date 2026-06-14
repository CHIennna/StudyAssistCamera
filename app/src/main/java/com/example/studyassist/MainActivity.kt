package com.example.studyassist

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
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
    private lateinit var apiKeyInput: EditText
    private lateinit var ocrText: TextView
    private lateinit var translationText: TextView
    private lateinit var analysisText: TextView
    private lateinit var toggleRecognitionButton: Button
    private lateinit var retryPermissionButton: Button
    private lateinit var saveApiKeyButton: Button
    private lateinit var clearApiKeyButton: Button
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
                updateStatus("摄像头权限已授予。正在启动预览...")
                startCamera()
            } else {
                updateStatus("摄像头权限被拒绝。预览不可用。")
                Toast.makeText(this, "需要摄像头权限才能使用。", Toast.LENGTH_LONG).show()
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
            updateStatus("需要摄像头权限才能预览。")
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

        // Preview takes 44% of screen — large enough to frame shots
        previewView = PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.PERFORMANCE
            scaleType = PreviewView.ScaleType.FILL_CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                0.44f,
            )
        }

        statusText = sectionText("正在准备摄像头...", textSize = 13f)
        apiKeyInput = EditText(this).apply {
            hint = "在此粘贴 DeepSeek API 密钥"
            setText(loadDeepSeekApiKey())
            textSize = 14f
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setPadding(16, 10, 16, 10)
            setBackgroundColor(0xFFFFFFFF.toInt())
        }
        ocrText = sectionText("OCR 识别文字将显示在此处。")
        translationText = sectionText("中文翻译将显示在此处。")
        analysisText = sectionText("DeepSeek 学习分析将显示在此处。")

        // Scrollable content area takes remaining 56% of screen
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(10, 4, 10, 12)
        }
        val contentScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                0.56f,
            )
            addView(content)
        }

        // Buttons in 2-column grid — compact and easy to reach
        toggleRecognitionButton = Button(this).apply { text = "暂停识别" }
        saveApiKeyButton = Button(this).apply { text = "保存密钥" }
        clearApiKeyButton = Button(this).apply { text = "清除密钥" }
        manualScanButton = Button(this).apply { text = "手动扫描" }
        cancelAiButton = Button(this).apply { text = "取消 AI 请求"; isEnabled = false }
        copyButton = Button(this).apply { text = "复制结果" }
        clearButton = Button(this).apply { text = "清除结果" }
        retryPermissionButton = Button(this).apply {
            text = "重新请求权限"
            isEnabled = false
        }

        val buttonGrid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 4, 0, 0)
        }
        buttonGrid.addView(buttonRow(toggleRecognitionButton, manualScanButton))
        buttonGrid.addView(buttonRow(saveApiKeyButton, clearApiKeyButton))
        buttonGrid.addView(buttonRow(cancelAiButton, copyButton))
        buttonGrid.addView(buttonRow(clearButton, retryPermissionButton))

        root.addView(previewView)
        content.addView(statusText)
        content.addView(sectionLabel("DeepSeek API 密钥"))
        content.addView(apiKeyInput)
        content.addView(labeledSection("OCR 识别结果", ocrText, minHeight = 70))
        content.addView(labeledSection("中文翻译", translationText, minHeight = 50))
        content.addView(labeledSection("学习分析", analysisText, minHeight = 90))
        content.addView(buttonGrid)
        root.addView(contentScroll)
        setContentView(root)
    }

    private fun bindControls() {
        toggleRecognitionButton.setOnClickListener {
            isRecognitionEnabled = !isRecognitionEnabled
            toggleRecognitionButton.text = if (isRecognitionEnabled) "暂停识别" else "开始识别"
            updateStatus(
                if (isRecognitionEnabled) {
                    "OCR 正在运行（限帧分析）。"
                } else {
                    "OCR 已暂停。摄像头预览正常。"
                },
            )
        }

        manualScanButton.setOnClickListener {
            lastAnalyzedAt = 0L
            updateStatus("等待下一帧摄像头画面进行扫描...")
        }

        saveApiKeyButton.setOnClickListener {
            saveDeepSeekApiKey()
        }

        clearApiKeyButton.setOnClickListener {
            clearDeepSeekApiKey()
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
            ocrText.text = "OCR 识别文字将显示在此处。"
            translationText.text = "中文翻译将显示在此处。"
            analysisText.text = "DeepSeek 学习分析将显示在此处。"
            updateStatus("结果已清除。")
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
                    updateStatus("摄像头预览和 OCR 正在运行。")
                } catch (error: Exception) {
                    updateStatus("摄像头启动失败：${error.localizedMessage ?: "未知错误"}")
                    Toast.makeText(this, "启动摄像头失败。", Toast.LENGTH_LONG).show()
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

        val rotation = imageProxy.imageInfo.rotationDegrees
        val inputImage = InputImage.fromMediaImage(mediaImage, rotation)

        // Calculate visible crop area — FILL_CENTER shows only a centre crop
        val visibleRect = visibleRectForPreview(imageProxy, rotation)

        recognizeLatinThenChinese(inputImage, imageProxy, visibleRect)
    }

    /** Returns the sensor region actually visible in the FILL_CENTER preview. */
    private fun visibleRectForPreview(imageProxy: ImageProxy, rotation: Int): Rect? {
        val viewW = previewView.width
        val viewH = previewView.height
        if (viewW <= 0 || viewH <= 0) return null

        val (imgW, imgH) = if (rotation == 90 || rotation == 270) {
            imageProxy.height to imageProxy.width
        } else {
            imageProxy.width to imageProxy.height
        }

        val imageRatio = imgW.toFloat() / imgH
        val viewRatio = viewW.toFloat() / viewH
        if (Math.abs(imageRatio - viewRatio) < 0.03f) return null // negligible crop

        return if (viewRatio > imageRatio) {
            // View wider than sensor — crop top & bottom
            val cropH = (imgW / viewRatio).toInt().coerceAtMost(imgH)
            Rect(0, (imgH - cropH) / 2, imgW, (imgH + cropH) / 2)
        } else {
            // View taller than sensor — crop left & right
            val cropW = (imgH * viewRatio).toInt().coerceAtMost(imgW)
            Rect((imgW - cropW) / 2, 0, (imgW + cropW) / 2, imgH)
        }
    }

    private fun recognizeLatinThenChinese(
        inputImage: InputImage,
        imageProxy: ImageProxy,
        visibleRect: Rect?,
    ) {
        var latinText = ""
        var chineseText = ""
        var latinError: Exception? = null
        var chineseError: Exception? = null
        var latinDone = false
        var chineseDone = false

        fun tryFinish() {
            if (!latinDone || !chineseDone) return
            finishOcrProcessing(
                imageProxy = imageProxy,
                latinText = latinText,
                chineseText = chineseText,
                latinError = latinError,
                chineseError = chineseError,
            )
        }

        // Run Latin and Chinese recognizers in parallel — cuts OCR time roughly in half
        latinTextRecognizer.process(inputImage)
            .addOnSuccessListener { result ->
                latinText = visibleTextOnly(result, visibleRect)
            }
            .addOnFailureListener { error -> latinError = error }
            .addOnCompleteListener { latinDone = true; tryFinish() }

        chineseTextRecognizer.process(inputImage)
            .addOnSuccessListener { result ->
                chineseText = visibleTextOnly(result, visibleRect)
            }
            .addOnFailureListener { error -> chineseError = error }
            .addOnCompleteListener { chineseDone = true; tryFinish() }
    }

    /** Keep only text blocks whose bounding box intersects the visible preview area. */
    private fun visibleTextOnly(result: Text, visibleRect: Rect?): String {
        if (visibleRect == null) return result.text
        val blocks = result.textBlocks
        if (blocks.isEmpty()) return result.text
        val visible = blocks.filter { block ->
            val box = block.boundingBox ?: return@filter true
            Rect.intersects(box, visibleRect)
        }
        if (visible.isEmpty()) return result.text // safety: never return empty if ML Kit found text
        return visible.joinToString("\n") { it.text }
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
                    .joinToString("; ") { it.localizedMessage ?: "未知 OCR 错误" }
                updateOcrResult(
                    if (errorText.isBlank()) {
                        "未检测到清晰文字。请调整拍摄角度或距离。"
                    } else {
                        "OCR 识别失败或未找到文字：$errorText"
                    },
                )
                return
            }

            if (isSimilarText(cleanedText, lastRecognizedText)) {
                updateStatus("OCR 文字与上次结果相似，跳过重复。")
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
                updateTranslation("文字过短或不完整。请调整角度后重新扫描。")
                updateAnalysis("等待更清晰的 OCR 结果后再调用 DeepSeek。")
            }
            ContentKind.ENGLISH -> {
                updateTranslation("检测到英文内容，正在请求中文翻译...")
                maybeRequestAi(text)
            }
            ContentKind.QUESTION -> {
                updateTranslation("检测到题目类内容，正在请求学习分析...")
                maybeRequestAi(text)
            }
            ContentKind.OTHER -> {
                updateTranslation("已检测到文字，但不像英文或完整题目。")
                updateAnalysis("保持页面稳定，或在完整内容可见时使用「手动扫描」。")
            }
        }
    }

    private fun maybeRequestAi(text: String) {
        val apiKey = loadDeepSeekApiKey()
        if (apiKey.isEmpty()) {
            updateAnalysis(
                "缺少 DeepSeek API 密钥。请在密钥输入框中粘贴密钥，点击「保存密钥」，然后重新扫描。",
            )
            updateStatus("缺少 DeepSeek API 密钥。")
            return
        }

        val now = System.currentTimeMillis()
        if (isSimilarText(text, lastAiRequestedText) && now - lastAiRequestedAt < AI_COOLDOWN_MS) {
            updateStatus("DeepSeek 请求因重复冷却被跳过。")
            return
        }

        lastAiRequestedText = text
        lastAiRequestedAt = now
        aiRequestVersion += 1
        val requestVersion = aiRequestVersion

        currentAiCall?.cancel()
        cancelAiButton.isEnabled = true
        updateAnalysis("正在加载 DeepSeek 学习分析...")
        updateStatus("正在调用 DeepSeek...")

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
            .put("max_tokens", 1024)
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
                    updateStatus("网络请求失败。")
                    analysisText.text = "DeepSeek 请求失败：${e.localizedMessage ?: "网络错误"}"
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val responseText = response.use { it.body?.string().orEmpty() }
                runOnUiThread {
                    if (requestVersion != aiRequestVersion) return@runOnUiThread
                    cancelAiButton.isEnabled = false
                    if (!response.isSuccessful) {
                        updateStatus("DeepSeek 返回 HTTP ${response.code}。")
                        analysisText.text = "DeepSeek 错误 ${response.code}：$responseText"
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
        updateStatus("AI 请求已取消。")
        if (showToast) {
            Toast.makeText(this, "AI 请求已取消。", Toast.LENGTH_SHORT).show()
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
            val translation = resultObject.optString("chinese_translation", "未返回翻译。")
            val analysis = resultObject.optString("study_analysis", content)
            val suspicious = resultObject.optString("suspicious_ocr", "")
            StudyResult(
                translation = translation,
                analysis = buildString {
                    append(analysis.ifBlank { content })
                    if (suspicious.isNotBlank()) {
                        append("\n\nOCR 提示：\n")
                        append(suspicious)
                    }
                },
                status = "DeepSeek 分析完成：$status",
            )
        } catch (error: Exception) {
            StudyResult(
                translation = "DeepSeek 已返回，但 JSON 解析失败。",
                analysis = "无法解析 DeepSeek 响应：${error.localizedMessage ?: "未知错误"}\n\n$responseText",
                status = "DeepSeek 响应解析失败。",
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
            updateStatus("OCR 已更新。")
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
            append("OCR 识别结果：\n")
            append(ocrText.text)
            append("\n\n中文翻译：\n")
            append(translationText.text)
            append("\n\n学习分析：\n")
            append(analysisText.text)
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("学习助手结果", resultText))
        Toast.makeText(this, "结果已复制。", Toast.LENGTH_SHORT).show()
    }

    private fun saveDeepSeekApiKey() {
        val apiKey = apiKeyInput.text.toString().trim()
        if (apiKey.isEmpty()) {
            clearDeepSeekApiKey()
            return
        }

        getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(PREFERENCE_DEEPSEEK_API_KEY, apiKey)
            .apply()
        apiKeyInput.setText(apiKey)
        updateStatus("DeepSeek API 密钥已保存在本设备。")
        Toast.makeText(this, "API 密钥已保存。", Toast.LENGTH_SHORT).show()
    }

    private fun clearDeepSeekApiKey() {
        getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(PREFERENCE_DEEPSEEK_API_KEY)
            .apply()
        apiKeyInput.text?.clear()
        updateStatus("DeepSeek API 密钥已清除。")
        Toast.makeText(this, "API 密钥已清除。", Toast.LENGTH_SHORT).show()
    }

    private fun loadDeepSeekApiKey(): String {
        return getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .getString(PREFERENCE_DEEPSEEK_API_KEY, "")
            .orEmpty()
            .trim()
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
            setPadding(0, 6, 0, 0)
            addView(sectionLabel(title))
            textView.setBackgroundColor(0xFFFFFFFF.toInt())
            textView.minHeight = dp(minHeight)
            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                setMargins(0, 2, 0, 0)
            }
            addView(textView, params)
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

    private fun buttonRow(left: Button, right: Button): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                setMargins(0, 4, 0, 0)
            }
            val half = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f,
            ).apply {
                setMargins(3, 0, 3, 0)
            }
            addView(left, half)
            addView(right, half)
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
        const val OCR_INTERVAL_MS = 1_100L
        const val AI_COOLDOWN_MS = 12_000L
        const val MIN_USEFUL_TEXT_LENGTH = 12
        const val MIN_ENGLISH_LETTERS = 8
        const val ENGLISH_RATIO_THRESHOLD = 0.45f
        const val DEEPSEEK_CHAT_URL = "https://api.deepseek.com/chat/completions"
        const val DEEPSEEK_MODEL = "deepseek-v4-flash"
        const val PREFERENCES_NAME = "study_assist_preferences"
        const val PREFERENCE_DEEPSEEK_API_KEY = "deepseek_api_key"
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
