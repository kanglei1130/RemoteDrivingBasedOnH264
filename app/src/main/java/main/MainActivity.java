package main;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.graphics.ImageFormat;
import android.hardware.Camera;
import android.os.Build;
import android.os.Bundle;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.Window;
import android.widget.Button;

import selfdriving.streaming.R;
import com.google.gson.Gson;

import database.DatabaseHelper;
import services.SerialPortConnection;
import services.SerialPortService;
import services.UDPServiceConnection;
import services.UDPService;
import services.SensorService;
import utility.Constants;
import utility.FrameData;
import utility.ControlCommand;
import utility.Trace;

import static java.lang.Math.abs;


public class MainActivity extends Activity implements SurfaceHolder.Callback, Camera.PreviewCallback {
	private final static String TAG = MainActivity.class.getSimpleName();

	// skype frame rate 5-30
	// skype bit rate 30kbps - 950kbps
	// skype resolution 	640*480, 320*240, 160*120
	private final static int DEFAULT_FRAME_RATE = 10;
	private static int frame_bitrate = (int)1e6; // 1mbps
	// 0.5mbps 1mpbs 1.5mpbs 2mbps 2.5mbps 3mbps

	Camera camera;
	SurfaceHolder previewHolder;
	byte[] previewBuffer;
	boolean isStreaming = false;
	AvcEncoder encoder;
    boolean consistentControl = false;

	private String ip = "192.168.11.2";

	public InetAddress address;
	public final int port = 55555;

	List<FrameData> encDataList = new LinkedList<FrameData>();
	List<ControlCommand> encControlCommandList = new LinkedList<ControlCommand>();
	LatencyMonitor latencyMonitor;

	private static Intent mSensor = null;
	private DatabaseHelper dbHelper_ = null;
	private FileOutputStream fOut_ = null;

	// width* height = 640 * 480 or 320 * 240
	private int width = 640;
	private int height = 480;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		this.requestWindowFeature(Window.FEATURE_NO_TITLE);
		this.setContentView(R.layout.activity_main);

