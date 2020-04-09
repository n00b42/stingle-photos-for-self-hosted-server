package org.stingle.photos.Sync;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.os.AsyncTask;

import org.json.JSONObject;
import org.stingle.photos.AsyncTasks.Sync.SyncCloudToLocalDbAsyncTask;
import org.stingle.photos.AsyncTasks.Sync.UploadToCloudAsyncTask;
import org.stingle.photos.Auth.KeyManagement;
import org.stingle.photos.Crypto.Crypto;
import org.stingle.photos.Crypto.CryptoException;
import org.stingle.photos.Crypto.CryptoHelpers;
import org.stingle.photos.Db.Objects.StingleDbFile;
import org.stingle.photos.Db.Objects.StingleDbAlbum;
import org.stingle.photos.Db.Query.AlbumFilesDb;
import org.stingle.photos.Db.Query.AlbumsDb;
import org.stingle.photos.Db.Query.GalleryTrashDb;
import org.stingle.photos.Net.HttpsClient;
import org.stingle.photos.Net.StingleResponse;
import org.stingle.photos.R;
import org.stingle.photos.StinglePhotosApplication;
import org.stingle.photos.Util.Helpers;

import java.io.IOException;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.Executor;

public class SyncManager {

	protected Context context;
	protected SQLiteDatabase db;
	public static final String PREF_LAST_SEEN_TIME = "file_last_seen_time";
	public static final String PREF_LAST_DEL_SEEN_TIME = "file_last_del_seen_time";
	public static final String PREF_LAST_SPACE_USED = "last_space_used";
	public static final String PREF_LAST_SPACE_QUOTA = "last_space_quota";
	public static final String PREF_SUSPEND_UPLOAD = "suspend_upload";
	public static final String PREF_LAST_AVAILABLE_SPACE = "last_available_space";
	public static final String PREF_FIRST_SYNC_DONE = "first_sync_done";

	public static final String PREF_BACKUP_ENABLED = "enable_backup";
	public static final String PREF_BACKUP_ONLY_WIFI = "upload_only_on_wifi";
	public static final String PREF_BACKUP_BATTERY_LEVEL = "upload_battery_level";

	public static final String SP_FILE_MIME_TYPE = "application/stinglephoto";

	public static final int GALLERY = 0;
	public static final int TRASH = 1;
	public static final int ALBUM = 2;
	public static final int SHARE = 3;

	public static final int DELETE_EVENT_MAIN = 1;
	public static final int DELETE_EVENT_TRASH = 2;
	public static final int DELETE_EVENT_DELETE = 3;
	public static final int DELETE_EVENT_ALBUM = 4;
	public static final int DELETE_EVENT_ALBUM_FILE = 5;

	public SyncManager(Context context) {
		this.context = context;
	}


	public static UploadToCloudAsyncTask uploadToCloud(Context context, UploadToCloudAsyncTask.UploadProgress progress, OnFinish onFinish, Executor executor) {
		if (executor == null) {
			executor = AsyncTask.THREAD_POOL_EXECUTOR;
		}
		UploadToCloudAsyncTask uploadTask = new UploadToCloudAsyncTask(context, progress, onFinish);
		uploadTask.executeOnExecutor(executor);
		return uploadTask;

	}

	public static void syncCloudToLocalDb(Context context, OnFinish onFinish, Executor executor) {
		if (executor == null) {
			executor = AsyncTask.THREAD_POOL_EXECUTOR;
		}
		(new SyncCloudToLocalDbAsyncTask(context, onFinish)).executeOnExecutor(executor);
	}


	public static boolean downloadFile(Context context, String filename, String outputPath, boolean isThumb, int set) {
		HashMap<String, String> postParams = new HashMap<String, String>();

		postParams.put("token", KeyManagement.getApiToken(context));
		postParams.put("file", filename);
		postParams.put("set", String.valueOf(set));
		if (isThumb) {
			postParams.put("thumb", "1");
		}

		try {
			HttpsClient.downloadFile(StinglePhotosApplication.getApiUrl() + context.getString(R.string.download_file_path), postParams, outputPath);
			return true;
		} catch (IOException | NoSuchAlgorithmException | KeyManagementException e) {

		}
		return false;
	}

