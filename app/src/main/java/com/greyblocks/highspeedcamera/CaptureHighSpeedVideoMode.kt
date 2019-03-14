package com.greyblocks.highspeedcamera


import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.app.DialogFragment
import android.app.Fragment
import android.content.Context
import android.content.DialogInterface
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.graphics.drawable.ColorDrawable
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread

import android.util.Log
import android.util.Range
import android.util.Size
import android.util.SparseIntArray
import android.view.LayoutInflater
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.Toast
import androidx.annotation.NonNull
import androidx.core.app.ActivityCompat
import com.example.android.camera2video.AutoFitTextureView

import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.ArrayList
import java.util.Comparator
import java.util.Date
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

class CaptureHighSpeedVideoMode : Fragment(), View.OnClickListener, ActivityCompat.OnRequestPermissionsResultCallback {

    /**
     * An [AutoFitTextureView] for camera preview.
     */
    private var mTextureView: AutoFitTextureView? = null

    /**
     * Button to record video
     */
    private var mRecButtonVideo: ImageButton? = null

    /**
     * A refernce to the opened [android.hardware.camera2.CameraDevice].
     */
    private var mCameraDevice: CameraDevice? = null

    /**
     * A reference to the current [CameraCaptureSession] for
     * preview.
     */
    private var mPreviewSessionHighSpeed: CameraConstrainedHighSpeedCaptureSession? = null
    private var mPreviewSession: CameraCaptureSession? = null


    /**
     * [TextureView.SurfaceTextureListener] handles several lifecycle events on a
     * [TextureView].
     */
    private val mSurfaceTextureListener = object : TextureView.SurfaceTextureListener {

        override fun onSurfaceTextureAvailable(
            surfaceTexture: SurfaceTexture,
            width: Int, height: Int
        ) {
            openCamera(width, height)
        }

        override fun onSurfaceTextureSizeChanged(
            surfaceTexture: SurfaceTexture,
            width: Int, height: Int
        ) {
            configureTransform(width, height)
        }

        override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
            return true
        }

