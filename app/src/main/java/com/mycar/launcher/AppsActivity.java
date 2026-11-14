package com.mycar.launcher;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.util.LruCache;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/** Список всех приложений. Нажатие — открыть, удержание — настройки приложения. */
public class AppsActivity extends Activity {

    private final LruCache<String, Drawable> iconCache = new LruCache<>(80);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_apps);

        final PackageManager pm = getPackageManager();
        final List<AppUtils.AppEntry> apps = AppUtils.loadApps(pm, getPackageName());

        GridView grid = findViewById(R.id.grid);
        grid.setAdapter(new BaseAdapter() {
            @Override
            public int getCount() {
                return apps.size();
            }

            @Override
            public Object getItem(int position) {
                return apps.get(position);
            }

            @Override
            public long getItemId(int position) {
                return position;
            }

            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View v = convertView != null
                        ? convertView
                        : getLayoutInflater().inflate(R.layout.item_app, parent, false);
                AppUtils.AppEntry entry = apps.get(position);
                ((TextView) v.findViewById(R.id.app_label)).setText(entry.label);

                Drawable icon = iconCache.get(entry.pkg);
                if (icon == null) {
                    icon = entry.info.loadIcon(pm);
                    iconCache.put(entry.pkg, icon);
                }
                ((ImageView) v.findViewById(R.id.app_icon)).setImageDrawable(icon);
                return v;
            }
        });

        grid.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                if (!AppUtils.launch(AppsActivity.this, apps.get(position).pkg)) {
                    Toast.makeText(AppsActivity.this, R.string.open_failed, Toast.LENGTH_SHORT).show();
                }
            }
        });

        grid.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                Intent details = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + apps.get(position).pkg));
                try {
                    startActivity(details);
                } catch (Exception e) {
                    Toast.makeText(AppsActivity.this, R.string.open_failed, Toast.LENGTH_SHORT).show();
                }
                return true;
            }
        });

        findViewById(R.id.back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
    }
}
