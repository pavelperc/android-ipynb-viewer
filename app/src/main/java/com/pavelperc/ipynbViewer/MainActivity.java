package com.pavelperc.ipynbViewer;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.widget.TextView;
import android.widget.Toast;

import com.termux.R;
import com.termux.app.BackgroundJob;
import com.termux.app.TermuxActivity;
import com.termux.app.TermuxInstaller;
import com.termux.terminal.TerminalSession;
import com.termux.view.TerminalView;
import com.termux.view.TerminalViewClient;

import java.io.File;

import static com.termux.app.TermuxService.HOME_PATH;
import static com.termux.app.TermuxService.PREFIX_PATH;

class EmptyTerminalViewCallback implements TerminalViewClient {
    
    @Override
    public float onScale(float scale) {
        return 0;
    }
    
    @Override
    public void onSingleTapUp(MotionEvent e) {
        
    }
    
    @Override
    public boolean shouldBackButtonBeMappedToEscape() {
        return false;
    }
    
    @Override
    public void copyModeChanged(boolean copyMode) {
        
    }
    
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent e, TerminalSession session) {
        return false;
    }
    
    @Override
    public boolean onKeyUp(int keyCode, KeyEvent e) {
        return false;
    }
    
    @Override
    public boolean readControlKey() {
        return false;
    }
    
    @Override
    public boolean readAltKey() {
        return false;
    }
    
    @Override
    public boolean onCodePoint(int codePoint, boolean ctrlDown, TerminalSession session) {
        return false;
    }
    
    @Override
    public boolean onLongPress(MotionEvent event) {
        return false;
    }
}

public class MainActivity extends Activity implements TerminalSession.SessionChangedCallback {
    
    TerminalSession session;
    TerminalView terminalView;
    TextView tvLog;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        terminalView = findViewById(R.id.termLog);
        tvLog = findViewById(R.id.tvLog);
        tvLog.setMovementMethod(new ScrollingMovementMethod());
    
        terminalView.setTextSize(12);
        terminalView.setOnKeyListener(new EmptyTerminalViewCallback());
        
        
        TermuxInstaller.setupIfNeeded(this, () -> {
            session = createTermSession(null, null, null, false);
            terminalView.attachSession(session);
            
            session.write("echo hello\n");
            session.write("echo hello\n");
            session.write("echo hello\n");
            session.write("echo hello\n");
            
        });
        
        
        findViewById(R.id.btnTermuxActivity).setOnClickListener(v -> {
            Intent intent = new Intent(this, TermuxActivity.class);
            startActivity(intent);
        });
        findViewById(R.id.btnPrint).setOnClickListener(v -> {
            session.write("echo hello\n");
        });
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
        Intent stylingIntent = new Intent("com.termux.app.reload_style");
        stylingIntent.putExtra("com.termux.app.reload_style", "styling");
        sendBroadcast(stylingIntent);
        
        return session;
    }
    
    // TerminalSession.SessionChangedCallback:
    
    @Override
    public void onTextChanged(TerminalSession changedSession) {
        String text = changedSession.getEmulator().getScreen().getTranscriptText();
        tvLog.setText(text);
        Toast.makeText(this, "Text changed", Toast.LENGTH_SHORT).show();
        terminalView.onScreenUpdated();
    }
    
    @Override
    public void onTitleChanged(TerminalSession changedSession) {
        Toast.makeText(this, "Title changed: " + changedSession.getTitle(), Toast.LENGTH_SHORT).show();
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
        Toast.makeText(this, "On colors changed", Toast.LENGTH_SHORT).show();
    }
    
    // ----- 
    
    
    @Override
    protected void onDestroy() {
        if (session != null) {
            session.finishIfRunning();
        }
        super.onDestroy();
    }
}
