//
// Source code recreated from a .class file by IntelliJ IDEA
// (powered by Fernflower decompiler)
//

package android.support.multidex;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.os.Build.VERSION;
import android.util.Log;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileFilter;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

final class MultiDexExtractor implements Closeable {
    private static final String TAG = "MultiDex";
    private static final String DEX_PREFIX = "classes";
    static final String DEX_SUFFIX = ".dex";
    private static final String EXTRACTED_NAME_EXT = ".classes";
    static final String EXTRACTED_SUFFIX = ".zip";
    private static final int MAX_EXTRACT_ATTEMPTS = 3;
    private static final String PREFS_FILE = "multidex.version";
    private static final String KEY_TIME_STAMP = "timestamp";
    private static final String KEY_CRC = "crc";
    private static final String KEY_DEX_NUMBER = "dex.number";
    private static final String KEY_DEX_CRC = "dex.crc.";
    private static final String KEY_DEX_TIME = "dex.time.";
    private static final int BUFFER_SIZE = 16384;
    private static final long NO_VALUE = -1L;
    private static final String LOCK_FILENAME = "MultiDex.lock";
    private final File sourceApk;
    private final long sourceCrc;
    private final File dexDir;
    private final RandomAccessFile lockRaf;
    private final FileChannel lockChannel;
    private final FileLock cacheLock;

    MultiDexExtractor(File sourceApk, File dexDir) throws IOException {
        //sourceApk.getPath() :   /data/app/packageName-s_ZR1N24kyfFdRoazc7SLw==/base.apk     APK所在目录
        //dexDir.getPath()   :    /data/user/0/packageName/files/code_cache/secondary-dexes    优化后的dex存放路径

        Log.i("MultiDex", "MultiDexExtractor(" + sourceApk.getPath() + ", " + dexDir.getPath() + ")");
        this.sourceApk = sourceApk;
        this.dexDir = dexDir;
        this.sourceCrc = getZipCrc(sourceApk); //  //获取Crc校验码，做文件完整性校验；
        File lockFile = new File(dexDir, "MultiDex.lock");     // 创建 /data/user/0/packageName/code_cache/secondary-dexes/MultiDex.lock 文件
        this.lockRaf = new RandomAccessFile(lockFile, "rw");   //赋予文件读写权限

        try {
            this.lockChannel = this.lockRaf.getChannel();

            try {
                Log.i("MultiDex", "Blocking on lock " + lockFile.getPath());
                this.cacheLock = this.lockChannel.lock();
            } catch (RuntimeException | Error | IOException var5) {
                closeQuietly(this.lockChannel);
                throw var5;
            }

            Log.i("MultiDex", lockFile.getPath() + " locked");
        } catch (RuntimeException | Error | IOException var6) {
            closeQuietly(this.lockRaf);
            throw var6;
        }
    }

    List<? extends File> load(Context context, String prefsKeyPrefix, boolean forceReload) throws IOException {
        Log.i("MultiDex", "MultiDexExtractor.load(" + this.sourceApk.getPath() + ", " + forceReload + ", " + prefsKeyPrefix + ")");

        if (!this.cacheLock.isValid()) {  //文件锁是否还有效，无效抛异常
            throw new IllegalStateException("MultiDexExtractor was closed");
        } else {
            List files;

            //forceReload判断文件是否重新加载,isModified()是判断sourceApk文件是否做过修改（简单点说这个条件就是没有覆盖安装过）
            if (!forceReload && !isModified(context, this.sourceApk, this.sourceCrc, prefsKeyPrefix)) {
                try {
                    files = this.loadExistingExtractions(context, prefsKeyPrefix);//加载之前已经解压过的dex（可以理解缓存过的）
                } catch (IOException var6) {
                    Log.w("MultiDex", "Failed to reload existing extracted secondary dex files, falling back to fresh extraction", var6);
                    files = this.performExtractions();    //出现异常重新执行提取dex
                    putStoredApkInfo(context, prefsKeyPrefix, getTimeStamp(this.sourceApk), this.sourceCrc, files);//出现异常保存apk时间戳 Crc码等信息缓存下来用于下次比对。
                }
            } else {
                if (forceReload) {
                    Log.i("MultiDex", "Forced extraction must be performed.");
                } else {
                    Log.i("MultiDex", "Detected that extraction must be performed.");
                }
                files = this.performExtractions();//走到else{}说明 没有缓存，本质上提取的是dex文件
                putStoredApkInfo(context, prefsKeyPrefix, getTimeStamp(this.sourceApk), this.sourceCrc, files);//把apk 信息缓存下来
            }

            Log.i("MultiDex", "load found " + files.size() + " secondary dex files");
            return files;
        }
    }

