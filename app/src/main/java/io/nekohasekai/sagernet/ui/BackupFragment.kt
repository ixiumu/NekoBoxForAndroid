package io.nekohasekai.sagernet.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Parcel
import android.os.Parcelable
import android.provider.OpenableColumns
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.InputStreamContent
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.database.preference.KeyValuePair
import io.nekohasekai.sagernet.database.preference.PublicDatabase
import io.nekohasekai.sagernet.databinding.LayoutBackupBinding
import io.nekohasekai.sagernet.databinding.LayoutImportBinding
import io.nekohasekai.sagernet.databinding.LayoutProgressBinding
import io.nekohasekai.sagernet.ktx.*
import moe.matsuri.nb4a.utils.Util
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.*

class BackupFragment : NamedFragment(R.layout.layout_backup) {

    override fun name0() = app.getString(R.string.backup)

    private var content = ""

    private var appName = app.getString(R.string.app_name)

    @SuppressWarnings("deprecation")
    private val exportSettings =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { data ->
            if (data != null) {
                runOnDefaultDispatcher {
                    try {
                        requireActivity().contentResolver.openOutputStream(
                            data
                        )!!.bufferedWriter().use {
                            it.write(content)
                        }
                        onMainDispatcher {
                            snackbar(getString(R.string.action_export_msg)).show()
                        }
                    } catch (e: Exception) {
                        Logs.w(e)
                        onMainDispatcher {
                            snackbar(e.readableMessage).show()
                        }
                    }
                }
            }
        }

    @SuppressWarnings("deprecation")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val binding = LayoutBackupBinding.bind(view)

