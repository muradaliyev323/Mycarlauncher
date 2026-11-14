package com.mycar.launcher;

import android.content.Context;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;

/** Скорость по GPS магнитолы — запасной источник, если OBD-адаптер не отвечает. */
final class GpsSpeed implements LocationListener {

    private final LocationManager manager;
    private volatile float kmh = -1f;
    private volatile long lastFix = 0L;
    private boolean running;

    GpsSpeed(Context context) {
        manager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
    }

    void start() {
        if (manager == null || running) return;
        try {
            manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this);
            running = true;
        } catch (SecurityException e) {
            // нет разрешения на геолокацию
        } catch (IllegalArgumentException e) {
            // на этой магнитоле нет GPS
        }
    }

    void stop() {
        if (manager == null || !running) return;
        try {
            manager.removeUpdates(this);
        } catch (Exception ignored) {
            // ничего
        }
        running = false;
    }

    boolean hasFix() {
        return System.currentTimeMillis() - lastFix < 5000L;
    }

    int speedKmh() {
        return kmh < 0 ? -1 : Math.round(kmh);
    }

    @Override
    public void onLocationChanged(Location location) {
        lastFix = System.currentTimeMillis();
        if (location.hasSpeed()) {
            kmh = location.getSpeed() * 3.6f;
        }
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {}

    @Override
    public void onProviderEnabled(String provider) {}

    @Override
    public void onProviderDisabled(String provider) {}
}
