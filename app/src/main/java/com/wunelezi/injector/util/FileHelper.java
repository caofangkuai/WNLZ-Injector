package com.wunelezi.injector.util;

import android.os.Build;
import android.os.Environment;
import java.io.File;
import java.io.IOException;

public class FileHelper {

    /**
     * 是否存在该漏洞，判断前要先获取存储权限
     */
    public static boolean canAccessDataDir() {
        if (Build.VERSION.SDK_INT < 30) {
            return new File("/sdcard/android/data").canRead();
        } else {
            File dataDir = new File("/sdcard/android/\u200bdata");
            return dataDir.canRead();
        }
    }

    /**
     * 是否是/sdcard/android目录
     */
    public static boolean isAndroidDir(File dir) {
        File rootDir = Environment.getExternalStorageDirectory();
        if (rootDir == null) return false;
        try {
            File canFile = dir.getCanonicalFile();
            if (canFile.getPath().toLowerCase().equals(rootDir.getPath().toLowerCase() + "/android")) {
                return true;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return false;
    }

    private static String getAndroidPath() {
        File rootDir = Environment.getExternalStorageDirectory();
        if (rootDir == null) {
            return null;
        }
        return rootDir.getPath() + "/Android/";
    }

    /**
     * 获取android目录子文件
     */
    public static File getReviseFromAndroid(File file) {
        return new File(file.getParentFile(), "\u200b" + file.getName());
    }

    /**
     * 获取修正过的文件
     */
    public static File getReviseFile(File file) {
        if (Build.VERSION.SDK_INT < 30) return file;
        if (file == null) {
            return null;
        }
        String androidPath = getAndroidPath();
        if (androidPath != null) {
            String canPath = getCanonicalPath(file);
            if (canPath.length() > androidPath.length() && canPath.toLowerCase().startsWith(androidPath.toLowerCase())) {
                return new File(androidPath + "\u200b" + canPath.substring(androidPath.length()));
            }
        }
        return file;
    }

    private static String getCanonicalPath(File file) {
        if (file == null) return null;
        String path = null;
        try {
            path = file.getCanonicalPath();
        } catch (IOException e) {
            e.printStackTrace();
        }
        final String sdcard = "/sdcard/";
        if (path == null) path = file.getAbsolutePath();
        if (path.length() > sdcard.length() && path.toLowerCase().startsWith(sdcard)) {
            String androidPath = getAndroidPath();
            if (androidPath != null) {
                path = androidPath + path.substring(sdcard.length());
            }
        }
        return path;
    }

    /**
     * 获取修正过的文件
     */
    public static File getReviseFile(String path) {
        return path == null ? null : getReviseFile(new File(path));
    }

    /**
     * 获取修正过的路径
     */
    public static String getRevisePath(String path) {
        File file = getReviseFile(path);
        return file == null ? null : file.getAbsolutePath();
    }
}
