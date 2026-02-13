package com.cloudwebrtc.webrtc.video;

import android.content.Context;
import android.hardware.usb.UsbDevice;
import android.util.Log;

import com.herohan.uvcapp.CameraHelper;
import com.herohan.uvcapp.ICameraHelper;
import com.serenegiant.usb.IFrameCallback;
import com.serenegiant.usb.Size;
import com.serenegiant.usb.UVCCamera;

import org.webrtc.CapturerObserver;
import org.webrtc.NV21Buffer;
import org.webrtc.SurfaceTextureHelper;
import org.webrtc.VideoCapturer;
import org.webrtc.VideoFrame;

import java.nio.ByteBuffer;
import java.util.List;

/**
 * UVC Camera VideoCapturer for WebRTC integration.
 * Captures frames from USB cameras and feeds them into WebRTC pipeline.
 */
public class UvcVideoCapturer implements VideoCapturer {
    private static final String TAG = "UvcVideoCapturer";

    private final Context context;
    private final UsbDevice usbDevice;
    private ICameraHelper cameraHelper;
    private CapturerObserver capturerObserver;
    private SurfaceTextureHelper surfaceTextureHelper;

    private int width = 640;
    private int height = 480;
    private int fps = 30;
    private boolean isCapturing = false;

    private final IFrameCallback frameCallback = new IFrameCallback() {
        @Override
        public void onFrame(ByteBuffer frame) {
            if (!isCapturing || capturerObserver == null) {
                return;
            }

            try {
                // Convert ByteBuffer to byte array
                byte[] data = new byte[frame.remaining()];
                frame.get(data);
                frame.rewind();

                // Create NV21Buffer (UVC cameras typically output NV21/YUV)
                NV21Buffer nv21Buffer = new NV21Buffer(data, width, height, null);

                // Create VideoFrame
                long timestampNs = System.nanoTime();
                VideoFrame videoFrame = new VideoFrame(nv21Buffer, 0, timestampNs);

                // Send to WebRTC
                capturerObserver.onFrameCaptured(videoFrame);

                // Release the frame
                videoFrame.release();
            } catch (Exception e) {
                Log.e(TAG, "Error processing frame: " + e.getMessage());
            }
        }
    };

    public UvcVideoCapturer(Context context, UsbDevice usbDevice) {
        this.context = context;
        this.usbDevice = usbDevice;
    }

    @Override
    public void initialize(SurfaceTextureHelper surfaceTextureHelper, Context applicationContext,
                          CapturerObserver capturerObserver) {
        this.surfaceTextureHelper = surfaceTextureHelper;
        this.capturerObserver = capturerObserver;

        Log.d(TAG, "UvcVideoCapturer initialized");
    }

    @Override
    public void startCapture(int width, int height, int framerate) {
        Log.d(TAG, "startCapture: " + width + "x" + height + "@" + framerate);

        this.width = width;
        this.height = height;
        this.fps = framerate;

        if (surfaceTextureHelper != null) {
            surfaceTextureHelper.getHandler().post(() -> {
                startCameraInternal();
            });
        } else {
            startCameraInternal();
        }
    }

    private void startCameraInternal() {
        try {
            cameraHelper = new CameraHelper();
            cameraHelper.setStateCallback(new ICameraHelper.StateCallback() {
                @Override
                public void onAttach(UsbDevice device) {
                    Log.d(TAG, "Camera attached: " + device.getDeviceName());
                    cameraHelper.selectDevice(device);
                }

                @Override
                public void onDeviceOpen(UsbDevice device, boolean isFirstOpen) {
                    Log.d(TAG, "Camera opened: " + device.getDeviceName());
                    configureAndStart();
                }

                @Override
                public void onCameraOpen(UsbDevice device) {
                    Log.d(TAG, "Camera streaming started");
                    isCapturing = true;
                    if (capturerObserver != null) {
                        capturerObserver.onCapturerStarted(true);
                    }
                }

                @Override
                public void onCameraClose(UsbDevice device) {
                    Log.d(TAG, "Camera streaming stopped");
                    isCapturing = false;
                }

                @Override
                public void onDeviceClose(UsbDevice device) {
                    Log.d(TAG, "Camera closed");
                }

                @Override
                public void onDetach(UsbDevice device) {
                    Log.d(TAG, "Camera detached");
                }

                @Override
                public void onCancel(UsbDevice device) {
                    Log.d(TAG, "Camera cancelled");
                }
            });

            // Manually trigger device selection if we have a specific device
            if (usbDevice != null) {
                cameraHelper.selectDevice(usbDevice);
            }

        } catch (Exception e) {
            Log.e(TAG, "Failed to start camera: " + e.getMessage());
            if (capturerObserver != null) {
                capturerObserver.onCapturerStarted(false);
            }
        }
    }

    private void configureAndStart() {
        try {
            // Get supported sizes and find best match
            List<Size> sizes = cameraHelper.getSupportedSizeList();
            Size selectedSize = findBestSize(sizes, width, height);

            if (selectedSize != null) {
                width = selectedSize.width;
                height = selectedSize.height;
                Log.d(TAG, "Selected resolution: " + width + "x" + height);
            }

            // Set preview size
            cameraHelper.setPreviewSize(new Size(width, height));

            // Set frame callback for raw frames
            cameraHelper.setFrameCallback(frameCallback, UVCCamera.PIXEL_FORMAT_NV21);

            // Open camera (this will trigger onCameraOpen)
            cameraHelper.openCamera();

        } catch (Exception e) {
            Log.e(TAG, "Failed to configure camera: " + e.getMessage());
        }
    }

    private Size findBestSize(List<Size> sizes, int targetWidth, int targetHeight) {
        if (sizes == null || sizes.isEmpty()) {
            return null;
        }

        Size bestSize = sizes.get(0);
        int targetPixels = targetWidth * targetHeight;
        int bestDiff = Integer.MAX_VALUE;

        for (Size size : sizes) {
            int diff = Math.abs(size.width * size.height - targetPixels);
            if (diff < bestDiff) {
                bestDiff = diff;
                bestSize = size;
            }
        }

        return bestSize;
    }

    @Override
    public void stopCapture() throws InterruptedException {
        Log.d(TAG, "stopCapture");
        isCapturing = false;

        if (cameraHelper != null) {
            try {
                cameraHelper.closeCamera();
                cameraHelper.release();
            } catch (Exception e) {
                Log.e(TAG, "Error stopping capture: " + e.getMessage());
            }
            cameraHelper = null;
        }

        if (capturerObserver != null) {
            capturerObserver.onCapturerStopped();
        }
    }

    @Override
    public void changeCaptureFormat(int width, int height, int framerate) {
        Log.d(TAG, "changeCaptureFormat: " + width + "x" + height + "@" + framerate);
        this.width = width;
        this.height = height;
        this.fps = framerate;

        // Restart with new format
        if (isCapturing) {
            try {
                stopCapture();
                startCapture(width, height, framerate);
            } catch (InterruptedException e) {
                Log.e(TAG, "Interrupted while changing format");
            }
        }
    }

    @Override
    public void dispose() {
        Log.d(TAG, "dispose");
        try {
            stopCapture();
        } catch (InterruptedException e) {
            Log.e(TAG, "Interrupted while disposing");
        }
    }

    @Override
    public boolean isScreencast() {
        return false;
    }
}
