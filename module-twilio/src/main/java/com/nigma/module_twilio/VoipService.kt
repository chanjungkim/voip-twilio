package com.nigma.module_twilio

import android.Manifest
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.nigma.lib_audio_router.AudioRoutingManager
import com.nigma.lib_audio_router.model.AudioDevice.EARPIECE
import com.nigma.lib_audio_router.model.AudioDevice.SPEAKER
import com.nigma.module_twilio.exception.*
import com.nigma.module_twilio.manager.VoipNotificationManager
import com.nigma.module_twilio.twilio.TwilioManager
import com.nigma.module_twilio.twilio.createAudioTrack
import com.nigma.module_twilio.twilio.createDataTrack
import com.nigma.module_twilio.twilio.createVideoTrack
import com.nigma.module_twilio.ui.VoipActivity
import com.nigma.module_twilio.utils.*
import com.twilio.video.*
import timber.log.Timber
import java.lang.ref.WeakReference

class VoipService : Service(), VoipServiceContract {


    var user: User? = null

    val mediaManager
        get() = twilioManager.localMediaManager

    val twilioManager by lazy {
        TwilioManager(this, capturer)
    }

    val audioRouteManager by lazy {
        AudioRoutingManager(applicationContext, clickHandler)
    }

    private val capturer by lazy {
        CameraCapturer(
            applicationContext,
            CameraCapturer.CameraSource.FRONT_CAMERA
        )
    }

    private val binder = VoipServiceBinder(this,)

    private val clickHandler = VoipServiceActionHandler(binder)

    private val exceptionHandler = VoipServiceExceptionHandler(this)

    private val notiManager = VoipNotificationManager(this)

    private val callback
        get() = binder.participantCallback

    private val localCallback
        get() = binder.voipLocalMediaStateCallback


    override fun onBind(intent: Intent): IBinder? {
        return binder
    }

    override fun onCreate() {
        super.onCreate()
        Timber.i("onCreate")
        Thread.setDefaultUncaughtExceptionHandler(exceptionHandler)
    }

    override fun onDestroy() {
        Timber.i("onDestroy")
        audioRouteManager.release()
        stopForeground(true)
        super.onDestroy()
    }

    @Throws(Exception::class)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Timber.i("onStartCommand action -> ${intent?.action}")
        intent ?: return super.onStartCommand(intent, flags, startId)
        try {
            when (intent.action) {
                ACTION_VOIP_CONNECT_ROOM -> handleConnectRoom(intent)

                ACTION_VOIP_DISCONNECT_ROOM -> twilioManager.disconnectRoom()

                ACTION_VOIP_HANDLE_AUDIO_OUTPUT -> clickHandler.routeAudio()

                ACTION_VOIP_HANDLE_MIC -> clickHandler.onOffMic()

                ACTION_VOIP_HANDLE_CAMERA -> clickHandler.onOffCamera()

                ACTION_VOIP_HANDLE_CAMERA_FLIP -> clickHandler.flipCamera()

                ACTION_VOIP_HANDLE_CHANGE_AUDIO_DEVICE -> clickHandler.selectAudioDevice(intent)
            }
        } catch (e: Exception) {
            exceptionHandler.handleException(e)
        }
        return START_NOT_STICKY
    }

    /*region ROOM related callback*/
    @Throws(VoipException::class)
    private fun handleConnectRoom(intent: Intent) {

        val dataTrack = createDataTrack(WeakReference(applicationContext))
            ?: throw CreateDataTrackFailedException()

        val audioTrack =
            if (packageManager.checkPermission(Manifest.permission.RECORD_AUDIO, packageName) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                throw AudioPermissionException()
            } else {
                createAudioTrack(WeakReference(applicationContext))
                    ?: throw CreateAudioTrackFailedException()
            }
        with(intent.getBooleanExtra(KEY_IS_VOIP_VIDEO_TYPE, false)) {
            user = intent.getSerializableExtra(KEY_USER_OBJECT) as User
            audioRouteManager.userDefaultDevice = (if (this) SPEAKER else EARPIECE)
            val videoTrack = if (this) {
                if (packageManager.checkPermission(Manifest.permission.RECORD_AUDIO, packageName) !=
                    PackageManager.PERMISSION_GRANTED
                ) {
                    throw CameraPermissionException()
                } else {
                    createVideoTrack(WeakReference(applicationContext), capturer)
                        ?: throw CreateVideoTrackFailedException()
                }
            } else {
                null
            }
            twilioManager.connectRoom(
                applicationContext,
                intent.getStringExtra(KEY_ACCESS_TOKEN),
                audioTrack,
                dataTrack,
                videoTrack
            )
            notiManager.initialForeground()
        }
        audioRouteManager.start()
        with(Intent(applicationContext, VoipActivity::class.java)) {
            putExtras(intent)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(this)
        }
    }

    override fun onCommunicationCommand(message: String) {
        toast(message)
    }

    override fun onRoomConnected(room: Room) {
        Handler(Looper.getMainLooper()).postDelayed({
            if (room.remoteParticipants.size == 0) {
                toast("nobody connect the room ,i will exit")
                twilioManager.disconnectRoom()
            }
        }, 30 * 1000)
    }

    override fun onRoomConnectStateChange(room: Room, twilioException: TwilioException?) {
        localCallback?.onConnectionStateChange(room.state.name)
        if (room.state == Room.State.DISCONNECTED){
            twilioManager.localMediaManager.releaseLocalMediaTrack()
            stopSelf()
        }
        /*if (room.remoteParticipants.size == 0) {
            Handler(Looper.getMainLooper()).postDelayed({
                toast("need to exit the room")
                twilioManager.disconnectRoom()
            }, 30 * 1000)
        }*/
    }

    override fun onParticipantDisconnected(room: Room, remoteParticipant: RemoteParticipant) {
        toast("user ${remoteParticipant.identity} disconnected")
        Handler(Looper.getMainLooper()).postDelayed({
            if (room.remoteParticipants.size == 0) {
                toast("nobody is here i will leave the room")
                twilioManager.disconnectRoom()
            }
        }, 30 * 1000)
    }

    override fun onRoomConnectFailure(room: Room, twilioException: TwilioException) {
        Timber.e(
            twilioException,
            "onRoomConnectFailure -> ${room.state} "
        )
        stopSelf()
    }

    override fun onTrackSubscriptionFailed(
        participant: Participant,
        publication: TrackPublication,
        twilioException: TwilioException
    ) {
        Timber.e(
            twilioException,
            "onTrackSubscriptionFailed -> ${participant.identity} | track ${publication.trackName}"
        )
    }

    override fun onTrackPublicationFailed(
        participant: Participant,
        track: Track,
        twilioException: TwilioException
    ) {
        Timber.e(
            twilioException,
            "onTrackPublicationFailed -> ${participant.identity} | track ${track.name}"
        )
    }

    override fun onNetworkQualityLevelChanged(
        remoteParticipant: RemoteParticipant,
        networkQualityLevel: NetworkQualityLevel
    ) {
        callback?.onParticipantNetworkStateChange(remoteParticipant, networkQualityLevel)
    }
    /*endregion*/
}

