package com.jimmy.camerax

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.drawable.ColorDrawable

import android.hardware.display.DisplayManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.DisplayMetrics
import android.util.Log
import android.util.Size
import android.view.*
import android.webkit.MimeTypeMap
import android.widget.ImageButton
import android.widget.Toast
import androidx.camera.core.*
import androidx.camera.extensions.BokehImageCaptureExtender
import androidx.camera.core.ImageCapture.Metadata
import androidx.camera.view.TextureViewMeteringPointFactory
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.setPadding
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.bumptech.glide.Glide
import com.bumptech.glide.request.RequestOptions
import kotlinx.android.synthetic.main.activity_main.*
import java.io.File
import java.lang.Math.*
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/** Helper type alias used for analysis use case callbacks */
typealias LumaListener = (luma: Double) -> Unit
const val KEY_EVENT_ACTION = "key_event_action"
const val KEY_EVENT_EXTRA = "key_event_extra"
private const val IMMERSIVE_FLAG_TIMEOUT = 500L

class MainActivity : AppCompatActivity() {

    // This is an array of all the permission specified in the manifest.
    private val permissions = arrayOf(Manifest.permission.CAMERA)
    //private val executor = Executors.newSingleThreadExecutor()


    private var lensFacing = CameraX.LensFacing.BACK
    private var displayId = -1
    private var preview: Preview? = null
    private var imageCapture: ImageCapture? = null
    private var imageAnalyzer: ImageAnalysis? = null


    private lateinit var container: ConstraintLayout
    private lateinit var outputDirectory: File
    private lateinit var broadcastManager: LocalBroadcastManager
    private lateinit var mainExecutorr: Executor

    companion object {
        // This is an arbitrary number we are using to keep track of the permission
        // request. Where an app has multiple context for requesting permission,
        // this can help differentiate the different contexts.
        private const val REQUEST_CODE_PERMISSIONS = 10

        private const val TAG = "CameraXBasic"
        private const val CAMERA_NEW_PICTURE_TAKEN = "CameraNewPictureTaken"
        private const val FILENAME = "yyyy-MM-dd-HH-mm-ss-SSS"
        private const val PHOTO_EXTENSION = ".jpg"
        private const val RATIO_4_3_VALUE = 4.0 / 3.0
        private const val RATIO_16_9_VALUE = 16.0 / 9.0
        /** Milliseconds used for UI animations */
        const val ANIMATION_FAST_MILLIS = 50L
        const val ANIMATION_SLOW_MILLIS = 100L

        /** Helper function used to create a timestamped file */
        private fun createFile(baseFolder: File, format: String, extension: String) =
            File(baseFolder, SimpleDateFormat(format, Locale.US)
                .format(System.currentTimeMillis()) + extension)
    }



