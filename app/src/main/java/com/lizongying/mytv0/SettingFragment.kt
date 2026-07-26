package com.lizongying.mytv0

import MainViewModel
import MainViewModel.Companion.CACHE_FILE_NAME
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.lizongying.mytv0.ModalFragment.Companion.KEY_URL
import com.lizongying.mytv0.SimpleServer.Companion.PORT
import com.lizongying.mytv0.databinding.SettingBinding
import com.lizongying.mytv0.view.FocusFx
import kotlin.math.max
import kotlin.math.min


class SettingFragment : Fragment() {

    private var _binding: SettingBinding? = null
    private val binding get() = _binding!!

    private lateinit var uri: Uri

    private lateinit var updateManager: UpdateManager

    private var server = "http://${PortUtil.lan()}:$PORT"

    private lateinit var viewModel: MainViewModel

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val context = requireContext()
        val mainActivity = (activity as MainActivity)

        _binding = SettingBinding.inflate(inflater, container, false)

        binding.versionName.text = "v${context.appVersionName}"
        binding.version.text = "https://github.com/Gahon1995/my-tv-0"

        // ===== 左侧导航 =====
        setupNav()

        // ===== ① 直播源 =====
        binding.configUrlText.text = SP.configUrl ?: ""
        syncRemoteServerText()

        binding.confirmConfig.setOnClickListener {
            val sourcesFragment = SourcesFragment()
            sourcesFragment.show(requireFragmentManager(), SourcesFragment.TAG)
            mainActivity.settingActive()
        }

        bindSwitch(binding.switchConfigAutoLoad, SP.configAutoLoad) {
            SP.configAutoLoad = it
        }

        binding.remoteSettings.setOnClickListener {
            val imageModalFragment = ModalFragment()
            val args = Bundle()
            args.putString(KEY_URL, server)
            imageModalFragment.arguments = args
            imageModalFragment.show(requireFragmentManager(), ModalFragment.TAG)
            mainActivity.settingActive()
        }

        bindSwitch(binding.switchDefaultLike, SP.defaultLike) {
            SP.defaultLike = it
        }

        binding.switchShowAllChannels.isChecked = SP.showAllChannels

        // ===== ② 播放设置 =====
        bindSwitch(binding.switchSoftDecode, SP.softDecode) {
            SP.softDecode = it
            mainActivity.switchSoftDecode()
        }

        bindSwitch(binding.switchRepeatInfo, SP.repeatInfo) {
            SP.repeatInfo = it
        }

        bindSwitch(binding.switchChannelReversal, SP.channelReversal) {
            SP.channelReversal = it
        }

        // ===== ③ 界面外观 =====
        bindSwitch(binding.switchGlassBlur, SP.glassBlur) {
            SP.glassBlur = it
        }

        bindSwitch(binding.switchElderMode, SP.elderMode) {
            SP.elderMode = it
            R.string.restart_to_apply.showToast()
        }

        bindSwitch(binding.switchChannelNum, SP.channelNum) {
            SP.channelNum = it
        }

        bindSwitch(binding.switchTime, SP.time) {
            SP.time = it
        }

        binding.switchDisplaySeconds.isChecked = SP.displaySeconds

        bindSwitch(binding.switchCompactMenu, SP.compactMenu) {
            SP.compactMenu = it
            mainActivity.updateMenuSize()
        }

        // ===== ④ 更新与关于 =====
        binding.checkVersion.setOnClickListener {
            requestInstallPermissions()
            mainActivity.settingActive()
        }

        bindSwitch(binding.switchBootStartup, SP.bootStartup) {
            SP.bootStartup = it
        }

        binding.appreciate.setOnClickListener {
            val imageModalFragment = ModalFragment()
            val args = Bundle()
            args.putInt(ModalFragment.KEY_DRAWABLE_ID, R.drawable.appreciate)
            imageModalFragment.arguments = args
            imageModalFragment.show(requireFragmentManager(), ModalFragment.TAG)
            mainActivity.settingActive()
        }

        binding.setting.setOnClickListener {
            hideSelf()
        }

        binding.exit.setOnClickListener {
            requireActivity().finishAffinity()
        }

        // 焦点态：按钮文字颜色跟随
        for (i in listOf(
            binding.remoteSettings,
            binding.confirmConfig,
            binding.clear,
            binding.checkVersion,
            binding.exit,
            binding.appreciate,
        )) {
            i.setOnFocusChangeListener { v, hasFocus ->
                FocusFx.apply(v, hasFocus)
                mainActivity.settingActive()
            }
        }

        updateManager = UpdateManager(context, context.appVersionCode)

