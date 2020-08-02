package com.flamingo.ipynbViewer.activity

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.method.ScrollingMovementMethod
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import com.hbisoft.pickit.PickiT
import com.hbisoft.pickit.PickiTCallbacks
import com.termux.R
import com.termux.app.BackgroundJob
import com.termux.app.TermuxActivity
import com.termux.app.TermuxInstaller
import com.termux.app.TermuxService
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSession.SessionChangedCallback
import kotlinx.android.synthetic.main.activity_main.*
import org.jetbrains.anko.toast
import java.io.*

class MainActivity : Activity() {
    lateinit var session: TerminalSession

    // lib for getting path from uri
    lateinit var pickiT: PickiT
    private var lastConvertedFilePath = ""

    override fun onCreate(savedInstanceState: Bundle) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        pickiT = PickiT(this, MyPickiTListener())
        TermuxInstaller.setupStorageSymlinks(this)

        tvLog.setMovementMethod(ScrollingMovementMethod())
        btnConvert.setEnabled(false)
        ensureStoragePermissionGranted()
        tvStatus.setText("Unknown")
        TermuxInstaller.setupIfNeeded(this) {
            session = createTermSession(null, null, null, false)
            session.updateSize(30, 10)
            session.reset()
            session.write("echo HELLO\n")
        }
        btnTermuxActivity.setOnClickListener {
            val intent = Intent(this, TermuxActivity::class.java)
            startActivity(intent)
        }
        btnConvert.setOnClickListener {
            selectFile()
        }
    }

    private fun checkConverter() {
        tvStatus.text = "Checking converter"
        session.write("jupyter nbconvert --version\n")
        session.write("echo DONE_CHECKING\n")
    }

    // check after install
    private fun checkConverterAgain() {
        tvStatus.text = "Checking converter"
        session.write("jupyter nbconvert --version\n")
        session.write("echo DONE_CHECKING_AGAIN\n")
    }

    private var startedInstallingPython = false
    private fun loadPython() {
        startedInstallingPython = true
        tvStatus.text = "Installing Python"
        session.write("pkg install python\n")
        session.write("Y\n")
    }

    private fun loadNBConvert() {
//        session.write("termux-setup-storage\n");
        tvStatus.text = "Installing nbconvert"
        session.write("pip install nbconvert==$NBCONVERT_VERSION\n")
        session.write("echo DONE_LOADING\n")
    }

    /**
     * For processes to access shared internal storage (/sdcard) we need this permission.
     */
    fun ensureStoragePermissionGranted(): Boolean {
        return if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
            true
        } else {
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), PERMISSION_STORAGE_REQUEST_CODE)
            false
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        if (requestCode == PERMISSION_STORAGE_REQUEST_CODE && grantResults.size > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            // OK
//            TermuxInstaller.setupStorageSymlinks(this);
        }
    }

    fun createTermSession(executablePath: String?, arguments: Array<String?>?, cwd: String?, failSafe: Boolean): TerminalSession {
        var executablePath = executablePath
        var cwd = cwd
        File(TermuxService.HOME_PATH).mkdirs()
        if (cwd == null) cwd = TermuxService.HOME_PATH
        val env = BackgroundJob.buildEnvironment(failSafe, cwd)
        var isLoginShell = false
        if (executablePath == null) {
            if (!failSafe) {
                for (shellBinary in arrayOf("login", "bash", "zsh")) {
                    val shellFile = File(TermuxService.PREFIX_PATH + "/bin/" + shellBinary)
                    if (shellFile.canExecute()) {
                        executablePath = shellFile.absolutePath
                        break
                    }
                }
            }
            if (executablePath == null) {
                // Fall back to system shell as last resort:
                executablePath = "/system/bin/sh"
            }
            isLoginShell = true
        }
        val processArgs = BackgroundJob.setupProcessArgs(executablePath, arguments)
        executablePath = processArgs[0]
        val lastSlashIndex = executablePath.lastIndexOf('/')
        val processName = (if (isLoginShell) "-" else "") +
            if (lastSlashIndex == -1) executablePath else executablePath.substring(lastSlashIndex + 1)
        val args = arrayOfNulls<String>(processArgs.size)
        args[0] = processName
        if (processArgs.size > 1) System.arraycopy(processArgs, 1, args, 1, processArgs.size - 1)
        //        mTerminalSessions.add(session);
//        updateNotification();

        // Make sure that terminal styling is always applied.
//        Intent stylingIntent = new Intent("com.termux.app.reload_style");
//        stylingIntent.putExtra("com.termux.app.reload_style", "styling");
//        sendBroadcast(stylingIntent);
        return TerminalSession(executablePath, cwd, args, env, MySessionChangedCallback())
    }

    private fun openConvertedFile(file: File) {
        try {
            val uri = FileProvider.getUriForFile(this, "com.pavelperc.ipynbViewer.fileprovider", file)
            //            Uri uri = Uri.fromFile(file);
            val intent = Intent(Intent.ACTION_VIEW)
            intent.data = uri
            intent.flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            startActivity(intent)
        } catch (e: Exception) {
            logError("error", e)
        }
    }

    private fun installFailureDialog(onSuccess: Runnable) {
        AlertDialog.Builder(this)
            .setTitle("Failed to install libraries.")
            .setMessage("Try again?")
            .setNegativeButton("No", null)
            .setPositiveButton("Yes") { _, _ -> onSuccess.run() }
            .setCancelable(false)
            .show()
    }

    private fun scrollLog() {
        if (tvLog.layout == null) {
            return
        }
        val scrollAmount = tvLog.layout.getLineTop(tvLog.lineCount) - tvLog.height
        // if there is no need to scroll, scrollAmount will be <=0
        if (scrollAmount > 0) tvLog.scrollTo(0, scrollAmount) else tvLog.scrollTo(0, 0)
    }

    inner class MySessionChangedCallback : SessionChangedCallback {
        override fun onTitleChanged(changedSession: TerminalSession) {
//        toast("Title changed: " + changedSession.getTitle());
        }

        override fun onSessionFinished(finishedSession: TerminalSession) {
            toast("Session Finished")
        }

        override fun onClipboardText(session: TerminalSession, text: String) {}
        override fun onBell(session: TerminalSession) {}
        override fun onColorsChanged(session: TerminalSession) {
//        toast("On colors changed");
        }

        // TerminalSession.SessionChangedCallback:
        override fun onTextChanged(changedSession: TerminalSession) {
            val screen = changedSession.emulator.screen
            val text = screen.transcriptText
            tvLog.text = text
            if (text.endsWith("HELLO\n$")) {
                checkConverter()
            } else if (text.endsWith("DONE_LOADING\n$")) {
                checkConverterAgain()
            } else if (text.endsWith("DONE_CONVERTING\n$")) {
                tvStatus.text = "Done converting"
                btnConvert!!.isEnabled = true
                logVerbose("terminal output:\n$text")
                openConvertedFile(File(lastConvertedFilePath))
            } else if (text.endsWith("DONE_CHECKING\n$") || text.endsWith("DONE_CHECKING_AGAIN\n$")) {
                val ending = text.substring(Math.max(0, text.length - 100))
                if (!ending.contains(NBCONVERT_VERSION)) {
                    if (text.endsWith("DONE_CHECKING_AGAIN\n$")) {
                        installFailureDialog(Runnable { loadPython() })
                    } else {
                        loadPython()
                    }
                } else {
                    tvStatus.text = "Converter is ready"
                    btnConvert!!.isEnabled = true
                }
            } else if (text.endsWith("$")) {
                // No feedback after installing python.
                if (startedInstallingPython) {
                    startedInstallingPython = false
                    loadNBConvert()
                } else {
                    tvStatus.text = "Some error. Restart the app."
                }
            }
            scrollLog()
        }
    }

    private fun selectFile() {
        val intent = Intent()
        intent.type = "*/*"
        intent.action = Intent.ACTION_GET_CONTENT
        startActivityForResult(Intent.createChooser(intent, "Select File"), FILE_PICKER_REQUEST_CODE)
    }

    // https://stackoverflow.com/questions/5568874/how-to-extract-the-file-name-from-uri-returned-from-intent-action-get-content
    private fun getFileName(uri: Uri?): String? {
        var result: String? = null
        if (uri!!.scheme == "content") {
            val cursor = contentResolver.query(uri, null, null, null, null)
            try {
                if (cursor != null && cursor.moveToFirst()) {
                    result = cursor.getString(cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME))
                }
            } finally {
                cursor!!.close()
            }
        }
        if (result == null) {
            result = uri.path
            val cut = result.lastIndexOf('/')
            if (cut != -1) {
                result = result.substring(cut + 1)
            }
        }
        if (!result!!.endsWith(".ipynb")) {
            result = "$result.ipynb"
        }
        return result
    }

    private var originalFileUri: Uri? = null
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent) {
        if (requestCode == FILE_PICKER_REQUEST_CODE && resultCode == RESULT_OK && data != null) {
            logDebug("Original uri: " + data.dataString)
            originalFileUri = data.data
            pickiT.getPath(data.data, Build.VERSION.SDK_INT)
        }
    }

    private fun saveFile(input: InputStream, file: File) {
        try {
            FileOutputStream(file).use { output ->
                val buffer = ByteArray(40 * 1024) // or other buffer size
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    output.write(buffer, 0, read)
                }
                output.flush()
            }
        } catch (e: Exception) {
        } finally {
            try {
                input.close()
            } catch (e: IOException) {
            }
        }
    }

    inner class MyPickiTListener : PickiTCallbacks {
        override fun PickiTonCompleteListener(path: String, wasDriveFile: Boolean, wasUnknownProvider: Boolean, wasSuccessful: Boolean, Reason: String) {
            var path = path
            if (!wasSuccessful) {
                logDebug("not successful: $Reason")
                val name = getFileName(originalFileUri)
                val file = File(externalCacheDir, name)
                try {
                    saveFile(contentResolver.openInputStream(originalFileUri), file)
                } catch (e: FileNotFoundException) {
                    return
                }
                path = file.path
            } else if (wasDriveFile) {
                val name = getFileName(originalFileUri)
                val newFile = File(externalCacheDir, name)
                val oldFile = File(path)
                oldFile.renameTo(newFile)
                path = newFile.path
            }
            logDebug("File path: $path")
            convertFile(path)
        }

        private fun convertFile(path: String) {
            tvStatus.text = "Converting"
            btnConvert!!.isEnabled = false
            val file = File(path)
            //        String fileName = file.getName() + "-" + file.lastModified() + ".html";
            val fileName = file.name + ".html"
            session.write("jupyter nbconvert " +
                "--output-dir=storage/temp " +
                "--output=\"$fileName\" \"${file.absolutePath}\"\n" +
                "echo DONE_CONVERTING\n")
            lastConvertedFilePath = externalCacheDir.absolutePath + "/" + fileName
            logDebug("see file in: $lastConvertedFilePath")
        }

        override fun PickiTonStartListener() {}
        override fun PickiTonProgressUpdate(progress: Int) {}
    }

    override fun onDestroy() {
        session.finishIfRunning()
        super.onDestroy()
    }
    

    private fun logDebug(text: String) {
        Log.d("my_tag", text)
    }

    private fun logVerbose(text: String) {
        Log.v("my_tag", text)
    }

    private fun logError(text: String, throwable: Throwable) {
        Log.e("my_tag", text, throwable)
    }

    companion object {
        private const val PERMISSION_STORAGE_REQUEST_CODE = 1
        private const val FILE_PICKER_REQUEST_CODE = 2
        private const val NBCONVERT_VERSION = "5.6.1"
    }
}