		if (Build.MODEL.equals("Nexus 5X")){
			//Nexus 5X's screen is reversed, ridiculous! the image sensor does not fit in correct orientation
			setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE);
		} else {
			setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
		}


		this.findViewById(R.id.btntest).setOnClickListener(
				new View.OnClickListener() {
					@Override
					public void onClick(View v) {
						Intent intent = new Intent(MainActivity.this, SettingsActivity.class);
						startActivity(intent);
					}
				});

		this.findViewById(R.id.btnstart).setOnClickListener(
				new View.OnClickListener() {
					@Override
					public void onClick(View v) {
						if (isStreaming) {
							((Button) v).setText("Start");
							stopServices();
							stopStream();
						} else {
							startStream();
							startServices();
						}
					}
				});


		setupFolders();

		SurfaceView svCameraPreview = (SurfaceView) this.findViewById(R.id.svCameraPreview);
		this.previewHolder = svCameraPreview.getHolder();
		this.previewHolder.addCallback(this);
	}


	private void startServices() {
		startSerialService();
		startUDPService();
		mSensor = new Intent(this, SensorService.class);
		startService(mSensor);
		LocalBroadcastManager.getInstance(this).registerReceiver(mMessageReceiver, new IntentFilter("sensor"));
		LocalBroadcastManager.getInstance(this).registerReceiver(mMessageReceiver, new IntentFilter("udp"));
		LocalBroadcastManager.getInstance(this).registerReceiver(mMessageReceiver, new IntentFilter("control"));

		long time = System.currentTimeMillis();
		dbHelper_ = new DatabaseHelper();
		dbHelper_.createDatabase(time);

		try {
			File file = new File(Constants.kVideoFolder.concat(String.valueOf(time)).concat(".raw"));
			this.fOut_ = new FileOutputStream(file, true);
		} catch (Exception e) {
			e.printStackTrace();
		}

		latencyMonitor = new LatencyMonitor();
	}

	private void setupFolders () {
		File dbDir = new File(Constants.kDBFolder);
		File videoDir = new File(Constants.kVideoFolder);
		if (!dbDir.exists()) {
			dbDir.mkdirs();
		}
		if(!videoDir.exists()) {
			videoDir.mkdir();
		}
	}

	protected void onDestroy() {
		super.onDestroy();
		stopServices();
	}

	private void stopServices() {
		stopUDPService();
		stopSerialService();
		if (mSensor!= null){
			stopService(mSensor);
			mSensor = null;
		}
		if (dbHelper_!= null) {
			dbHelper_.closeDatabase();
		}
		if (fOut_ != null) {
			try {
				fOut_.close();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
		if (mMessageReceiver!= null) {
			LocalBroadcastManager.getInstance(this).unregisterReceiver(mMessageReceiver);
		}
	}

	@Override
	protected void onPause() {
		this.stopStream();
		if (encoder != null)
			encoder.close();

		super.onPause();
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		getMenuInflater().inflate(R.menu.main, menu);
		return true;
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		int id = item.getItemId();
		if (id == R.id.action_settings)
			return true;
		return super.onOptionsItemSelected(item);
	}


	@Override
	public void surfaceCreated(SurfaceHolder holder) {
		startCamera();
	}

	@Override
	public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
		Log.d(TAG, "surface changed");
	}

	@Override
	public void surfaceDestroyed(SurfaceHolder holder) {
		stopCamera();
	}


	private void loadPreferences() {
		List<Integer> resolution = SettingsActivity.getResolution(MainActivity.this);
		this.width = resolution.get(0);
		this.height = resolution.get(1);

		this.ip = SettingsActivity.getRemoteIP(MainActivity.this);
		Log.d(TAG, "Resolution:" + this.width + "x" + this.height);

		List<Double> bitRate = SettingsActivity.getBitRate(MainActivity.this);
		if (bitRate.get(0) != null) {
			double temp = bitRate.get(0);
			this.frame_bitrate = (int) temp * 1000000;
		}
	}


	private void startStream() {

		loadPreferences();

		stopCamera();
		startCamera();

		this.encoder = new AvcEncoder();
		this.encoder.init(width, height, DEFAULT_FRAME_RATE, frame_bitrate);
		try {
			this.address = InetAddress.getByName(ip);
		} catch (UnknownHostException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
			return;
		}

		this.isStreaming = true;

		Thread streamingThread = new Thread(senderRun);
		streamingThread.start();
		Thread controlThread = new Thread(controlMessageThread);
		controlThread.start();

		((Button) this.findViewById(R.id.btnstart)).setText("Stop");
		this.findViewById(R.id.btntest).setEnabled(false);


	}

	private void stopStream() {
		this.isStreaming = false;

		if (this.encoder != null)
			this.encoder.close();
		this.encoder = null;

		this.findViewById(R.id.btntest).setEnabled(true);
	}

	private void startCamera() {


		Log.d(TAG, "width: " + width + " height:" + height);


		this.previewHolder.setFixedSize(width, height);

		int stride = (int) Math.ceil(width / 16.0f) * 16;
		int cStride = (int) Math.ceil(width / 32.0f) * 16;
		final int frameSize = stride * height;
		final int qFrameSize = cStride * height / 2;

		this.previewBuffer = new byte[frameSize + qFrameSize * 2];

		try {
			camera = Camera.open();
			camera.setPreviewDisplay(this.previewHolder);

			Camera.Parameters params = camera.getParameters();
			params.setPreviewSize(width, height);
			params.setPreviewFormat(ImageFormat.YV12);
			camera.setParameters(params);
			camera.addCallbackBuffer(previewBuffer);
			camera.setPreviewCallbackWithBuffer(this);
			camera.startPreview();

			// adjust the orientation
			camera.setDisplayOrientation(0);
		} catch (IOException e) {
			//TODO:
		} catch (RuntimeException e) {
			//TODO:
		}
	}


	private void stopCamera() {
		if (camera != null) {
			camera.setPreviewCallback(null);
			camera.stopPreview();
			camera.release();
			camera = null;
		}
	}

	/**
	 * we cannot store the video when we use onPreviewFrame
	 * @param data
	 * @param camera
	 */
	@Override
	public void onPreviewFrame(byte[] data, Camera camera) {
		camera.addCallbackBuffer(previewBuffer);

		if (isStreaming) {
			/*
			if (FrameData.sequenceIndex%2 == 0) {
				encoder.forceIFrame();
			}
			*/
			// long time = System.currentTimeMillis();
			FrameData frameData = encoder.offerEncoder(data);
			// Log.d(TAG, String.valueOf(System.currentTimeMillis() - time));

            List<FrameData> frames = frameData.split();
			for (int i = 0; i < frames.size(); ++i) {
				FrameData frame = frames.get(i);
				dbHelper_.insertFrameData(frame);
				if (frame.getDataSize() > 0) {
					synchronized (encDataList) {
						encDataList.add(frame);
					}
				}
			}

		}
	}

	private void appendToVideoFile(byte [] data) {
		try {
			int datalen = data.length;
			String strlen = String.valueOf(datalen);
			int encodelen = strlen.length();
			byte [] header = new byte[encodelen + 1];
			for (int i = 0; i < encodelen; ++ i) {
				header[i] = (byte)strlen.charAt(i);
			}
			header[encodelen] = '\n';
			this.fOut_.write(header, 0, encodelen + 1);
			this.fOut_.write(data, 0, data.length);
		} catch (IOException e) {
			e.printStackTrace();
		}
	}


	//initial UDPConnetion
	private static Intent mUDPService = null;
	private static UDPServiceConnection mUDPConnection = null;

	private void startUDPService() {
		Log.d(TAG, "startUDPService");
		mUDPService = new Intent(this, UDPService.class);
		mUDPConnection = new UDPServiceConnection();
		bindService(mUDPService, mUDPConnection, Context.BIND_AUTO_CREATE);
		startService(mUDPService);
	}

	private void stopUDPService() {
		if (mUDPService != null && mUDPConnection != null) {
			unbindService(mUDPConnection);
			stopService(mUDPService);
			mUDPService = null;
			mUDPConnection = null;
		}
	}

	//initial SerialPortConnection
	private static Intent mSerial = null;
	private static SerialPortConnection mSerialPortConnection = null;

	private void startSerialService() {
		Log.d(TAG, "start serial service");
		mSerial = new Intent(this, SerialPortService.class);
		mSerialPortConnection = new SerialPortConnection();
		bindService(mSerial, mSerialPortConnection, Context.BIND_AUTO_CREATE);
		startService(mSerial);
	}

	private void stopSerialService() {

		Log.d(TAG, "stop serial service");
		if(mSerial != null && mSerialPortConnection != null) {
			mSerialPortConnection.sendCommandFunction("throttle(0.0)");
			mSerialPortConnection.sendCommandFunction("steering(0.5)");

			unbindService(mSerialPortConnection);
			stopService(mSerial);
			mSerial = null;
			mSerialPortConnection = null;
		}
	}


	private BroadcastReceiver mMessageReceiver = new BroadcastReceiver(){
		@Override
		public void onReceive(Context context, Intent intent) {

			if (intent.getAction().equals("sensor")) {
				String message = intent.getStringExtra("trace");
				Trace trace = new Trace();
				trace.fromJson(message);

				if (dbHelper_.isOpen()) {
					dbHelper_.insertSensorData(trace);
				}
				// Log.d(TAG, "sensor data: " + message);
			} else if(intent.getAction().equals("udp")) {
				String message = intent.getStringExtra("latency");

				Gson gson = new Gson();
				FrameData frameData = gson.fromJson(message, FrameData.class);

				if (dbHelper_.isOpen()) {
					dbHelper_.updateFrameData(frameData);
				}
				//Log.d(TAG, "frame data update: " + dbHelper_.updateFrameData(frameData));

			} else if (intent.getAction().equals("control")){
				String receivedCommand = intent.getStringExtra("control");
				Gson gson = new Gson();
				// Log.d(TAG,"control: " + receivedCommand);
				ControlCommand controlCommand = gson.fromJson(receivedCommand, ControlCommand.class);
				if (controlCommand != null) {
				    latencyMonitor.recordOneWayLatency(System.currentTimeMillis() - controlCommand.timeStamp);
					synchronized (encControlCommandList) {
						encControlCommandList.add(controlCommand);
					}
				}
			} else {
				Log.d(TAG, "unknown intent: " + intent.getAction());
			}
		}

	};


	/**
	 * push data to sender
	 */
	Runnable senderRun = new Runnable() {
		@Override
		public void run() {
			while (isStreaming) {
				boolean empty = false;
				FrameData frameData = null;

				synchronized (encDataList) {
					if (encDataList.size() == 0) {
						empty = true;
					} else
						frameData = encDataList.remove(0);
				}
				if (empty) {
					try {
						Thread.sleep(1);
					} catch (InterruptedException e) {
						e.printStackTrace();
					}
					continue;
				}
				//we can start 2 thread, one is with timeStamp header send to one server and get timeStamp back
				// the other thread will send without header and directly show the video.
				appendToVideoFile(frameData.rawFrameData);
				if (mUDPConnection != null && mUDPConnection.isRunning()) {
					mUDPConnection.sendData(frameData, address, port);
				}
			}
		}
	};

	/**
	 * push data to sender
	 */
	Runnable controlMessageThread = new Runnable() {
		@Override
		public void run() {
			while (isStreaming) {
				boolean empty = false;
				ControlCommand controlCommand = null;

				synchronized (encControlCommandList) {
					if (encControlCommandList.size() == 0) {
						empty = true;
					} else
						controlCommand = encControlCommandList.remove(0);
				}
				if (empty) {
					try {
						Thread.sleep(1);
					} catch (InterruptedException e) {
						e.printStackTrace();
					}
					continue;
				}
				/*
				* delay for consistence control
				* */
                long timeDiff = System.currentTimeMillis() - controlCommand.timeStamp;
                if (consistentControl) {
                    long diff = timeDiff - latencyMonitor.getAverageOneWayLatency();
                    if (diff < 0) {
                        try {
                            Thread.sleep(Math.abs(diff));
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                    }
                }
				if (mSerialPortConnection != null) {
					double throttle = (float)0.0;
					double steering = controlCommand.steering;
					if(controlCommand.throttle > 0.5) {
						throttle = (float) ((controlCommand.throttle-0.5) * 0.4 + 1.0);
					}
					mSerialPortConnection.sendCommandFunction("throttle(" + String.valueOf(throttle) + ")");
					mSerialPortConnection.sendCommandFunction("steering(" + String.valueOf(steering) + ")");
				}
			}
		}
	};

}
