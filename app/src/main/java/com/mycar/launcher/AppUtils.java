package com.mycar.launcher;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Небольшие помощники: список приложений, запуск, подбор приложения по названию. */
final class AppUtils {

    private AppUtils() {}

    static final class AppEntry {
        final String label;
        final String pkg;
        final ResolveInfo info;

        AppEntry(String label, String pkg, ResolveInfo info) {
            this.label = label;
            this.pkg = pkg;
            this.info = info;
        }
    }

    /** Все приложения, которые можно запустить, кроме самого лаунчера. */
    static List<AppEntry> loadApps(PackageManager pm, String selfPkg) {
        Intent main = new Intent(Intent.ACTION_MAIN);
        main.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> infos = pm.queryIntentActivities(main, 0);

        Map<String, AppEntry> byPkg = new LinkedHashMap<>();
        for (ResolveInfo ri : infos) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(selfPkg) || byPkg.containsKey(pkg)) continue;
            CharSequence label = ri.loadLabel(pm);
            byPkg.put(pkg, new AppEntry(label == null ? pkg : label.toString(), pkg, ri));
        }

        List<AppEntry> result = new ArrayList<>(byPkg.values());
        Collections.sort(result, new Comparator<AppEntry>() {
            @Override
            public int compare(AppEntry a, AppEntry b) {
                return a.label.compareToIgnoreCase(b.label);
            }
        });
        return result;
    }

    static boolean launch(Context context, String pkg) {
        Intent intent = context.getPackageManager().getLaunchIntentForPackage(pkg);
        if (intent == null) return false;
        try {
            context.startActivity(intent);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Короткие слова (fm, bt) ищем как отдельные слова, длинные — как часть названия. */
    static boolean matches(String label, String keyword) {
        String l = label.toLowerCase(Locale.ROOT);
        if (keyword.length() <= 3) {
            for (String word : l.split("[^\\p{L}\\p{N}]+")) {
                if (word.equals(keyword)) return true;
            }
            return false;
        }
        return l.contains(keyword);
    }

    /** Сначала известные пакеты, потом поиск по названию. Если ничего нет — null. */
    static String guess(List<AppEntry> apps, String[] knownPackages, String[] keywords) {
        for (String p : knownPackages) {
            for (AppEntry a : apps) {
                if (a.pkg.equals(p)) return p;
            }
        }
        for (String k : keywords) {
            for (AppEntry a : apps) {
                if (matches(a.label, k)) return a.pkg;
            }
        }
        return null;
    }
}
