package io.nekohasekai.sagernet.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ktx.dp2px

class AboutFragment : ToolbarFragment(R.layout.layout_about) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<View>(R.id.appbar).visibility = View.GONE
        view.layoutDirection = View.LAYOUT_DIRECTION_RTL
        view.textDirection = View.TEXT_DIRECTION_RTL

        ViewCompat.setOnApplyWindowInsetsListener(view.findViewById(R.id.layout_about)) { content, insets ->
            content.layoutDirection = View.LAYOUT_DIRECTION_RTL
            content.textDirection = View.TEXT_DIRECTION_RTL
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            content.updatePadding(
                left = bars.left,
                right = bars.right,
                bottom = bars.bottom + dp2px(32),
            )
            insets
        }

        bindInstagram(view, R.id.about_instagram_one, "mobile.tina")
        bindInstagram(view, R.id.about_instagram_two, "mobile.tina2")
        bindInstagram(view, R.id.about_instagram_three, "mobile.tinaa")
        view.findViewById<View>(R.id.about_developer).setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, "https://t.me/vpn963".toUri()))
        }

        (requireActivity() as? MainActivity)?.onBackPressedCallback?.isEnabled = true
    }

    private fun bindInstagram(root: View, viewId: Int, handle: String) {
        root.findViewById<View>(viewId).setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, "https://www.instagram.com/$handle/".toUri()))
        }
    }
}
