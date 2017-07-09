package de.hundebarf.fundus.scanner;

import android.app.Fragment;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.hardware.Camera;
import android.hardware.Camera.CameraInfo;
import android.hardware.Camera.Parameters;
import android.os.AsyncTask;
import android.os.Bundle;
import android.util.Log;
import android.view.Display;
import android.view.LayoutInflater;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewGroup.LayoutParams;
import android.view.animation.Animation;
import android.view.animation.Animation.AnimationListener;
import android.widget.FrameLayout;

import java.util.List;

import de.hundebarf.fundus.R;

public class ScannerFragment extends Fragment {
	private static final String TAG = ScannerFragment.class.getSimpleName();

	static final double BOUNDS_FRACTION = 0.6;

	private FrameLayout mScannerPanel;
	private CameraPreview mCameraPreview;
	private TargetReticle mTargetReticle;

	private Camera mCamera;
	private int mCameraId;
	private CameraInfo mCameraInfo = new CameraInfo();
	private AsyncTask<Void, Void, Exception> mStartCameraTask;

	private Decoder mDecoder;

	private PanelAnimation mCollapseAnimation;
	private PanelAnimation mExpandAnimation;
	private int mScannerHeightExpanded;
	private int mScannerHeightCollapsed;
	private boolean mExpanded = false;

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
        mCameraId = selectCamera();
        Camera.getCameraInfo(mCameraId, mCameraInfo);
		mDecoder = new Decoder(getActivity());
	}

	@Override
	public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
		View rootView = inflater.inflate(R.layout.fragment_scanner, container, false);
		mScannerPanel = (FrameLayout) rootView.findViewById(R.id.scanner_panel);
		mCameraPreview = (CameraPreview) rootView.findViewById(R.id.scanner_view);
		mTargetReticle = (TargetReticle) rootView.findViewById(R.id.target_reticle);

		initPanelAnimations();

		return rootView;
	}

	protected int selectCamera() {
        CameraInfo cameraInfo = new CameraInfo();

		/*
		 * Barcodes will be blurry and thus unreadably on the back camera,
		 * should the device not support autofocus (e.g. Samsung Galaxy Tab 2
		 * 7.0). In that case we use the front camera which usually has a closer
		 * focus.
		 */
		PackageManager packageManager = getActivity().getPackageManager();
		boolean hasAutoFocus = packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_AUTOFOCUS);
		boolean hasFrontCamera = packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FRONT);
		if (!hasAutoFocus && hasFrontCamera) {
			// use front camera
			for (int curCameraId = 0; curCameraId < Camera.getNumberOfCameras(); curCameraId++) {
				Camera.getCameraInfo(curCameraId, cameraInfo);
				if (cameraInfo.facing == CameraInfo.CAMERA_FACING_FRONT) {
                    return curCameraId;
				}
			}
		}

        // use default camera;
        return 0;
    }

	private void initPanelAnimations() {
		mScannerHeightExpanded = (int) getResources().getDimension(R.dimen.scanner_height_expanded);
		mScannerHeightCollapsed = (int) getResources().getDimension(R.dimen.scanner_height_collapsed);

		// expand animation
		mExpandAnimation = new PanelAnimation(mScannerPanel, mScannerHeightCollapsed, mScannerHeightExpanded);
		mExpandAnimation.setDuration(200);
		mExpandAnimation.setAnimationListener(new AnimationListener() {
			@Override
			public void onAnimationStart(Animation animation) {
				startCamera();
			}

			@Override
			public void onAnimationRepeat(Animation animation) { }

			@Override
			public void onAnimationEnd(Animation animation) {
				mExpanded = true;
			}
		});

		// collapse animation
		mCollapseAnimation = new PanelAnimation(mScannerPanel, mScannerHeightExpanded, mScannerHeightCollapsed);
		mCollapseAnimation.setDuration(200);
		mCollapseAnimation.setAnimationListener(new AnimationListener() {
			public void onAnimationStart(Animation animation) {	}

			@Override
			public void onAnimationRepeat(Animation animation) {

			}

			@Override
			public void onAnimationEnd(Animation animation) {
				stopCamera();
				mExpanded = false;
			}
		});
	}

    // FIXME not called when flipping from landscape to reverse landscape
	@Override
	public void onResume() {
		super.onResume();
		if (mExpanded) {
            startCamera();
		}
	}

	@Override
	public void onPause() {
		super.onPause();
		stopCamera();
	}

	// FIXME not called when flipping from landscape to reverse landscape
	@Override
	public void onConfigurationChanged(Configuration newConfig) {
		// called when display is rotated
		super.onConfigurationChanged(newConfig);
		if (mCamera != null) {
            Display display = getActivity().getWindowManager().getDefaultDisplay();
            int displayOrientation = getCameraDisplayOrientation(display, mCameraInfo);
			mCamera.setDisplayOrientation(displayOrientation);
			mCameraPreview.stopPreview();
			mCameraPreview.startPreview(mCamera, displayOrientation);
			mDecoder.stopDecoding();
			mDecoder.startDecoding(mCamera, displayOrientation);
		}
	}

	protected final void startCamera() {
		// Task for smooth UI while camera loads
		mStartCameraTask = new AsyncTask<Void, Void, Exception>() {
			@Override
			protected Exception doInBackground(Void... v) {
				try {
					mCamera = Camera.open(mCameraId);
				} catch (RuntimeException e) {
					return e;
				}
				// optimize params
				optimizeCameraParams(mCamera);
				return null;
			}

			@Override
			protected void onPostExecute(Exception e) {
				if (e != null) {
					Log.w(TAG, "Exception while opening camera: " + e.getMessage());
					mCamera = null;
					collapseNoAnim();
					return;
				}
				Display display = getActivity().getWindowManager().getDefaultDisplay();
				int displayOrientation = getCameraDisplayOrientation(display, mCameraInfo);
				mCamera.setDisplayOrientation(displayOrientation);
				mCameraPreview.startPreview(mCamera, displayOrientation);
				mDecoder.startDecoding(mCamera, displayOrientation);
				mCameraPreview.setVisibility(View.VISIBLE);
				mTargetReticle.setVisibility(View.VISIBLE);
			}
		}.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
	}

    protected final void stopCamera() {
		if (mStartCameraTask != null) {
			mStartCameraTask.cancel(true);
		}
		if (mCamera != null) {
			mDecoder.stopDecoding();
			mCameraPreview.stopPreview();
			mCamera.release();
			mCamera = null;
		}
		mCameraPreview.setVisibility(View.INVISIBLE);
		mTargetReticle.setVisibility(View.INVISIBLE);
	}

	protected void optimizeCameraParams(Camera camera) {
		Parameters params = camera.getParameters();
		List<String> focusModes = params.getSupportedFocusModes();
		if (focusModes.contains(Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) {
			params.setFocusMode(Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
		}
		List<String> flashModes = params.getSupportedFlashModes();
		if (flashModes != null
				&& flashModes.contains(Parameters.FLASH_MODE_TORCH)) {
			params.setFlashMode(Parameters.FLASH_MODE_TORCH);
		}
		params.setRecordingHint(true);
		camera.setParameters(params);
	}

	private static int getCameraDisplayOrientation(Display display, CameraInfo cameraInfo) {
		int rotation = display.getRotation();
		int degrees = 0;
		switch (rotation) {
		case Surface.ROTATION_0:
			degrees = 0;
			break;
		case Surface.ROTATION_90:
			degrees = 90;
			break;
		case Surface.ROTATION_180:
			degrees = 180;
			break;
		case Surface.ROTATION_270:
			degrees = 270;
			break;
		}
		int result;
		if (cameraInfo.facing == CameraInfo.CAMERA_FACING_FRONT) {
			result = (cameraInfo.orientation + degrees) % 360;
			result = (360 - result) % 360; // compensate the mirror
		} else { // back-facing
			result = (cameraInfo.orientation - degrees + 360) % 360;
		}
		return result;
	}

	public void expand() {
		if (!isExpanded()) {
			// calls startCamera() when starting
			mScannerPanel.startAnimation(mExpandAnimation);
		}
	}

	public void collapse() {
		if (isExpanded()) {
			// calls stopCamera() when finished
			mScannerPanel.startAnimation(mCollapseAnimation);
		}
	}

	public void collapseNoAnim() {
		// collapse panel instantaneously
		LayoutParams lp = mScannerPanel.getLayoutParams();
		lp.height = mScannerHeightCollapsed;
		mExpanded = false;
		mCameraPreview.setVisibility(View.INVISIBLE);
		mTargetReticle.setVisibility(View.INVISIBLE);
	}

	public boolean isExpanded() {
		return mExpanded;
	}

	public void setOnDecodedCallback(OnDecodedCallback callback) {
		mDecoder.setOnDecodedCallback(callback);
	}

}
