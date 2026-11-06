package dev.dsh.harness;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


/** Pairing screen: host URL + one-shot token, persisted as a cookie (ADR-0006). */
public final class ConnectActivity extends Activity {
    private EditText urlField;
    private EditText tokenField;
    private TextView errorView;
    private TextView lastHost;
    private ProgressBar progress;
    private Button connectButton;
    private Button retryButton;
    private Prefs prefs;
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        DshLog.lifecycle("create");
        setContentView(R.layout.activity_connect);
        prefs = new Prefs(this);
        urlField = findViewById(R.id.connect_url_input);
        tokenField = findViewById(R.id.connect_token);
        errorView = findViewById(R.id.connect_error);
        lastHost = findViewById(R.id.connect_last);
        progress = findViewById(R.id.connect_progress);
        connectButton = findViewById(R.id.connect_pair_button);
        retryButton = findViewById(R.id.connect_retry_button);

        if (prefs.paired()) {
            urlField.setText(prefs.baseUrl());
            tokenField.setText(prefs.token());
            lastHost.setVisibility(View.VISIBLE);
            lastHost.setText("Use last host · " + prefs.baseUrl());
            lastHost.setOnClickListener(v -> {
                urlField.setText(prefs.baseUrl());
                tokenField.setText(prefs.token());
                connect();
            });
        } else {
            urlField.setText(getString(R.string.connect_url_hint));
        }

        connectButton.setOnClickListener(v -> connect());
        retryButton.setOnClickListener(v -> connect());
        if (applyIntent(getIntent())) return;
        if (state == null && prefs.paired()) {
            launchMain();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        applyIntent(intent);
    }

    private boolean applyIntent(Intent intent) {
        if (intent == null || intent.getData() == null) return false;
        Uri data = intent.getData();
        String scheme = data.getScheme();
        if (!"dsh".equals(scheme) && !"dshnative".equals(scheme)) return false;
        String url = data.getQueryParameter("url");
        String token = data.getQueryParameter("token");
        if ("dshnative".equals(scheme) && "debug".equals(data.getHost())) {
            Intent next = new Intent(this, MainActivity.class);
            next.setData(data);
            startActivity(next);
            finish();
            return true;
        }
        if ("dshnative".equals(scheme) && "decision".equals(data.getHost())) {
            Intent next = new Intent(this, MainActivity.class);
            next.setData(data);
            startActivity(next);
            finish();
            return true;
        }
        if (TextUtils.isEmpty(url) && TextUtils.isEmpty(token)) return false;
        if (!TextUtils.isEmpty(url)) urlField.setText(url);
        if (!TextUtils.isEmpty(token)) tokenField.setText(token);
        connect();
        return true;
    }

    private void connect() {
        String raw = urlField.getText().toString().trim();
        String token = tokenField.getText().toString().trim();
        String base;
        if (raw.contains("token=")) {
            Uri parsed = Uri.parse(raw);
            String queryToken = parsed.getQueryParameter("token");
            if (!TextUtils.isEmpty(queryToken)) token = queryToken;
            String authority = parsed.getAuthority();
            String scheme = parsed.getScheme() == null ? "http" : parsed.getScheme();
            base = authority == null ? "" : scheme + "://" + authority;
        } else {
            base = Prefs.normalize(raw);
        }
        if (TextUtils.isEmpty(base)) {
            showError(getString(R.string.connect_invalid_url));
            return;
        }
        final String finalBase = base;
        final String finalToken = token;
        progress.setVisibility(View.VISIBLE);
        errorView.setVisibility(View.GONE);
        retryButton.setVisibility(View.GONE);
        connectButton.setEnabled(false);
        io.execute(() -> {
            try {
                ApiClient api = new ApiClient(finalBase, prefs.cookie());
                boolean paired = api.pair(finalToken);
                runOnUiThread(() -> {
                    progress.setVisibility(View.GONE);
                    connectButton.setEnabled(true);
                    if (!paired) {
                        showError(getString(R.string.connect_failed));
                        return;
                    }
                    prefs.save(finalBase, finalToken, api.cookie());
                    DshLog.pairSuccess(finalBase);
                    launchMain();
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    progress.setVisibility(View.GONE);
                    connectButton.setEnabled(true);
                    showError(getString(R.string.connect_error_title) + ": " + error.getMessage());
                });
            }
        });
    }

    private void showError(String message) {
        errorView.setText(message);
        errorView.setVisibility(View.VISIBLE);
        retryButton.setVisibility(View.VISIBLE);
    }

    private void launchMain() {
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }
}