        val signInOptions = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(DriveScopes.DRIVE_APPDATA))
            .build()
        signInClient = GoogleSignIn.getClient(requireContext(), signInOptions)

        binding.resetSettings.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.confirm)
                .setMessage(R.string.reset_settings_message)
                .setNegativeButton(R.string.no, null)
                .setPositiveButton(R.string.yes) { _, _ ->
                    DataStore.configurationStore.reset()
                    triggerFullRestart(requireContext())
                }
                .show()
        }

        binding.actionExport.setOnClickListener {
            runOnDefaultDispatcher {
                content = doBackup(
                    binding.backupConfigurations.isChecked,
                    binding.backupRules.isChecked,
                    binding.backupSettings.isChecked
                )
                onMainDispatcher {
                    startFilesForResult(
                        exportSettings, getFileName()
                    )
                }
            }
        }

        binding.actionShare.setOnClickListener {
            runOnDefaultDispatcher {
                content = doBackup(
                    binding.backupConfigurations.isChecked,
                    binding.backupRules.isChecked,
                    binding.backupSettings.isChecked
                )
                app.cacheDir.mkdirs()
                val cacheFile = File(
                    app.cacheDir, getFileName()
                )
                cacheFile.writeText(content)
                onMainDispatcher {
                    startActivity(
                        Intent.createChooser(
                            Intent(Intent.ACTION_SEND).setType("application/json")
                                .setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                .putExtra(
                                    Intent.EXTRA_STREAM, FileProvider.getUriForFile(
                                        app, BuildConfig.APPLICATION_ID + ".cache", cacheFile
                                    )
                                ), app.getString(R.string.abc_shareactionprovider_share_with)
                        )
                    )
                }

            }
        }

        binding.actionImportFile.setOnClickListener {
            startFilesForResult(importFile, "*/*")
        }

        binding.actionBackupDrive.setOnClickListener {
            isRestoreMode = false
            startGoogleSignIn()
        }

        binding.actionRestoreDrive.setOnClickListener {
            isRestoreMode = true
            startGoogleSignIn()
        }
    }

    fun Parcelable.toBase64Str(): String {
        val parcel = Parcel.obtain()
        writeToParcel(parcel, 0)
        try {
            return Util.b64EncodeUrlSafe(parcel.marshall())
        } finally {
            parcel.recycle()
        }
    }

    fun doBackup(profile: Boolean, rule: Boolean, setting: Boolean): String {
        val out = JSONObject().apply {
            put("version", 1)
            if (profile) {
                put("profiles", JSONArray().apply {
                    SagerDatabase.proxyDao.getAll().forEach {
                        put(it.toBase64Str())
                    }
                })

                put("groups", JSONArray().apply {
                    SagerDatabase.groupDao.allGroups().forEach {
                        put(it.toBase64Str())
                    }
                })
            }
            if (rule) {
                put("rules", JSONArray().apply {
                    SagerDatabase.rulesDao.allRules().forEach {
                        put(it.toBase64Str())
                    }
                })
            }
            if (setting) {
                put("settings", JSONArray().apply {
                    PublicDatabase.kvPairDao.all().forEach {
                        put(it.toBase64Str())
                    }
                })
            }
        }
        return out.toStringPretty()
    }

    val importFile = registerForActivityResult(ActivityResultContracts.GetContent()) { file ->
        if (file != null) {
            runOnDefaultDispatcher {
                startImport(file)
            }
        }
    }

    suspend fun startImport(file: Uri) {
        val fileName = requireContext().contentResolver.query(file, null, null, null, null)
            ?.use { cursor ->
                cursor.moveToFirst()
                cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME).let(cursor::getString)
            }
            ?.takeIf { it.isNotBlank() } ?: file.pathSegments.last()
            .substringAfterLast('/')
            .substringAfter(':')

        if (!fileName.endsWith(".json")) {
            onMainDispatcher {
                snackbar(getString(R.string.backup_not_file, fileName)).show()
            }
            return
        }

        suspend fun invalid() = onMainDispatcher {
            onMainDispatcher {
                snackbar(getString(R.string.invalid_backup_file)).show()
            }
        }

        val content = try {
            JSONObject((requireContext().contentResolver.openInputStream(file) ?: return).use {
                it.bufferedReader().readText()
            })
        } catch (e: Exception) {
            Logs.w(e)
            invalid()
            return
        }
        val version = content.optInt("version", 0)
        if (version < 1 || version > 1) {
            invalid()
            return
        }

        onMainDispatcher {
            val import = LayoutImportBinding.inflate(layoutInflater)
            if (!content.has("profiles")) {
                import.backupConfigurations.isVisible = false
            }
            if (!content.has("rules")) {
                import.backupRules.isVisible = false
            }
            if (!content.has("settings")) {
                import.backupSettings.isVisible = false
            }
            MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.backup_import)
                .setView(import.root)
                .setPositiveButton(R.string.backup_import) { _, _ ->
                    SagerNet.stopService()

                    val binding = LayoutProgressBinding.inflate(layoutInflater)
                    binding.content.text = getString(R.string.backup_importing)
                    val dialog = AlertDialog.Builder(requireContext())
                        .setView(binding.root)
                        .setCancelable(false)
                        .show()
                    runOnDefaultDispatcher {
                        runCatching {
                            finishImport(
                                content,
                                import.backupConfigurations.isChecked,
                                import.backupRules.isChecked,
                                import.backupSettings.isChecked
                            )
                            triggerFullRestart(requireContext())
                        }.onFailure {
                            Logs.w(it)
                            onMainDispatcher {
                                alert(it.readableMessage).tryToShow()
                            }
                        }

                        onMainDispatcher {
                            dialog.dismiss()
                        }
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    fun finishImport(
        content: JSONObject, profile: Boolean, rule: Boolean, setting: Boolean
    ) {
        if (profile && content.has("profiles")) {
            val profiles = mutableListOf<ProxyEntity>()
            val jsonProfiles = content.getJSONArray("profiles")
            for (i in 0 until jsonProfiles.length()) {
                val data = Util.b64Decode(jsonProfiles[i] as String)
                val parcel = Parcel.obtain()
                parcel.unmarshall(data, 0, data.size)
                parcel.setDataPosition(0)
                profiles.add(ProxyEntity.CREATOR.createFromParcel(parcel))
                parcel.recycle()
            }
            SagerDatabase.proxyDao.reset()
            SagerDatabase.proxyDao.insert(profiles)

            val groups = mutableListOf<ProxyGroup>()
            val jsonGroups = content.getJSONArray("groups")
            for (i in 0 until jsonGroups.length()) {
                val data = Util.b64Decode(jsonGroups[i] as String)
                val parcel = Parcel.obtain()
                parcel.unmarshall(data, 0, data.size)
                parcel.setDataPosition(0)
                groups.add(ProxyGroup.CREATOR.createFromParcel(parcel))
                parcel.recycle()
            }
            SagerDatabase.groupDao.reset()
            SagerDatabase.groupDao.insert(groups)
        }
        if (rule && content.has("rules")) {
            val rules = mutableListOf<RuleEntity>()
            val jsonRules = content.getJSONArray("rules")
            for (i in 0 until jsonRules.length()) {
                val data = Util.b64Decode(jsonRules[i] as String)
                val parcel = Parcel.obtain()
                parcel.unmarshall(data, 0, data.size)
                parcel.setDataPosition(0)
                rules.add(ParcelizeBridge.createRule(parcel))
                parcel.recycle()
            }
            SagerDatabase.rulesDao.reset()
            SagerDatabase.rulesDao.insert(rules)
        }
        if (setting && content.has("settings")) {
            val settings = mutableListOf<KeyValuePair>()
            val jsonSettings = content.getJSONArray("settings")
            for (i in 0 until jsonSettings.length()) {
                val data = Util.b64Decode(jsonSettings[i] as String)
                val parcel = Parcel.obtain()
                parcel.unmarshall(data, 0, data.size)
                parcel.setDataPosition(0)
                settings.add(KeyValuePair.CREATOR.createFromParcel(parcel))
                parcel.recycle()
            }
            PublicDatabase.kvPairDao.reset()
            PublicDatabase.kvPairDao.insert(settings)
        }
    }

    fun getFileName(): String {
        val timeStamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmssSSS", Locale.ROOT).format(Date())
        return "${appName}_backup_${timeStamp}.json"
    }

    // Google Drive API
    private lateinit var signInClient: GoogleSignInClient
    private lateinit var driveService: Drive
    private var isRestoreMode = false

    private val signInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
            handleSignInResult(task)
        } else {
            snackbar(getString(R.string.google_sign_in_failed)).show()
        }
    }


    @SuppressWarnings("deprecation")
    private fun startGoogleSignIn() {
        val account = GoogleSignIn.getLastSignedInAccount(requireContext())
        val hasRequiredScope = account?.grantedScopes?.contains(Scope(DriveScopes.DRIVE_APPDATA)) == true
        if (account != null && hasRequiredScope) {
            handleSignInResult(com.google.android.gms.tasks.Tasks.forResult(account))
        } else {
            val signInIntent = signInClient.signInIntent
            signInLauncher.launch(signInIntent)
        }
    }

    private fun handleSignInResult(completedTask: Task<GoogleSignInAccount>) {
        try {
            val account = completedTask.getResult(ApiException::class.java)

            val credential = GoogleAccountCredential.usingOAuth2(
                requireContext(), listOf(DriveScopes.DRIVE_APPDATA)
            )
            credential.selectedAccount = account.account

            driveService = Drive.Builder(
                NetHttpTransport(),
                GsonFactory.getDefaultInstance(),
                credential
            )
                .setApplicationName(requireContext().getString(R.string.app_name))
                .build()

            runOnDefaultDispatcher {
                if (isRestoreMode) {
                    performDriveRestore()
                } else {
                    performDriveBackup()
                }
            }

        } catch (e: ApiException) {
            Logs.e("signInResult:failed code=" + e.statusCode, e)
            requireActivity().runOnUiThread {
                snackbar(getString(R.string.google_sign_in_failed) + ": ${e.statusCode}").show()
            }
        }
    }

    private suspend fun performDriveBackup() {
        var dialog: AlertDialog? = null
        onMainDispatcher {
            val progressBinding = LayoutProgressBinding.inflate(layoutInflater)
            progressBinding.content.text = getString(R.string.drive_uploading)
            dialog = AlertDialog.Builder(requireContext())
                .setView(progressBinding.root)
                .setCancelable(false)
                .show()
        }

        content = doBackup(profile = true, rule = true, setting = true)

        try {
            val metadata = com.google.api.services.drive.model.File().apply {
                setName(getFileName())
                setMimeType("application/json")
                setParents(listOf("appDataFolder"))
            }

            val contentStream = ByteArrayInputStream(content.toByteArray(Charsets.UTF_8))

            driveService.files().create(
                metadata,
                InputStreamContent("application/json", contentStream)
            )
                .setFields("id")
                .execute()

            onMainDispatcher {
                dialog?.dismiss()
                snackbar(getString(R.string.drive_backup_success)).show()
            }

        } catch (e: Exception) {
            Logs.e("Drive REST API Upload Failed", e)
            onMainDispatcher {
                dialog?.dismiss()
                snackbar(getString(R.string.drive_backup_failed) + ": ${e.readableMessage}").show()
            }
        }
    }

    @SuppressLint("StringFormatInvalid")
    private suspend fun performDriveRestore() {
        var dialog: AlertDialog? = null
        onMainDispatcher {
            val progressBinding = LayoutProgressBinding.inflate(layoutInflater)
            progressBinding.content.text = getString(R.string.drive_fetching)
            dialog = AlertDialog.Builder(requireContext())
                .setView(progressBinding.root)
                .setCancelable(false)
                .show()
        }

        try {
            val query = driveService.files().list()
                .setSpaces("appDataFolder")
                .setFields("files(id, name, createdTime)")
                .setQ("mimeType='application/json' and name contains '${appName}_backup'")
                .setOrderBy("createdTime desc")
                .execute()

            val files = query.files

            onMainDispatcher { dialog?.dismiss() }

            if (files.isNullOrEmpty()) {
                onMainDispatcher {
                    snackbar(getString(R.string.drive_no_backup_found)).show()
                }
                return
            }

            val latestFile = files.first()

            onMainDispatcher {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.confirm)
                    .setMessage(getString(R.string.drive_confirm_restore_message, latestFile.name, latestFile.createdTime.toString()))
                    .setPositiveButton(R.string.yes) { _, _ ->
                        runOnDefaultDispatcher {
                            downloadAndImportFile(latestFile.id)
                        }
                    }
                    .setNegativeButton(R.string.no, null)
                    .show()
            }

        } catch (e: Exception) {
            Logs.e("Drive Restore Query Failed", e)
            onMainDispatcher {
                dialog?.dismiss()
                snackbar("${getString(R.string.drive_fetching_failed)}: ${e.readableMessage}").show()
            }
        }
    }

    private suspend fun downloadAndImportFile(fileId: String) {
        var dialog: AlertDialog? = null

        onMainDispatcher {
            val progressBinding = LayoutProgressBinding.inflate(layoutInflater)
            progressBinding.content.text = getString(R.string.backup_importing)
            dialog = AlertDialog.Builder(requireContext())
                .setView(progressBinding.root)
                .setCancelable(false)
                .show()
            SagerNet.stopService()
        }

        val tempFile = File(requireContext().cacheDir, "drive_restore.json")

        try {
            FileOutputStream(tempFile).use { outputStream ->
                driveService.files().get(fileId)
                    .executeMediaAndDownloadTo(outputStream)
            }

            val restoredContent = tempFile.readText()
            val contentObject = JSONObject(restoredContent)

            finishImport(contentObject, profile = true, rule = true, setting = true)

            onMainDispatcher {
                dialog?.dismiss()
                snackbar(getString(R.string.drive_restore_success)).show()
                triggerFullRestart(requireContext())
            }

        } catch (e: Exception) {
            Logs.e("Drive Download/Import Failed", e)
            onMainDispatcher {
                dialog?.dismiss()
                alert("${getString(R.string.drive_restore_failed)}: ${e.readableMessage}").tryToShow()
            }
        } finally {
            tempFile.delete()
        }
    }

}