    public void close() throws IOException {
        this.cacheLock.release();
        this.lockChannel.close();
        this.lockRaf.close();
    }

    private List<MultiDexExtractor.ExtractedDex> loadExistingExtractions(Context context, String prefsKeyPrefix) throws IOException {
        Log.i("MultiDex", "loading existing secondary dex files");//加载现有的dex文件
        String extractedFilePrefix = this.sourceApk.getName() + ".classes";  //data/app/packageName-s_ZR1N24kyfFdRoazc7SLw==/base.apk.classes
        SharedPreferences multiDexPreferences = getMultiDexPreferences(context);
        int totalDexNumber = multiDexPreferences.getInt(prefsKeyPrefix + "dex.number", 1);
        List<MultiDexExtractor.ExtractedDex> files = new ArrayList(totalDexNumber - 1);

        for(int secondaryNumber = 2; secondaryNumber <= totalDexNumber; ++secondaryNumber) {
            String fileName = extractedFilePrefix + secondaryNumber + ".zip";   // base.apk.classes2.zip

            MultiDexExtractor.ExtractedDex extractedFile = new MultiDexExtractor.ExtractedDex(this.dexDir, fileName);

            if (!extractedFile.isFile()) {
                throw new IOException("Missing extracted secondary dex file '" + extractedFile.getPath() + "'");
            }

            extractedFile.crc = getZipCrc(extractedFile);
            long expectedCrc = multiDexPreferences.getLong(prefsKeyPrefix + "dex.crc." + secondaryNumber, -1L);
            long expectedModTime = multiDexPreferences.getLong(prefsKeyPrefix + "dex.time." + secondaryNumber, -1L);
            long lastModified = extractedFile.lastModified();
            if (expectedModTime != lastModified || expectedCrc != extractedFile.crc) {
                throw new IOException("Invalid extracted dex: " + extractedFile + " (key \"" + prefsKeyPrefix + "\"), expected modification time: " + expectedModTime + ", modification time: " + lastModified + ", expected crc: " + expectedCrc + ", file crc: " + extractedFile.crc);
            }

            files.add(extractedFile);//添加文件列表
        }

        return files;
    }

    /**
     * 校验文件是否做了修改
     * isModified（）是根据SharedPreference中存放的APK文件上一次修改的时间戳和currentCrc来判断是否修改过文件
     * @return
     */
    private static boolean isModified(Context context, File archive, long currentCrc, String prefsKeyPrefix) {
        SharedPreferences prefs = getMultiDexPreferences(context);
        return prefs.getLong(prefsKeyPrefix + "timestamp", -1L) != getTimeStamp(archive) || prefs.getLong(prefsKeyPrefix + "crc", -1L) != currentCrc;
    }

    /**
     * 获取base.apk时间戳
     * @param archive
     * @return
     */
    private static long getTimeStamp(File archive) {
        long timeStamp = archive.lastModified(); //文件最后一次修改的时间
        if (timeStamp == -1L) {
            --timeStamp;
        }

        return timeStamp;
    }

