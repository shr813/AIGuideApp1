package com.example.aiguideapp

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors

import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView

// MediaPipe
//import com.google.mediapipe.tasks.core.BaseOptions
//import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
//import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker.HandLandmarkerOptions
//import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
//import com.google.mediapipe.framework.image.MPImage
//import com.google.mediapipe.framework.image.BitmapImageBuilder

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.YuvImage
import java.io.ByteArrayOutputStream

// Custom View
import com.example.aiguideapp.OverlayView

// NCNN YOLOv8
import com.tencent.ncnn.Net
import com.tencent.ncnn.Mat



/** ImageProxy(NV21) → Bitmap 변환 유틸 */
private fun toBitmap(image: ImageProxy): Bitmap {
    // 1) ImageProxy의 YUV 플레인 버퍼 읽기
    val yBuffer = image.planes[0].buffer
    val uBuffer = image.planes[1].buffer
    val vBuffer = image.planes[2].buffer
    val ySize = yBuffer.remaining()
    val uSize = uBuffer.remaining()
    val vSize = vBuffer.remaining()

    // 2) NV21 포맷 바이트 배열(nv21) 생성 (Y + V + U 순서)
    val nv21 = ByteArray(ySize + uSize + vSize)
    yBuffer.get(nv21, 0, ySize)
    vBuffer.get(nv21, ySize, vSize)
    uBuffer.get(nv21, ySize + vSize, uSize)

    // 3) YuvImage → JPEG → BitmapFactory.decodeByteArray
    val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
    val out = ByteArrayOutputStream()
    yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 100, out)
    val jpegBytes = out.toByteArray()
    return BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
}


