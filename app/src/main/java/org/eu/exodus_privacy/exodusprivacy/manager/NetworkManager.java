/*
 * Copyright (C) 2018 Anthony Chomienne, anthony@mob-dev.fr
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 3
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */

package org.eu.exodus_privacy.exodusprivacy.manager;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Bundle;

import org.eu.exodus_privacy.exodusprivacy.listener.NetworkListener;
import org.eu.exodus_privacy.exodusprivacy.R;
import org.eu.exodus_privacy.exodusprivacy.objects.Application;
import org.eu.exodus_privacy.exodusprivacy.objects.Report;
import org.eu.exodus_privacy.exodusprivacy.objects.Tracker;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.Semaphore;

/*
    Singleton that handle all network connection
*/
public class NetworkManager {

    private static NetworkManager instance;
    private NetworkProcessingThread thread;

    private NetworkManager() {
    }

    public static NetworkManager getInstance() {
        if (instance == null)
            instance = new NetworkManager();
        return instance;
    }

    public void getReports(Context context, NetworkListener listener, ArrayList<String> packageList) {
        Message mes = new Message();
        mes.type = Message_Type.GET_REPORTS;
        mes.context = context;
        mes.listener = listener;
        mes.args = new Bundle();
        mes.args.putStringArrayList("packages",packageList);
        addMessageToQueue(mes);
    }

    private enum Message_Type {
        GET_REPORTS,
        UNKNOWN
    }

    private void addMessageToQueue(Message mes){
        if(thread == null || thread.getState() == Thread.State.TERMINATED || !thread.isRunning)
            thread = new NetworkProcessingThread();
        thread.queueMessage(mes);
        if(thread.getState() == Thread.State.NEW)
            thread.start();
    }

    private class NetworkProcessingThread extends Thread {
        private List<Message> messageQueue;
        private Semaphore sem;
        boolean isRunning;
        private final String apiUrl =  "https://reports.exodus-privacy.eu.org/api/";

        NetworkProcessingThread() {
            messageQueue = new ArrayList<>();
            sem = new Semaphore(0, true);
        }

        void queueMessage(Message mes) {
            messageQueue.add(mes);
            sem.release();
        }

        @Override
        public void run() {
            isRunning = true;
            while (isRunning) {
                try {
                    sem.acquire();
                    Message mes = messageQueue.remove(0);
                    switch (mes.type) {
                        case GET_REPORTS:
                            getTrackers(mes);
                            getReports(mes);
                            break;
                        default:
                            break;
                    }
                } catch (InterruptedException e) {
                    isRunning = false;
                }
            }
        }

