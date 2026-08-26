package io.nekohasekai.sagernet.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ktx.dp2px
import io.nekohasekai.sagernet.utils.MobileTinaVault

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

        view.findViewById<TextView>(R.id.about_title).text = MobileTinaVault.aboutTitle()
        view.findViewById<TextView>(R.id.about_subtitle).text = MobileTinaVault.aboutSubtitle()
        view.findViewById<TextView>(R.id.about_branch_one_label).text = MobileTinaVault.branchOne()
        view.findViewById<TextView>(R.id.about_branch_two_label).text = MobileTinaVault.branchTwo()
        view.findViewById<TextView>(R.id.about_page_three_label).text = MobileTinaVault.pageThree()
        view.findViewById<TextView>(R.id.about_developer_label).text = MobileTinaVault.developer()
        view.findViewById<TextView>(R.id.about_stores_title).text = MobileTinaVault.storesTitle()
        view.findViewById<TextView>(R.id.about_store_one).text = MobileTinaVault.storeOne()
        view.findViewById<TextView>(R.id.about_store_two).text = MobileTinaVault.storeTwo()

        view.findViewById<View>(R.id.about_back).setOnClickListener {
            (requireActivity() as MainActivity).displayFragmentWithId(R.id.nav_configuration)
        }
        bindInstagram(view, R.id.about_instagram_one, MobileTinaVault.instagramOne())
        bindInstagram(view, R.id.about_instagram_two, MobileTinaVault.instagramTwo())
        bindInstagram(view, R.id.about_instagram_three, MobileTinaVault.instagramThree())
        view.findViewById<View>(R.id.about_developer).setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, MobileTinaVault.telegram().toUri()))
        }

        (requireActivity() as? MainActivity)?.onBackPressedCallback?.isEnabled = true
    }

    private fun bindInstagram(root: View, viewId: Int, url: String) {
        root.findViewById<View>(viewId).setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
        }
    }
}