	public static abstract class OnFinish {
		public abstract void onFinish(Boolean needToUpdateUI);
	}

	public static void resetAndStopSync(Context context) {
		Helpers.deletePreference(context, SyncManager.PREF_LAST_SEEN_TIME);
		Helpers.deletePreference(context, SyncManager.PREF_LAST_DEL_SEEN_TIME);

		GalleryTrashDb galleryDb = new GalleryTrashDb(context, SyncManager.GALLERY);
		galleryDb.truncateTable();
		galleryDb.close();

		GalleryTrashDb trashDb = new GalleryTrashDb(context, SyncManager.TRASH);
		trashDb.truncateTable();
		trashDb.close();

		AlbumFilesDb albumFilesDb = new AlbumFilesDb(context);
		albumFilesDb.truncateTable();
		albumFilesDb.close();

		AlbumsDb albumsDb = new AlbumsDb(context);
		albumsDb.truncateTable();
		albumsDb.close();
	}

	public static boolean moveFiles(Context context, ArrayList<StingleDbFile> files, int fromSet, int toSet, String fromAlbumId, String toAlbumId, boolean isMoving) {
		if(files == null || files.size() == 0){
			return true;
		}
		try {
			Crypto crypto = StinglePhotosApplication.getCrypto();
			AlbumsDb albumsDb = new AlbumsDb(context);
			AlbumFilesDb albumFilesDb = new AlbumFilesDb(context);

			if (fromSet == SyncManager.GALLERY && toSet == SyncManager.ALBUM) {

				if (toAlbumId == null) {
					return false;
				}

				GalleryTrashDb galleryDb = new GalleryTrashDb(context, SyncManager.GALLERY);

				StingleDbAlbum album = albumsDb.getAlbumById(toAlbumId);

				HashMap<String, String> newHeaders = new HashMap<>();
				for (StingleDbFile file : files) {
					newHeaders.put(file.filename, crypto.reencryptFileHeaders(file.headers, Crypto.base64ToByteArray(album.albumPK), null, null));
				}

				if (!SyncManager.notifyCloudAboutFileMove(context, files, fromSet, toSet, fromAlbumId, toAlbumId, isMoving, newHeaders)) {
					return false;
				}

				for (StingleDbFile file : files) {
					albumFilesDb.insertAlbumFile(album.albumId, file.filename, file.isLocal, file.isRemote, file.version, newHeaders.get(file.filename), file.dateCreated, System.currentTimeMillis());
					if (isMoving) {
						galleryDb.deleteFile(file.filename);
					}
				}

				galleryDb.close();
			} else if (fromSet == SyncManager.ALBUM && (toSet == SyncManager.GALLERY || toSet == SyncManager.TRASH)) {

				if (fromAlbumId == null) {
					return false;
				}

				GalleryTrashDb galleryTrashDb = new GalleryTrashDb(context, toSet);

				StingleDbAlbum album = albumsDb.getAlbumById(fromAlbumId);
				Crypto.AlbumData albumData = crypto.parseAlbumData(album.data);

				HashMap<String, String> newHeaders = new HashMap<>();
				for (StingleDbFile file : files) {
					newHeaders.put(file.filename, crypto.reencryptFileHeaders(file.headers, crypto.getPublicKey(), albumData.privateKey, Crypto.base64ToByteArray(album.albumPK)));
				}

				if (!SyncManager.notifyCloudAboutFileMove(context, files, fromSet, toSet, fromAlbumId, toAlbumId, isMoving, newHeaders)) {
					return false;
				}

				for (StingleDbFile file : files) {
					galleryTrashDb.insertFile(file.filename, file.isLocal, file.isRemote, file.version, file.dateCreated, System.currentTimeMillis(), newHeaders.get(file.filename));
					if (isMoving) {
						albumFilesDb.deleteAlbumFile(file.id);
					}
				}

				galleryTrashDb.close();
			} else if (fromSet == SyncManager.ALBUM && toSet == SyncManager.ALBUM) {

				if (fromAlbumId == null || toAlbumId == null) {
					return false;
				}

				StingleDbAlbum fromDbAlbum = albumsDb.getAlbumById(fromAlbumId);
				Crypto.AlbumData fromAlbumData = crypto.parseAlbumData(fromDbAlbum.data);
				StingleDbAlbum toDbAlbum = albumsDb.getAlbumById(toAlbumId);

				HashMap<String, String> newHeaders = new HashMap<>();
				for (StingleDbFile file : files) {
					newHeaders.put(file.filename, crypto.reencryptFileHeaders(file.headers, Crypto.base64ToByteArray(toDbAlbum.albumPK), fromAlbumData.privateKey, Crypto.base64ToByteArray(fromDbAlbum.albumPK)));
				}

				if (!SyncManager.notifyCloudAboutFileMove(context, files, fromSet, toSet, fromAlbumId, toAlbumId, isMoving, newHeaders)) {
					return false;
				}

				for (StingleDbFile file : files) {
					albumFilesDb.insertAlbumFile(toDbAlbum.albumId, file.filename, file.isLocal, file.isRemote, file.version, newHeaders.get(file.filename), file.dateCreated, System.currentTimeMillis());
					if (isMoving) {
						albumFilesDb.deleteAlbumFile(file.id);
					}
				}
			} else if (fromSet == SyncManager.GALLERY && toSet == SyncManager.TRASH) {
				GalleryTrashDb galleryDb = new GalleryTrashDb(context, SyncManager.GALLERY);
				GalleryTrashDb trashDb = new GalleryTrashDb(context, SyncManager.TRASH);

				if (!SyncManager.notifyCloudAboutFileMove(context, files, fromSet, toSet, fromAlbumId, toAlbumId, isMoving, null)) {
					return false;
				}

				for (StingleDbFile file : files) {
					file.dateModified = System.currentTimeMillis();
					trashDb.insertFile(file);
					galleryDb.deleteFile(file.filename);
				}

				galleryDb.close();
				trashDb.close();
			} else if (fromSet == SyncManager.TRASH && toSet == SyncManager.GALLERY) {
				GalleryTrashDb galleryDb = new GalleryTrashDb(context, SyncManager.GALLERY);
				GalleryTrashDb trashDb = new GalleryTrashDb(context, SyncManager.TRASH);

				if (!SyncManager.notifyCloudAboutFileMove(context, files, fromSet, toSet, fromAlbumId, toAlbumId, isMoving, null)) {
					return false;
				}

				for (StingleDbFile file : files) {
					file.dateModified = System.currentTimeMillis();
					galleryDb.insertFile(file);
					trashDb.deleteFile(file.filename);
				}

				galleryDb.close();
				trashDb.close();
			}

			albumsDb.close();
			albumFilesDb.close();

		} catch (IOException | CryptoException e) {
			e.printStackTrace();
			return false;
		}

		return true;
	}

