package com.pavelperc.ipynbViewer;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.content.FileProvider;

import com.hbisoft.pickit.PickiT;
import com.hbisoft.pickit.PickiTCallbacks;
import com.termux.R;
import com.termux.app.BackgroundJob;
import com.termux.app.TermuxActivity;
import com.termux.app.TermuxInstaller;
import com.termux.terminal.TerminalBuffer;
import com.termux.terminal.TerminalSession;

import java.io.File;

import static com.termux.app.TermuxService.HOME_PATH;
import static com.termux.app.TermuxService.PREFIX_PATH;


public class MainActivity extends Activity implements TerminalSession.SessionChangedCallback, PickiTCallbacks {
    private static final int PERMISSION_STORAGE_REQUEST_CODE = 1;
    private static final int FILE_PICKER_REQUEST_CODE = 2;
    
    TerminalSession session;
    TextView tvLog;
    Button btnConvert;
    TextView tvStatus;
    
    PickiT pickiT;
    
    private String lastConvertedFilePath = "";
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        pickiT = new PickiT(this, this);
        
        TermuxInstaller.setupStorageSymlinks(this);
        
        tvLog = findViewById(R.id.tvLog);
        tvStatus = findViewById(R.id.tvStatus);
        tvLog.setMovementMethod(new ScrollingMovementMethod());
        
        btnConvert = findViewById(R.id.btnConvert);
        btnConvert.setEnabled(false);
        
        ensureStoragePermissionGranted();
        
        tvStatus.setText("Unknown");
        
        TermuxInstaller.setupIfNeeded(this, () -> {
            session = createTermSession(null, null, null, false);
            session.updateSize(30, 10);
            session.reset();
            session.write("echo HELLO\n");
        });
        
        
        findViewById(R.id.btnTermuxActivity).setOnClickListener(v -> {
            Intent intent = new Intent(this, TermuxActivity.class);
            startActivity(intent);
        });
        