class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {
    companion object {
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA
        )
        private const val REQUEST_PERMISSIONS_CODE = 100
    }

    private var mediaRecorder: MediaRecorder? = null
    private var audioFilePath: String = ""
    private lateinit var tts: TextToSpeech
    private lateinit var previewView: PreviewView
    private lateinit var overlayView: OverlayView
    //private lateinit var handLandmarker: HandLandmarker
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private lateinit var yolo: Net


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)            // 레이아웃 세팅
        previewView = findViewById(R.id.previewView)      // PreviewView 연결
        overlayView = findViewById(R.id.overlayView)

        if (!allPermissionsGranted()) {
            ActivityCompat.requestPermissions(
                this,
                REQUIRED_PERMISSIONS,
                REQUEST_PERMISSIONS_CODE
            )
        } else {
            // 권한이 이미 있다면 바로 TTS 초기화
            tts = TextToSpeech(this, this)
        }
    }

    /*private fun initHandLandmarker() {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath("hand_landmarker.task")
            .build()
        val options = HandLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setNumHands(1)
            .setMinHandDetectionConfidence(0.5f)
            .setMinHandPresenceConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .build()
        handLandmarker = HandLandmarker.createFromOptions(this, options)
    }*/

    // YOLO 모델 로드
    private fun loadYoloModel() {
        yolo = Net()
        // assets 폴더에 복사해둔 param/bin 파일명
        yolo.load_param(assets.open("yolov8n.param"))
        yolo.load_model(assets.open("yolov8n.bin"))
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS_CODE) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                // 사용자가 모두 허용했으면 TTS 초기화 → onInit() → startCameraPreview()
                tts = TextToSpeech(this, this)
            } else {
                Toast.makeText(this, "녹음·카메라 권한을 모두 허용해야 앱을 사용할 수 있습니다.", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    // TTS 초기화 완료 콜백
    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.KOREAN
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String) {}
                override fun onError(utteranceId: String) {}
                override fun onDone(utteranceId: String) {
                    runOnUiThread {
                        try {
                            Log.d("UPLOAD", "▶ (UI) startCameraPreview 호출")
                            startCameraPreview()
                            loadYoloModel()
                        } catch (e: Exception) {
                            Log.e("UPLOAD", "▶ startCameraPreview 실패", e)
                        }
                        /*try {
                            Log.d("UPLOAD", "▶ initHandLandmarker 호출")
                            initHandLandmarker()
                            Log.d("UPLOAD", "▶ initHandLandmarker 성공")
                        } catch (e: Exception) {
                            Log.e("UPLOAD", "▶ initHandLandmarker 실패", e)
                        }*/
                    }
                }
            })
            val params = Bundle().apply { putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "INIT") }
            tts.speak(
                "안녕하세요, 저는 여러분의 눈이 되어 물건을 찾아드리겠습니다.",
                TextToSpeech.QUEUE_FLUSH,
                /* params = */ null,
                /* utteranceId = */ "INIT"
            )
        } else {
            Toast.makeText(this, "TTS 초기화 실패", Toast.LENGTH_LONG).show()
        }
    }

    private fun checkPermissionsAndStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.RECORD_AUDIO),
                    REQUEST_PERMISSIONS_CODE
                )
            } else {
                startRecording()
            }
        } else {
            startRecording()
        }
    }

    private fun allPermissionsGranted(): Boolean =
        REQUIRED_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

    /*// 각 프레임마다 호출할 분석 함수
    private fun analyzeFrame(imageProxy: ImageProxy) {
        // 1) ImageProxy → Bitmap → MPImage
        val bitmap = toBitmap(imageProxy)
        val mpImage: MPImage = BitmapImageBuilder(bitmap).build()

        // 2) HandLandmarker 호출 (바로 Bitmap)
        val result = handLandmarker.detect(mpImage)

        // 3) Landmark 추출 → OverlayView에 전달
        val hands = result.landmarks()
        runOnUiThread {
            overlayView.landmarks = hands
        }

        imageProxy.close()
    }*/



    private fun startCameraPreview() {
        Log.d("UPLOAD", "▶ startCameraPreview 진입")
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            Log.d("UPLOAD", "▶ CameraProvider 준비 완료")
            val provider = cameraProviderFuture.get()

            // 1) Preview use-case
            val preview = Preview.Builder()
                .build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }

            // 2) Analysis use-case
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor, ::analyzeFrame)
                }

            // 3) bind
            provider.unbindAll()
            provider.bindToLifecycle(this,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                analysis
            )
            Log.d("UPLOAD", "▶ 카메라 바인딩 완료")
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyzeFrame(imageProxy: ImageProxy) {
        val bitmap = toBitmap(imageProxy)
        val centers = detectHandCenters(bitmap)
        runOnUiThread { overlayView.centers = centers }
        imageProxy.close()
    }

    private fun detectHandCenters(bitmap: Bitmap): List<PointF> {
        // 1) resize & RGB 변환
        val mat0 = Mat.fromPixels(bitmap, Mat.MP_PIXELS_RGBA2RGB)
        val mat = Mat()
        mat0.resize(320, 320, Mat.INTERP_LINEAR, mat)

        // 2) 정규화
        val mean = floatArrayOf(0.485f,0.456f,0.406f)
        val norm = floatArrayOf(0.229f,0.224f,0.225f)
        mat.substractMeanNormalize(mean, norm)

        // 3) inference
        val ex = yolo.create_extractor()
        ex.set_light_mode(true)
        ex.input("images", mat)
        val out = Mat()
        ex.extract("output", out)

        // 4) postprocess: [x,y,w,h,conf,cls]
        val results = mutableListOf<PointF>()
        var i = 0
        while (i + 5 < out.total().toInt()) {
            val x = out[i]; val y = out[i+1]
            val w = out[i+2]; val h = out[i+3]
            val cls = out[i+5].toInt()
            if (cls == 0) { // 손 클래스 ID
                val cx = (x + w/2f) / 320f
                val cy = (y + h/2f) / 320f
                results.add(PointF(cx, cy))
            }
            i += 6
        }
        mat0.release(); mat.release(); out.release()
        return results
    }




    private fun startRecording() {
        try {
            val outputDir = externalCacheDir?.absolutePath ?: cacheDir.absolutePath
            audioFilePath = "$outputDir/recorded_audio.3gp"
            mediaRecorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
                setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
                setOutputFile(audioFilePath)
                prepare()
                start()
            }
            Toast.makeText(this, "녹음 시작", Toast.LENGTH_SHORT).show()
            Handler(Looper.getMainLooper()).postDelayed({ stopRecording() }, 5000) //5초 녹음 후 stopRecording() 자동 호출
        } catch (e: Exception) {
            Log.e("UPLOAD", "startRecording 실패", e)
        }
    }

    private fun stopRecording() { //서버에 전송
        try {
            mediaRecorder?.apply {
                stop()
                release()
            }
            mediaRecorder = null
            Toast.makeText(this, "녹음 완료. 서버에 전송합니다.", Toast.LENGTH_SHORT).show()
            Log.d("UPLOAD", "stopRecording() 호출됨. 파일: $audioFilePath")
            sendAudioFileToServer(audioFilePath)
        } catch (e: Exception) {
            Log.e("UPLOAD", "stopRecording 실패", e)
        }
    }

    private fun sendAudioFileToServer(filePath: String) {
        Log.d("UPLOAD", "서버 전송 시작")
        val audioFile = File(filePath)
        if (!audioFile.exists()) {
            Log.e("UPLOAD", "파일이 존재하지 않습니다: $filePath")
            return
        }
        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                audioFile.name,
                RequestBody.create("audio/3gp".toMediaTypeOrNull(), audioFile)
            )
            .build()
        val request = Request.Builder()
            .url("http://192.168.1.4:5000/upload")
            .post(requestBody)
            .build()
        Thread {
            try {
                val response = client.newCall(request).execute()
                val raw = response.body?.string().orEmpty()
                Log.d("UPLOAD", "서버 원응답: $raw")
                if (response.isSuccessful) {
                    val json = JSONObject(raw)
                    val target = json.optString("target", "")
                    val destination = json.optString("destination", "")
                    runOnUiThread {
                        speak("타겟은: $target")
                        if (destination.isNotBlank()) speak("목적지는: $destination")
                    }
                } else {
                    runOnUiThread { speak("서버 에러가 발생했습니다.") }
                }
            } catch (e: IOException) {
                runOnUiThread { speak("서버와 통신에 실패했습니다.") }
            }
        }.start()
    }

    private fun speak(text: String) {
        tts.speak(text, TextToSpeech.QUEUE_ADD, null, null)
    }

    override fun onDestroy() {
        super.onDestroy()
        tts.shutdown()
        cameraExecutor.shutdown()
    }
}