	public static boolean notifyCloudAboutAlbumAdd(Context context, String albumId, String data, String albumPK, long dateCreated, long dateModified) {
		HashMap<String, String> params = new HashMap<>();

		params.put("albumId", albumId);
		params.put("data", data);
		params.put("albumPK", albumPK);
		params.put("dateCreated", String.valueOf(dateCreated));
		params.put("dateModified", String.valueOf(dateModified));

		try {
			HashMap<String, String> postParams = new HashMap<>();
			postParams.put("token", KeyManagement.getApiToken(context));
			postParams.put("params", CryptoHelpers.encryptParamsForServer(params));

			JSONObject json = HttpsClient.postFunc(StinglePhotosApplication.getApiUrl() + context.getString(R.string.add_album), postParams);
			StingleResponse response = new StingleResponse(context, json, false);

			if (response.isStatusOk()) {
				return true;
			}
			return false;
		} catch (CryptoException e) {
			return false;
		}

	}

	public static boolean notifyCloudAboutAlbumDelete(Context context, String albumId) {
		HashMap<String, String> params = new HashMap<>();

		params.put("albumId", albumId);

		try {
			HashMap<String, String> postParams = new HashMap<>();
			postParams.put("token", KeyManagement.getApiToken(context));
			postParams.put("params", CryptoHelpers.encryptParamsForServer(params));

			JSONObject json = HttpsClient.postFunc(StinglePhotosApplication.getApiUrl() + context.getString(R.string.delete_album_path), postParams);
			StingleResponse response = new StingleResponse(context, json, false);

			if (response.isStatusOk()) {
				return true;
			}
			return false;
		} catch (CryptoException e) {
			return false;
		}

	}

