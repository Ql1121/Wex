package wex.ui.core.feature

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import wex.ui.core.WexStore
import de.robv.android.xposed.XposedBridge
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 更新定位：获取当前经纬度 + 城市名，存入 WexStore 供天气查询用。
 * 定位链：系统 GPS/网络 provider 拿经纬度 → nominatim 逆地理转城市名；
 * 拿不到 GPS → ip-api 兜底（返回经纬度+城市名）。
 * 全部公开接口，无 key。
 */
object LocationHelper {

    /**
     * 点击"更新定位"入口。
     * @param act 微信当前 Activity（申请权限/拿系统服务）
     * @param onResult 结果回调（主线程）：success, msg
     */
    fun updateLocation(act: Activity, onResult: (Boolean, String) -> Unit) {
        // 检查定位权限，没有则申请（申请后本次先走 IP 兜底，下次再用 GPS）
        val hasPerm = act.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                act.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!hasPerm) {
            try { act.requestPermissions(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 0x7E02
            ) } catch (_: Exception) {}
        }
        Thread {
            var ok = false
            var msg = "定位失败"
            try {
                var lat = 0.0; var lon = 0.0; var city = ""
                // 1) 系统 GPS/网络 provider
                val gl = getLastLocation(act)
                if (gl != null) {
                    lat = gl.latitude; lon = gl.longitude
                    city = reverseGeo(lat, lon)
                    log("GPS 定位: $lat,$lon $city")
                } else {
                    // 2) IP 兜底
                    val ip = ipLocate()
                    if (ip != null) {
                        lat = ip.optDouble("lat", 0.0); lon = ip.optDouble("lon", 0.0)
                        city = ip.optString("city", "")
                        log("IP 定位: $lat,$lon $city")
                    }
                }
                if (lat != 0.0 && lon != 0.0) {
                    WexStore.putString("wp_lat", lat.toString())
                    WexStore.putString("wp_lon", lon.toString())
                    if (city.isNotEmpty()) WexStore.putString("weather_city", city)
                    // 清天气缓存时间，下次日历刷新会立即重新拉天气
                    WexStore.putString("weather_time", "0")
                    ok = true
                    msg = "定位成功：${if (city.isNotEmpty()) city else "%.3f,%.3f".format(lat, lon)}"
                }
            } catch (e: Exception) {
                log("更新定位异常: ${e.message}")
            }
            Handler(Looper.getMainLooper()).post { onResult(ok, msg) }
        }.start()
    }

    /** 系统 provider 拿最近一次定位（5分钟内有效） */
    private fun getLastLocation(ctx: Context): Location? {
        return try {
            val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
            val fresh = 5 * 60 * 1000L
            val now = System.currentTimeMillis()
            var loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            if (loc != null && now - loc.time < fresh) return loc
            loc = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            if (loc != null && now - loc.time < fresh) return loc
            // 退而求其次：即使旧一点也用
            loc ?: lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
        } catch (e: Exception) { log("getLastLocation 异常: ${e.message}"); null }
    }

    /** 逆地理：经纬度 → 城市名（nominatim，公开接口） */
    private fun reverseGeo(lat: Double, lon: Double): String {
        return try {
            val u = "https://nominatim.openstreetmap.org/reverse?lat=$lat&lon=$lon&format=json&accept-language=zh"
            val j = JSONObject(httpGet(u))
            val ad = j.optJSONObject("address") ?: return ""
            ad.optString("city", "").ifEmpty {
                ad.optString("state", "").ifEmpty {
                    ad.optString("county", "").ifEmpty { ad.optString("country", "") }
                }
            }
        } catch (e: Exception) { "" }
    }

    /** IP 定位兜底（ip-api，公开接口） */
    private fun ipLocate(): JSONObject? {
        return try {
            val u = "http://ip-api.com/json/?lang=zh-CN&fields=country,regionName,city,lat,lon"
            val j = JSONObject(httpGet(u))
            if (j.optString("city", "").isNotEmpty()) j else null
        } catch (e: Exception) { null }
    }

    private fun httpGet(urlStr: String): String {
        val c = URL(urlStr).openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 8000; c.requestMethod = "GET"
        c.setRequestProperty("User-Agent", "Mozilla/5.0")
        return c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    private fun log(msg: String) {
        XposedBridge.log("WEX: $msg")
        try { WexStore.appendLog(msg) } catch (_: Exception) {}
    }
}