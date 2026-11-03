package com.kidguard.app.util

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

object LocationHelper {

    fun hasLocationPermission(context: Context): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    @SuppressLint("MissingPermission")
    suspend fun getCurrentLocation(context: Context, timeoutMs: Long = 10_000L): Location? {
        if (!hasLocationPermission(context)) return null

        val locationManager =
            context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
                ?: return null

        val activeProvider = when {
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ->
                LocationManager.GPS_PROVIDER
            locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) ->
                LocationManager.NETWORK_PROVIDER
            else -> null
        }

        if (activeProvider != null) {
            val freshLocation = withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine<Location?> { cont ->
                    val listener = object : LocationListener {
                        override fun onLocationChanged(location: Location) {
                            locationManager.removeUpdates(this)
                            if (cont.isActive) {
                                cont.resume(location)
                            }
                        }

                        @Deprecated("Deprecated in Android API")
                        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                        override fun onProviderEnabled(provider: String) {}
                        override fun onProviderDisabled(provider: String) {}
                    }

                    try {
                        locationManager.requestLocationUpdates(
                            activeProvider,
                            0L,
                            0f,
                            listener,
                            Looper.getMainLooper()
                        )
                        cont.invokeOnCancellation {
                            locationManager.removeUpdates(listener)
                        }
                    } catch (_: SecurityException) {
                        if (cont.isActive) cont.resume(null)
                    } catch (_: IllegalArgumentException) {
                        if (cont.isActive) cont.resume(null)
                    }
                }
            }
            if (freshLocation != null) {
                return freshLocation
            }
        }

        return getBestLastKnownLocation(locationManager)
    }

    @SuppressLint("MissingPermission")
    private fun getBestLastKnownLocation(locationManager: LocationManager): Location? {
        val providers = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        )
        var bestLocation: Location? = null
        for (provider in providers) {
            val candidate = try {
                locationManager.getLastKnownLocation(provider)
            } catch (_: Exception) {
                null
            } ?: continue

            if (bestLocation == null || candidate.time > bestLocation.time) {
                bestLocation = candidate
            }
        }
        return bestLocation
    }

    fun buildGoogleMapsUrl(latitude: Double, longitude: Double): String {
        return "https://maps.google.com/?q=$latitude,$longitude"
    }
}
