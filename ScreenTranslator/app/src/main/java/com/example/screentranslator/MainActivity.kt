package com.example.screentranslator

import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private var source = "ja"

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            if (res.resultCode == RESULT_OK && res.data != null) {
                val intent = Intent(this, OverlayService::class.java).apply {
                    putExtra(OverlayService.EXTRA_RESULT_CODE, res.resultCode)
                    putExtra(OverlayService.EXTRA_DATA, res.data)
                    putExtra(OverlayService.EXTRA_SOURCE, source)
                }
                ContextCompat.startForegroundService(this, intent)
                Toast.makeText(this, "เริ่มแล้ว กดปุ่มลอยเพื่อแปลหน้าจอ", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "ยกเลิกการอนุญาตจับภาพหน้าจอ", Toast.LENGTH_SHORT).show()
            }
        }

    private val notifLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val projectionManager = getSystemService(MediaProjectionManager::class.java)

        val pad = (24 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        root.addView(TextView(this).apply {
            text = "Screen Translator"
            textSize = 24f
        })
        root.addView(TextView(this).apply {
            text = "เลือกภาษาต้นทางที่ต้องการแปล"
            setPadding(0, pad / 2, 0, pad / 2)
        })

        val group = RadioGroup(this)
        val options = listOf("ja" to "ญี่ปุ่น", "ko" to "เกาหลี", "en" to "อังกฤษ")
        options.forEachIndexed { index, (code, label) ->
            group.addView(RadioButton(this).apply {
                text = label
                tag = code
                id = View.generateViewId()
                isChecked = index == 0
            })
        }
        group.setOnCheckedChangeListener { g, checkedId ->
            source = g.findViewById<RadioButton>(checkedId).tag as String
        }
        root.addView(group)

        root.addView(Button(this).apply {
            text = "1. อนุญาตแสดงทับแอปอื่น"
            setOnClickListener {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            }
        })

        root.addView(Button(this).apply {
            text = "2. เริ่มปุ่มลอยแปลภาษา"
            setOnClickListener {
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    Toast.makeText(
                        this@MainActivity,
                        "กรุณาอนุญาตแสดงทับแอปอื่นก่อน",
                        Toast.LENGTH_SHORT
                    ).show()
                    return@setOnClickListener
                }
                if (Build.VERSION.SDK_INT >= 33 &&
                    checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                    android.content.pm.PackageManager.PERMISSION_GRANTED
                ) {
                    notifLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                }
                projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
            }
        })

        root.addView(Button(this).apply {
            text = "หยุดการทำงาน"
            setOnClickListener {
                startService(
                    Intent(this@MainActivity, OverlayService::class.java)
                        .setAction(OverlayService.ACTION_STOP)
                )
            }
        })

        setContentView(root)
    }
}