        return binding.root
    }

    /** 统一装配开关：初值 + 变更回调 + 焦点动效 */
    private fun bindSwitch(
        switch: androidx.appcompat.widget.SwitchCompat,
        initial: Boolean,
        onChange: (Boolean) -> Unit
    ) {
        switch.isChecked = initial
        switch.setOnCheckedChangeListener { _, isChecked ->
            onChange(isChecked)
            (activity as MainActivity).settingActive()
        }
        switch.setOnFocusChangeListener { v, hasFocus ->
            FocusFx.apply(v, hasFocus)
            (activity as MainActivity).settingActive()
        }
    }

    // ===== 左侧导航切换 =====

    private fun setupNav() {
        val navToGroup = mapOf(
            binding.navSource to binding.groupSource,
            binding.navPlay to binding.groupPlay,
            binding.navDisplay to binding.groupDisplay,
            binding.navAbout to binding.groupAbout,
        )

        for ((nav, group) in navToGroup) {
            nav.setOnFocusChangeListener { v, hasFocus ->
                FocusFx.apply(v, hasFocus)
                if (hasFocus) {
                    showGroup(navToGroup, group)
                    nav.setTextColor(
                        ContextCompat.getColor(requireContext(), R.color.text_primary)
                    )
                } else {
                    nav.setTextColor(
                        ContextCompat.getColor(requireContext(), R.color.text_tertiary)
                    )
                }
                (activity as MainActivity).settingActive()
            }
            nav.setOnClickListener {
                showGroup(navToGroup, group)
            }
        }
    }

    private fun showGroup(
        navToGroup: Map<out View, View>,
        target: View
    ) {
        for (group in navToGroup.values) {
            group.visibility = if (group === target) View.VISIBLE else View.GONE
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val context = requireActivity()
        val mainActivity = (activity as MainActivity)
        val application = context.applicationContext as MyTVApplication
        val imageHelper = application.imageHelper

        viewModel = ViewModelProvider(context)[MainViewModel::class.java]

        binding.switchDisplaySeconds.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setDisplaySeconds(isChecked)
        }
        binding.switchDisplaySeconds.setOnFocusChangeListener { v, hasFocus ->
            FocusFx.apply(v, hasFocus)
            mainActivity.settingActive()
        }

        binding.clear.setOnClickListener {
            SP.channelNum = SP.DEFAULT_CHANNEL_NUM

            SP.sources = SP.DEFAULT_SOURCES
            Log.i(TAG, "DEFAULT_SOURCES ${SP.DEFAULT_SOURCES}")
            viewModel.sources.init()

            SP.channelReversal = SP.DEFAULT_CHANNEL_REVERSAL
            SP.time = SP.DEFAULT_TIME
            SP.bootStartup = SP.DEFAULT_BOOT_STARTUP
            SP.repeatInfo = SP.DEFAULT_REPEAT_INFO
            SP.configAutoLoad = SP.DEFAULT_CONFIG_AUTO_LOAD
            SP.proxy = SP.DEFAULT_PROXY

            imageHelper.clearImage()

            SP.softDecode = SP.DEFAULT_SOFT_DECODE
            SP.glassBlur = SP.DEFAULT_GLASS_BLUR

            SP.configUrl = SP.DEFAULT_CONFIG_URL
            Log.i(TAG, "config url: ${SP.configUrl}")
            binding.configUrlText.text = SP.configUrl ?: ""
            context.deleteFile(CACHE_FILE_NAME)
            viewModel.reset(context)
            confirmConfig()

            SP.channel = SP.DEFAULT_CHANNEL
            Log.i(TAG, "default channel: ${SP.channel}")
            confirmChannel()

            SP.deleteLike()
            Log.i(TAG, "clear like")

            SP.positionGroup = viewModel.groupModel.defaultPosition()
            viewModel.groupModel.initPosition()

            SP.position = SP.DEFAULT_POSITION
            Log.i(TAG, "list position: ${SP.position}")
            val tvListModel = viewModel.groupModel.getCurrentList()
            tvListModel?.setPosition(SP.DEFAULT_POSITION)
            tvListModel?.setPositionPlaying(SP.DEFAULT_POSITION)

            viewModel.groupModel.setPositionPlaying()
            viewModel.groupModel.getCurrentList()?.setPositionPlaying()
            viewModel.groupModel.getCurrent()?.setReady()

            SP.showAllChannels = SP.DEFAULT_SHOW_ALL_CHANNELS
            SP.compactMenu = SP.DEFAULT_COMPACT_MENU

            viewModel.setDisplaySeconds(SP.DEFAULT_DISPLAY_SECONDS)

            SP.epg = SP.DEFAULT_EPG
            viewModel.updateEPG()

            // 清除用户覆盖标记，重新拉取并应用远端配置（远端 > 内置默认）
            // 注意：远程配置中心地址本身保留，不随恢复默认清除
            SP.clearUserOverrides()
            SP.logoBaseUrl = ""
            viewModel.updateConfig()

            // 同步界面开关状态
            syncSwitchStates()
            syncRemoteServerText()

            R.string.config_restored.showToast()
        }

        binding.switchShowAllChannels.setOnCheckedChangeListener { _, isChecked ->
            SP.showAllChannels = isChecked
            viewModel.groupModel.setChange()

            mainActivity.settingActive()
        }
        binding.switchShowAllChannels.setOnFocusChangeListener { v, hasFocus ->
            FocusFx.apply(v, hasFocus)
            mainActivity.settingActive()
        }

        binding.navSource.requestFocus()
    }

    private fun syncRemoteServerText() {
        val server = SP.remoteConfigServer ?: ""
        binding.remoteConfigServerText.text =
            server.ifEmpty { getString(R.string.remote_config_server_hint) }
    }

    /** 恢复默认后刷新所有开关显示 */
    private fun syncSwitchStates() {
        binding.switchChannelReversal.isChecked = SP.channelReversal
        binding.switchChannelNum.isChecked = SP.channelNum
        binding.switchTime.isChecked = SP.time
        binding.switchDisplaySeconds.isChecked = SP.displaySeconds
        binding.switchBootStartup.isChecked = SP.bootStartup
        binding.switchRepeatInfo.isChecked = SP.repeatInfo
        binding.switchConfigAutoLoad.isChecked = SP.configAutoLoad
        binding.switchDefaultLike.isChecked = SP.defaultLike
        binding.switchShowAllChannels.isChecked = SP.showAllChannels
        binding.switchCompactMenu.isChecked = SP.compactMenu
        binding.switchSoftDecode.isChecked = SP.softDecode
        binding.switchElderMode.isChecked = SP.elderMode
        binding.switchGlassBlur.isChecked = SP.glassBlur
    }

    private fun confirmConfig() {
        if (SP.configUrl.isNullOrEmpty()) {
            Log.w(TAG, "SP.configUrl is null or empty")
            return
        }

        uri = Uri.parse(Utils.formatUrl(SP.configUrl!!))
        if (uri.scheme == "") {
            uri = uri.buildUpon().scheme("http").build()
        }
        if (uri.isAbsolute) {
            if (uri.scheme == "file") {
                requestReadPermissions()
            } else {
                viewModel.importFromUri(uri)
            }
        } else {
            R.string.invalid_config_address.showToast()
        }
        (activity as MainActivity).settingActive()
    }

    private fun confirmChannel() {
        SP.channel =
            min(max(SP.channel, 0), viewModel.groupModel.getAllList()!!.size())

        (activity as MainActivity).settingActive()
    }

    private fun hideSelf() {
        requireActivity().supportFragmentManager.beginTransaction()
            .hide(this)
            .commitAllowingStateLoss()
        (activity as MainActivity).showTimeFragment()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (_binding != null && !hidden) {
            binding.configUrlText.text = SP.configUrl ?: ""
            syncRemoteServerText()
            FocusFx.panelIn(binding.settingPanel)
            binding.navSource.requestFocus()
        }
    }

    private fun checkAndAddPermission(
        context: Context,
        permission: String,
        permissionsList: MutableList<String>
    ) {
        if (ContextCompat.checkSelfPermission(
                context,
                permission
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            permissionsList.add(permission)
        }
    }

    private fun requestInstallPermissions() {
        val context = requireContext()
        val permissionsList = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            permissionsList.add(Manifest.permission.REQUEST_INSTALL_PACKAGES)
        }

        checkAndAddPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE, permissionsList)
        checkAndAddPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE, permissionsList)

        if (permissionsList.isNotEmpty()) {
            Log.i(TAG, "ask $permissionsList")
            ActivityCompat.requestPermissions(
                requireActivity(),
                permissionsList.toTypedArray(),
                PERMISSIONS_REQUEST_CODE
            )
        } else {
            updateManager.checkAndUpdate()
        }
    }

    private fun requestReadPermissions() {
        val context = requireContext()
        val permissionsList = mutableListOf<String>()

        checkAndAddPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE, permissionsList)

        if (permissionsList.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                requireActivity(),
                permissionsList.toTypedArray(),
                PERMISSIONS_REQUEST_CODE
            )
        } else {
            viewModel.importFromUri(uri)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_READ_EXTERNAL_STORAGE_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                viewModel.importFromUri(uri)
            } else {
                R.string.authorization_failed.showToast()
            }
        }
        if (requestCode == PERMISSIONS_REQUEST_CODE) {
            var allPermissionsGranted = true
            for (result in grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allPermissionsGranted = false
                    break
                }
            }
            if (allPermissionsGranted) {
                updateManager.checkAndUpdate()
            } else {
                Log.w(TAG, "ask permissions failed")
                R.string.authorization_failed.showToast()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "SettingFragment"
        const val PERMISSIONS_REQUEST_CODE = 1
        const val PERMISSION_READ_EXTERNAL_STORAGE_REQUEST_CODE = 2
    }
}
