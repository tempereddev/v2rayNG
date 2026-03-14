package com.v2ray.ang.ui

import android.os.Bundle
import com.v2ray.ang.AppConfig
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.R
import com.v2ray.ang.databinding.ActivityAboutBinding
import com.v2ray.ang.handler.V2RayNativeManager
import com.v2ray.ang.util.Utils

class AboutActivity : BaseActivity() {
    private val binding by lazy { ActivityAboutBinding.inflate(layoutInflater) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        //setContentView(binding.root)
        setContentViewWithToolbar(binding.root, showHomeAsUp = true, title = getString(R.string.title_about))

        binding.layoutSoureCcode.setOnClickListener {
            Utils.openUri(this, AppConfig.APP_URL)
        }

        binding.layoutUpstreamProject.setOnClickListener {
            Utils.openUri(this, AppConfig.UPSTREAM_APP_URL)
        }

        binding.layoutFeedback.setOnClickListener {
            Utils.openUri(this, AppConfig.APP_ISSUES_URL)
        }

        binding.layoutOssLicenses.setOnClickListener {
            val webView = android.webkit.WebView(this)
            webView.loadUrl("file:///android_asset/open_source_licenses.html")
            android.app.AlertDialog.Builder(this)
                .setTitle("Open source licenses")
                .setView(webView)
                .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
                .show()
        }

        binding.layoutTgChannel.setOnClickListener {
            Utils.openUri(this, AppConfig.TG_CHANNEL_URL)
        }

        binding.layoutPrivacyPolicy.setOnClickListener {
            Utils.openUri(this, AppConfig.APP_PRIVACY_POLICY)
        }

        binding.tvVersion.text = getString(
            R.string.about_fork_version,
            AppConfig.FORK_DISPLAY_NAME,
            BuildConfig.VERSION_NAME,
            V2RayNativeManager.getLibVersion()
        )
        binding.tvUpstreamVersion.text = getString(
            R.string.about_upstream_version,
            AppConfig.UPSTREAM_VERSION_NAME,
            AppConfig.UPSTREAM_COMMIT
        )
        binding.tvAppId.text = getString(
            R.string.about_build_identity,
            AppConfig.FORK_MAINTAINER,
            BuildConfig.APPLICATION_ID
        )
    }
}
