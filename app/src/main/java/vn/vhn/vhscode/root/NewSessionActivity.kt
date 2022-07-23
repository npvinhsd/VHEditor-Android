package vn.vhn.vhscode.root

import android.Manifest
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.Html
import android.text.method.LinkMovementMethod
import android.view.View
import android.view.Window
import android.widget.CheckBox
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.widget.doOnTextChanged
import com.facebook.react.PackageList
import com.facebook.react.ReactInstanceManager
import com.facebook.react.ReactPackage
import com.facebook.react.ReactRootView
import com.facebook.react.common.LifecycleState
import com.facebook.soloader.SoLoader
import com.google.android.material.textfield.TextInputEditText
import com.rnfs.RNFSPackage
import com.termux.app.TermuxInstaller
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import vn.vhn.reactnative.modules.VHEReactNativePackage
import vn.vhn.vhscode.BuildConfig
import vn.vhn.vhscode.CodeServerService
import vn.vhn.vhscode.GithubReleaseInstallActivity
import vn.vhn.vhscode.R
import vn.vhn.vhscode.databinding.ActivityNewSessionBinding
import vn.vhn.vhscode.preferences.EditorHostPrefs
import vn.vhn.vhscode.root.codeserver.CodeServerManager
import vn.vhn.vhscode.service_features.setupIfNeeded
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.net.URL


class NewSessionActivity : AppCompatActivity() {
    companion object {
        val kCurrentServerVersion = "4.5.0-" + BuildConfig.CS_VERSION

        val kVersionCheckPeriodMilli = 24 * 60 * 60 * 1000; // 1 day

        val kSessionSSL = "ssl"
        val kSessionAllInterfaces = "all-interfaces"

        val kSessionType = "SESSION_TYPE"
        val kSessionTypeTerminal = "SESSION_TYPE_TERMINAL"
        val kSessionTypeCodeEditor = "SESSION_TYPE_CODEEDITOR"
        val kSessionTypeRemoteCodeEditor = "SESSION_TYPE_REMOTE_CODEEDITOR"

        val kRemoteCodeEditorURL = "REMOTE_CODEEDITOR_URL"

        val kIsInitialStart = "INITIAL_START"
    }

    private lateinit var binding: ActivityNewSessionBinding
    lateinit var preferences: EditorHostPrefs
    private var mCanAutoRun: Boolean = false
    private var mIgnoreAutoRunNotified: Boolean = false

    private var latestRemoteVersion: String? = null