    /**
     * 获取zip包的Crc码
     * @param archive
     * @return
     * @throws IOException
     */
    private static long getZipCrc(File archive) throws IOException {
        long computedValue = ZipUtil.getZipCrc(archive);
        if (computedValue == -1L) {
            --computedValue;
        }

        return computedValue;
    }

    private List<MultiDexExtractor.ExtractedDex> performExtractions() throws IOException {
        //  格式：base.apk.classes"
        String extractedFilePrefix = this.sourceApk.getName() + ".classes";
        this.clearDexDir();  //清理dex文件
        List<MultiDexExtractor.ExtractedDex> files = new ArrayList();

        ZipFile apk = new ZipFile(this.sourceApk);// 把.apk转换成.zip

        try {
            int secondaryNumber = 2;
            //apk本质上就是归档文件上面步骤已经把apk变成了zip文件 ，for循环遍历zip文件 ，获取的dex文件
            // classes2.dex  classesN.dex
            for(ZipEntry dexFile = apk.getEntry("classes" + secondaryNumber + ".dex"); dexFile != null; dexFile = apk.getEntry("classes" + secondaryNumber + ".dex")) {
                //获取的应该是base.apk.classes2.zip
                String fileName = extractedFilePrefix + secondaryNumber + ".zip";
                //创建base.apk.classes2.zip 文件
                MultiDexExtractor.ExtractedDex extractedFile = new MultiDexExtractor.ExtractedDex(this.dexDir, fileName);//
                // 添加到文件列表(base.apk.classes2.zip 添加到/data/user/0/packageName/files/code_cache/secondary-dexes文件下)
                files.add(extractedFile);
                Log.i("MultiDex", "Extraction is needed for file " + extractedFile);
                int numAttempts = 0;
                boolean isExtractionSuccessful = false; //是否提取成功

                while(numAttempts < 3 && !isExtractionSuccessful) {
                    ++numAttempts;

                    //将classes2.dex文件写到压缩文件classes2.zip里去，最多重试三次
                    extract(apk, dexFile, extractedFile, extractedFilePrefix);

                    try {
                        extractedFile.crc = getZipCrc(extractedFile);
                        isExtractionSuccessful = true;
                    } catch (IOException var18) {
                        isExtractionSuccessful = false;
                        Log.w("MultiDex", "Failed to read crc from " + extractedFile.getAbsolutePath(), var18);
                    }

                    Log.i("MultiDex", "Extraction " + (isExtractionSuccessful ? "succeeded" : "failed") + " '" + extractedFile.getAbsolutePath() + "': length " + extractedFile.length() + " - crc: " + extractedFile.crc);

                    if (!isExtractionSuccessful) {
                        //未校验通过则删除。
                        extractedFile.delete();
                        if (extractedFile.exists()) {
                            Log.w("MultiDex", "Failed to delete corrupted secondary dex '" + extractedFile.getPath() + "'");
                        }
                    }
                }

                if (!isExtractionSuccessful) {
                    throw new IOException("Could not create zip file " + extractedFile.getAbsolutePath() + " for secondary dex (" + secondaryNumber + ")");
                }

                ++secondaryNumber;
            }
        } finally {
            try {
                apk.close();
            } catch (IOException var17) {
                Log.w("MultiDex", "Failed to close resource", var17);
            }
        }
        return files; //返回dex的压缩文件列表
    }
    /**
     * 重要方法
     * @param apk apk源文件：/data/app/ base.apk
     * @param dexFile apk源文件解压出来的Dex文件：classes2.dex等
     * @param extractTo 提取出来的文件
     * @param extractedFilePrefix 提取出来的文件前缀
     */

    private static void extract(ZipFile apk, ZipEntry dexFile, File extractTo, String extractedFilePrefix) throws IOException, FileNotFoundException {
        InputStream in = apk.getInputStream(dexFile);
        ZipOutputStream out = null;
        //tmp-  base.apk.classes.zip
        File tmp = File.createTempFile("tmp-" + extractedFilePrefix, ".zip", extractTo.getParentFile()); //创建临时文件
        Log.i("MultiDex", "Extracting " + tmp.getPath());

        try {
            out = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(tmp)));

