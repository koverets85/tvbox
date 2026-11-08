package io.github.koverets85.tvbox

import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.admin.DevicePolicyManager
import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Build
import android.os.Environment
import android.os.Process
import android.os.StatFs
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Diagnostics {
    private val INTERESTING = mapOf(
        "android.permission.RECORD_AUDIO" to "микрофон",
        "android.permission.CAMERA" to "камера",
        "android.permission.ACCESS_FINE_LOCATION" to "геолокация",
        "android.permission.ACCESS_COARSE_LOCATION" to "геолокация",
        "android.permission.READ_CONTACTS" to "контакты",
        "android.permission.READ_EXTERNAL_STORAGE" to "файлы",
        "android.permission.WRITE_EXTERNAL_STORAGE" to "файлы",
        "android.permission.SYSTEM_ALERT_WINDOW" to "поверх окон",
        "android.permission.REQUEST_INSTALL_PACKAGES" to "ставит программы",
        "android.permission.INSTALL_PACKAGES" to "ставит программы(сист)",
        "android.permission.DELETE_PACKAGES" to "удаляет программы",
        "android.permission.READ_LOGS" to "читает журналы",
        "android.permission.PACKAGE_USAGE_STATS" to "статистика",
        "android.permission.WRITE_SECURE_SETTINGS" to "сист.настройки",
    )

    fun hasUsageAccess(ctx: Context): Boolean {
        val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        @Suppress("DEPRECATION")
        val mode = if (Build.VERSION.SDK_INT >= 29)
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        else ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun mb(b: Long) = String.format(Locale.US, "%.1f МБ", b / 1048576.0)
    private val df = SimpleDateFormat("dd.MM.yy HH:mm", Locale.US)
    private fun date(t: Long) = if (t <= 0) "-" else df.format(Date(t))

    fun build(ctx: Context): String {
        val pm = ctx.packageManager
        val sb = StringBuilder()
        fun line(s: String = "") = sb.append(s).append('\n')

        line("ОТЧЁТ ПРИСТАВКИ  ${date(System.currentTimeMillis())}")
        line("Модель: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}, ${Build.BOARD}, ${Build.HARDWARE})")
        line("Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}), патч ${Build.VERSION.SECURITY_PATCH}")
        line("Сборка: ${Build.DISPLAY}")
        line("Отпечаток: ${Build.FINGERPRINT}")
        line("ABI: ${Build.SUPPORTED_ABIS.joinToString()}")
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        line("ОЗУ: свободно ${mb(mi.availMem)} из ${mb(mi.totalMem)}")
        val st = StatFs(Environment.getDataDirectory().path)
        line("Память: свободно ${mb(st.availableBytes)} из ${mb(st.totalBytes)}")
        line("Аптайм: ${android.os.SystemClock.elapsedRealtime() / 60000} мин")

        // network / DNS
        try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val lp = cm.getLinkProperties(cm.activeNetwork)
            line("Сеть: ${lp?.interfaceName}, DNS: ${lp?.dnsServers?.joinToString { it.hostAddress ?: "?" }}, " +
                "частный DNS: ${Settings.Global.getString(ctx.contentResolver, "private_dns_mode")} " +
                "${Settings.Global.getString(ctx.contentResolver, "private_dns_specifier") ?: ""}")
        } catch (e: Exception) { line("Сеть: ошибка ${e.message}") }

        val home = pm.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
        line("Главный экран (лаунчер): ${home?.activityInfo?.packageName}")
        val homes = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            .map { it.activityInfo.packageName }
        line("Все лаунчеры: ${homes.joinToString()}")
        line("Анимации: окна=${Settings.Global.getString(ctx.contentResolver, "window_animation_scale")} " +
            "переходы=${Settings.Global.getString(ctx.contentResolver, "transition_animation_scale")} " +
            "аниматор=${Settings.Global.getString(ctx.contentResolver, "animator_duration_scale")}")
        line("ADB: ${Settings.Global.getString(ctx.contentResolver, "adb_enabled")}, " +
            "беспроводная отладка: ${Settings.Global.getString(ctx.contentResolver, "adb_wifi_enabled")}")
        line("Часовой пояс: ${java.util.TimeZone.getDefault().id}")

        val admins = (ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager)
            .activeAdmins?.map { it.packageName } ?: emptyList()
        line("Администраторы устройства: ${admins.joinToString().ifEmpty { "нет" }}")
        val acc = (ctx.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager)
            .getEnabledAccessibilityServiceList(-1).map { it.resolveInfo.serviceInfo.packageName }
        line("Спец.возможности (видят экран): ${acc.joinToString().ifEmpty { "нет" }}")
        line()

        // boot receivers
        val boot = pm.queryBroadcastReceivers(Intent(Intent.ACTION_BOOT_COMPLETED), PackageManager.MATCH_DISABLED_COMPONENTS)
            .map { it.activityInfo.packageName }.toSet()

        // usage + traffic
        val usage = HashMap<String, Pair<Long, Long>>() // pkg -> (fg ms, last used)
        val traffic = HashMap<Int, LongArray>()         // uid -> [rx, tx]
        val week = 7L * 24 * 3600 * 1000
        val now = System.currentTimeMillis()
        val access = hasUsageAccess(ctx)
        if (access) {
            try {
                val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
                usm.queryAndAggregateUsageStats(now - week, now).forEach { (p, s) ->
                    usage[p] = s.totalTimeInForeground to s.lastTimeUsed
                }
            } catch (e: Exception) { line("Статистика использования: ошибка ${e.message}") }
            try {
                val nsm = ctx.getSystemService(Context.NETWORK_STATS_SERVICE) as NetworkStatsManager
                @Suppress("DEPRECATION")
                for (type in intArrayOf(ConnectivityManager.TYPE_WIFI, ConnectivityManager.TYPE_ETHERNET)) {
                    try {
                        val stats = nsm.querySummary(type, null, now - week, now)
                        val b = NetworkStats.Bucket()
                        while (stats.hasNextBucket()) {
                            stats.getNextBucket(b)
                            val a = traffic.getOrPut(b.uid) { LongArray(2) }
                            a[0] += b.rxBytes; a[1] += b.txBytes
                        }
                        stats.close()
                    } catch (e: Exception) { /* type not present */ }
                }
            } catch (e: Exception) { line("Трафик: ошибка ${e.message}") }
        } else {
            line("!! Нет доступа к истории использования — трафик и запуски не видны")
        }

        if (traffic.isNotEmpty()) {
            line("== ТРАФИК ЗА 7 ДНЕЙ (вниз/вверх), топ 25 ==")
            traffic.entries.sortedByDescending { it.value[0] + it.value[1] }.take(25).forEach { (uid, v) ->
                val names = when (uid) {
                    NetworkStats.Bucket.UID_REMOVED -> "удалённые"
                    NetworkStats.Bucket.UID_TETHERING -> "раздача"
                    Process.SYSTEM_UID -> "system"
                    else -> pm.getPackagesForUid(uid)?.joinToString() ?: "uid $uid"
                }
                line("${mb(v[0])} / ${mb(v[1])}  $names")
            }
            line()
        }

        @Suppress("DEPRECATION")
        val pkgs: List<PackageInfo> = pm.getInstalledPackages(PackageManager.GET_PERMISSIONS or PackageManager.MATCH_DISABLED_COMPONENTS)
        val user = pkgs.filter { it.applicationInfo?.let { a -> a.flags and ApplicationInfo.FLAG_SYSTEM == 0 } ?: false }
        val sys = pkgs - user.toSet()

        fun describe(p: PackageInfo): String {
            val a = p.applicationInfo!!
            val label = try { a.loadLabel(pm).toString() } catch (e: Exception) { "" }
            val tags = mutableListOf<String>()
            if (!a.enabled) tags += "ВЫКЛ"
            if (a.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0) tags += "обновл."
            if (p.packageName in boot) tags += "автозапуск"
            if (p.packageName in homes) tags += "лаунчер"
            if (p.packageName in admins) tags += "АДМИН"
            if (p.packageName in acc) tags += "СПЕЦ.ВОЗМ"
            val perms = p.requestedPermissions ?: emptyArray()
            val flags = p.requestedPermissionsFlags ?: IntArray(0)
            if ("android.permission.INTERNET" in perms) tags += "сеть"
            val granted = perms.indices.filter { flags.getOrElse(it) { 0 } and PackageInfo.REQUESTED_PERMISSION_GRANTED != 0 }
                .mapNotNull { INTERESTING[perms[it]] }.toSet()
            if (granted.isNotEmpty()) tags += "права: " + granted.joinToString("/")
            val launch = pm.getLeanbackLaunchIntentForPackage(p.packageName) != null || pm.getLaunchIntentForPackage(p.packageName) != null
            if (launch) tags += "ярлык"
            usage[p.packageName]?.let { (fg, last) -> if (fg > 0 || last > 0) tags += "экран ${fg / 60000}мин, посл. ${date(last)}" }
            traffic[a.uid]?.let { tags += "трафик ${mb(it[0] + it[1])}" }
            return "${p.packageName} [$label] v${p.versionName}  ${tags.joinToString(", ")}"
        }

        line("== УСТАНОВЛЕННЫЕ ПОЛЬЗОВАТЕЛЕМ (${user.size}) ==")
        user.sortedBy { it.packageName }.forEach { line(describe(it)); line("   уст. ${date(it.firstInstallTime)}, обн. ${date(it.lastUpdateTime)}") }
        line()
        line("== СИСТЕМНЫЕ (${sys.size}) ==")
        sys.sortedBy { it.packageName }.forEach { line(describe(it)) }
        return sb.toString()
    }
}
