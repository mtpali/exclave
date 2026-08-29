package io.nekohasekai.sagernet.ui

import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.preference.RoomPreferenceDataStore
import io.nekohasekai.sagernet.utils.MobileTinaVault
import java.security.MessageDigest

/** Install-scoped MobileTina social notice, styled like the reference MobileTinaVPN dialog. */
internal object MobileTinaFirstLaunchDialog {

    private val expectedWelcomeDigest = byteArrayOf(
        -120, 26, 127, -109, -62, -16, -34, -79,
        101, 96, 54, -117, 117, 41, 86, -31,
        -78, 17, 18, 23, 8, 70, -94, -12,
        22, -7, 55, -49, 6, 108, 106, 43,
    )

    fun showOnce(
        activity: AppCompatActivity,
        preferences: RoomPreferenceDataStore,
        key: String,
        onDismiss: () -> Unit,
    ): Dialog? {
        if (preferences.getBoolean(key) == true || activity.isFinishing || activity.isDestroyed) {
            return null
        }

        val lines = MobileTinaVault.welcome()
            .split(Regex("\\n\\s*\\n"))
            .map(String::trim)
            .filter(String::isNotEmpty)
            .toTypedArray()
        verifyWelcome(lines)

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            setPadding(dp(activity, 24), dp(activity, 20), dp(activity, 24), dp(activity, 18))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(activity, 24).toFloat()
                setColor(Color.rgb(8, 8, 10))
                setStroke(dp(activity, 1), Color.WHITE)
            }
        }

        root.addView(View(activity).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(
                    Color.rgb(131, 58, 180),
                    Color.rgb(253, 29, 29),
                    Color.rgb(252, 175, 69),
                ),
            ).apply { cornerRadius = dp(activity, 3).toFloat() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 5)).apply {
            bottomMargin = dp(activity, 18)
        })

        lines.forEach { value ->
            root.addView(TextView(activity).apply {
                text = value
                setTextColor(Color.WHITE)
                textSize = 15f
                gravity = Gravity.CENTER
                layoutDirection = View.LAYOUT_DIRECTION_LTR
                textDirection = View.TEXT_DIRECTION_LTR
                textAlignment = View.TEXT_ALIGNMENT_CENTER
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setPadding(dp(activity, 16), dp(activity, 14), dp(activity, 16), dp(activity, 14))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(activity, 14).toFloat()
                    setColor(Color.rgb(18, 18, 20))
                    setStroke(dp(activity, 1), Color.WHITE)
                }
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(activity, 10) })
        }

        val close = MaterialButton(activity).apply {
            setText(R.string.mobiletina_dialog_close)
            isAllCaps = false
            textSize = 15f
            setTextColor(Color.WHITE)
            backgroundTintList = ColorStateList.valueOf(Color.rgb(193, 53, 132))
        }
        root.addView(close, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(activity, 50),
        ).apply { topMargin = dp(activity, 6) })

        val dialog = Dialog(activity).apply {
            setContentView(root)
            setCancelable(true)
            setCanceledOnTouchOutside(true)
            setOnDismissListener { onDismiss() }
        }
        close.setOnClickListener { dialog.dismiss() }

        return try {
            dialog.show()
            dialog.window?.let { window ->
                window.setBackgroundDrawableResource(android.R.color.transparent)
                window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                window.setDimAmount(0.58f)
                val targetWidth = (activity.resources.displayMetrics.widthPixels * 0.88f).toInt()
                    .coerceAtMost(dp(activity, 430))
                window.setLayout(targetWidth, WindowManager.LayoutParams.WRAP_CONTENT)
            }
            preferences.putBoolean(key, true)
            dialog
        } catch (_: RuntimeException) {
            dialog.setOnDismissListener(null)
            if (dialog.isShowing) dialog.dismiss()
            null
        }
    }

    private fun verifyWelcome(lines: Array<String>) {
        val digest = MessageDigest.getInstance("SHA-256")
        lines.forEach { value ->
            digest.update(value.toByteArray(Charsets.UTF_8))
            digest.update(0.toByte())
        }
        if (!MessageDigest.isEqual(digest.digest(), expectedWelcomeDigest)) {
            throw SecurityException("MobileTina welcome integrity check failed")
        }
    }

    private fun dp(activity: AppCompatActivity, value: Int): Int =
        (value * activity.resources.displayMetrics.density + 0.5f).toInt()
}