        btnConvert.setOnClickListener(v -> {
            selectFile();
        });
    }
    
    private void checkConverter() {
        tvStatus.setText("Checking converter");
        session.write("jupyter nbconvert --version\n");
        session.write("echo DONE_CHECKING\n");
    }
    
    // check after install
    private void checkConverterAgain() {
        tvStatus.setText("Checking converter");
        session.write("jupyter nbconvert --version\n");
        session.write("echo DONE_CHECKING_AGAIN\n");
    }
    
    private boolean startedInstallingPython = false;
    
    private void loadPython() {
        startedInstallingPython = true;
        tvStatus.setText("Installing Python");
        session.write("pkg install python\n");
        session.write("Y\n");
    }
    
    private void loadNBConvert() {
//        session.write("termux-setup-storage\n");
        tvStatus.setText("Installing nbconvert");
        session.write("pip install nbconvert==" + NBCONVERT_VERSION + "\n");
        session.write("echo DONE_LOADING\n");
    }
    
    /**
     * For processes to access shared internal storage (/sdcard) we need this permission.
     */
    public boolean ensureStoragePermissionGranted() {
        if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
            return true;
        } else {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, PERMISSION_STORAGE_REQUEST_CODE);
            return false;
        }
    }
    
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String permissions[], @NonNull int[] grantResults) {
        if (requestCode == PERMISSION_STORAGE_REQUEST_CODE && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            // OK
//            TermuxInstaller.setupStorageSymlinks(this);
        }
    }
    
    
    TerminalSession createTermSession(String executablePath, String[] arguments, String cwd, boolean failSafe) {
        new File(HOME_PATH).mkdirs();
        
        if (cwd == null) cwd = HOME_PATH;
        
        String[] env = BackgroundJob.buildEnvironment(failSafe, cwd);
        boolean isLoginShell = false;
        
        if (executablePath == null) {
            if (!failSafe) {
                for (String shellBinary : new String[]{"login", "bash", "zsh"}) {
                    File shellFile = new File(PREFIX_PATH + "/bin/" + shellBinary);
                    if (shellFile.canExecute()) {
                        executablePath = shellFile.getAbsolutePath();
                        break;
                    }
                }
            }
            
            if (executablePath == null) {
                // Fall back to system shell as last resort:
                executablePath = "/system/bin/sh";
            }
            isLoginShell = true;
        }
        
        String[] processArgs = BackgroundJob.setupProcessArgs(executablePath, arguments);
        executablePath = processArgs[0];
        int lastSlashIndex = executablePath.lastIndexOf('/');
        String processName = (isLoginShell ? "-" : "") +
            (lastSlashIndex == -1 ? executablePath : executablePath.substring(lastSlashIndex + 1));
        
        String[] args = new String[processArgs.length];
        args[0] = processName;
        if (processArgs.length > 1)
            System.arraycopy(processArgs, 1, args, 1, processArgs.length - 1);
        
        TerminalSession session = new TerminalSession(executablePath, cwd, args, env, this);
//        mTerminalSessions.add(session);
//        updateNotification();
        
        // Make sure that terminal styling is always applied.
//        Intent stylingIntent = new Intent("com.termux.app.reload_style");
//        stylingIntent.putExtra("com.termux.app.reload_style", "styling");
//        sendBroadcast(stylingIntent);
        
        return session;
    }
    
    private static String NBCONVERT_VERSION = "5.6.1";
    
    // TerminalSession.SessionChangedCallback:
    
    @Override
    public void onTextChanged(TerminalSession changedSession) {
        TerminalBuffer screen = changedSession.getEmulator().getScreen();
        String text = screen.getTranscriptText();
        tvLog.setText(text);
        if (text.endsWith("HELLO\n$")) {
            checkConverter();
        } else if (text.endsWith("DONE_LOADING\n$")) {
            checkConverterAgain();
        } else if (text.endsWith("DONE_CONVERTING\n$")) {
            tvStatus.setText("Done converting");
            btnConvert.setEnabled(true);
            
            openConvertedFile(new File(lastConvertedFilePath));
            
        } else if (text.endsWith("DONE_CHECKING\n$") || text.endsWith("DONE_CHECKING_AGAIN\n$")) {
            String ending = text.substring(Math.max(0, text.length() - 100));
            if (!ending.contains(NBCONVERT_VERSION)) {
                
                if (text.endsWith("DONE_CHECKING_AGAIN\n$")) {
                    installFailureDialog(() -> loadPython());
                } else {
                    loadPython();
                }
            } else {
                tvStatus.setText("Converter is ready");
                btnConvert.setEnabled(true);
            }
        } else if (text.endsWith("$")) {
            // No feedback after installing python.
            if (startedInstallingPython) {
                startedInstallingPython = false;
                loadNBConvert();
            } else {
                tvStatus.setText("Some error. Restart the app.");
            }
            
        }
        scrollLog();
    }
    
    private void openConvertedFile(File file) {
        try {
            Uri uri = FileProvider.getUriForFile(this, "com.pavelperc.ipynbViewer.fileprovider", file);
//            Uri uri = Uri.fromFile(file);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setData(uri);
            intent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(intent);
            
        } catch (Exception e) {
            Log.e("my_tag", "error", e);
        }
    }
    
    private void installFailureDialog(Runnable onSuccess) {
        new AlertDialog.Builder(this)
            .setTitle("Failed to install libraries.")
            .setMessage("Try again?")
            .setNegativeButton("No", null)
            .setPositiveButton("Yes", (dialog, which) -> onSuccess.run())
            .setCancelable(false)
            .create()
            .show();
    }
    
    private void scrollLog() {
        if (tvLog.getLayout() == null) {
            return;
        }
        final int scrollAmount = tvLog.getLayout().getLineTop(tvLog.getLineCount()) - tvLog.getHeight();
        // if there is no need to scroll, scrollAmount will be <=0
        if (scrollAmount > 0)
            tvLog.scrollTo(0, scrollAmount);
        else
            tvLog.scrollTo(0, 0);
    }
    
    @Override
    public void onTitleChanged(TerminalSession changedSession) {
//        Toast.makeText(this, "Title changed: " + changedSession.getTitle(), Toast.LENGTH_SHORT).show();
    }
    
    @Override
    public void onSessionFinished(TerminalSession finishedSession) {
        Toast.makeText(this, "Session Finished", Toast.LENGTH_SHORT).show();
    }
    
    @Override
    public void onClipboardText(TerminalSession session, String text) {
        
    }
    
    @Override
    public void onBell(TerminalSession session) {
        
    }
    
    @Override
    public void onColorsChanged(TerminalSession session) {
//        Toast.makeText(this, "On colors changed", Toast.LENGTH_SHORT).show();
    }
    
    private void selectFile() {
        Intent intent = new Intent();
        intent.setType("*/*");
        intent.setAction(Intent.ACTION_GET_CONTENT);
        
        startActivityForResult(Intent.createChooser(intent, "Select File"), FILE_PICKER_REQUEST_CODE);
    }
    
    private Uri originalFileUri;
    
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == FILE_PICKER_REQUEST_CODE && resultCode == RESULT_OK && data != null) {
            Log.d("my_tag", "Original uri: " + data.getDataString());
            pickiT.getPath(data.getData(), Build.VERSION.SDK_INT);
        }
    }
    
    @Override
    public void PickiTonCompleteListener(String path, boolean wasDriveFile, boolean wasUnknownProvider, boolean wasSuccessful, String Reason) {
        if (!wasSuccessful) {
            Toast.makeText(this, "Can not find file:\n" + Reason, Toast.LENGTH_LONG).show();
            return;
        }
        Log.d("my_tag", "File path: " + path);
        convertFile(path);
    }
    
    private void convertFile(String path) {
        tvStatus.setText("Converting");
        btnConvert.setEnabled(false);
        File file = new File(path);
//        String fileName = file.getName() + "-" + file.lastModified() + ".html";
        String fileName = file.getName() + ".html";
        
        session.write("jupyter nbconvert --output-dir=storage/temp --output=\"" + fileName + "\" \"" +
            file.getAbsolutePath() + "\"\n" +
            "echo DONE_CONVERTING\n");
        
        lastConvertedFilePath = getExternalCacheDir().getAbsolutePath() + "/" + fileName;
        Log.d("my_tag", "see file in: " + lastConvertedFilePath);
    }
    
    
    @Override
    public void PickiTonStartListener() {
    }
    
    @Override
    public void PickiTonProgressUpdate(int progress) {
    }
    
    @Override
    protected void onDestroy() {
        if (session != null) {
            session.finishIfRunning();
        }
        super.onDestroy();
    }
}
