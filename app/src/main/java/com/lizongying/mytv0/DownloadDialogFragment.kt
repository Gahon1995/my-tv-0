package com.lizongying.mytv0

import android.content.DialogInterface
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.appcompat.widget.AppCompatButton
import androidx.core.content.ContextCompat
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import com.lizongying.mytv0.databinding.DownloadDialogBinding
import java.util.Locale

/**
 * APK 下载进度弹窗,三态:下载中(进度条+百分比)/ 下载完成(立即安装)/ 失败(重试)。
 * 状态由 UpdateManager 持有,本弹窗是纯视图,经 [showDownloading]/[showProgress]/
 * [showCompleted]/[showFailed] 驱动;用户操作经 [Listener] 回传。
 */
class DownloadDialogFragment(
    private var listener: Listener?
) : DialogFragment() {

    interface Listener {
        /** 下载中取消(返回键或「取消」按钮) */
        fun onCancelDownload()

        /** 失败态点「重试」 */
        fun onRetryDownload()

        /** 完成态点「立即安装」 */
        fun onInstall()

        /** 用户关闭弹窗(完成/失败态返回键、「暂不安装」、「取消」) */
        fun onDialogDismissed()

        /** 弹窗 view 创建后回调,用于 UpdateManager 保存引用并回灌当前状态 */
        fun onDialogAttached(dialog: DownloadDialogFragment)
    }

    private enum class State { DOWNLOADING, COMPLETED, FAILED }

    private var _binding: DownloadDialogBinding? = null
    private val binding get() = _binding

    private var state = State.DOWNLOADING

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 重建时构造参数为 null,从 companion holder 补回;仍无则直接关闭
        if (listener == null) {
            listener = activeListener
        }
        if (listener == null) {
            dismiss()
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DownloadDialogBinding.inflate(inflater, container, false)
        return binding!!.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val b = binding ?: return

        setupFocus(b.primaryButton)
        setupFocus(b.secondaryButton)

        b.primaryButton.setOnClickListener {
            when (state) {
                State.COMPLETED -> listener?.onInstall()
                State.FAILED -> listener?.onRetryDownload()
                State.DOWNLOADING -> {
                }
            }
        }
        b.secondaryButton.setOnClickListener {
            when (state) {
                State.DOWNLOADING -> listener?.onCancelDownload()
                else -> {
                    listener?.onDialogDismissed()
                    dismiss()
                }
            }
        }

        // 窗口显示后再请求焦点,保证 D-pad 可用
        view.post {
            if (b.primaryButton.visibility == View.VISIBLE) {
                b.primaryButton.requestFocus()
            } else {
                b.secondaryButton.requestFocus()
            }
        }

        // 状态回灌由 UpdateManager 在 onDialogAttached 中执行
        listener?.onDialogAttached(this)
    }

    override fun onCancel(dialog: DialogInterface) {
        // 下载中返回键 → 取消下载;完成/失败态返回键 → 仅关闭弹窗(文件保留)
        if (state == State.DOWNLOADING) {
            listener?.onCancelDownload()
        } else {
            listener?.onDialogDismissed()
        }
        super.onCancel(dialog)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (activeListener === listener) {
            activeListener = null
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    fun showDownloading(versionName: String?) {
        state = State.DOWNLOADING
        binding?.let { b ->
            b.title.text =
                if (versionName.isNullOrEmpty()) "正在下载" else "正在下载 $versionName"
            b.subtitle.visibility = View.GONE
            b.progressBar.isIndeterminate = true
            b.progressBar.progress = 0
            b.progressPct.text = "--"
            b.primaryButton.visibility = View.GONE
            b.secondaryButton.text = "取消"
        }
    }

    /** [pct] 为 -1 表示 total 未知(content-length 缺失),降级为 indeterminate + 已下载字节数 */
    fun showProgress(pct: Int, downloaded: Long, total: Long?) {
        binding?.let { b ->
            val downloadedMb = downloaded / 1024.0 / 1024.0
            if (pct >= 0) {
                b.progressBar.isIndeterminate = false
                b.progressBar.progress = pct
                b.progressPct.text = "$pct%"
            } else {
                b.progressBar.isIndeterminate = true
                b.progressPct.text = String.format(Locale.US, "%.1f MB", downloadedMb)
            }
            b.subtitle.visibility = View.VISIBLE
            b.subtitle.text = if (total != null && total > 0) {
                String.format(
                    Locale.US, "已下载 %.1f MB / %.1f MB",
                    downloadedMb, total / 1024.0 / 1024.0
                )
            } else {
                String.format(Locale.US, "已下载 %.1f MB", downloadedMb)
            }
        }
    }

    fun showCompleted() {
        state = State.COMPLETED
        binding?.let { b ->
            b.title.text = "下载完成"
            b.subtitle.visibility = View.VISIBLE
            b.subtitle.text = "点击「立即安装」升级到新版本"
            b.progressBar.isIndeterminate = false
            b.progressBar.progress = 100
            b.progressPct.text = "100%"
            b.primaryButton.visibility = View.VISIBLE
            b.primaryButton.text = "立即安装"
            b.secondaryButton.text = "暂不安装"
        }
    }

    fun showFailed(reason: String) {
        state = State.FAILED
        binding?.let { b ->
            b.title.text = "下载失败"
            b.subtitle.visibility = View.VISIBLE
            b.subtitle.text = reason
            b.progressBar.isIndeterminate = false
            b.progressBar.progress = 0
            b.progressPct.text = "--"
            b.primaryButton.visibility = View.VISIBLE
            b.primaryButton.text = "重试"
            b.secondaryButton.text = "取消"
        }
    }

    private fun setupFocus(button: AppCompatButton) {
        button.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                button.background =
                    ContextCompat.getDrawable(requireContext(), R.drawable.bg_btn_focused)
                button.setTextColor(ContextCompat.getColor(requireContext(), R.color.white))
            } else {
                button.background =
                    ContextCompat.getDrawable(requireContext(), R.drawable.bg_btn_normal)
                button.setTextColor(
                    ContextCompat.getColor(requireContext(), R.color.description_blur)
                )
            }
        }
    }

    companion object {
        const val TAG = "DownloadDialog"

        /** 弹窗重建兜底:UpdateManager 的 listener 由 show() 存入,onDestroy 时清空防泄漏 */
        @Volatile
        var activeListener: Listener? = null
            private set

        fun show(manager: FragmentManager, listener: Listener): DownloadDialogFragment {
            activeListener = listener
            val dialog = DownloadDialogFragment(listener)
            dialog.show(manager, TAG)
            return dialog
        }
    }
}
