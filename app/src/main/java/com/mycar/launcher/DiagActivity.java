package com.mycar.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.os.Handler;
import android.util.TypedValue;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

/** Ошибки двигателя и коробки: чтение и стирание одной кнопкой. */
public class DiagActivity extends Activity {

    private final Handler handler = new Handler();
    private float u;
    private TextView status;
    private TextView list;
    private boolean waitingClear;

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            update();
            handler.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_diag);
        u = getResources().getDisplayMetrics().heightPixels / 720f;

        status = findViewById(R.id.diag_status);
        list = findViewById(R.id.diag_list);
        px(status, 18);
        px(list, 20);
        px((TextView) findViewById(R.id.back), 20);
        px((TextView) findViewById(R.id.diag_title), 20);
        px((TextView) findViewById(R.id.btn_read), 20);
        px((TextView) findViewById(R.id.btn_clear), 20);

        findViewById(R.id.back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        findViewById(R.id.btn_read).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (ObdClient.get().state == ObdClient.CONNECTED) {
                    ObdClient.get().requestRead();
                }
            }
        });

        findViewById(R.id.btn_clear).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmClear();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (ObdClient.get().state == ObdClient.CONNECTED) ObdClient.get().requestRead();
        handler.post(refresh);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(refresh);
    }

    private void px(TextView t, float size) {
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, size * u);
    }

    private void confirmClear() {
        if (ObdClient.get().state != ObdClient.CONNECTED) {
            Toast.makeText(this, R.string.diag_not_connected, Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.diag_confirm_title)
                .setMessage(R.string.diag_confirm_msg)
                .setPositiveButton(R.string.diag_erase, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        waitingClear = true;
                        ObdClient.get().requestClear();
                    }
                })
                .setNegativeButton(R.string.diag_cancel, null)
                .show();
    }

    private void update() {
        ObdClient obd = ObdClient.get();

        if (waitingClear && obd.clearResult != 0) {
            int msg = obd.clearResult == 1 ? R.string.diag_cleared : R.string.diag_clear_fail;
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
            obd.clearResult = 0;
            waitingClear = false;
        }

        if (obd.state != ObdClient.CONNECTED) {
            status.setText(R.string.diag_not_connected);
            list.setText("");
            return;
        }
        if (obd.busy || obd.job != ObdClient.JOB_NONE) {
            status.setText(R.string.diag_reading);
            return;
        }

        status.setText(R.string.diag_ready);
        StringBuilder sb = new StringBuilder();
        appendCodes(sb, getString(R.string.diag_confirmed), obd.confirmed, true);
        String[] pend = obd.pending;
        if (pend.length > 0) {
            sb.append("\n");
            appendCodes(sb, getString(R.string.diag_pending), pend, false);
        }
        list.setText(sb.toString());
    }

    private void appendCodes(StringBuilder sb, String header, String[] codes, boolean showNone) {
        sb.append(header).append(" (").append(codes.length).append(")\n");
        if (codes.length == 0) {
            if (showNone) sb.append(getString(R.string.diag_none)).append('\n');
            return;
        }
        for (String code : codes) {
            String text = DtcInfo.describe(code);
            sb.append(code).append(" \u2014 ")
                    .append(text != null ? text : getString(R.string.diag_unknown))
                    .append('\n');
        }
    }
}
