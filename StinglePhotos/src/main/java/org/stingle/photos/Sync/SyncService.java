package org.stingle.photos.Sync;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.util.Log;

import org.stingle.photos.Db.StingleDbContract;
import org.stingle.photos.Db.StingleDbFile;
import org.stingle.photos.Db.StingleDbHelper;
import org.stingle.photos.GalleryActivity;
import org.stingle.photos.R;
import org.stingle.photos.Util.Helpers;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SyncService extends Service {
	final Messenger mMessenger = new Messenger(new IncomingHandler());
	private ArrayList<Messenger> mClients = new ArrayList<Messenger>();

	static final public int MSG_REGISTER_CLIENT = 1;
	static final public int MSG_UNREGISTER_CLIENT = 2;
	static final public int MSG_SYNC_CURRENT_FILE = 3;
	static final public int MSG_SYNC_UPLOAD_PROGRESS = 4;
	static final public int MSG_SYNC_FINISHED = 5;
	static final public int MSG_START_SYNC = 6;
	static final public int MSG_GET_SYNC_STATUS = 7;
	static final public int MSG_RESP_SYNC_STATUS = 8;
	static final public int MSG_SYNC_STATUS_CHANGE = 9;
	static final public int MSG_REFRESH_GALLERY = 10;
	static final public int MSG_REFRESH_GALLERY_ITEM = 11;

	static final public int STATUS_IDLE = 0;
	static final public int STATUS_REFRESHING = 1;
	static final public int STATUS_UPLOADING = 2;
	static final public int STATUS_NO_SPACE_LEFT = 3;

	protected int currentStatus = STATUS_IDLE;
	protected SyncManager.UploadToCloudAsyncTask.UploadProgress progress;
	private boolean restartSyncAfterComplete = false;
	private ExecutorService cachedThreadPool;
	private NotificationManager mNotifyManager;
	private Notification.Builder notificationBuilder;
	private boolean isNotificationActive = false;

	public SyncService() {
	}

	@Override
	public void onCreate() {
		super.onCreate();

		cachedThreadPool = Executors.newCachedThreadPool();
		mNotifyManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

		progress = new SyncManager.UploadToCloudAsyncTask.UploadProgress() {
			@Override
			public void currentFile(String filename) {
				super.currentFile(filename);
				sendStringToUi(MSG_SYNC_CURRENT_FILE, "currentFile", filename);
			}

			@Override
			public void fileUploadFinished(String filename, int folder) {
				StingleDbHelper db = new StingleDbHelper(SyncService.this, (folder == SyncManager.FOLDER_TRASH ? StingleDbContract.Files.TABLE_NAME_TRASH : StingleDbContract.Files.TABLE_NAME_FILES));

				Integer filePos = db.getFilePositionByFilename(filename);
				db.close();

				if(filePos != null) {
					HashMap<String, Integer> values = new HashMap<String, Integer>();
					values.put("position", filePos);
					sendIntToUi(MSG_REFRESH_GALLERY_ITEM, values);
				}
			}

			@Override
			public void uploadProgress(int uploadedFilesCount) {
				super.uploadProgress(uploadedFilesCount);
				showNotification();
				HashMap<String, Integer> values = new HashMap<String, Integer>();
				values.put("uploadedFilesCount", uploadedFilesCount);
				values.put("totalItemsNumber", progress.totalItemsNumber);
				sendIntToUi(MSG_SYNC_UPLOAD_PROGRESS, values);
				notificationBuilder.setProgress(progress.totalItemsNumber, uploadedFilesCount, false);
				notificationBuilder.setContentTitle(getString(R.string.uploading_file, String.valueOf(uploadedFilesCount), String.valueOf(progress.totalItemsNumber)));
				mNotifyManager.notify(R.string.sync_service_started, notificationBuilder.build());
			}
		};

		//startSync();
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		return START_STICKY;
	}

	protected void startSync(){
		if(currentStatus != STATUS_IDLE){
			restartSyncAfterComplete = true;
			return;
		}
		currentStatus = STATUS_REFRESHING;
		sendIntToUi(MSG_SYNC_STATUS_CHANGE, "newStatus", currentStatus);



		SyncManager.syncCloudToLocalDb(this, new SyncManager.OnFinish() {
			@Override
			public void onFinish(Boolean needToUpdateUI) {
				if (needToUpdateUI){
					sendMessageToUI(MSG_REFRESH_GALLERY);
				}
				currentStatus = STATUS_UPLOADING;
				sendIntToUi(MSG_SYNC_STATUS_CHANGE, "newStatus", currentStatus);

				boolean isUploadSuspended = Helpers.getPreference(SyncService.this, SyncManager.PREF_SUSPEND_UPLOAD, false);
				if(isUploadSuspended){
					int lastAvailableSpace = Helpers.getPreference(SyncService.this, SyncManager.PREF_LAST_AVAILABLE_SPACE, 0);
					int availableSpace = Helpers.getAvailableUploadSpace(SyncService.this);
					if(availableSpace > lastAvailableSpace){
						Helpers.storePreference(SyncService.this, SyncManager.PREF_SUSPEND_UPLOAD, false);
						Helpers.deletePreference(SyncService.this, SyncManager.PREF_LAST_AVAILABLE_SPACE);
						isUploadSuspended = false;
						Log.d("upload", "resuming upload");
					}
				}

				if(!isUploadSuspended) {
					SyncManager.uploadToCloud(SyncService.this, progress, new SyncManager.OnFinish() {
						@Override
						public void onFinish(Boolean needUpdateUI) {
							currentStatus = STATUS_IDLE;
							sendIntToUi(MSG_SYNC_STATUS_CHANGE, "newStatus", currentStatus);
							//sendMessageToUI(MSG_SYNC_FINISHED);
							if(restartSyncAfterComplete){
								restartSyncAfterComplete = false;
								startSync();
							}
							isNotificationActive = false;
							stopForeground(true);
						}
					}, cachedThreadPool);
				}
				else{
					Log.d("upload", "upload is disabled, no space");
					currentStatus = STATUS_IDLE;
					sendIntToUi(MSG_SYNC_STATUS_CHANGE, "newStatus", STATUS_NO_SPACE_LEFT);
					//sendMessageToUI(MSG_SYNC_FINISHED);
				}
			}
		}, cachedThreadPool);

		/*SyncManager.syncFSToDB(SyncService.this, new SyncManager.OnFinish(){
			@Override
			public void onFinish(Boolean needUpdateFSToDB){

			}
		}, cachedThreadPool);*/
	}

	@Override
	public IBinder onBind(Intent intent) {
		return mMessenger.getBinder();
	}

	private void sendStringToUi(int type, String key, String value) {
		for (int i=mClients.size()-1; i>=0; i--) {
			try {
				Bundle b = new Bundle();
				b.putString(key, value);
				Message msg = Message.obtain(null, type);
				msg.setData(b);
				mClients.get(i).send(msg);
			}
			catch (RemoteException e) {
				mClients.remove(i);
			}
		}
	}

	private void sendIntToUi(int type, String key, int value) {
		for (int i=mClients.size()-1; i>=0; i--) {
			try {
				Bundle b = new Bundle();
				b.putInt(key, value);
				Message msg = Message.obtain(null, type);
				msg.setData(b);
				mClients.get(i).send(msg);
			}
			catch (RemoteException e) {
				mClients.remove(i);
			}
		}
	}

	private void sendIntToUi(int type, HashMap<String, Integer> values) {
		for (int i=mClients.size()-1; i>=0; i--) {
			try {
				Bundle b = new Bundle();
				for(String key : values.keySet()) {
					b.putInt(key, values.get(key));
				}
				Message msg = Message.obtain(null, type);
				msg.setData(b);
				mClients.get(i).send(msg);
			}
			catch (RemoteException e) {
				mClients.remove(i);
			}
		}
	}

	private void sendMessageToUI(int type) {
		for (int i=mClients.size()-1; i>=0; i--) {
			try {
				mClients.get(i).send(Message.obtain(null, type));
			}
			catch (RemoteException e) {
				mClients.remove(i);
			}
		}
	}

	class IncomingHandler extends Handler {
		@Override
		public void handleMessage(Message msg) {
			switch (msg.what) {
				case MSG_REGISTER_CLIENT:
					mClients.add(msg.replyTo);
					break;
				case MSG_UNREGISTER_CLIENT:
					mClients.remove(msg.replyTo);
					break;
				case MSG_START_SYNC:
					startSync();
					break;
				case MSG_GET_SYNC_STATUS:
					if(progress != null) {
						for (int i = mClients.size() - 1; i >= 0; i--) {
							try {
								Bundle b = new Bundle();
								b.putInt("totalItemsNumber", progress.totalItemsNumber);
								b.putString("currentFile", progress.currentFile);
								b.putInt("uploadedFilesCount", progress.uploadedFilesCount);
								b.putInt("syncStatus", currentStatus);
								Message msgToSend = Message.obtain(null, MSG_RESP_SYNC_STATUS);
								msgToSend.setData(b);
								mClients.get(i).send(msgToSend);
							} catch (RemoteException e) {
								mClients.remove(i);
							}
						}
					}
					break;
				/*case MSG_SET_VALUE:
					mValue = msg.arg1;
					for (int i = mClients.size() - 1; i >= 0; i--) {
						try {
							mClients.get(i).send(Message.obtain(null, MSG_SET_VALUE, mValue, 0));
						} catch (RemoteException e) {
							mClients.remove(i);
						}
					}
					break;*/
				default:
					super.handleMessage(msg);
			}
		}
	}

	private void showNotification() {
		if(isNotificationActive){
			return;
		}

		isNotificationActive = true;
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			String NOTIFICATION_CHANNEL_ID = "org.stingle.photos.sync";
			String channelName = "Sync Service";
			NotificationChannel chan = new NotificationChannel(NOTIFICATION_CHANNEL_ID, channelName, NotificationManager.IMPORTANCE_NONE);
			chan.setLightColor(getColor(R.color.primaryLightColor));
			chan.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
			NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
			assert manager != null;
			manager.createNotificationChannel(chan);
			notificationBuilder = new Notification.Builder(this, NOTIFICATION_CHANNEL_ID);
		}
		else{
			notificationBuilder = new Notification.Builder(this);
		}

		PendingIntent contentIntent = PendingIntent.getActivity(this, 0,
				new Intent(this, GalleryActivity.class), 0);

		Notification notification = notificationBuilder
				.setSmallIcon(R.drawable.ic_sp)  // the status icon
				.setWhen(System.currentTimeMillis())  // the time stamp
				.setContentIntent(contentIntent)  // The intent to send when the entry is clicked
				.build();

		mNotifyManager.notify(R.string.sync_service_started, notification);
		startForeground(R.string.sync_service_started, notification);
	}
}
