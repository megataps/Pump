package com.huxq17.download.demo;

import android.app.Application;
import android.os.Environment;

import com.huxq17.download.Pump;
import com.huxq17.download.PumpFactory;
import com.huxq17.download.core.DownloadInfo;
import com.huxq17.download.core.DownloadInterceptor;
import com.huxq17.download.core.DownloadTaskExecutor;
import com.huxq17.download.core.SimpleDownloadTaskExecutor;
import com.huxq17.download.core.service.IDownloadConfigService;
import com.huxq17.download.utils.FileUtil;

import java.io.File;

public class DemoApplication extends Application {
    private static DemoApplication instance;
    public static final int MD5_VERIFY_FAILED = 3000;

    public static DemoApplication getInstance() {
        return instance;
    }

    public DownloadTaskExecutor imageDownloadDispatcher = new SimpleDownloadTaskExecutor() {

        @Override
        public int getMaxDownloadNumber() {
            return PumpFactory.getService(IDownloadConfigService.class).getMaxRunningTaskNumber();
        }

        @Override
        public String getName() {
            return "ImageDownloadDispatcher";
        }

        @Override
        public String getTag() {
            return "image";
        }
    };
    public DownloadTaskExecutor musicDownloadDispatcher = new SimpleDownloadTaskExecutor() {

        @Override
        public int getMaxDownloadNumber() {
            return 1;
        }

        @Override
        public String getName() {
            return "MusicDownloadDispatcher";
        }

        @Override
        public String getTag() {
            return "music";
        }
    };

    /**
     * Use for move download file to another directory.
     */
    private DownloadInterceptor changePathInterceptor = new DownloadInterceptor() {
        @Override
        public DownloadInfo intercept(DownloadChain chain) {
            DownloadInfo downloadInfo = chain.proceed(chain.request());
            if (downloadInfo.getStatus() == DownloadInfo.Status.FINISHED) {
                File oldFile = new File(downloadInfo.getFilePath());
                File newFile = new File(Environment.getExternalStorageDirectory(), "AE6-1D01" + File.separatorChar + "Test" + File.separatorChar + oldFile.getName());
                if(!oldFile.equals(newFile)){
                    FileUtil.copyFile(oldFile, newFile);
                    downloadInfo.updateFilePath(newFile.getAbsolutePath());
                }
            }
            return downloadInfo;
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        Pump.newConfigBuilder()
                //Optional,set the maximum number of tasks to run at the same time, default 3.
                .setMaxRunningTaskNum(2)
                //Optional,set the minimum available storage space size for downloading to avoid insufficient storage space during downloading, default is 4kb.
                .setMinUsableStorageSpace(4 * 1024L)
                .setDownloadConnectionFactory(new AuthorizationHeaderConnection
                        .Factory(Utils.getIgnoreCertificateOkHttpClient()))//Optional
//                .addDownloadInterceptor(new DownloadInterceptor() {
//                    @Override
//                    public DownloadInfo intercept(DownloadChain chain) {
//                        DownloadRequest downloadRequest = chain.request();
//                        DownloadInfo downloadInfo = chain.proceed(downloadRequest);
//                        if (false&&downloadInfo.isFinished()) {
//                            //verify md5.
//                            File downloadFile = new File(downloadInfo.getFilePath());
//                            String fileMD5 = Utils.getMD5ByBase64(downloadFile);
//                            String serverMD5 = downloadInfo.getMD5();
//                            LogUtil.e("verify MD5 fileMD5=" + fileMD5 + ";serverMD5=" + serverMD5);
//                            if (!TextUtils.isEmpty(serverMD5) && !serverMD5.equals(fileMD5)) {
//                                //setErrorCode will make download failed.
//                                downloadInfo.setErrorCode(MD5_VERIFY_FAILED);
//                                FileUtil.deleteFile(downloadFile);
//                            }
//
//                            //unzip file here if need.
//                        }
//                        return downloadInfo;
//                    }
//                })
//                .addDownloadInterceptor(changePathInterceptor)
                .build();

    }
}
