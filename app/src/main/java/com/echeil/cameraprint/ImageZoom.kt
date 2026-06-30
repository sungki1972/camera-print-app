package com.echeil.cameraprint

import android.app.Dialog
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.view.ViewGroup
import android.view.Window
import android.widget.ImageView
import android.widget.Toast
import java.io.File

/** 기록 항목 클릭 시 원본 사진을 전체화면으로 확대 표시. 화면 탭하면 닫힘. */
object ImageZoom {
    fun show(context: Context, filePath: String) {
        val file = File(filePath)
        if (!file.exists()) {
            Toast.makeText(context, "원본 파일이 없습니다", Toast.LENGTH_SHORT).show()
            return
        }
        val bmp = try {
            // 과도한 메모리 방지: 화면 크기에 맞춰 적당히 다운샘플
            val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
            BitmapFactory.decodeFile(filePath, opts)
        } catch (_: Throwable) {
            null
        } ?: run {
            Toast.makeText(context, "이미지를 열 수 없습니다", Toast.LENGTH_SHORT).show()
            return
        }

        val dialog = Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val image = ImageView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.BLACK)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageBitmap(bmp)
            setOnClickListener { dialog.dismiss() }
        }
        dialog.setContentView(image)
        dialog.show()
    }
}