	public static boolean notifyCloudAboutFileMove(Context context, ArrayList<StingleDbFile> files, int setFrom, int setTo, String albumIdFrom, String albumIdTo, boolean isMoving, HashMap<String, String> headers) {
		HashMap<String, String> params = new HashMap<>();

		params.put("setFrom", String.valueOf(setFrom));
		params.put("setTo", String.valueOf(setTo));
		params.put("albumIdFrom", albumIdFrom);
		params.put("albumIdTo", albumIdTo);
		params.put("isMoving", (isMoving ? "1" : "0"));
		params.put("count", String.valueOf(files.size()));
		for (int i = 0; i < files.size(); i++) {
			params.put("filename" + i, files.get(i).filename);
			if (headers != null) {
				params.put("headers" + i, headers.get(files.get(i).filename));
			}
		}

		try {
			HashMap<String, String> postParams = new HashMap<>();
			postParams.put("token", KeyManagement.getApiToken(context));
			postParams.put("params", CryptoHelpers.encryptParamsForServer(params));

			JSONObject json = HttpsClient.postFunc(StinglePhotosApplication.getApiUrl() + context.getString(R.string.move_file), postParams);
			StingleResponse response = new StingleResponse(context, json, false);

			if (response.isStatusOk()) {
				return true;
			}
			return false;
		} catch (CryptoException e) {
			return false;
		}

	}

	public static boolean notifyCloudAboutDelete(Context context, ArrayList<String> filenamesToNotify){
		HashMap<String, String> params = new HashMap<String, String>();

		params.put("count", String.valueOf(filenamesToNotify.size()));
		for(int i=0; i < filenamesToNotify.size(); i++) {
			params.put("filename" + i, filenamesToNotify.get(i));
		}

		try {
			HashMap<String, String> postParams = new HashMap<>();
			postParams.put("token", KeyManagement.getApiToken(context));
			postParams.put("params", CryptoHelpers.encryptParamsForServer(params));

			JSONObject json = HttpsClient.postFunc(StinglePhotosApplication.getApiUrl() + context.getString(R.string.delete_file_path), postParams);
			StingleResponse response = new StingleResponse(context, json, false);

			if (response.isStatusOk()) {
				return true;
			}
			return false;
		}
		catch (CryptoException e){
			return false;
		}
	}

	public static boolean notifyCloudAboutEmptyTrash(Context context){
		HashMap<String, String> params = new HashMap<>();
		long timestamp = System.currentTimeMillis();
		params.put("time", String.valueOf(timestamp));

		try {
			HashMap<String, String> postParams = new HashMap<>();
			postParams.put("token", KeyManagement.getApiToken(context));
			postParams.put("params", CryptoHelpers.encryptParamsForServer(params));

			JSONObject json = HttpsClient.postFunc(StinglePhotosApplication.getApiUrl() + context.getString(R.string.empty_trash_path), postParams);
			StingleResponse response = new StingleResponse(context, json, false);

			if (response.isStatusOk()) {
				return true;
			}
			return false;
		}
		catch (CryptoException e){
			return false;
		}
	}

}
