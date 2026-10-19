package com.frit.app.features.veil

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import android.util.Log
import com.frit.app.core.Diag

/**
 * Pickup detection for the stranger-pickup guard (Phase 2).
 *
 * Uses the hardware significant-motion trigger sensor: near-zero battery
 * cost, one-shot (re-armed after every fire). A fire only matters while a
 * task is actually running — VeilManager.onPickup() re-checks that gate, so
 * everyday motion while idle is ignored. Devices without the sensor simply
 * never trigger (logged once).
 */
object PickupMonitor {
    private const val TAG = "PickupMonitor"

    @Volatile var lastMotionMs = 0L
        private set

    private var sensorMgr: SensorManager? = null
    private var listener: TriggerEventListener? = null
    private var loggedMissing = false

    @Synchronized
    fun register(ctx: Context): Boolean {
        unregister()
        return try {
            val safe = ctx.applicationContext
            val sm = safe.getSystemService(Context.SENSOR_SERVICE) as SensorManager
            val sensor = sm.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)
            if (sensor == null) {
                if (!loggedMissing) {
                    loggedMissing = true
                    Log.i(TAG, "no significant-motion sensor — pickup guard inactive")
                }
                return false
            }
            val l = object : TriggerEventListener() {
                override fun onTrigger(event: TriggerEvent?) {
                    lastMotionMs = System.currentTimeMillis()
                    try {
                        VeilManager.onPickup(safe)
                    } catch (e: Exception) {
                        Log.w(TAG, "onTrigger", e)
                    } finally {
                        rearm(safe)
                    }
                }
            }
            sensorMgr = sm
            listener = l
            sm.requestTriggerSensor(l, sensor)
        } catch (e: Exception) {
            Log.w(TAG, "register", e)
            unregister()
            false
        }
    }

    @Synchronized
    fun unregister() {
        try {
            val l = listener
            val sm = sensorMgr
            if (l != null && sm != null) {
                val sensor = sm.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)
                if (sensor != null) sm.cancelTriggerSensor(l, sensor)
            }
        } catch (e: Exception) { Diag.warn("Veil", "unregister_sensor", e) }
        listener = null
        sensorMgr = null
    }

    private fun rearm(ctx: Context) {
        try {
            val sm = sensorMgr ?: return
            val l = listener ?: return
            val sensor = sm.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION) ?: return
            sm.cancelTriggerSensor(l, sensor)
            sm.requestTriggerSensor(l, sensor)
        } catch (e: Exception) {
            Log.w(TAG, "rearm", e)
        }
    }
}
