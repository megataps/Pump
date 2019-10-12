package com.huxq17.download.task;


import com.huxq17.download.DownloadChain;
import com.huxq17.download.DownloadDetailsInfo;
import com.huxq17.download.DownloadInfo;
import com.huxq17.download.DownloadRequest;
import com.huxq17.download.PumpFactory;
import com.huxq17.download.SpeedMonitor;
import com.huxq17.download.Utils.LogUtil;
import com.huxq17.download.Utils.Util;
import com.huxq17.download.db.DBService;
import com.huxq17.download.listener.DownLoadLifeCycleObserver;
import com.huxq17.download.message.IMessageCenter;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class DownloadTask implements Task {
    private final DownloadDetailsInfo downloadInfo;
    private DBService dbService;
    private AtomicBoolean isDestroyed;
    private boolean isNeedDelete;
    private IMessageCenter messageCenter;
    protected DownLoadLifeCycleObserver downLoadLifeCycleObserver;
    private SpeedMonitor speedMonitor;
    private Thread thread;
    private List<Task> downloadBlockTasks = new ArrayList<>();
    private int lastProgress = 0;
    /**
     * True indicate that not support breakpoint download.
     */
    private boolean isDowngrade;
    private DownloadRequest downloadRequest;
    private long startTime;
    private final Object lock;

    public DownloadTask(DownloadRequest downloadRequest, DownLoadLifeCycleObserver downLoadLifeCycleObserver) {
        this.downLoadLifeCycleObserver = downLoadLifeCycleObserver;
        if (downloadRequest != null) {
            this.downloadRequest = downloadRequest;
            this.downloadInfo = downloadRequest.getDownloadInfo();
            downloadInfo.setDownloadTask(this);
            isDestroyed = new AtomicBoolean();
            isDowngrade = false;
            dbService = DBService.getInstance();
            downloadInfo.setUsed(true);
            speedMonitor = new SpeedMonitor(downloadInfo);
            messageCenter = PumpFactory.getService(IMessageCenter.class);
            downloadInfo.setErrorCode(0);
            if (downloadInfo.getCompletedSize() == downloadInfo.getContentLength()) {
                downloadInfo.setCompletedSize(0);
            }
            downloadInfo.setStatus(DownloadInfo.Status.WAIT);
            notifyProgressChanged(downloadInfo);
        } else {
            downloadInfo = null;
        }
        lock = downloadInfo;
    }

    public Object getLock() {
        return lock;
    }

    public DownloadRequest getRequest() {
        return downloadRequest;
    }

    public String getUrl() {
        return downloadRequest.getUrl();
    }

    public String getId() {
        return downloadRequest.getId();
    }

    public String getName() {
        return downloadRequest.getDownloadInfo().getName();
    }

    @Override
    public void run() {
        thread = Thread.currentThread();
        if (!isDestroyed.get()) {
            downloadInfo.setStatus(DownloadInfo.Status.RUNNING);
//            notifyProgressChanged(downloadInfo);
        }
        downLoadLifeCycleObserver.onDownloadStart(this);
        if (!shouldStop()) {
            startTime = System.currentTimeMillis();
            downloadWithDownloadChain();
            LogUtil.d("download " + downloadInfo.getName() + " spend=" + (System.currentTimeMillis() - startTime));
        }
        thread = null;
        downLoadLifeCycleObserver.onDownloadEnd(this);
        downloadBlockTasks.clear();
        speedMonitor = null;
    }

    private void downloadWithDownloadChain() {
        DownloadChain chain = new DownloadChain(this);
        chain.proceed();
    }

    public boolean onDownload(int length) {
        synchronized (lock) {
            if (isDestroyed.get()) {
                return false;
            }
            downloadInfo.download(length);
            speedMonitor.compute(length);
            int progress = (int) (downloadInfo.getCompletedSize() * 1f / downloadInfo.getContentLength() * 100);
            if (progress != lastProgress) {
                if (progress != 100) {
                    lastProgress = progress;
                    notifyProgressChanged(downloadInfo);
                }
            }
        }
        return true;
    }

    public boolean isDowngrade() {
        synchronized (lock) {
            return isDowngrade && downloadRequest.getThreadNum() == 1;
        }
    }

    /**
     * downgrade when server not support breakpoint download.
     */
    public void downgrade() {
        synchronized (lock) {
            if (!isDowngrade) {
                isDowngrade = true;
                downloadRequest.setThreadNum(1);
                for (Task task : downloadBlockTasks) {
                    task.cancel();
                }
                downloadBlockTasks.clear();
                Util.deleteDir(downloadInfo.getTempDir());
                DBService.getInstance().updateInfo(downloadInfo);
            }
        }
    }

    public void notifyProgressChanged(DownloadDetailsInfo downloadInfo) {
        if (messageCenter != null)
            messageCenter.notifyProgressChanged(downloadInfo);
    }

    public DownloadDetailsInfo getDownloadInfo() {
        return downloadInfo;
    }

    public void addBlockTask(Task task) {
        downloadBlockTasks.add(task);
    }

    public void pause() {
        synchronized (lock) {
            if (!isDestroyed.get()) {
                downloadInfo.setStatus(DownloadInfo.Status.PAUSING);
                notifyProgressChanged(downloadInfo);
                cancel();
            }
        }
    }

    public void stop() {
        synchronized (lock) {
            if (!isDestroyed.get()) {
                downloadInfo.setStatus(DownloadInfo.Status.STOPPED);
                downloadInfo.setDownloadTask(null);
                cancel();
            }
        }
    }

    public void cancel() {
        if (thread != null) {
            thread.interrupt();
        } else {
            downLoadLifeCycleObserver.onDownloadEnd(this);
        }
        for (Task task : downloadBlockTasks) {
            task.cancel();
        }
        destroy();
    }

    public void delete() {
        synchronized (lock) {
            if (!isDestroyed.get()) {
                isNeedDelete = true;
                cancel();
            }
        }
    }

    public boolean isNeedDelete() {
        return isNeedDelete;
    }

    public void setErrorCode(int errorCode) {
        if (downloadInfo.getStatus() != DownloadInfo.Status.PAUSING) {
            downloadInfo.setErrorCode(errorCode);
        }
    }

    public void updateInfo() {
        synchronized (lock) {
            if (!isNeedDelete) {
                dbService.updateInfo(downloadInfo);
            }
        }
    }

    public void destroy() {
        isDestroyed.set(true);
    }

    public boolean shouldStop() {
        return isDestroyed.get();
    }

}