        private void getTrackers(Message mes) {
            if (!isConnectedToInternet(mes.context)) {
                mes.listener.onError(mes.context.getString(R.string.not_connected));
                return;
            }
            mes.listener.onProgress(R.string.get_trackers_connection,0,0);
            URL url;
            try {
                url = new URL(apiUrl+"trackers");
            } catch (Exception e){
                e.printStackTrace();
                return;
            }
            InputStream inStream;
            HttpURLConnection urlConnection;
            boolean success = true;
            try {
                urlConnection = (HttpURLConnection) url.openConnection();
                urlConnection.setRequestProperty("Content-Type", "application/json");
                urlConnection.setRequestProperty("Accept", "application/json");
                urlConnection.setDoInput(true);
            } catch (Exception e) {
                return;
            }

            try {
                inStream = urlConnection.getInputStream();
            } catch (Exception e) {
                success = false;
                inStream = urlConnection.getErrorStream();
            }
            mes.listener.onProgress(R.string.get_trackers,0,0);

            if(success) {
                String jsonStr = getJSON(inStream);
                try {

                    JSONObject object = new JSONObject(jsonStr);
                    JSONObject trackers = object.getJSONObject("trackers");
                    List<Tracker> trackersList = new ArrayList<>();
                    for(int i = 0; i<trackers.names().length(); i++) {
                        mes.listener.onProgress(R.string.parse_trackers,i+1,trackers.names().length());
                        String trackerId = trackers.names().get(i).toString();
                        JSONObject tracker = trackers.getJSONObject(trackerId);
                        Tracker track = parseTracker(tracker,trackerId);
                        trackersList.add(track);
                    }
                    DatabaseManager.getInstance(mes.context).insertOrUpdateTrackers(trackersList);
                } catch (JSONException e) {
                    mes.listener.onError(mes.context.getString(R.string.json_error));
                }
            }

            try {
                if(inStream != null)
                    inStream.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        private void getReports(Message mes) {
            if (!isConnectedToInternet(mes.context)) {
                mes.listener.onError(mes.context.getString(R.string.not_connected));
                return;
            }
            mes.listener.onProgress(R.string.get_reports_connection,0,0);

            URL url;
            try {
                url = new URL(apiUrl+"reports");
            } catch (Exception e){
                e.printStackTrace();
                return;
            }
            InputStream inStream;
            HttpURLConnection urlConnection;
            boolean success = true;
            try {
                urlConnection = (HttpURLConnection) url.openConnection();
                urlConnection.setRequestProperty("Content-Type", "application/json");
                urlConnection.setRequestProperty("Accept", "application/json");
                urlConnection.setDoInput(true);
            } catch (Exception e) {
                return;
            }

            try {
                inStream = urlConnection.getInputStream();
            } catch (Exception e) {
                success = false;
                inStream = urlConnection.getErrorStream();
            }
            mes.listener.onProgress(R.string.get_reports,0,0);
            if(success) {
                String jsonStr = getJSON(inStream);
                try {
                    JSONObject object = new JSONObject(jsonStr);
                    JSONObject applications = object.getJSONObject("applications");
                    ArrayList<String> packages = mes.args.getStringArrayList("packages");
                    for(int i = 0; i<applications.names().length(); i++) {
                        mes.listener.onProgress(R.string.parse_application,i+1,applications.names().length());
                        String packageName = applications.names().get(i).toString();
                        if(packages.contains(packageName)) {
                            JSONObject application = applications.getJSONObject(packageName);
                            Application app = parseApplication(application, packageName);
                            DatabaseManager.getInstance(mes.context).insertOrUpdateApplication(app);
                        }
                    }
                } catch (JSONException e) {
                    mes.listener.onError(mes.context.getString(R.string.json_error));
                }
            }

            try {
                if(inStream != null)
                        inStream.close();
            } catch (IOException e) {
                e.printStackTrace();
            }

            mes.listener.onSuccess();
        }

        private Application parseApplication(JSONObject object, String packageName) throws JSONException {
            Application application = new Application();
            application.packageName = packageName;
            application.creator = object.getString("creator");
            application.name = object.getString("name");
            //parse Report
            application.reports = new HashSet<>();
            JSONArray reports = object.getJSONArray("reports");
            for(int i = 0; i < reports.length(); i++) {
                Report report = parseReport(reports.getJSONObject(i));
                application.reports.add(report);
            }
            return application;
        }

        private Report parseReport(JSONObject object) throws JSONException {
            Report report = new Report();
            report.id = object.getLong("id");
            report.downloads = object.getString("downloads");
            report.version = object.getString("version");
            if(!object.getString("version_code").isEmpty())
                report.versionCode = Long.parseLong(object.getString("version_code"));
            DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.getDefault());
            try {
                report.updateDate = Calendar.getInstance();
                report.updateDate.setTimeZone(TimeZone.getTimeZone("UTC"));
                report.updateDate.setTime(dateFormat.parse(object.getString("updated_at")));

                report.creationDate = Calendar.getInstance();
                report.creationDate.setTimeZone(TimeZone.getTimeZone("UTC"));
                report.creationDate.setTime(dateFormat.parse(object.getString("creation_date")));
            } catch (ParseException e) {
                e.printStackTrace();
            }
            JSONArray trackersArray = object.getJSONArray("trackers");
            report.trackers = new HashSet<>();
            for(int i = 0; i < trackersArray.length(); i++)
                report.trackers.add(trackersArray.getLong(i));
            return report;
        }

        private Tracker parseTracker(JSONObject object, String trackerId) throws JSONException {
            Tracker tracker = new Tracker();
            tracker.id = Long.parseLong(trackerId);
            tracker.website = object.getString("website");
            tracker.name = object.getString("name");
            tracker.description = object.getString("description");
            tracker.networkSignature = object.getString("network_signature");
            tracker.codeSignature = object.getString("code_signature");
            DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
            try {
                tracker.creationDate = Calendar.getInstance();
                tracker.creationDate.setTimeZone(TimeZone.getTimeZone("UTC"));
                tracker.creationDate.setTime(dateFormat.parse(object.getString("creation_date")));
            } catch (ParseException e) {
                e.printStackTrace();
            }
            return tracker;
        }

        private String getJSON(InputStream stream) {
            InputStreamReader isr = new InputStreamReader(stream);
            BufferedReader br = new BufferedReader(isr);
            boolean isReading = true;
            String data;
            StringBuilder builder = new StringBuilder();
            //get all data in a String
            do {
                try {
                    data = br.readLine();
                    if (data != null)
                        builder.append(data);
                    else
                        isReading = false;
                } catch (IOException e) {
                    e.printStackTrace();
                    isReading = false;
                }
            } while (isReading);
            return builder.toString();
        }

        private boolean isConnectedToInternet(Context context) {
            //verify the connectivity
            ConnectivityManager connectivityManager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if(connectivityManager == null)
                return false;
            NetworkInfo networkInfo = connectivityManager.getActiveNetworkInfo();
            if (networkInfo != null) {
                NetworkInfo.State networkState = networkInfo.getState();
                if (networkState.equals(NetworkInfo.State.CONNECTED)) {
                    return true;
                }
            }
            return false;
        }
    }

    private class Message{
        Message_Type type = Message_Type.UNKNOWN;
        Bundle args = new Bundle();
        NetworkListener listener;
        Context context;
    }
}
