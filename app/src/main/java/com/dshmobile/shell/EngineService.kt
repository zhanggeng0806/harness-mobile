package com.dshmobile.shell

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * 前台服务：持有内嵌引擎生命周期（后台时保活，用户可见通知）并在引擎
 * 进程死亡/卡死时重启（看门狗）。
 *
 * 相比原实现的一处修复：原版 ensureEngine() 在"引擎已在运行时"直接 return，
 * 导致常见路径下看门狗根本不会被武装；这里改为只要引擎就绪就武装看门狗，
 * 由 tick 自行判断是否需重启。
 *
 * 看门狗升级点：
 *  - 进程级存活判定（/proc/<pid>，比 HTTP 超时更快识别进程死亡）；
 *  - "活着但不响应"与"正在 boot"区分：超过 boot 上限仍无响应才判 wedged 并杀进程。
 */
class EngineService : Service() {

  private lateinit var engineManager: EngineManager
  private var watchdog: ScheduledExecutorService? = null
  private var unresponsiveTicks = 0

  override fun onCreate() {
    super.onCreate()
    engineManager = EngineManager(this)
    startAsForeground()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (intent?.action == ACTION_PAUSE) {
      engineManager.stopEngine()
      stopSelf()
      return START_NOT_STICKY
    }
    if (engineManager.engineReady) {
      // 幂等启动：引擎已在跑则 CAS + 冷却窗口兜底，不会双启。
      engineManager.startEngine()
      armWatchdog()
    }
    return START_STICKY
  }

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onDestroy() {
    watchdog?.shutdownNow()
    watchdog = null
    super.onDestroy()
  }

  private fun startAsForeground() {
    if (Build.VERSION.SDK_INT >= 29) {
      startForeground(
        NOTIFICATION_ID,
        buildNotification(),
        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
      )
    } else {
      startForeground(NOTIFICATION_ID, buildNotification())
    }
  }

  /** 只要引擎就绪就武装看门狗（与引擎当前是否在跑无关）。 */
  private fun armWatchdog() {
    if (watchdog == null) {
      watchdog = Executors.newSingleThreadScheduledExecutor().also { exec ->
        exec.scheduleWithFixedDelay(
          ::watchdogTick,
          WATCHDOG_INTERVAL_SEC,
          WATCHDOG_INTERVAL_SEC,
          TimeUnit.SECONDS,
        )
      }
    }
  }

  private fun watchdogTick() {
    if (!engineManager.engineReady) return
    val processAlive = engineManager.isEngineProcessAlive()
    val serving = EngineProbe.isRunning()
    when {
      processAlive && serving -> unresponsiveTicks = 0
      processAlive && !serving -> {
        // 进程活着但不响应：可能在 boot（冷却窗口内），也可能已卡死。
        unresponsiveTicks++
        if (unresponsiveTicks >= WEDGE_THRESHOLD_TICKS) {
          // 超过 boot 上限仍无响应 → 判定 wedged，杀进程（stopEngine 会重置
          // 冷却，下个 tick 立即重启）。
          engineManager.stopEngine()
          unresponsiveTicks = 0
        }
      }
      else -> {
        // 进程已死（或从未成功启动）：重启；EngineManager 冷却窗口兜底防双启。
        unresponsiveTicks = 0
        engineManager.startEngine()
      }
    }
  }

  private fun buildNotification(): android.app.Notification {
    val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
    if (Build.VERSION.SDK_INT >= 26) {
      manager.createNotificationChannel(
        NotificationChannel(CHANNEL_ID, "dsh 引擎", NotificationManager.IMPORTANCE_LOW),
      )
    }
    val open = PendingIntent.getActivity(
      this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
    )
    val pause = PendingIntent.getService(
      this, 1, Intent(this, EngineService::class.java).setAction(ACTION_PAUSE),
      PendingIntent.FLAG_IMMUTABLE,
    )
    return NotificationCompat.Builder(this, CHANNEL_ID)
      .setSmallIcon(android.R.drawable.stat_notify_chat)
      .setContentTitle("dsh 引擎运行中")
      .setContentText("DeepSeek Harness 正在后台工作")
      .setContentIntent(open)
      .addAction(0, "暂停", pause)
      .setOngoing(true)
      .build()
  }

  companion object {
    private const val NOTIFICATION_ID = 2
    private const val CHANNEL_ID = "engine"
    const val ACTION_PAUSE = "com.dshmobile.shell.action.PAUSE"

    private const val WATCHDOG_INTERVAL_SEC = 5L

    /** 5s * 12 = 60s，大于最长 boot（~45s）：此之后仍不响应才判 wedged。 */
    private const val WEDGE_THRESHOLD_TICKS = 12
  }
}
