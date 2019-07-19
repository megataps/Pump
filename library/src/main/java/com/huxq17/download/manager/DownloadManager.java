package com.huxq17.download.manager;


import android.content.Context;

import com.huxq17.download.DownloadConfig;
import com.huxq17.download.DownloadDetailsInfo;
import com.huxq17.download.DownloadInfo;
import com.huxq17.download.DownloadInfoSnapshot;
import com.huxq17.download.DownloadRequest;
import com.huxq17.download.Utils.LogUtil;
import com.huxq17.download.Utils.Util;
import com.huxq17.download.db.DBService;
import com.huxq17.download.listener.DownLoadLifeCycleObserver;
import com.huxq17.download.DownloadService;
import com.huxq17.download.task.DownloadTask;
import com.huxq17.download.task.ShutdownTask;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.ReentrantLock;

public class DownloadManager implements IDownloadManager, DownLoadLifeCycleObserver {
    private Context context;
    private LinkedBlockingQueue<DownloadTask> readyTaskQueue;
    private LinkedBlockingQueue<DownloadTask> runningTaskQueue;
    private ConcurrentHashMap<String, DownloadTask> taskMap;
    private ConcurrentHashMap<String, DownloadDetailsInfo> downloadMap;
    private Semaphore semaphore;

    /**
     * 允许同时下载的任务数量
     */
    private int maxRunningTaskNumber = 3;
    private boolean isShutdown = true;
    DownloadService downloadService;

    private DownloadManager() {
        taskMap = new ConcurrentHashMap<>();
        downloadService = new DownloadService(this);
    }

    private DownloadDetailsInfo createDownloadInfo(String url, String filePath, String tag) {
        DownloadDetailsInfo downloadInfo = null;
        if (downloadMap != null) {
            downloadInfo = downloadMap.get(url);
        }
        if (downloadInfo == null) {
            downloadInfo = DBService.getInstance().getDownloadInfo(url);
        }
        if (downloadInfo != null) {
            return downloadInfo;
        }
        //create a new instance if not found.
        downloadInfo = new DownloadDetailsInfo(url, filePath, tag);
        downloadInfo.setCreateTime(System.currentTimeMillis());
        DBService.getInstance().updateInfo(downloadInfo);
        return downloadInfo;
    }

    public synchronized void submit(DownloadRequest downloadRequest) {
        String url = downloadRequest.getUrl();
        String filePath = downloadRequest.getFilePath();
        String tag = downloadRequest.getTag();
        DownloadDetailsInfo downloadInfo = createDownloadInfo(url, filePath, tag);
        if (downloadMap != null) {
            downloadMap.put(url, downloadInfo);
        }
        downloadRequest.setDownloadInfo(downloadInfo);
        if (taskMap.get(url) != null) {
            //The task is running,we need do nothing.
            LogUtil.e("task " + downloadInfo.getName() + " is running,we need do nothing.");
            return;
        }
//        if (downloadInfo.isFinished()) {
//            downloadInfo.setCompletedSize(0);
//        }
//            downloadInfo.calculateDownloadProgress();
//            downloadInfo.setTag(null);
        downloadInfo.setStatus(DownloadInfo.Status.STOPPED);
//            if (downloadInfo.getDownloadFile().exists()) {
//                downloadInfo.getDownloadFile().delete();
//            }
        submitTask(downloadRequest);
    }

    private void submitTask(DownloadRequest downloadRequest) {
        isShutdown = false;
        if (semaphore == null) {
            semaphore = new Semaphore(maxRunningTaskNumber);
        }
        DownloadTask downloadTask = new DownloadTask(downloadRequest, this);
        taskMap.put(downloadRequest.getUrl(), downloadTask);
        readyTaskQueue.offer(downloadTask);
        LogUtil.d("task " + downloadRequest.getDownloadInfo().getName() + " is ready" + ",remaining " + semaphore.availablePermits() + " permits.");
        if (!downloadService.isRunning()) {
            downloadService.start();
        }
    }

    public synchronized void delete(DownloadInfo downloadInfo) {
        if (downloadInfo == null) return;
        if (downloadMap != null) {
            downloadMap.remove(downloadInfo.getUrl());
        }
        synchronized (downloadInfo) {
            DownloadDetailsInfo transferInfo = (DownloadDetailsInfo) downloadInfo;
            DownloadTask downloadTask = transferInfo.getDownloadTask();
            if (downloadTask == null) {
                downloadTask = taskMap.get(downloadInfo.getUrl());
            }
            if (downloadTask != null) {
                readyTaskQueue.remove(downloadTask);
                downloadTask.delete();
            }
            transferInfo.getDownloadFile().delete();
            Util.deleteDir(transferInfo.getTempDir());
            DBService.getInstance().deleteInfo(downloadInfo.getUrl(), downloadInfo.getFilePath());
        }
    }

    public synchronized void delete(String tag) {
        if (tag == null) return;
        List<DownloadDetailsInfo> tasks = DBService.getInstance().getDownloadListByTag(tag);
        for (DownloadDetailsInfo info : tasks) {
            delete(info);
        }
    }

