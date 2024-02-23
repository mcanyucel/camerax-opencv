This note includes on setting up OpenCV on Android, without JNI support (i.e. no C++, only Kotlin).

The versions are:
* OpenCV: 4.9.0
* CameraX: 1.3.1
* Android Studio: 2023.1.1 Patch 2

### Assumptions
* You are using Android Studio as IDE.
  
# Setup
### Download the Android SDK
1. Download the latest version from the official website. It is usually found [here](https://opencv.org/releases/).
2. Extract it into a suitable location. If you are planning to use *shared mode* where multiple projects just reference to a single SDK on the disk (instead of every project including its own SDK files), remember this location. Actually, remember this location anyway. Note that the SDK is ~200 MB.
3. The extracted API should have the following directory structure: *\OpenCV-android-sdk\sdk*.

### Adding SDK to Existing Android Studio Project as Module
#### Compatibility with Gradle Language
The newer Android Studio allows to select a build configuration language from the following:
* Kotlin DSL (build.gradle.kts)
* Kotlin DSL (build.gradle.kts) + Gradle Version Catalogs
* Groovy DSL (build.gradle)

Even though the first one is recommended, OpenCV will not work as a module unless you have selected the third option. The error will be due to the following lines in the `build.gradle` file:
```
plugins {
  id 'com.android.application' version '8.2.2' apply false
  id 'org.jetbrains.kotlin.android' version '1.9.22' apply false
}
```
The Gradle will complain about not being able to find application plugin, and then it will complain about the versions...etc. The easiest solution is creating the project with Groovy DSL for now.

#### One SDK Per Project
1. Import the SDK to the existing project as a module using *Menu > File > New > Module > Import Project*
2. Select the *\OpenCV-android-sdk\sdk* directory, and change the module name (e.g. *:opencv*)
#### Shared SDK
1. Add the following line to the *settings.gradle* file:
```
def opencvsdk='<path_to_opencv_android_sdk_rootdir>'

include ':opencv'
project(':opencv').projectDir = new File(opencvsdk + '/sdk')
```
2. Alternatively, add the following line to the *gradle.properties* file:
```
opencvsdk=<path_to_opencv_android_sdk_rootdir>
```

### Adding Dependency to the Module
The easiest way is to open the module settings of your app (*F4*), then go to *Dependencies*, select *Add Module* and select the *opencv* module. 
Alternatively, you can all the following lines to the *app/build.gradle* file:
```
dependencies {
    implementation fileTree(dir: 'libs', include: ['*.jar'])
    ...
    implementation project(':opencv')
}
```

### Loading the Library SDK
Add the following line before using any functionality:
```kotlin
System.loadLibrary("opencv_java4")
```

### Handling `JavaVersionCompatibility` Error
It is very likely that the first build of the application will fail 
due to the following error:

> ERROR : 'compileJava' task (current target is 11) and 'compileKotlin' task (current target is 1.8) jvm target compatibility should be set to the same Java version.
>>>

To fix this problem, set all the java versions to 17, including the ones in the *build.gradle* file of the opencv module:
```
compileOptions {
    sourceCompatibility JavaVersion.VERSION_17
    targetCompatibility JavaVersion.VERSION_17
}
```

Now the OpenCV library SDK should be working. 

# Using CameraX with OpenCV
The camera object (with its UI counterpart) of the OpenCV SDK is really bad; it does not allow any customization and used to have big issues with the device orientation.

## What is CameraX?
Android CameraX is a powerful and user-friendly library introduced by Google, designed to simplify the process of integrating camera functionalities into Android applications. It offers a consistent and robust API that abstracts away the complexities of dealing with different device implementations and versions of the Android operating system. CameraX provides developers with a streamlined way to access core camera features, enabling them to focus more on building innovative camera-based experiences rather than dealing with low-level camera APIs.

## What is the catch?
The catch is that CameraX uses YUV420-888 format whereas OpenCV deals with BGR8888. Moreover, the chroma planes may be interleaved or ordered. This requires an adapter or a converter between the two libraries.

## Converting CameraX Stream to `Mat` Instances

The `ImageAnalysis.Analyzer` class allows to intercept the camera stream in the form of an `ImageProxy`. This is a crucial intermediary for accessing and processing images captured by the device’s camera in real-time, aligned with the lifecycle management of the app. 
An `ImageProxy` instance has a property named `Image`, which if the `ImageProxy` is a wrapper for an android `Image`, it will return the `Image`. It is possible for an `ImageProxy` to wrap something that isn't an `Image`. If that's the case then it will return `null`. The returned image should not be closed by the application. Instead it should be closed by the `ImageProxy`, which happens, for example, on return from the `ImageAnalysis.Analyzer` function. Destroying the `ImageAnalysis` will close the underlying `android.media.ImageReader`. So an `Image` obtained with this method will behave as such.

The following extension function can be used for the conversion:

  ```kotlin
fun Image.yuvToRgba(): Mat {
    val rgbaMat = Mat()

    if (format == ImageFormat.YUV_420_888 && planes.size == 3){
        val chromaPixelStride = planes[1].pixelStride

        if (chromaPixelStride == 2) // chroma channels are interleaved
        {
            assert(planes[0].pixelStride == 1)
            assert(planes[2].pixelStride == 2)
            val yPlane = planes[0].buffer
            val uvPlane1 = planes[1].buffer
            val uvPlane2 = planes[2].buffer

            val yMat = Mat(height, width, CvType.CV_8UC1, yPlane)
            val uvMat1 = Mat(height / 2, width / 2, CvType.CV_8UC2, uvPlane1)
            val uvMat2 = Mat(height / 2, width / 2, CvType.CV_8UC2, uvPlane2)
            val addrDiff = uvMat2.dataAddr() - uvMat1.dataAddr()

            if (addrDiff > 0) {
                assert(addrDiff == 1L)
                Imgproc.cvtColorTwoPlane(yMat,uvMat1, rgbaMat, Imgproc.COLOR_YUV2RGBA_NV12)
            } else {
                assert(addrDiff == -1L)
                Imgproc.cvtColorTwoPlane(yMat, uvMat2, rgbaMat, Imgproc.COLOR_YUV2RGBA_NV21)
            }
        }
        else // chroma channels are not interleaved
        {
            val yuvBytes = ByteArray(width * (height + height / 2))
            val yPlane = planes[0].buffer
            val uPlane = planes[1].buffer
            val vPlane = planes[2].buffer

            yPlane.get(yuvBytes, 0, width * height)

            val chromaRowStride = planes[1].rowStride
            val chromaRowPadding = chromaRowStride - width / 2

            var offset = width * height

            if (chromaRowPadding == 0) {
                // When the row stride of the chroma channels equals their width, we can copy
                // the entire channels in one go
                uPlane.get(yuvBytes, offset, width * height / 4)
                offset += width * height / 4
                vPlane.get(yuvBytes, offset, width * height / 4)
            } else {
                // When not equal, we need to copy the channels row by row
                for (i in 0 until height / 2) {
                    uPlane.get(yuvBytes, offset, width / 2)
                    offset += width / 2
                    if (i < height / 2 - 1) {
                        uPlane.position(uPlane.position() + chromaRowPadding)
                    }
                }
                for (i in 0 until height / 2) {
                    vPlane.get(yuvBytes, offset, width / 2)
                    offset += width / 2
                    if (i < height / 2 - 1) {
                        vPlane.position(vPlane.position() + chromaRowPadding)
                    }
                }
            }

            val yuvMat = Mat(height + height / 2, width, CvType.CV_8UC1)
            yuvMat.put(0,0,yuvBytes)
            Imgproc.cvtColor(yuvMat, rgbaMat, Imgproc.COLOR_YUV2RGBA_I420, 4)
        }
    }
    return rgbaMat
}
  ```

## Intercepting Frames of `CameraX` 
One of the major advantages of `CameraX` is being able to bind analyzers to the life cycle of its camera provider. The framework exposes every frame to  these analyzers in the form of an `ImageProxy`. These analyzer classes have to implement the `ImageAnalysis.Analyzer` interface, which ensures that the class has to contain a function named `analyze(image: ImageProxy)`. Combining this function with the `yuvToRgba()` extension method defined above, it is possible to process every frame with OpenCV.
One important point to note is that since `CameraX` runs on its own executor, it is not possible to modify the UI elements directly (remember that UI elements can only be accessed from the `Main` dispatcher). Also, the `analyze` function is in a separate class, therefore it does not have access to the `context` of the `Activity` or `Fragment` that it is called, making `runOnUIThread` calls impossible. Since it is a very very bad practice to move the `context` around, one of the ways to go is defining a listener delegate type that we can give to the analyzer class as a constructor argument. This delegate can be bound to a function on the calling class (`Activity` or `Fragment`), and do all the work using the data that is supplied by the analyzer class.
Below is an example analyzer class together with its listener definition that converts the current frame to grayscale:

```kotlin
typealias OpencvListener = (message: String, bitmap: Bitmap) -> Unit

private class LightnessAnalyzer(private val listener: OpencvListener) : ImageAnalysis.Analyzer {
    fun Image.yuvToRgba(): Mat {
        // this method is already explained above
    }

    @OptIn(ExperimentalGetImage::class) override fun analyze(image: ImageProxy) {
        image.image?.let {
            if (it.format == ImageFormat.YUV_420_888 && it.planes.size == 3) {
                val rgbMat = it.yuvToRgba()
                val buf = Mat()
                Imgproc.cvtColor(rgbMat, buf, Imgproc.COLOR_RGBA2GRAY)
                val bmp = Bitmap.createBitmap(buf.cols(), buf.rows(), Bitmap.Config.ARGB_8888)
                val message = "You can pass in additional metadata here"

                Utils.matToBitmap(buf, bmp)

                listener(message, bmp)
            }
        }

        image.close()
    }
}
```

Note the `@OptIn(ExperimentalGetImage::class)` attribute; it is required because `ImageProxy.image` getter is still experimental. This attribute will possibly be obsolete with the future versions.
In the above analyzer class, the listener will be supplied with a message (`string`) and the grayscale image of the frame as `Bitmap` every frame.

This listener can be subscribed to within the configuration of the CameraX:
```kotlin
private fun startCamera() {
    
    // initialization - see below

    // analyze
    val opencvAnalyzer = ImageAnalysis.Builder()
          .build()
          .also {
          it.setAnalyzer(cameraExecutor, LightnessAnalyzer { message, bitmap ->
                    // Log.d(TAG, message)
                    runOnUiThread {
                        // Rotate the bitmap based on the device orientation
                        try {
                            val rotation = when (binding.viewFinder.display.rotation) {
                                Surface.ROTATION_0 -> 90
                                Surface.ROTATION_90 -> 0
                                Surface.ROTATION_180 -> 270
                                Surface.ROTATION_270 -> 180
                                else -> 0
                            }

                            if (rotation == 0) {
                                binding.opencvImage.setImageBitmap(bitmap)
                            } else {
                                val matrix = android.graphics.Matrix()
                                matrix.postRotate(rotation.toFloat())
                                val rotatedBitmap = Bitmap.createBitmap(
                                    bitmap,
                                    0,
                                    0,
                                    bitmap.width,
                                    bitmap.height,
                                    matrix,
                                    true
                                )
                                binding.opencvImage.setImageBitmap(rotatedBitmap)
                            }
                        } catch (ex: Exception) {
                            binding.opencvImage.setImageBitmap(bitmap)
                        }
                    }
                })
            }

        // back camera as default
        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

        try {
            cameraProvider.unbindAll()

            cameraProvider.bindToLifecycle(
                this, cameraSelector, preview, imageCapture, opencvAnalyzer
            )
        } catch (ex: Exception) {
            Log.e(TAG, "use case binding failed", ex)
        }
    }, ContextCompat.getMainExecutor(this))

}
```

The above code block gets the grayscale frame bitmap and assigns it to an `ImageView` element in the UI (*opencvImage*) through the view binding class (`binding`) in the UI thread.
Note the additional code in the delegate that rotates the image; the reason of this section is that **CameraX returns the image 90 degrees rotated CW**, or directly as it acquires from the sensor. This means that when the device is in portrait mode, the image has to be rotated, but when the device is in landscape mode, we do not need a transformation.
The easiest solution to this problem is getting the orientation of the *viewFinder* control, which is of type `androidx.camera.view.PreviewView`. The preview of the `CameraX` is bound to this control, and it allows us to get the orientation of the device without meddling with the device sensors. One problem with this approach is, during the orientation switching, all the UI elements and non-lifecycle bound components will be destroyed and recreated. However, the camera will continue shooting since it works on a separate executor, resulting in an exception thrown in `binding.viewFinder.display.rotation`. For this reason, we wrap it in a try-catch block and just push the untouched image within this few transition frames.

### The Full CameraX Configuration
Since it is a very flexible and extensible library, `CameraX` requires slightly more configuration than a camera intent. The following example demonstrates a configuration for the following features:

* Permissions: It handles the necessary permissions for capturing photos. It also includes capturing audio for video capture, but it is not implemented.
* Preview: The camera feed is continuously fed to the preview control in the UI, simulating a view-finder.
* Photo Capture: When the user clicks a button, a photo is captured and saved to the device; it is also added to the media library with metadata.
* Analyze: The analyzer class creates a grayscale version of the camera feed, and it is displayed on the UI in a smaller `ImageView`. It uses OpenCV for this.
* Everything is in an `Activity`.
  