            try {
                ZipEntry classesDex = new ZipEntry("classes.dex");
                classesDex.setTime(dexFile.getTime());
                out.putNextEntry(classesDex);
                byte[] buffer = new byte[16384];

                for(int length = in.read(buffer); length != -1; length = in.read(buffer)) {
                    out.write(buffer, 0, length);
                }

                out.closeEntry();
            } finally {
                out.close();
            }

            if (!tmp.setReadOnly()) {
                throw new IOException("Failed to mark readonly \"" + tmp.getAbsolutePath() + "\" (tmp of \"" + extractTo.getAbsolutePath() + "\")");
            }

            Log.i("MultiDex", "Renaming to " + extractTo.getPath());
            if (!tmp.renameTo(extractTo)) {
                throw new IOException("Failed to rename \"" + tmp.getAbsolutePath() + "\" to \"" + extractTo.getAbsolutePath() + "\"");
            }
        } finally {
            closeQuietly(in);
            tmp.delete();
        }

    }




    /**
     * 本地存储方式保存APK信息
     * @param context
     * @param keyPrefix
     * @param timeStamp
     * @param crc
     * @param extractedDexes
     */
    private static void putStoredApkInfo(Context context, String keyPrefix, long timeStamp, long crc, List<MultiDexExtractor.ExtractedDex> extractedDexes) {
        SharedPreferences prefs = getMultiDexPreferences(context);
        Editor edit = prefs.edit();
        edit.putLong(keyPrefix + "timestamp", timeStamp);
        edit.putLong(keyPrefix + "crc", crc);
        edit.putInt(keyPrefix + "dex.number", extractedDexes.size() + 1);//dex 个数
        int extractedDexId = 2;

        for(Iterator var10 = extractedDexes.iterator(); var10.hasNext(); ++extractedDexId) {
            MultiDexExtractor.ExtractedDex dex = (MultiDexExtractor.ExtractedDex)var10.next();
            edit.putLong(keyPrefix + "dex.crc." + extractedDexId, dex.crc);
            edit.putLong(keyPrefix + "dex.time." + extractedDexId, dex.lastModified());
        }

        edit.commit();
    }

   @SuppressLint("WrongConstant")
    private static SharedPreferences getMultiDexPreferences(Context context) {
        return context.getSharedPreferences("multidex.version", VERSION.SDK_INT < 11 ? 0 : 4);
    }

    private void clearDexDir() {
        File[] files = this.dexDir.listFiles(new FileFilter() {
            public boolean accept(File pathname) {
                return !pathname.getName().equals("MultiDex.lock");
            }
        });
        if (files == null) {
            Log.w("MultiDex", "Failed to list secondary dex dir content (" + this.dexDir.getPath() + ").");
        } else {
            File[] var2 = files;
            int var3 = files.length;

            for(int var4 = 0; var4 < var3; ++var4) {
                File oldFile = var2[var4];
                Log.i("MultiDex", "Trying to delete old file " + oldFile.getPath() + " of size " + oldFile.length());
                if (!oldFile.delete()) {
                    Log.w("MultiDex", "Failed to delete old file " + oldFile.getPath());
                } else {
                    Log.i("MultiDex", "Deleted old file " + oldFile.getPath());
                }
            }

        }
    }



    /**
     * 关闭流并且释放与其相关的任何方法
     * @param closeable
     */
    private static void closeQuietly(Closeable closeable) {
        try {
            closeable.close();
        } catch (IOException var2) {
            Log.w("MultiDex", "Failed to close resource", var2);
        }

    }

    private static class ExtractedDex extends File {
        public long crc = -1L;

        public ExtractedDex(File dexDir, String fileName) {
            super(dexDir, fileName);
        }
    }
}
