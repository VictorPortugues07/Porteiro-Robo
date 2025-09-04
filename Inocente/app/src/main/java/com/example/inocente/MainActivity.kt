package com.example.inocente

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.media.*
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresPermission
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.*
import okhttp3.*
import okio.BufferedSink
import okio.source
import java.io.*
import java.util.concurrent.TimeUnit
import ai.picovoice.porcupine.PorcupineManager
import okhttp3.MediaType.Companion.toMediaTypeOrNull

class MainActivity : AppCompatActivity() {

    private var porcupineManager: PorcupineManager? = null
    private var isRecording = false
    private lateinit var audioFile: File
    private lateinit var responseText: TextView
    private lateinit var statusText: TextView


    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()


    @SuppressLint("MissingPermission")
    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission())  { granted ->
            if (!granted) {
                Toast.makeText(this, "Permissão de microfone é necessária", Toast.LENGTH_SHORT).show()
            } else {
                initPorcupine()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        responseText = findViewById(R.id.responseText)
        statusText = findViewById(R.id.statusText)

        // Pede permissão do microfone
        micPermission.launch(Manifest.permission.RECORD_AUDIO)
        statusText.text = "🔴 Aguardando permissão..."
    }
    @androidx.annotation.RequiresPermission(android.Manifest.permission.RECORD_AUDIO)
    private fun initPorcupine() {
        try {
            // Copia .ppn e .pv de assets para filesDir
            val keywordFile = File(filesDir, "inocencio.ppn")
            if (!keywordFile.exists()) {
                assets.open("inocencio.ppn").use { input ->
                    FileOutputStream(keywordFile).use { output -> input.copyTo(output) }
                }
            }
            val modelFile = File(filesDir, "porcupine_params_pt.pv")
            if (!modelFile.exists()) {
                assets.open("porcupine_params_pt.pv").use { input ->
                    FileOutputStream(modelFile).use { output -> input.copyTo(output) }
                }
            }

            porcupineManager = PorcupineManager.Builder()
                .setAccessKey("NTPArxA9/tB5OxfJ5zjoakX6YaIFuWKTJpqCzp0CzExidAtLscD3sA==")
                .setModelPath(modelFile.absolutePath)
                .setKeywordPath(keywordFile.absolutePath)
                .setSensitivity(0.7f)
                .build(applicationContext)  { keywordIndex ->
                    if (keywordIndex >= 0) {
                        runOnUiThread {
                            statusText.text = "🟢 Palavra detectada!"
                            Toast.makeText(this, "Inocencio acordou!", Toast.LENGTH_SHORT).show()
                        }
                        startRecording()
                    }
                }

            porcupineManager?.start()
            statusText.text = "🟡 Escutando palavra de ativação..."
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Erro ao inicializar Porcupine: ${e.message}", Toast.LENGTH_LONG).show()
            statusText.text = "❌ Erro Porcupine"
        }
    }

    override fun onStop() {
        super.onStop()
        porcupineManager?.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        porcupineManager?.delete()
    }

    @SuppressLint("MissingPermission")
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun startRecording() {
        if (isRecording) return

        isRecording = true
        statusText.text = "🎤 Gravando..."

        CoroutineScope(Dispatchers.IO).launch {
            val sampleRate = 16000
            val channelConfig = AudioFormat.CHANNEL_IN_MONO
            val audioFormat = AudioFormat.ENCODING_PCM_16BIT
            val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

            val recorder = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize
            )

            audioFile = File(externalCacheDir, "user_input.wav")
            if (audioFile.exists()) audioFile.delete()

            val outputStream = audioFile.outputStream().buffered()
            writeWavHeader(outputStream, sampleRate, 1, 16)

            recorder.startRecording()
            var totalAudioLen = 0L
            var silenceStart = System.currentTimeMillis()
            val silenceThreshold = 200.0
            val silenceDuration = 2000

            val dataBuffer = ByteArray(bufferSize)

            while (isRecording) {
                val read = recorder.read(dataBuffer, 0, bufferSize)
                if (read > 0) {
                    outputStream.write(dataBuffer, 0, read)
                    totalAudioLen += read

                    var sum = 0.0
                    for (i in 0 until read step 2) {
                        val sample = (dataBuffer[i].toInt() or (dataBuffer[i + 1].toInt() shl 8)).toShort()
                        sum += (sample * sample).toDouble()
                    }
                    val rms = Math.sqrt(sum / (read / 2))
                    if (rms < silenceThreshold) {
                        if (System.currentTimeMillis() - silenceStart > silenceDuration) break
                    } else {
                        silenceStart = System.currentTimeMillis()
                    }
                }
            }

            isRecording = false
            recorder.stop()
            recorder.release()
            outputStream.close()
            updateWavHeader(audioFile, totalAudioLen)

            runOnUiThread { statusText.text = "⏳ Processando..." }
            sendAudioToApi(audioFile)
        }
    }

    private fun stopRecording() {
        isRecording = false
    }

    private fun writeWavHeader(out: OutputStream, sampleRate: Int, channels: Int, bitsPerSample: Int) {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val header = ByteArray(44)

        header[0] = 'R'.code.toByte()
        header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte()
        header[3] = 'F'.code.toByte()
        header[8] = 'W'.code.toByte()
        header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte()
        header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte()
        header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte()
        header[15] = ' '.code.toByte()
        header[16] = 16
        header[20] = 1
        header[22] = channels.toByte()
        header[24] = (sampleRate and 0xff).toByte()
        header[25] = ((sampleRate shr 8) and 0xff).toByte()
        header[26] = ((sampleRate shr 16) and 0xff).toByte()
        header[27] = ((sampleRate shr 24) and 0xff).toByte()
        header[28] = (byteRate and 0xff).toByte()
        header[29] = ((byteRate shr 8) and 0xff).toByte()
        header[30] = ((byteRate shr 16) and 0xff).toByte()
        header[31] = ((byteRate shr 24) and 0xff).toByte()
        header[32] = (channels * bitsPerSample / 8).toByte()
        header[34] = bitsPerSample.toByte()
        header[36] = 'd'.code.toByte()
        header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte()
        header[39] = 'a'.code.toByte()
        out.write(header, 0, 44)
    }

    private fun updateWavHeader(file: File, totalAudioLen: Long) {
        val totalDataLen = totalAudioLen + 36
        val raf = RandomAccessFile(file, "rw")
        raf.seek(4)
        raf.write((totalDataLen and 0xff).toInt())
        raf.write(((totalDataLen shr 8) and 0xff).toInt())
        raf.write(((totalDataLen shr 16) and 0xff).toInt())
        raf.write(((totalDataLen shr 24) and 0xff).toInt())
        raf.seek(40)
        raf.write((totalAudioLen and 0xff).toInt())
        raf.write(((totalAudioLen shr 8) and 0xff).toInt())
        raf.write(((totalAudioLen shr 16) and 0xff).toInt())
        raf.write(((totalAudioLen shr 24) and 0xff).toInt())
        raf.close()
    }

    private fun sendAudioToApi(file: File) {
        val requestBody = object : RequestBody() {
            override fun contentType() = "audio/wav".toMediaTypeOrNull()
            override fun writeTo(sink: BufferedSink) {
                file.inputStream().use { input -> sink.writeAll(input.source()) }
            }
        }

        val request = Request.Builder()
            .url("http://10.0.2.2:8000/voice")
            .post(MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", file.name, requestBody)
                .build())
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    statusText.text = "❌ Erro de conexão"
                    responseText.text = "Erro: ${e.message}"
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val text = response.body?.string() ?: ""
                runOnUiThread {
                    statusText.text = "✅ Processado"
                    responseText.text = text
                }
            }
        })
    }
}