    @Override
    public void stop(DownloadInfo downloadInfo) {
        DownloadDetailsInfo transferInfo = (DownloadDetailsInfo) downloadInfo;
        DownloadTask downloadTask = transferInfo.getDownloadTask();
        if (downloadTask != null) {
            downloadTask.stop();
        }
    }

    @Override
    public void pause(DownloadInfo downloadInfo) {
        for (DownloadTask task : runningTaskQueue) {
            if (task.getDownloadInfo() == downloadInfo) {
                task.pause();
            }
        }
    }

    @Override
    public synchronized void resume(DownloadInfo downloadInfo) {
        DownloadDetailsInfo transferInfo = (DownloadDetailsInfo) downloadInfo;
        DownloadTask downloadTask = transferInfo.getDownloadTask();
        if (downloadTask != null && downloadTask.getRequest() != null) {
            DownloadRequest downloadRequest = downloadTask.getRequest();
            submit(downloadRequest);
        } else {
            DownloadRequest.newRequest(transferInfo.getUrl(), transferInfo.getFilePath()).submit();
        }
    }

    @Override
    public List<DownloadDetailsInfo> getDownloadingList() {
        List<DownloadDetailsInfo> downloadList = new ArrayList<>();
        List<DownloadDetailsInfo> list = getAllDownloadList();
        for (DownloadDetailsInfo info : list) {
            if (!info.isFinished()) {
                downloadList.add(info);
            }
        }
        return downloadList;
    }

    @Override
    public List<DownloadDetailsInfo> getDownloadedList() {
        List<DownloadDetailsInfo> downloadList = new ArrayList<>();
        List<DownloadDetailsInfo> list = getAllDownloadList();
        for (DownloadDetailsInfo info : list) {
            if (info.isFinished()) {
                downloadList.add(info);
            }
        }
        return downloadList;
    }

    @Override
    public List<DownloadDetailsInfo> getDownloadListByTag(String tag) {
        List<DownloadDetailsInfo> downloadList = new ArrayList<>();
        List<DownloadDetailsInfo> list = getAllDownloadList();
        for (DownloadDetailsInfo info : list) {
            if (info.getTag().equals(tag)) {
                downloadList.add(info);
            }
        }
        return downloadList;
    }

    @Override
    public List<DownloadDetailsInfo> getAllDownloadList() {
        if (downloadMap == null) {
            downloadMap = new ConcurrentHashMap<>();
            List<DownloadDetailsInfo> list = DBService.getInstance().getDownloadList();
            for (DownloadDetailsInfo transferInfo : list) {
                String url = transferInfo.getUrl();
                DownloadTask downloadTask = taskMap.get(url);
                if (downloadTask != null) {
                    downloadMap.put(url, downloadTask.getDownloadInfo());
                } else {
                    downloadMap.put(url, transferInfo);
                }
            }
        }
        return new ArrayList<>(downloadMap.values());
    }

    @Override
    public boolean hasDownloadSucceed(String url) {
        DownloadDetailsInfo info = DBService.getInstance().getDownloadInfo(url);
        if (info != null && info.isFinished()) {
            return true;
        }
        return false;
    }

    @Override
    public File getFileIfSucceed(String url) {
        if (hasDownloadSucceed(url)) {
            DownloadDetailsInfo info = DBService.getInstance().getDownloadInfo(url);
            return info.getDownloadFile();
        }
        return null;
    }

    @Override
    public void setDownloadConfig(DownloadConfig downloadConfig) {
        maxRunningTaskNumber = downloadConfig.getMaxRunningTaskNumber();
    }

    @Override
    public synchronized void shutdown() {
        isShutdown = true;
        for (DownloadTask downloadTask : runningTaskQueue) {
            if (downloadTask != null) {
                downloadTask.stop();
            }
        }
        for (DownloadTask downloadTask : readyTaskQueue) {
            onDownloadEnd(downloadTask);
        }
        readyTaskQueue.clear();
        downloadService.cancel();
        if (downloadService.isRunning()) {
            readyTaskQueue.offer(new ShutdownTask());
        }
        DownloadInfoSnapshot.release();
        DBService.getInstance().close();
    }

    public boolean isShutdown() {
        return isShutdown;
    }

    @Override
    public Context getContext() {
        return context;
    }

    public DownloadTask acquireTask() throws InterruptedException {
        if (semaphore != null) {
            semaphore.acquire();
        }
        DownloadTask task = readyTaskQueue.take();
        if (task instanceof ShutdownTask) {
            semaphore.release();
            return null;
        }
        return task;
    }

    @Override
    public void start(Context context) {
        this.context = context;
        readyTaskQueue = new LinkedBlockingQueue<>();
        runningTaskQueue = new LinkedBlockingQueue<>();
    }

    @Override
    public void onDownloadStart(DownloadTask downloadTask) {
        runningTaskQueue.add(downloadTask);
    }

    @Override
    public void onDownloadEnd(DownloadTask downloadTask) {
        DownloadDetailsInfo downloadInfo = downloadTask.getDownloadInfo();
        LogUtil.d("Task " + downloadInfo.getName() + " is stopped.");
        taskMap.remove(downloadInfo.getUrl());
        if (runningTaskQueue.remove(downloadTask)) {
            semaphore.release();
        }
    }
}