    private lateinit var reactRootView: ReactRootView
    private lateinit var reactInstanceManager: ReactInstanceManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)


        requestWindowFeature(Window.FEATURE_NO_TITLE)
        supportActionBar?.hide()

        binding = ActivityNewSessionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        mResultSet = false


        initializeVHEMod()


        SoLoader.init(this, false)
        reactRootView = ReactRootView(this)
        val packages: List<ReactPackage> = PackageList(application).packages
        reactInstanceManager = ReactInstanceManager.builder()
            .setApplication(application)
            .setCurrentActivity(this)
            .setBundleAssetName("loader.bundled.js")
            .setJSMainModulePath("loader")
            .addPackages(packages)
            .addPackage(VHEReactNativePackage())
            .setUseDeveloperSupport(false && BuildConfig.DEBUG)
            .setInitialLifecycleState(LifecycleState.RESUMED)
            .build()
        reactRootView.startReactApplication(reactInstanceManager, "VHERoot", Bundle().also {
            it.putString("mod", "${CodeServerService.VHEMOD_PATH}/new-session")
        })
        binding.customTerminalReactHost.addView(reactRootView)

    }

    override fun onResume() {
        super.onResume()
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_BEHIND
        preferences = EditorHostPrefs(this)
        configureUI()
    }

    private fun configureUI() {
        binding.txtAppVersion.movementMethod = LinkMovementMethod.getInstance()
        binding.editTxtRemoteServer.setText(preferences.defaultRemoteEditorURL)
        checkLatestVersion()
    }

    private fun ensureSetup(whenDone: () -> Unit) {
        TermuxInstaller.setupIfNeeded(this) {
            CodeServerService.setupIfNeeded(this, whenDone)
        }
    }

    fun onNewTerminal(view: View) {
        ensureSetup {
            CoroutineScope(Dispatchers.Main).launch {
                setResult(RESULT_OK, Intent().putExtra(kSessionType, kSessionTypeTerminal))
                finish()
            }
        }
    }

    private var mResultSet = false

    fun onStartCode(view: View) {
        if (mResultSet) return
        mResultSet = true
        ensureSetup {
            CoroutineScope(Dispatchers.Main).launch {
                setResult(
                    RESULT_OK,
                    Intent()
                        .putExtra(kSessionType, kSessionTypeCodeEditor)
                        .putExtra(kSessionSSL, preferences.editorUseSSL)
                        .putExtra(kSessionAllInterfaces, preferences.editorListenAllInterfaces)
                )
                finish()
            }
        }
    }

    fun onStartRemote(view: View) {
        if (mResultSet) return
        mResultSet = true
        setResult(
            RESULT_OK, Intent()
                .putExtra(kSessionType, kSessionTypeRemoteCodeEditor)
                .putExtra(kRemoteCodeEditorURL, binding.editTxtRemoteServer.text.toString())
        )
        preferences.defaultRemoteEditorURL = binding.editTxtRemoteServer.text.toString()
        finish()
    }

    private fun checkLatestVersion() {
        CoroutineScope(Dispatchers.IO).launch {
            if (preferences.latestVersionCheckTime >= System.currentTimeMillis() - kVersionCheckPeriodMilli
            ) {
                latestRemoteVersion = preferences.latestVersion
                updateUI()
                //return?
            }

            try {
                val url = URL("https://github.com/vhqtvn/VHEditor-Android/releases/latest")
                val br = BufferedReader(InputStreamReader(url.openStream()))

                var inputLine: String?
                var version = ""

                val versionExtractor =
                    Regex("\"/vhqtvn/VHEditor-Android/releases/tag/v([\\d\\.]+)\"")

                while (br.readLine().also { inputLine = it } != null) {
                    if (inputLine != null) {
                        val matches = versionExtractor.find(inputLine!!)
                        if (matches != null) {
                            version = matches!!.groupValues[1]
                        }
                    }
                }

                br.close()

                if (version != "" && version != latestRemoteVersion) {
                    latestRemoteVersion = version
                    preferences.latestVersion = version
                    updateUI()
                }
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    fun updateUI() {
        if (wrongTargetSDK()) {
            val intent = Intent(this, GithubReleaseInstallActivity::class.java)
            startActivity(intent)
            finish()
        }
        CoroutineScope(Dispatchers.Main).launch {
            var canAutoRun = true
            var txtVersion = "App version: ${BuildConfig.VERSION_NAME}"
            if (latestRemoteVersion != null) {
                if (latestRemoteVersion == BuildConfig.VERSION_NAME) {
                    txtVersion += ", already latest version."
                } else {
                    txtVersion += ", latest version: <a href=\"https://github.com/vhqtvn/VHEditor-Android/releases/latest\">${latestRemoteVersion}</a>"
                    txtVersion += " (<a href=\"https://github.com/vhqtvn/VHEditor-Android/blob/master/CHANGELOG.md\">ChangeLog</a>)"
                    canAutoRun = false
                }
            }
            binding.txtAppVersion.text = Html.fromHtml(txtVersion, 0)

            val codeServerVersion =
                withContext(Dispatchers.IO) { getCurrentCodeServerVersion() }
            binding.txtInstalledServerVersion.text = getString(
                R.string.server_version,
                codeServerVersion ?: getString(R.string.not_installed)
            )
            var isInstalled = !codeServerVersion.isNullOrBlank()
            if (isInstalled) {
                binding.btnStartCode.isEnabled = true
                if (codeServerVersion != kCurrentServerVersion) {
                    binding.btnInstallServer.text =
                        getString(R.string.update_server) + " (" + kCurrentServerVersion + ")"
                    binding.btnInstallServer.visibility = View.VISIBLE
                    canAutoRun = false
                } else {
                    binding.btnInstallServer.text = getString(R.string.reinstall_server)
                    binding.btnInstallServer.visibility = View.GONE
                }
            } else {
                binding.btnStartCode.isEnabled = false
                binding.btnInstallServer.text =
                    getString(R.string.install_server)
                binding.btnInstallServer.visibility = View.VISIBLE
                canAutoRun = false
            }
//            for (v in listOf(binding.installedRegionGroup)) {
//                v.visibility = if (isInstalled) View.VISIBLE else View.GONE
//            }

            mCanAutoRun = canAutoRun
            performRequestPermissions()
        }
    }

    private fun wrongTargetSDK(): Boolean {
        val targetSdkVersion = applicationContext.applicationInfo.targetSdkVersion
        return android.os.Build.VERSION.SDK_INT >= 29 && targetSdkVersion != 28
    }

    private fun checkAndAutoRun() {
        if (intent.getBooleanExtra(kIsInitialStart, false))
            when (preferences.startupTool) {
                EditorHostPrefs.StartupTool.EDITOR -> {
                    if (!mCanAutoRun) {
                        if (!mIgnoreAutoRunNotified) {
                            mIgnoreAutoRunNotified = true
                            Toast.makeText(baseContext,
                                R.string.ignore_auto_start_notification,
                                Toast.LENGTH_SHORT).show()
                        }
                        return
                    }
                    onStartCode(binding.root)
                }
            }
    }

    var requestedPermission: Boolean = false
    private fun performRequestPermissions() {
        CoroutineScope(Dispatchers.Main).launch {
            if (requestedPermission) {
                checkAndAutoRun()
                return@launch
            }
            requestedPermission = true
            preferences.requestedPermissions = true
            val listPermissionsNeeded = mutableListOf<String>()
            for (permission in listOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ))
                if (ContextCompat.checkSelfPermission(this@NewSessionActivity, permission)
                    != PackageManager.PERMISSION_GRANTED
                )
                    listPermissionsNeeded.add(permission)
            if (!listPermissionsNeeded.isEmpty()) {
                ActivityCompat.requestPermissions(
                    this@NewSessionActivity,
                    listPermissionsNeeded.toTypedArray(),
                    0
                )
            } else {
                checkAndAutoRun()
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        checkAndAutoRun()
    }

    suspend fun getCurrentCodeServerVersion(): String? {
        val node = getFileStreamPath("node")
        if (!node.exists()) return null
        val dataHome = node.parent!!.toString()
        val versionFile = File("$dataHome/code-server/VERSION")
        if (versionFile.exists()) {
            val stream = FileInputStream(versionFile)
            val bytes = stream.readBytes()
            stream.close()
            var csVersion = String(bytes, 0, bytes.size).trim()
            val buildFile = File("$dataHome/code-server/CSBUILD_VERSION")
            if (buildFile.exists()) {
                val stream = FileInputStream(buildFile)
                val bytes = stream.readBytes()
                stream.close()
                var csBuildVersion = String(bytes, 0, bytes.size).trim()
                csVersion += "-$csBuildVersion"
            }
            return csVersion
        }
        return ""
    }

    fun onChkFullScreenClick(view: View) {
        preferences.fullScreen = (view as CheckBox).isChecked
    }

    fun onChkListenOnAllInterfacesClick(view: View) {
        preferences.editorListenAllInterfaces = (view as CheckBox).isChecked
    }

    fun onChkUseSSLClick(view: View) {
        preferences.editorUseSSL = (view as CheckBox).isChecked
    }

    fun onChkInitialStartEditorClick(view: View) {
        preferences.startupTool =
            if ((view as CheckBox).isChecked) EditorHostPrefs.StartupTool.EDITOR
            else EditorHostPrefs.StartupTool.NONE
    }

    fun onSettingsClick(view: View) {
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.settings)
            .setView(R.layout.dialog_settings)
            .setNegativeButton(
                R.string.close
            ) { dialog: DialogInterface?, _: Int ->
                dialog?.dismiss()
            }
            .setOnDismissListener {
                updateUI()
            }
            .show()

        // Set checkboxes to their sharedPreferences setting state
        dialog.findViewById<CheckBox>(R.id.chkFullScreen)?.isChecked =
            preferences.fullScreen
        dialog.findViewById<CheckBox>(R.id.chkListenOnAllInterfaces)?.isChecked =
            preferences.editorListenAllInterfaces
        dialog.findViewById<CheckBox>(R.id.chkUseSSL)?.isChecked =
            preferences.editorUseSSL
        dialog.findViewById<CheckBox>(R.id.chkInitialStartEditor)?.isChecked =
            preferences.startupTool == EditorHostPrefs.StartupTool.EDITOR
        dialog.findViewById<TextInputEditText>(R.id.txtSettingsLocalServerListenPort)?.apply {
            setText(preferences.editLocalServerListenPort)
            doOnTextChanged { text, start, count, after ->
                preferences.editLocalServerListenPort = text.toString()
            }
        }
    }


    fun onResetRootFS(v: View) {
        AlertDialog.Builder(this)
            .setCancelable(true)
            .setMessage(R.string.confirm_reset_rootfs)
            .setPositiveButton(android.R.string.ok) { dialog, id ->
                dialog.dismiss()
                CodeServerManager.onResetRootFS(this) {
                    updateUI()
                }
            }
            .setNegativeButton(android.R.string.cancel) { dialog, id ->
                dialog.dismiss()
            }
            .show()
    }

    fun onInstallServerClick(v: View) {
        val run = {
            CodeServerManager.runInstallServer(this) {
                TermuxInstaller.setupIfNeeded(this) {
                    updateUI()
                }
            }
        }
        if (v.id == R.id.btnSettingsInstallServer) {
            AlertDialog.Builder(this)
                .setCancelable(true)
                .setMessage(R.string.confirm_reinstall_server)
                .setPositiveButton(android.R.string.ok) { dialog, _ ->
                    dialog.dismiss()
                    run()
                }
                .setNegativeButton(android.R.string.cancel) { dialog, _ ->
                    dialog.dismiss()
                }
                .show()
        } else {
            run()
        }
    }

    fun initializeVHEMod() {
        val ctx = this
        File(CodeServerService.VHEMOD_PATH).mkdirs()
        File("${CodeServerService.VHEMOD_PATH}/new-session.js").apply {
            if (!exists()) CodeServerService.copyRawResource(
                ctx,
                R.raw.new_session_loader,
                absolutePath)
        }
        CodeServerService.copyRawResource(
            ctx,
            R.raw.new_session_default,
            "${CodeServerService.VHEMOD_PATH}/new-session-default.js")
    }
}