        override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) {}

    }

    /**
     * The [android.util.Size] of camera preview.
     */
    private var mPreviewSize: Size? = null

    /**
     * The [android.util.Size] of video recording.
     */
    private var mVideoSize: Size? = null

    private var mVideoFps: Array<Range<Int>>? = null

    /**
     * Camera preview.
     */
    private var mPreviewBuilder: CaptureRequest.Builder? = null


    /**
     * High Speed Camera Request-list
     */
    //    private
    /**
     * MediaRecorder
     */
    private var mMediaRecorder: MediaRecorder? = null


    internal var surfaces: MutableList<Surface> = ArrayList()

    /**
     * Whether the app is recording video now
     */
    private var mIsRecordingVideo = false

    /**
     * An additional thread for running tasks that shouldn't block the UI.
     */
    private var mBackgroundThread: HandlerThread? = null

    /**
     * A [Handler] for running tasks in the background.
     */
    private var mBackgroundHandler: Handler? = null

    /**
     * A [Semaphore] to prevent the app from exiting before closing the camera.
     */
    private val mCameraOpenCloseLock = Semaphore(1)

    /**
     * [CameraDevice.StateCallback] is called when [CameraDevice] changes its status.
     */
    private val mStateCallback = object : CameraDevice.StateCallback() {

        override fun onOpened(@NonNull cameraDevice: CameraDevice) {
            mCameraDevice = cameraDevice
            startPreview()
            mCameraOpenCloseLock.release()
            if (null != mTextureView) {
                configureTransform(mTextureView!!.getWidth(), mTextureView!!.getHeight())
            }
        }

        override fun onDisconnected(@NonNull cameraDevice: CameraDevice) {
            mCameraOpenCloseLock.release()
            cameraDevice.close()
            mCameraDevice = null
        }

        override fun onError(@NonNull cameraDevice: CameraDevice, error: Int) {
            mCameraOpenCloseLock.release()
            cameraDevice.close()

            mCameraDevice = null
            val activity = activity
            activity?.finish()
        }

    }

    /**
     * Camcorder Profile
     */
    private var availableFpsRange: Array<Range<Int>>? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle
    ): View? {
        return inflater.inflate(R.layout.camera, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        mTextureView = view.findViewById(R.id.texture) as AutoFitTextureView
        mRecButtonVideo = view.findViewById(R.id.video_record) as ImageButton
        mRecButtonVideo!!.setOnClickListener(this)
        view.findViewById<View>(R.id.info).setOnClickListener(this)
        val decorView = activity.window.decorView
        activity.window.setBackgroundDrawable(ColorDrawable(Color.parseColor("#000000")))
        val uiOptions =
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        decorView.systemUiVisibility = uiOptions

    }

    override fun onResume() {
        surfaces.clear()
        super.onResume()
        startBackgroundThread()
        val decorView = activity.window.decorView
        activity.window.setBackgroundDrawable(ColorDrawable(Color.parseColor("#000000")))
        val uiOptions =
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        decorView.systemUiVisibility = uiOptions
        if (mTextureView!!.isAvailable()) {
            openCamera(mTextureView!!.getWidth(), mTextureView!!.getHeight())
        } else {
            mTextureView!!.setSurfaceTextureListener(mSurfaceTextureListener)
        }
    }

    override fun onPause() {
        if (mIsRecordingVideo) {
            stopRecordingVideoOnPause()
        }
        closeCamera()
        stopBackgroundThread()
        super.onPause()
    }

    override fun onClick(view: View) {
        when (view.id) {

            R.id.video_record -> {
                if (mIsRecordingVideo) {
                    stopRecordingVideo()
                } else {
                    startRecordingVideo()
                }
            }
        }
    }

    /**
     * Starts a background thread and its [Handler].
     */
    private fun startBackgroundThread() {
        mBackgroundThread = HandlerThread("CameraBackground")
        mBackgroundThread!!.start()
        mBackgroundHandler = Handler(mBackgroundThread!!.looper)
    }

    /**
     * Stops the background thread and its [Handler].
     */
    private fun stopBackgroundThread() {
        mBackgroundThread!!.quitSafely()
        try {
            mBackgroundThread!!.join()
            mBackgroundThread = null
            mBackgroundHandler = null
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }

    }


    override fun onRequestPermissionsResult(
        requestCode: Int, @NonNull permissions: Array<String>,
        @NonNull grantResults: IntArray
    ) {
        Log.d(TAG, "onRequestPermissionsResult")
        if (requestCode == REQUEST_VIDEO_PERMISSIONS) {
            if (grantResults.size == VIDEO_PERMISSIONS.size) {
                for (result in grantResults) {
                    if (result != PackageManager.PERMISSION_GRANTED) {
                        ErrorDialog.newInstance(getString(R.string.permission_request))
                            .show(childFragmentManager, FRAGMENT_DIALOG)
                        break
                    }
                }
            } else {
                ErrorDialog.newInstance(getString(R.string.permission_request))
                    .show(childFragmentManager, FRAGMENT_DIALOG)
            }
        } else {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        }
    }

    private fun hasPermissionsGranted(permissions: Array<String>): Boolean {
        for (permission in permissions) {
            if (ActivityCompat.checkSelfPermission(activity, permission) !== PackageManager.PERMISSION_GRANTED) {
                return false
            }
        }
        return true
    }

    private fun openCamera(width: Int, height: Int) {
        val activity = activity
        if (null == activity || activity.isFinishing) {
            return
        }
        val manager = activity.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            Log.d(TAG, "tryAcquire")
            if (!mCameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw RuntimeException("Time out waiting to lock camera opening.")
            }
            val cameraId = manager.cameraIdList[0]

            // Choose the sizes for camera preview and video recording
            val characteristics = manager.getCameraCharacteristics(cameraId)
            /*
      Tries to open a {@link CameraDevice}. The result is listened by `mStateCallback`.
     */
            val map = characteristics
                .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
            mVideoSize = chooseVideoSize(map.highSpeedVideoSizes)
            for (size in map.highSpeedVideoSizes) {
                Log.d("RESOLUTION", size.toString())
            }
            mVideoFps = map.getHighSpeedVideoFpsRangesFor(mVideoSize)

            mPreviewSize = chooseOptimalSize(
                map.getOutputSizes(SurfaceTexture::class.java),
                width, height, mVideoSize
            )

            // FPS
            availableFpsRange = map.getHighSpeedVideoFpsRangesFor(mVideoSize)
            var max = 0
            var min: Int
            for (r in availableFpsRange!!) {
                if (max < r.upper) {
                    max = r.upper
                }
            }
            min = max
            for (r in availableFpsRange!!) {
                if (min > r.lower) {
                    min = r.upper
                }
            }
            //            for(Range<Integer> r: availableFpsRange) {
            //                if(min == r.getLower() && max == r.getUpper()) {
            //                     mPreviewBuilder.set(CONTROL_AE_TARGET_FPS_RANGE,r);
            //                    Log.d("RANGES", "[ " + r.getLower() + " , " + r.getUpper() + " ]");
            //                }
            //            }

            for (r in availableFpsRange!!) {
                Log.d("RANGES", "[ " + r.lower + " , " + r.upper + " ]")
            }
            Log.d("RANGE", "[ $min , $max ]")
            val orientation = resources.configuration.orientation
            if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
                mTextureView!!.setAspectRatio(mPreviewSize!!.width, mPreviewSize!!.height)
            } else {
                mTextureView!!.setAspectRatio(mPreviewSize!!.height, mPreviewSize!!.width)
            }
            configureTransform(width, height)
            mMediaRecorder = MediaRecorder()
            //            mMediaFormat = new MediaFormat();
            if (ActivityCompat.checkSelfPermission(
                    getActivity(),
                    Manifest.permission.CAMERA
                ) !== PackageManager.PERMISSION_GRANTED
            ) {
                // TODO: Consider calling
                //    ActivityCompat#requestPermissions
                // here to request the missing permissions, and then overriding
                //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
                //                                          int[] grantResults)
                // to handle the case where the user grants the permission. See the documentation
                // for ActivityCompat#requestPermissions for more details.
                return
            }
            manager.openCamera(cameraId, mStateCallback, null)
        } catch (e: CameraAccessException) {
            e.printStackTrace()
            Toast.makeText(activity, "Cannot access the camera.", Toast.LENGTH_SHORT).show()
            activity.finish()
        } catch (e: NullPointerException) {
            e.printStackTrace()
            // Currently an NPE is thrown when the Camera2API is used but not supported on the
            // device this code runs.
            ErrorDialog.newInstance(getString(R.string.camera_error))
                .show(childFragmentManager, FRAGMENT_DIALOG)
        } catch (e: InterruptedException) {
            e.printStackTrace()
            throw RuntimeException("Interrupted while trying to lock camera opening.")
        }

    }

    private fun closeCamera() {
        try {
            mCameraOpenCloseLock.acquire()
            if (null != mCameraDevice) {
                mCameraDevice!!.close()
                mCameraDevice = null
            }
            if (null != mMediaRecorder) {
                mMediaRecorder!!.release()
                mMediaRecorder = null
            }
        } catch (e: InterruptedException) {
            throw RuntimeException("Interrupted while trying to lock camera closing.")
        } finally {
            mCameraOpenCloseLock.release()
        }
    }

    /**
     * Start the camera preview.
     */
    private fun startPreview() {
        if (null == mCameraDevice || !mTextureView!!.isAvailable() || null == mPreviewSize) {
            return
        }
        try {
            surfaces.clear()
            val texture = mTextureView!!.getSurfaceTexture()!!
            texture!!.setDefaultBufferSize(mPreviewSize!!.width, mPreviewSize!!.height)
            mPreviewBuilder = mCameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
            val previewSurface = Surface(texture)
            surfaces.add(previewSurface)
            mPreviewBuilder!!.addTarget(previewSurface)

            mCameraDevice!!.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {

                override fun onConfigured(@NonNull cameraCaptureSession: CameraCaptureSession) {
                    mPreviewSession = cameraCaptureSession
                    updatePreview()
                }

                override fun onConfigureFailed(@NonNull cameraCaptureSession: CameraCaptureSession) {
                    val activity = activity
                    if (null != activity) {
                        Toast.makeText(activity, "Failed", Toast.LENGTH_SHORT).show()
                    }
                }
            }, mBackgroundHandler)
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }

    }


    /**
     * Update the camera preview. [.startPreview] needs to be called in advance.
     */
    private fun updatePreview() {
        if (null == mCameraDevice) {
            return
        }
        try {
            val thread = HandlerThread("CameraHighSpeedPreview")
            thread.start()

            if (mIsRecordingVideo) {
                setUpCaptureRequestBuilder(mPreviewBuilder!!)
                val mPreviewBuilderBurst =
                    mPreviewSessionHighSpeed!!.createHighSpeedRequestList(mPreviewBuilder!!.build())
                mPreviewSessionHighSpeed!!.setRepeatingBurst(mPreviewBuilderBurst, null, mBackgroundHandler)
            } else {
                mPreviewSession!!.setRepeatingRequest(mPreviewBuilder!!.build(), null, mBackgroundHandler)
            }
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }

    }

    /**
     * Finds the framerate-range with the highest capturing framerate, and the lowest
     * preview framerate.
     *
     * @param fpsRanges A list contains framerate ranges.
     * @return The best option available.
     */
    private fun getHighestFpsRange(fpsRanges: Array<Range<Int>>?): Range<Int> {
        //        Range<Integer> fpsRange = Range.create(fpsRanges[0].getLower(), fpsRanges[0].getUpper());
        //        for (Range<Integer> r : fpsRanges) {
        //            if (r.getUpper() > fpsRange.getUpper()) {
        //                fpsRange.extend(0, r.getUpper());
        //            }
        //        }
        //
        //        for (Range<Integer> r : fpsRanges) {
        //            if (r.getUpper() == fpsRange.getUpper()) {
        ////                if (r.getLower() < fpsRange.getLower()) {
        //                    fpsRange.extend(r.getUpper(), fpsRange.getUpper());
        ////                }
        //            }
        //        }
        var max = 0
        var min: Int
        for (r in availableFpsRange!!) {
            if (max < r.upper) {
                max = r.upper
            }
        }
        min = max
        for (r in availableFpsRange!!) {
            if (min > r.lower) {
                min = r.upper
            }
        }
        return Range.create(min, max)
    }

    private fun setUpCaptureRequestBuilder(builder: CaptureRequest.Builder) {
        //        Range<Integer> fpsRange = Range.create(30, 120);
        val fpsRange = getHighestFpsRange(availableFpsRange)
        builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange)

    }

    /**
     * Configures the necessary [android.graphics.Matrix] transformation to `mTextureView`.
     * This method should not to be called until the camera preview size is determined in
     * openCamera, or until the size of `mTextureView` is fixed.
     *
     * @param viewWidth  The width of `mTextureView`
     * @param viewHeight The height of `mTextureView`
     */
    private fun configureTransform(viewWidth: Int, viewHeight: Int) {
        val activity = activity
        if (null == mTextureView || null == mPreviewSize || null == activity) {
            return
        }
        val rotation = activity.windowManager.defaultDisplay.rotation
        val matrix = Matrix()
        val viewRect = RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
        val bufferRect = RectF(0f, 0f, mPreviewSize!!.height.toFloat(), mPreviewSize!!.width.toFloat())
        val centerX = viewRect.centerX()
        val centerY = viewRect.centerY()
        if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
            val scale = Math.max(
                viewHeight.toFloat() / mPreviewSize!!.height,
                viewWidth.toFloat() / mPreviewSize!!.width
            )
            matrix.postScale(scale, scale, centerX, centerY)
            matrix.postRotate((90 * (rotation - 2)).toFloat(), centerX, centerY)
        }
        mTextureView!!.setTransform(matrix)
    }

    //    private MediaFormat mMediaFormat;
    @Throws(IOException::class)
    private fun setUpMediaRecorder() {
        val activity = activity ?: return
        mMediaRecorder!!.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        mMediaRecorder!!.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        mMediaRecorder!!.setOutputFile(getVideoFile(activity).absolutePath)
        mMediaRecorder!!.setVideoEncodingBitRate(20000000)
        mMediaRecorder!!.setVideoFrameRate(120)
        mMediaRecorder!!.setVideoSize(mVideoSize!!.width, mVideoSize!!.height)
        mMediaRecorder!!.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        val rotation = activity.windowManager.defaultDisplay.rotation
        val orientation = ORIENTATIONS.get(rotation)
        mMediaRecorder!!.setOrientationHint(orientation)
        mMediaRecorder!!.prepare()
    }

    /**
     * This method chooses where to save the video and what the name of the video file is
     *
     * @param context where the camera activity is
     * @return path + filename
     */
    private fun getVideoFile(context: Context): File {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss").format(Date())
        val dir = File(Environment.getExternalStorageDirectory().toString() + File.separator + "DCIM/Camera/")
        //        return new File(context.getExternalFilesDir("DCIM"),
        //                "TEST_VID_" + timeStamp + ".mp4");
        return File(
            dir,
            "TEST_VID_$timeStamp.mp4"
        )
    }

    private fun startRecordingVideo() {
        try {
            // UI
            mIsRecordingVideo = true
            surfaces.clear()
            setUpMediaRecorder()
            val texture = mTextureView!!.getSurfaceTexture()!!
            texture!!.setDefaultBufferSize(mPreviewSize!!.width, mPreviewSize!!.height)
            mPreviewBuilder = mCameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
            //            List<Surface> surfaces = new ArrayList<>();
            val previewSurface = Surface(texture)
            surfaces.add(previewSurface)
            mPreviewBuilder!!.addTarget(previewSurface)

            val recorderSurface = mMediaRecorder!!.surface
            surfaces.add(recorderSurface)
            mPreviewBuilder!!.addTarget(recorderSurface)

            Log.d("FPS", CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE.toString())

            mCameraDevice!!.createConstrainedHighSpeedCaptureSession(
                surfaces,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(@NonNull cameraCaptureSession: CameraCaptureSession) {
                        mPreviewSession = cameraCaptureSession
                        mPreviewSessionHighSpeed = mPreviewSession as CameraConstrainedHighSpeedCaptureSession?
                        updatePreview()
                    }

                    override fun onConfigureFailed(@NonNull cameraCaptureSession: CameraCaptureSession) {
                        val activity = activity
                        if (null != activity) {
                            Log.d("ERROR", "COULD NOT START CAMERA")
                            Toast.makeText(activity, "Failed", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                mBackgroundHandler
            )
            mRecButtonVideo!!.setImageResource(R.drawable.ic_stop_black_24dp)
            // Start recording
            mMediaRecorder!!.start()

        } catch (e: IllegalStateException) {
            e.printStackTrace()
        } catch (e: IOException) {
            e.printStackTrace()
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }

    }

    private fun stopRecordingVideo() {
        // UI
        mIsRecordingVideo = false
        mRecButtonVideo!!.setImageResource(R.drawable.ic_slow_motion_video_black_24dp)
        // Stop recording
        try {
            mPreviewSessionHighSpeed!!.stopRepeating()
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }

        mMediaRecorder!!.stop()
        mMediaRecorder!!.reset()
        val activity = activity
        if (null != activity) {
            Toast.makeText(
                activity, "Video saved: " + getVideoFile(activity),
                Toast.LENGTH_SHORT
            ).show()
        }
        startPreview()
    }

    private fun stopRecordingVideoOnPause() {
        mIsRecordingVideo = false
        try {
            mPreviewSessionHighSpeed!!.stopRepeating()
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }

        mRecButtonVideo!!.setImageResource(R.drawable.ic_slow_motion_video_black_24dp)
        mMediaRecorder!!.stop()

        mMediaRecorder!!.reset()
    }

    /**
     * Compares two `Size`s based on their areas.
     */
    internal class CompareSizesByArea : Comparator<Size> {

        override fun compare(lhs: Size, rhs: Size): Int {
            // We cast here to ensure the multiplications won't overflow
            return java.lang.Long.signum(lhs.width.toLong() * lhs.height - rhs.width.toLong() * rhs.height)
        }

    }

    class ErrorDialog : DialogFragment() {

        override fun onCreateDialog(savedInstanceState: Bundle): Dialog {
            val activity = activity
            return AlertDialog.Builder(activity)
                .setMessage(arguments.getString(ARG_MESSAGE))
                .setPositiveButton(android.R.string.ok) { dialogInterface, i -> activity.finish() }
                .create()
        }

        companion object {

            private val ARG_MESSAGE = "message"

            fun newInstance(message: String): ErrorDialog {
                val dialog = ErrorDialog()
                val args = Bundle()
                args.putString(ARG_MESSAGE, message)
                dialog.arguments = args
                return dialog
            }
        }

    }

    class ConfirmationDialog : DialogFragment() {

//        override fun onCreateDialog(savedInstanceState: Bundle): Dialog {
//            val parent = parentFragment
//            return AlertDialog.Builder(activity)
//                .setMessage(R.string.permission_request)
//                .setPositiveButton(android.R.string.ok, DialogInterface.OnClickListener { dialog, which ->
//                    FragmentCompat.requestPermissions(
//                        parent, VIDEO_PERMISSIONS,
//                        REQUEST_VIDEO_PERMISSIONS
//                    )
//                })
//                .setNegativeButton(android.R.string.cancel,
//                    DialogInterface.OnClickListener { dialog, which -> parent.activity.finish() })
//                .create()
//        }

    }

    companion object {
        private val ORIENTATIONS = SparseIntArray()

        private val TAG = "CaptureVideoMode"
        private val REQUEST_VIDEO_PERMISSIONS = 1
        private val FRAGMENT_DIALOG = "dialog"

        private val VIDEO_PERMISSIONS = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)

        init {
            ORIENTATIONS.append(Surface.ROTATION_0, 90)
            ORIENTATIONS.append(Surface.ROTATION_90, 0)
            ORIENTATIONS.append(Surface.ROTATION_180, 270)
            ORIENTATIONS.append(Surface.ROTATION_270, 180)
        }

        fun newInstance(): CaptureHighSpeedVideoMode {
            return CaptureHighSpeedVideoMode()
        }

        /**
         * In this sample, we choose a video size with 3x4 aspect ratio. Also, we don't use sizes
         * larger than 1080p, since MediaRecorder cannot handle such a high-resolution video.
         *
         * @param choices The list of available sizes
         * @return The video size
         */
        private fun chooseVideoSize(choices: Array<Size>): Size {
            for (size in choices) {
                if (size.width == 1920 && size.height <= 1080) {
                    return size
                }
            }
            Log.e(TAG, "Couldn't find any suitable video size")
            return choices[choices.size - 1]
        }

        /**
         * Given `choices` of `Size`s supported by a camera, chooses the smallest one whose
         * width and height are at least as large as the respective requested values, and whose aspect
         * ratio matches with the specified value.
         *
         * @param choices     The list of sizes that the camera supports for the intended output class
         * @param width       The minimum desired width
         * @param height      The minimum desired height
         * @param aspectRatio The aspect ratio
         * @return The optimal `Size`, or an arbitrary one if none were big enough
         */
        private fun chooseOptimalSize(choices: Array<Size>, width: Int, height: Int, aspectRatio: Size?): Size {
            // Collect the supported resolutions that are at least as big as the preview Surface
            //        List<Size> bigEnough = new ArrayList<>();
            //        int w = aspectRatio.getWidth();
            //        int h = aspectRatio.getHeight();
            //        for (Size option : choices) {
            //            if (option.getHeight() == option.getWidth() * h / w &&
            //                    option.getWidth() <= width && option.getHeight() <= height) {
            //                bigEnough.add(option);
            //            }
            //        }
            //
            //        // Pick the smallest of those, assuming we found any
            //        if (bigEnough.size() > 0) {
            //            return Collections.max(bigEnough, new CompareSizesByArea());
            //        } else {
            //            Log.e(TAG, "Couldn't find any suitable preview size");
            //            return choices[0];
            //        }
            return Size(1920, 1080)
        }
    }

}