package com.mossreader.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.mossreader.MainActivity
import com.mossreader.R
import com.mossreader.ReaderApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** 前台服务：保证锁屏后继续朗读，并提供通知栏控制。 */
class PlaybackService : Service() {
    companion object {
        private const val CHANNEL = "playback"
        private const val NOTIF_ID = 42
        const val ACTION_TOGGLE = "com.mossreader.TOGGLE"
        const val ACTION_NEXT = "com.mossreader.NEXT"
        const val ACTION_PREV = "com.mossreader.PREV"
        const val ACTION_STOP = "com.mossreader.STOP"

        fun start(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, PlaybackService::class.java))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private var wake: PowerManager.WakeLock? = null
    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) { ReaderApp.instance.controller.pause() }
    }

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "朗读播放", NotificationManager.IMPORTANCE_LOW))
        val controller = ReaderApp.instance.controller
        startForeground(NOTIF_ID, build(controller.state.value), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MossReader:tts").apply {
            setReferenceCounted(false); acquire(6 * 60 * 60 * 1000L)
        }
        ContextCompat.registerReceiver(this, noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_EXPORTED)
        job = scope.launch {
            controller.state.collect { st ->
                if (st.status == PlayStatus.IDLE) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
                else nm.notify(NOTIF_ID, build(st))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val c = ReaderApp.instance.controller
        when (intent?.action) {
            ACTION_TOGGLE -> c.toggle()
            ACTION_NEXT -> c.nextSegment()
            ACTION_PREV -> c.prevSegment()
            ACTION_STOP -> c.stopPlayback()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        job?.cancel(); scope.cancel()
        runCatching { unregisterReceiver(noisy) }
        runCatching { wake?.release() }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun action(code: Int, act: String, icon: Int, title: String): NotificationCompat.Action {
        val pi = PendingIntent.getService(this, code, Intent(this, PlaybackService::class.java).setAction(act), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Action(icon, title, pi)
    }

    private fun build(st: ReaderState): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val chapter = st.chapters.getOrNull(st.chapterIndex)?.title.orEmpty()
        val total = st.plan?.segments?.size ?: 0
        val playing = st.status == PlayStatus.PLAYING || st.status == PlayStatus.BUFFERING || st.status == PlayStatus.LOADING
        val text = when {
            st.status == PlayStatus.LOADING -> "正在准备…"
            st.status == PlayStatus.BUFFERING -> "缓冲中…  $chapter"
            else -> "$chapter  ${st.segmentIndex + 1}/$total"
        }
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(st.bookTitle.ifEmpty { "小说朗读" })
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(action(1, ACTION_PREV, android.R.drawable.ic_media_previous, "上一段"))
            .addAction(action(2, ACTION_TOGGLE, if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play, if (playing) "暂停" else "播放"))
            .addAction(action(3, ACTION_NEXT, android.R.drawable.ic_media_next, "下一段"))
            .addAction(action(4, ACTION_STOP, android.R.drawable.ic_menu_close_clear_cancel, "停止"))
            .build()
    }
}
