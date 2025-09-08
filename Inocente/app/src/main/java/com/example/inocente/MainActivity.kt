package com.example.inocente

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.media.*
import android.os.Bundle
import android.util.Log
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

    companion object {
        private const val TAG = "MainActivity"
    }

    private var porcupineManager: PorcupineManager? = null
    private var isRecording = false
    private var mediaPlayer: MediaPlayer? = null
    private lateinit var audioFile: File
    private lateinit var responseText: TextView
    private lateinit var statusText: TextView

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    // Permissões múltiplas
    @SuppressLint("MissingPermission")
    private val permissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            Log.d(TAG, "Todas as permissões concedidas")
            initPorcupine()
        } else {
            Toast.makeText(this, "Permissões necessárias não foram concedidas", Toast.LENGTH_LONG).show()
            statusText.text = "❌ Permissões necessárias"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        responseText = findViewById(R.id.responseText)
        statusText = findViewById(R.id.statusText)

        // Adiciona listener para teste manual (toque na tela)
        statusText.setOnClickListener {
            if (statusText.text.toString().contains("Toque para gravar") ||
                statusText.text.toString().contains("Porcupine falhou")) {
                Log.d(TAG, "Iniciando gravação manual")
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    startRecording()
                } else {
                    Toast.makeText(this, "Permissão de microfone necessária", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // Solicita permissões
        requestPermissions()


    }

    private fun requestPermissions() {
        val permissionsToRequest = if (android.os.Build.VERSION.SDK_INT >= 33) {
            arrayOf(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.READ_MEDIA_AUDIO
            )
        } else {
            arrayOf(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.READ_EXTERNAL_STORAGE
            )
        }
        permissions.launch(permissionsToRequest)
        statusText.text = "🔴 Solicitando permissões..."
    }


    @SuppressLint("MissingPermission")
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun initPorcupine() {
        try {
            statusText.text = "🔄 Inicializando Porcupine..."

            // Verifica se os arquivos assets existem
            val keywordFile = File(filesDir, "inocencio.ppn")
            val modelFile = File(filesDir, "porcupine_params_pt.pv")

            // Copia arquivos se não existirem
            if (!keywordFile.exists()) {
                try {
                    assets.open("inocencio.ppn").use { input ->
                        FileOutputStream(keywordFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                    Log.d(TAG, "Arquivo inocencio.ppn copiado com sucesso")
                } catch (e: Exception) {
                    Log.e(TAG, "Erro ao copiar inocencio.ppn: ${e.message}")
                    statusText.text = "❌ Arquivo inocencio.ppn não encontrado em assets"
                    Toast.makeText(this, "Arquivo inocencio.ppn não encontrado", Toast.LENGTH_LONG).show()
                    return
                }
            }

            if (!modelFile.exists()) {
                try {
                    assets.open("porcupine_params_pt.pv").use { input ->
                        FileOutputStream(modelFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                    Log.d(TAG, "Arquivo porcupine_params_pt.pv copiado com sucesso")
                } catch (e: Exception) {
                    Log.e(TAG, "Erro ao copiar porcupine_params_pt.pv: ${e.message}")
                    statusText.text = "❌ Arquivo porcupine_params_pt.pv não encontrado em assets"
                    Toast.makeText(this, "Arquivo porcupine_params_pt.pv não encontrado", Toast.LENGTH_LONG).show()
                    return
                }
            }

            // Inicializa PorcupineManager
            porcupineManager = PorcupineManager.Builder()
                .setAccessKey("NTPArxA9/tB5OxfJ5zjoakX6YaIFuWKTJpqCzp0CzExidAtLscD3sA==")
                .setModelPath(modelFile.absolutePath)
                .setKeywordPath(keywordFile.absolutePath)
                .setSensitivity(0.7f)
                .build(applicationContext) { keywordIndex ->
                    Log.d(TAG, "Wake word detectada! Index: $keywordIndex")
                    if (keywordIndex >= 0) {
                        runOnUiThread {
                            statusText.text = "🟢 Palavra detectada!"
                            Toast.makeText(this@MainActivity, "Inocêncio acordou!", Toast.LENGTH_SHORT).show()
                        }
                        startRecording()
                    }
                }

            porcupineManager?.start()
            statusText.text = "🟡 Escutando 'Inocêncio'..."
            Log.d(TAG, "Porcupine iniciado com sucesso")

        } catch (e: Exception) {
            Log.e(TAG, "Erro ao inicializar Porcupine", e)
            Toast.makeText(this, "Erro ao inicializar Porcupine: ${e.message}", Toast.LENGTH_LONG).show()
            statusText.text = "❌ Erro Porcupine: ${e.message}"
        }
    }

    @SuppressLint("MissingPermission")
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun startRecording() {
        if (isRecording) {
            Log.w(TAG, "Já está gravando")
            return
        }

        Log.d(TAG, "Iniciando gravação")
        isRecording = true

        runOnUiThread {
            statusText.text = "🎤 Gravando... (fale sua pergunta)"
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val sampleRate = 16000
                val channelConfig = AudioFormat.CHANNEL_IN_MONO
                val audioFormat = AudioFormat.ENCODING_PCM_16BIT
                val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat) * 2

                val recorder = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    channelConfig,
                    audioFormat,
                    bufferSize
                )

                if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                    Log.e(TAG, "AudioRecord não foi inicializado corretamente")
                    runOnUiThread {
                        statusText.text = "❌ Erro no microfone"
                    }
                    return@launch
                }

                // Cria arquivo de áudio
                audioFile = File(externalCacheDir, "user_input_${System.currentTimeMillis()}.wav")
                if (audioFile.exists()) audioFile.delete()

                val outputStream = audioFile.outputStream().buffered()
                writeWavHeader(outputStream, sampleRate, 1, 16)

                recorder.startRecording()
                Log.d(TAG, "Gravação iniciada")

                var totalAudioLen = 0L
                var silenceStart = System.currentTimeMillis()
                val silenceThreshold = 300.0
                val silenceDuration = 3000L // 3 segundos de silêncio
                val maxRecordingTime = 10000L // máximo 10 segundos

                val dataBuffer = ByteArray(bufferSize)
                val recordingStart = System.currentTimeMillis()

                while (isRecording && (System.currentTimeMillis() - recordingStart) < maxRecordingTime) {
                    val read = recorder.read(dataBuffer, 0, bufferSize)
                    if (read > 0) {
                        outputStream.write(dataBuffer, 0, read)
                        totalAudioLen += read

                        // Calcula RMS para detectar silêncio
                        var sum = 0.0
                        for (i in 0 until read step 2) {
                            if (i + 1 < read) {
                                val sample = (dataBuffer[i].toInt() and 0xFF) or
                                        ((dataBuffer[i + 1].toInt() and 0xFF) shl 8)
                                val signedSample = if (sample > 32767) sample - 65536 else sample
                                sum += (signedSample * signedSample).toDouble()
                            }
                        }
                        val rms = Math.sqrt(sum / (read / 2))

                        if (rms < silenceThreshold) {
                            if (System.currentTimeMillis() - silenceStart > silenceDuration) {
                                Log.d(TAG, "Silêncio detectado, parando gravação")
                                break
                            }
                        } else {
                            silenceStart = System.currentTimeMillis()
                        }
                    }
                    Thread.sleep(10)
                }

                isRecording = false
                recorder.stop()
                recorder.release()
                outputStream.close()

                Log.d(TAG, "Gravação finalizada. Arquivo: ${audioFile.absolutePath}, Tamanho: $totalAudioLen bytes")

                updateWavHeader(audioFile, totalAudioLen)

                runOnUiThread {
                    statusText.text = "⏳ Enviando para API..."
                }

                sendAudioToApi(audioFile)

            } catch (e: Exception) {
                Log.e(TAG, "Erro durante gravação", e)
                isRecording = false
                runOnUiThread {
                    statusText.text = "❌ Erro na gravação"
                    Toast.makeText(this@MainActivity, "Erro na gravação: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun writeWavHeader(out: OutputStream, sampleRate: Int, channels: Int, bitsPerSample: Int) {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val header = ByteArray(44)

        // RIFF header
        header[0] = 'R'.code.toByte()
        header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte()
        header[3] = 'F'.code.toByte()
        // File size (será atualizado depois)
        header[4] = 0; header[5] = 0; header[6] = 0; header[7] = 0
        // WAVE
        header[8] = 'W'.code.toByte()
        header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte()
        header[11] = 'E'.code.toByte()
        // fmt chunk
        header[12] = 'f'.code.toByte()
        header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte()
        header[15] = ' '.code.toByte()
        // fmt chunk size
        header[16] = 16; header[17] = 0; header[18] = 0; header[19] = 0
        // Audio format (PCM)
        header[20] = 1; header[21] = 0
        // Number of channels
        header[22] = channels.toByte(); header[23] = 0
        // Sample rate
        header[24] = (sampleRate and 0xff).toByte()
        header[25] = ((sampleRate shr 8) and 0xff).toByte()
        header[26] = ((sampleRate shr 16) and 0xff).toByte()
        header[27] = ((sampleRate shr 24) and 0xff).toByte()
        // Byte rate
        header[28] = (byteRate and 0xff).toByte()
        header[29] = ((byteRate shr 8) and 0xff).toByte()
        header[30] = ((byteRate shr 16) and 0xff).toByte()
        header[31] = ((byteRate shr 24) and 0xff).toByte()
        // Block align
        header[32] = (channels * bitsPerSample / 8).toByte(); header[33] = 0
        // Bits per sample
        header[34] = bitsPerSample.toByte(); header[35] = 0
        // data chunk
        header[36] = 'd'.code.toByte()
        header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte()
        header[39] = 'a'.code.toByte()
        // Data size (será atualizado depois)
        header[40] = 0; header[41] = 0; header[42] = 0; header[43] = 0

        out.write(header)
    }

    private fun updateWavHeader(file: File, totalAudioLen: Long) {
        try {
            val totalDataLen = totalAudioLen + 36
            val raf = RandomAccessFile(file, "rw")

            // Atualiza o tamanho do arquivo
            raf.seek(4)
            raf.write((totalDataLen and 0xff).toInt())
            raf.write(((totalDataLen shr 8) and 0xff).toInt())
            raf.write(((totalDataLen shr 16) and 0xff).toInt())
            raf.write(((totalDataLen shr 24) and 0xff).toInt())

            // Atualiza o tamanho dos dados
            raf.seek(40)
            raf.write((totalAudioLen and 0xff).toInt())
            raf.write(((totalAudioLen shr 8) and 0xff).toInt())
            raf.write(((totalAudioLen shr 16) and 0xff).toInt())
            raf.write(((totalAudioLen shr 24) and 0xff).toInt())

            raf.close()
            Log.d(TAG, "Header WAV atualizado")
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao atualizar header WAV", e)
        }
    }

    private fun sendAudioToApi(file: File) {
        Log.d(TAG, "Enviando arquivo para API: ${file.absolutePath}")
        Log.d(TAG, "Tamanho do arquivo: ${file.length()} bytes")

        // Primeiro, testa a conectividade
        val testRequest = Request.Builder()
            .url("http://172.20.141.65:8000/check")
            .build()

        client.newCall(testRequest).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "Erro de conectividade básica", e)
                runOnUiThread {
                    statusText.text = "❌ Servidor inacessível"
                    responseText.text = "Erro de conectividade: ${e.message}\nVerifique se a API está rodando na porta 8000"
                    Toast.makeText(this@MainActivity, "Servidor não encontrado", Toast.LENGTH_LONG).show()
                }
                return
            }

            override fun onResponse(call: Call, response: Response) {
                Log.d(TAG, "Teste de conectividade OK: ${response.code}")
                // Se chegou aqui, a API está acessível, agora envia o áudio
                enviarArquivoAudio(file)
            }
        })
    }

    private fun enviarArquivoAudio(file: File) {
        val requestBody = object : RequestBody() {
            override fun contentType() = "audio/wav".toMediaTypeOrNull()
            override fun writeTo(sink: BufferedSink) {
                file.inputStream().use { input ->
                    sink.writeAll(input.source())
                }
            }
        }

        val request = Request.Builder()
            .url("http://172.20.141.65:8000/voice")
            .post(MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", file.name, requestBody)
                .build())
            .build()

        Log.d(TAG, "Enviando arquivo de áudio...")

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "Erro ao enviar áudio para API", e)
                runOnUiThread {
                    statusText.text = "❌ Erro no envio"
                    responseText.text = "Erro: ${e.message}"
                    Toast.makeText(this@MainActivity, "Erro no envio: ${e.message}", Toast.LENGTH_LONG).show()

                    CoroutineScope(Dispatchers.Main).launch {
                        delay(3000)
                        statusText.text = "🟡 Escutando 'Inocêncio'..."
                    }
                }
            }

            override fun onResponse(call: Call, response: Response) {
                Log.d(TAG, "Resposta recebida. Status: ${response.code}")

                runOnUiThread {
                    if (response.isSuccessful) {
                        val contentType = response.header("Content-Type")
                        Log.d(TAG, "Content-Type: $contentType")

                        if (contentType?.startsWith("audio/") == true) {
                            statusText.text = "🔊 Reproduzindo resposta..."
                            responseText.text = "Áudio recebido da API"

                            response.body?.let { body ->
                                CoroutineScope(Dispatchers.IO).launch {
                                    try {
                                        val audioResponse = File(externalCacheDir, "response_${System.currentTimeMillis()}.mp3")
                                        audioResponse.outputStream().use { output ->
                                            body.byteStream().copyTo(output)
                                        }
                                        Log.d(TAG, "Áudio salvo: ${audioResponse.absolutePath}, ${audioResponse.length()} bytes")

                                        runOnUiThread {
                                            playAudio(audioResponse)
                                        }
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Erro ao salvar áudio de resposta", e)
                                        runOnUiThread {
                                            statusText.text = "❌ Erro ao processar áudio"
                                        }
                                    }
                                }
                            }
                        } else {
                            val text = response.body?.string() ?: "Resposta vazia"
                            statusText.text = "✅ Resposta recebida"
                            responseText.text = text
                            Log.d(TAG, "Resposta em texto: $text")
                        }
                    } else {
                        val errorBody = response.body?.string() ?: "Sem detalhes"
                        statusText.text = "❌ Erro na API"
                        responseText.text = "Erro HTTP: ${response.code}\n$errorBody"
                        Log.e(TAG, "Erro HTTP: ${response.code} - $errorBody")
                    }

                    CoroutineScope(Dispatchers.Main).launch {
                        delay(3000)
                        if (statusText.text != "🔊 Reproduzindo resposta...") {
                            statusText.text = "🟡 Escutando 'Inocêncio'..."
                        }
                    }
                }
            }
        })
    }

    private fun playAudio(audioFile: File) {
        try {
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer().apply {
                setDataSource(audioFile.absolutePath)
                setOnPreparedListener { player ->
                    Log.d(TAG, "MediaPlayer preparado, iniciando reprodução")
                    player.start()
                }
                setOnCompletionListener {
                    Log.d(TAG, "Reprodução de áudio concluída")
                    runOnUiThread {
                        statusText.text = "🟡 Escutando 'Inocêncio'..."
                    }
                }
                setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "Erro no MediaPlayer: what=$what, extra=$extra")
                    runOnUiThread {
                        statusText.text = "❌ Erro na reprodução"
                        Toast.makeText(this@MainActivity, "Erro ao reproduzir áudio", Toast.LENGTH_SHORT).show()
                    }
                    true
                }
                prepareAsync()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao reproduzir áudio", e)
            runOnUiThread {
                statusText.text = "❌ Erro na reprodução"
                Toast.makeText(this, "Erro ao reproduzir áudio: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onStop() {
        super.onStop()
        porcupineManager?.stop()
        mediaPlayer?.release()
    }

    override fun onDestroy() {
        super.onDestroy()
        porcupineManager?.delete()
        mediaPlayer?.release()
    }
}

