package com.example.hardwaretester

import android.app.ActivityManager
import android.content.Context
import android.os.Bundle
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.util.concurrent.Executors
import kotlin.system.measureTimeMillis

class MainActivity : AppCompatActivity() {

    private lateinit var tvLog: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val scrollView = ScrollView(this)
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        val btnStart = Button(this).apply {
            text = "开始全套硬件检测与性能倒推"
        }

        tvLog = TextView(this).apply {
            textSize = 14f
            text = "点击上方按钮开始检测...\n"
        }

        layout.addView(btnStart)
        layout.addView(tvLog)
        scrollView.addView(layout)
        setContentView(scrollView)

        btnStart.setOnClickListener {
            btnStart.isEnabled = false
            tvLog.text = "正在测试中，请稍候...\n"
            Thread {
                val result = runAllTests()
                runOnUiThread {
                    tvLog.text = result
                    btnStart.isEnabled = true
                }
            }.start()
        }
    }

    private fun runAllTests(): String {
        val sb = StringBuilder()

        // 1. CPU
        val cores = Runtime.getRuntime().availableProcessors()
        val matrixSize = 350
        val executor = Executors.newFixedThreadPool(cores)
        val cpuTime = measureTimeMillis {
            val futures = (0 until cores).map {
                executor.submit {
                    val a = Array(matrixSize) { FloatArray(matrixSize) { 1.0f } }
                    val b = Array(matrixSize) { FloatArray(matrixSize) { 2.0f } }
                    val c = Array(matrixSize) { FloatArray(matrixSize) }
                    for (i in 0 until matrixSize) {
                        for (j in 0 until matrixSize) {
                            var sum = 0.0f
                            for (k in 0 until matrixSize) { sum += a[i][k] * b[k][j] }
                            c[i][j] = sum
                        }
                    }
                }
            }
            futures.forEach { it.get() }
        }
        executor.shutdown()
        val gflops = ((2.0 * matrixSize * matrixSize * matrixSize * cores) / (cpuTime / 1000.0)) / 1e9

        sb.append("===== CPU 检测 =====\n")
        sb.append("核心数: $cores 核\n")
        sb.append("多核算力压测: ${String.format("%.2f", gflops)} GFLOPS\n\n")

        // 2. RAM (LPDDR 倒推)
        val arraySize = 8 * 1024 * 1024
        val src = IntArray(arraySize) { it }
        val dst = IntArray(arraySize)
        val ramTime = measureTimeMillis {
            for (i in 0 until 15) { System.arraycopy(src, 0, dst, 0, arraySize) }
        }
        val ramBandwidth = ((arraySize * 4.0 * 15) / (1024 * 1024 * 1024)) / (ramTime / 1000.0)
        val inferredLpddr = when {
            ramBandwidth > 12.0 -> "LPDDR5X"
            ramBandwidth > 8.0 -> "LPDDR5"
            ramBandwidth > 5.0 -> "LPDDR4X"
            else -> "LPDDR4 或更早"
        }

        val actManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        actManager.getMemoryInfo(memInfo)

        sb.append("===== RAM (内存) 检测 =====\n")
        sb.append("内存总量: ${String.format("%.2f", memInfo.totalMem / (1024.0 * 1024 * 1024))} GB\n")
        sb.append("内存拷贝带宽: ${String.format("%.2f", ramBandwidth)} GB/s\n")
        sb.append("推断 RAM 技术: $inferredLpddr\n\n")

        // 3. ROM (UFS 倒推)
        val testFile = File(cacheDir, "rom_test.tmp")
        val buffer = ByteArray(1024 * 1024) { 0x5A }
        val testSizeMB = 50

        val writeTime = measureTimeMillis {
            val fos = testFile.outputStream()
            for (i in 0 until testSizeMB) { fos.write(buffer) }
            fos.flush(); fos.fd.sync(); fos.close()
        }
        val writeSpeed = testSizeMB.toDouble() / (writeTime / 1000.0)

        val readTime = measureTimeMillis {
            val fis = testFile.inputStream()
            while (fis.read(buffer) != -1) {}
            fis.close()
        }
        val readSpeed = testSizeMB.toDouble() / (readTime / 1000.0)
        testFile.delete()

        val inferredUfs = when {
            readSpeed > 2500 -> "UFS 4.0 (或更高)"
            readSpeed > 1500 -> "UFS 3.1"
            readSpeed > 1000 -> "UFS 3.0"
            readSpeed > 600  -> "UFS 2.2 / 2.1"
            else -> "UFS 2.0 / eMMC"
        }

        sb.append("===== ROM (闪存) 检测 =====\n")
        sb.append("顺序读取速度: ${String.format("%.1f", readSpeed)} MB/s\n")
        sb.append("顺序写入速度: ${String.format("%.1f", writeSpeed)} MB/s\n")
        sb.append("推断 ROM 技术: $inferredUfs\n")

        return sb.toString()
    }
}