    /** Volume down button receiver used to trigger shutter */
    private val volumeDownReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(KEY_EVENT_EXTRA, KeyEvent.KEYCODE_UNKNOWN)) {
                // When the volume down button is pressed, simulate a shutter button click
                KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    val shutter = container
                        .findViewById<ImageButton>(R.id.camera_capture_button)
                    shutter.performClick()
                }
            }
        }
    }


    /** Internal reference of the [DisplayManager] */
    private lateinit var displayManager: DisplayManager

    /**
     * We need a display listener for orientation changes that do not trigger a configuration
     * change, for example if we choose to override config change in manifest or for 180-degree
     * orientation changes.
     */
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) = viewFinder.rootView?.let { view ->
            if (displayId == this@MainActivity.displayId) {
                Log.d(
                    MainActivity::class.java.simpleName,
                    "Rotation changed: ${view.display.rotation}"
                )
                preview?.setTargetRotation(view.display.rotation)
                imageCapture?.setTargetRotation(view.display.rotation)
                imageAnalyzer?.setTargetRotation(view.display.rotation)
            }
        } ?: Unit

    }



    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        container = findViewById<ConstraintLayout>(R.id.camera_container)
        /*
            Return an Executor that will run enqueued tasks on the main thread associated with this
            context. This is the thread used to dispatch calls to application components
            (activities, services, etc).
         */
        mainExecutorr = ContextCompat.getMainExecutor(baseContext)

        broadcastManager = LocalBroadcastManager.getInstance(this)
        displayManager = viewFinder.context
            .getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        // Request camera permissions
        if (allPermissionsGranted()) {



            // Set up the intent filter that will receive events
            val filter = IntentFilter().apply { addAction(KEY_EVENT_ACTION) }
            broadcastManager.registerReceiver(volumeDownReceiver, filter)

            // Every time the orientation of device changes, recompute layout

            displayManager.registerDisplayListener(displayListener, null)

            // Determine the output directory
            outputDirectory = getOutputDirectory(this)

            /* using viewFinder thread
                Instead of calling `setupCameraUseCases()` on the main thread, we use `viewFinder.post { ... }`
                to make sure that `viewFinder` has already been inflated
                into the view when `setupCameraUseCases()` is called.
             */
            viewFinder.post {
                setupCameraUseCases()
            }

            findViewById<ImageButton>(R.id.camera_capture_button)
                .setOnClickListener (::shutterBtnClickListenr)

            findViewById<ImageButton>(R.id.camera_switch_button)
                .setOnClickListener(::changeCameraBtnClickListenr)

            findViewById<ImageButton>(R.id.photo_view_button)
                .setOnClickListener(::viewSavedImageBtnClickListenr)

        } else {
            ActivityCompat.requestPermissions(
                this, permissions, REQUEST_CODE_PERMISSIONS)
        }

        //Every time the provided texture view changes, recompute layout
        viewFinder.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateTransform()
        }

    }


    private fun setupCameraUseCases() {
        val metrics = DisplayMetrics().also { viewFinder.display.getRealMetrics(it) }
        val screenSize = Size(metrics.widthPixels, metrics.heightPixels)
        val screenAspectRatio = aspectRatio(metrics.widthPixels, metrics.heightPixels)
        

        // Keep track of the display in which this view is attached
        displayId = viewFinder.display.displayId

        // Build the viewfinder use case
//        val preview = Preview(previewConfig)

        // Use the auto-fit preview builder to automatically handle size and orientation changes
        val preview = setupPreview(screenAspectRatio)

        // Build the image capture use case and attach button click listener
         imageCapture = setupCamera (screenAspectRatio)

        // Build the image analysis use case and instantiate our analyzer
        val analyzerUseCase = setupImageAnalyzer()

        // Bind use cases to lifecycle
        // If Android Studio complains about "this" being not a LifecycleOwner
        // try rebuilding the project or updating the appcompat dependency to
        // version 1.1.0 or higher.
        CameraX.bindToLifecycle(this,
            preview,
            imageCapture,
            analyzerUseCase
        )
    }

    private fun setupPreview (screenAspectRatio : AspectRatio):Preview{
        // Create configuration object for the viewfinder use case
        val previewConfig = PreviewConfig.Builder().apply {
            //            setTargetResolution(screenSize)
            setLensFacing(lensFacing)
            // We request aspect ratio but no resolution to let CameraX optimize our use cases
            setTargetAspectRatio(screenAspectRatio) // either use aspect_ration or target_resolution not both
            // Set initial target rotation, we will have to call this again if rotation changes
            // during the lifecycle of this use case
            setTargetRotation(viewFinder.display.rotation)
        }.build()

        // Use the auto-fit preview builder to automatically handle size and orientation changes
        val preview = AutoFitPreviewBuilder.build(previewConfig, viewFinder)

        // Every time the viewfinder is updated, recompute layout
        preview.setOnPreviewOutputUpdateListener {

            // To update the SurfaceTexture, we have to remove it and re-add it
            val parent = viewFinder.parent as ViewGroup
            parent.removeView(viewFinder)
            parent.addView(viewFinder, 0)

            viewFinder.surfaceTexture = it.surfaceTexture
            updateTransform()
        }
        return preview
    }

    private fun setupCamera (screenAspectRatio : AspectRatio): ImageCapture {
        // Create configuration object for the image capture use case
        val imageCaptureConfig = ImageCaptureConfig.Builder()
            .apply {
                setLensFacing(lensFacing)
                // We request aspect ratio but no resolution to match preview config but letting
                // CameraX optimize for whatever specific resolution best fits requested capture mode
                setTargetAspectRatio(screenAspectRatio)
                setTargetRotation(viewFinder.display.rotation)
                // We don't set a resolution for image capture; instead, we
                // select a capture mode which will infer the appropriate
                // resolution based on aspect ration and requested mode
                setCaptureMode(ImageCapture.CaptureMode.MIN_LATENCY)

            }.also{
                bokehModeAddOn(it)
            }.build()

        // Build the image capture use case and attach button click listener
        return ImageCapture(imageCaptureConfig)
    }

    private fun setupImageAnalyzer(): ImageAnalysis {

        // Setup image analysis pipeline that computes average pixel luminance
        val analyzerConfig = ImageAnalysisConfig.Builder().apply {
            setLensFacing(lensFacing)
            // In our analysis, we care more about the latest image than
            // analyzing *every* image
            setImageReaderMode(
                ImageAnalysis.ImageReaderMode.ACQUIRE_LATEST_IMAGE)
            // Set initial target rotation, we will have to call this again if rotation changes
            // during the lifecycle of this use case
            setTargetRotation(viewFinder.display.rotation)
        }.build()

        return ImageAnalysis(analyzerConfig).apply {
            setAnalyzer(mainExecutorr, LuminosityAnalyzer { luma ->
                // Values returned from our analyzer are passed to the attached listener
                // We log image analysis results here --
                // you should do something useful instead!
                val fps = (analyzer as LuminosityAnalyzer).framesPerSecond
                Log.d(TAG, "Average luminosity: $luma. " +
                        "Frames per second: ${"%.01f".format(fps)}")
            })
        }

    }

    /** Define callback that will be triggered after a photo has been taken and saved to disk */
    private val imageSavedListener = object : ImageCapture.OnImageSavedListener {
        override fun onError( imageCaptureError: ImageCapture.ImageCaptureError,
                              message: String, exc: Throwable? ) {
            val msg = "Photo capture failed: $message"
            Log.e("CameraXApp", msg, exc)
            viewFinder.post {
                Toast.makeText(baseContext, msg, Toast.LENGTH_SHORT).show()
            }
        }

        override fun onImageSaved(photoFile: File) {
            val msg = "Photo capture succeeded: ${photoFile.absolutePath}"
            Log.d("CameraXApp", msg)

            // We can only change the foreground Drawable using API level 23+ API
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {

                // Update the gallery thumbnail with latest picture taken
                setGalleryThumbnail(photoFile)
            }

            // Implicit broadcasts will be ignored for devices running API
            // level >= 24, so if you only target 24+ you can remove this statement
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                sendBroadcast(Intent(CAMERA_NEW_PICTURE_TAKEN, Uri.fromFile(photoFile)))
            }

            // If the folder selected is an external media directory, this is unnecessary
            // but otherwise other apps will not be able to access our images unless we
            // scan them using [MediaScannerConnection]
            val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(photoFile.extension)

            MediaScannerConnection.scanFile(this@MainActivity,
                arrayOf(photoFile.absolutePath), arrayOf(mimeType), null)

            viewFinder.post {
                Toast.makeText(baseContext, msg, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun shutterBtnClickListenr(view :View){

        // Create output file to hold the image
        val photoFile = createFile(outputDirectory, FILENAME, PHOTO_EXTENSION)

        // Setup image capture metadata
        val metadata = Metadata().apply {
            // Mirror image when using the front camera
            isReversedHorizontal = lensFacing == CameraX.LensFacing.FRONT
        }

        Log.e("jjjjjjj", "$imageCapture")
        imageCapture?.takePicture(photoFile, metadata, mainExecutorr, imageSavedListener)

        // We can only change the foreground Drawable using API level 23+ API
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {

            // Display flash animation to indicate that photo was captured
            container.postDelayed({
                container.foreground = ColorDrawable(Color.WHITE)
                container.postDelayed({
                    container.foreground = null
                }, ANIMATION_FAST_MILLIS)
            }, ANIMATION_SLOW_MILLIS)
        }

    }

    private fun changeCameraBtnClickListenr(view :View){

        lensFacing = if (CameraX.LensFacing.FRONT == lensFacing) {
            CameraX.LensFacing.BACK
        } else {
            CameraX.LensFacing.FRONT
        }

        try {
            // Only bind use cases if we can query a camera with this orientation
            CameraX.getCameraInfo(lensFacing)

            // Unbind all use cases and bind them again with the new lens facing configuration
            CameraX.unbindAll()
            viewFinder.post {
                setupCameraUseCases()
            }
        } catch (exc: Exception) {
            Log.e("changeCameraBtnClick", "${exc.message}")
        }
    }

    private fun viewSavedImageBtnClickListenr(view :View) {
        //
    }


    private fun updateTransform() {
        val matrix = Matrix()

        // Compute the center of the view finder
        val centerX = viewFinder.width / 2f
        val centerY = viewFinder.height / 2f

        // Correct preview output to account for display rotation
        val rotationDegrees = when(viewFinder.display.rotation) {
            Surface.ROTATION_0 -> 0
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> return
        }
        matrix.postRotate(-rotationDegrees.toFloat(), centerX, centerY)

        // Finally, apply transformations to our TextureView
        viewFinder.setTransform(matrix)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                viewFinder.post {
                    setupCameraUseCases() }
            }
        }
    }


    /**
     * Check if all permission specified in the manifest have been granted
     */
    private fun allPermissionsGranted() = permissions.all {
        ContextCompat.checkSelfPermission(  baseContext, it) == PackageManager.PERMISSION_GRANTED
    }


    private fun bokehModeAddOn(imageCaptureConfig: ImageCaptureConfig.Builder) {

        // Create a Extender object which can be used to apply extension
        // configurations.
        val bokehImageCapture = BokehImageCaptureExtender.create(imageCaptureConfig)

        // Query if extension is available (optional).
        if (bokehImageCapture.isExtensionAvailable) {
            // Enable the extension if available.
            bokehImageCapture.enableExtension()
        }
    }

    /**
     * LuminosityAnalyzer : logs the average luma (luminosity) of the image,
     * but exemplifies what needs to be done for arbitrarily complex use cases.
     */
    private class LuminosityAnalyzer (listener: LumaListener? = null): ImageAnalysis.Analyzer {
        private var lastAnalyzedTimestamp = 0L
        private val frameRateWindow = 8
        private val frameTimestamps = ArrayDeque<Long>(5)
        private val listeners = ArrayList<LumaListener>().apply {
            listener?.let { add(it) } }
        var framesPerSecond: Double = -1.0
            private set

        /**
         * Used to add listeners that will be called with each luma computed
         */
        fun onFrameAnalyzed(listener: LumaListener) = listeners.add(listener)

        /**
         * Helper extension function used to extract a byte array from an
         * image plane buffer
         */
        private fun ByteBuffer.toByteArray(): ByteArray {
            rewind()    // Rewind the buffer to zero
            val data = ByteArray(remaining())
            get(data)   // Copy the buffer into a byte array
            return data // Return the byte array
        }

        /**
         * Analyzes an image to produce a result.
         *
         * <p>The caller is responsible for ensuring this analysis method can be executed quickly
         * enough to prevent stalls in the image acquisition pipeline. Otherwise, newly available
         * images will not be acquired and analyzed.
         *
         * <p>The image passed to this method becomes invalid after this method returns. The caller
         * should not store external references to this image, as these references will become
         * invalid.
         *
         * @param image image being analyzed VERY IMPORTANT: do not close the image, it will be
         * automatically closed after this method returns
         * @return the image analysis result
         */
        override fun analyze(image: ImageProxy, rotationDegrees: Int) {

            // If there are no listeners attached, we don't need to perform analysis
            if (listeners.isEmpty()) return

            val currentTime = System.currentTimeMillis()
            frameTimestamps.push(currentTime)

            // Compute the FPS using a moving average
            while (frameTimestamps.size >= frameRateWindow) frameTimestamps.removeLast()
            val timestampFirst = frameTimestamps.peekFirst() ?: currentTime
            val timestampLast = frameTimestamps.peekLast() ?: currentTime
            framesPerSecond = 1.0 / ((timestampFirst - timestampLast) /
                    frameTimestamps.size.coerceAtLeast(1).toDouble()) * 1000.0

            // Calculate the average luma no more often than every second
            if (frameTimestamps.first - lastAnalyzedTimestamp >= TimeUnit.SECONDS.toMillis(1)) {
                lastAnalyzedTimestamp = frameTimestamps.first

                // Since format in ImageAnalysis is YUV, image.planes[0] contains the luminance
                //  plane
                val buffer = image.planes[0].buffer

                // Extract image data from callback object
                val data = buffer.toByteArray()

                // Convert the data into an array of pixel values ranging 0-255
                val pixels = data.map { it.toInt() and 0xFF }

                // Compute average luminance for the image
                val luma = pixels.average()

                // Call all listeners with new value
                listeners.forEach { it(luma) }
            }
        }
    }


    /** Use external media if it is available, our app's file directory otherwise */
    private fun getOutputDirectory(context: Context): File {
        val appContext = context.applicationContext
        val mediaDir = context.externalMediaDirs.firstOrNull()?.let {
            File(it, appContext.resources.getString(R.string.app_name)).apply { mkdirs() } }
        return if (mediaDir != null && mediaDir.exists())
            mediaDir else appContext.filesDir
    }


    /**
     *  [androidx.camera.core.ImageAnalysisConfig] requires enum value of
     *  [androidx.camera.core.AspectRatio]. Currently it has values of 4:3 & 16:9.
     *
     *  Detecting the most suitable ratio for dimensions provided in @params by counting absolute
     *  of preview ratio to one of the provided values.
     *
     *  @param width - preview width
     *  @param height - preview height
     *  @return suitable aspect ratio
     */
    private fun aspectRatio(width: Int, height: Int): AspectRatio {
        val previewRatio = max(width, height).toDouble() / min(width, height)

        if (kotlin.math.abs(previewRatio - RATIO_4_3_VALUE) <= abs(previewRatio - RATIO_16_9_VALUE)) {
            return AspectRatio.RATIO_4_3
        }
        return AspectRatio.RATIO_16_9
    }


    private fun setGalleryThumbnail(file: File) {
        // Reference of the view that holds the gallery thumbnail
        val thumbnail = container.findViewById<ImageButton>(R.id.photo_view_button)

        // Run the operations in the view's thread
        thumbnail.post {

            // Remove thumbnail padding
            thumbnail.setPadding(resources.getDimension(R.dimen.stroke_small).toInt())

            // Load thumbnail into circular button using Glide
            Glide.with(thumbnail)
                .load(file)
                .apply(RequestOptions.circleCropTransform())
                .into(thumbnail)
        }
    }



    override fun onResume() {
        super.onResume()
        // Make sure that all permissions are still present, since user could have removed them
        //  while the app was on paused state
        if(!allPermissionsGranted()){
            ActivityCompat.requestPermissions(
                this, permissions, REQUEST_CODE_PERMISSIONS)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Unregister the broadcast receivers and listeners
        broadcastManager.unregisterReceiver(volumeDownReceiver)
        displayManager.unregisterDisplayListener(displayListener)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                val intent = Intent(KEY_EVENT_ACTION).apply { putExtra(KEY_EVENT_EXTRA, keyCode) }
                LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }


    /**
     * Inflate camera controls and update the UI manually upon config changes to avoid removing
     * and re-adding the view finder from the view hierarchy; this provides a seamless rotation
     * transition on devices that support it.
     *
     * NOTE: The flag is supported starting in Android 8 but there still is a small flash on the
     * screen for devices that run Android 9 or below.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        //
    }

    /*
        camera controls implementation
     */

    fun cameraControlsSetup(){
        /*
            An API that allows you to control the camera directly, independently of the use cases.
            This is useful for configuring things such as the camera’s zoom,
            focus and metering across all use cases.
         */
        val cameraControl = CameraX.getCameraControl(lensFacing)

        /*
        An API that provides camera related information, such as the zoom ratio,
        the zoom percentage, the sensor rotation degrees and flash availability.
         */
        val cameraInfo = CameraX.getCameraInfo(lensFacing)
    }


    private fun setUpTapToFocus(cameraControl : CameraControl) {
        viewFinder.setOnTouchListener { _, event ->
            if (event.action != MotionEvent.ACTION_UP) {
                return@setOnTouchListener false
            }

            val factory = TextureViewMeteringPointFactory(viewFinder)
            val point = factory.createPoint(event.x, event.y)
            val action = FocusMeteringAction.Builder.from(point).build()
            cameraControl.startFocusAndMetering(action)
            return@setOnTouchListener true
        }
    }

    private fun setUpPinchToZoom(context:Context, cameraControl : CameraControl, cameraInfo : CameraInfo) {
      /*  val listener = object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val currentZoomRatio: Float = cameraInfo.zoomRatio.value ?: 0F
                val delta = detector.scaleFactor
                cameraControl.setZoomRatio(currentZoomRatio * delta)
                return true
            }
        }

        val scaleGestureDetector = ScaleGestureDetector(context, listener)

        viewFinder.setOnTouchListener { _, event ->
            scaleGestureDetector.onTouchEvent(event)
            return@setOnTouchListener true
        }*/
    }

    
}
