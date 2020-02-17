/*
 * Copyright (C) 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.tests.fused;

import static android.os.SystemProperties.getBoolean;
import static android.provider.MediaStore.MediaColumns;

import static androidx.test.InstrumentationRegistry.getContext;

import static com.android.tests.fused.lib.RedactionTestHelper.assertExifMetadataMatch;
import static com.android.tests.fused.lib.RedactionTestHelper.assertExifMetadataMismatch;
import static com.android.tests.fused.lib.RedactionTestHelper.getExifMetadata;
import static com.android.tests.fused.lib.RedactionTestHelper.getExifMetadataFromRawResource;
import static com.android.tests.fused.lib.TestUtils.adoptShellPermissionIdentity;
import static com.android.tests.fused.lib.TestUtils.allowAppOpsToUid;
import static com.android.tests.fused.lib.TestUtils.assertThrows;
import static com.android.tests.fused.lib.TestUtils.createFileAs;
import static com.android.tests.fused.lib.TestUtils.deleteFileAs;
import static com.android.tests.fused.lib.TestUtils.deleteFileAsNoThrow;
import static com.android.tests.fused.lib.TestUtils.deleteRecursively;
import static com.android.tests.fused.lib.TestUtils.deleteWithMediaProvider;
import static com.android.tests.fused.lib.TestUtils.denyAppOpsToUid;
import static com.android.tests.fused.lib.TestUtils.dropShellPermissionIdentity;
import static com.android.tests.fused.lib.TestUtils.executeShellCommand;
import static com.android.tests.fused.lib.TestUtils.getContentResolver;
import static com.android.tests.fused.lib.TestUtils.getFileMimeTypeFromDatabase;
import static com.android.tests.fused.lib.TestUtils.getFileRowIdFromDatabase;
import static com.android.tests.fused.lib.TestUtils.getFileUri;
import static com.android.tests.fused.lib.TestUtils.installApp;
import static com.android.tests.fused.lib.TestUtils.listAs;
import static com.android.tests.fused.lib.TestUtils.openWithMediaProvider;
import static com.android.tests.fused.lib.TestUtils.readExifMetadataFromTestApp;
import static com.android.tests.fused.lib.TestUtils.revokeReadExternalStorage;
import static com.android.tests.fused.lib.TestUtils.uninstallApp;
import static com.android.tests.fused.lib.TestUtils.uninstallAppNoThrow;
import static com.android.tests.fused.lib.TestUtils.updateDisplayNameWithMediaProvider;
import static com.android.tests.fused.lib.TestUtils.pollForExternalStorageState;

import static com.google.common.truth.Truth.assertThat;

import static junit.framework.Assert.fail;

import static org.junit.Assume.assumeTrue;

import android.Manifest;
import android.app.AppOpsManager;
import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.os.FileUtils;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.provider.MediaStore;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.test.runner.AndroidJUnit4;

import com.android.cts.install.lib.TestApp;
import com.android.tests.fused.lib.ReaddirTestHelper;

import com.google.common.io.ByteStreams;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashMap;

@RunWith(AndroidJUnit4.class)
public class FilePathAccessTest {
    static final String TAG = "FilePathAccessTest";
    static final String THIS_PACKAGE_NAME = getContext().getPackageName();

    static final File EXTERNAL_STORAGE_DIR = Environment.getExternalStorageDirectory();

    static final File DCIM_DIR = new File(EXTERNAL_STORAGE_DIR, Environment.DIRECTORY_DCIM);
    static final File PICTURES_DIR = new File(EXTERNAL_STORAGE_DIR, Environment.DIRECTORY_PICTURES);
    static final File MUSIC_DIR = new File(EXTERNAL_STORAGE_DIR, Environment.DIRECTORY_MUSIC);
    static final File MOVIES_DIR = new File(EXTERNAL_STORAGE_DIR, Environment.DIRECTORY_MOVIES);
    static final File DOWNLOAD_DIR = new File(EXTERNAL_STORAGE_DIR,
            Environment.DIRECTORY_DOWNLOADS);
    static final File ANDROID_DATA_DIR = new File(EXTERNAL_STORAGE_DIR, "Android/data");
    static final File ANDROID_MEDIA_DIR = new File(EXTERNAL_STORAGE_DIR, "Android/media");
    static final String TEST_DIRECTORY_NAME = "FilePathAccessTestDirectory";

    static final File EXTERNAL_FILES_DIR = getContext().getExternalFilesDir(null);
    static final File EXTERNAL_MEDIA_DIR = getContext().getExternalMediaDirs()[0];

    static final String MUSIC_FILE_NAME = "FilePathAccessTest_file.mp3";
    static final String VIDEO_FILE_NAME = "FilePathAccessTest_file.mp4";
    static final String IMAGE_FILE_NAME = "FilePathAccessTest_file.jpg";
    static final String NONMEDIA_FILE_NAME = "FilePathAccessTest_file.pdf";

    static final String STR_DATA1 = "Just some random text";
    static final String STR_DATA2 = "More arbitrary stuff";

    static final byte[] BYTES_DATA1 = STR_DATA1.getBytes();
    static final byte[] BYTES_DATA2 = STR_DATA2.getBytes();

    static final String FILE_CREATION_ERROR_MESSAGE = "No such file or directory";

    private static final TestApp TEST_APP_A  = new TestApp("TestAppA",
            "com.android.tests.fused.testapp.A", 1, false, "TestAppA.apk");
    private static final TestApp TEST_APP_B  = new TestApp("TestAppB",
            "com.android.tests.fused.testapp.B", 1, false, "TestAppB.apk");
    private static final String[] SYSTEM_GALERY_APPOPS = { AppOpsManager.OPSTR_WRITE_MEDIA_IMAGES,
            AppOpsManager.OPSTR_WRITE_MEDIA_VIDEO };

    @Before
    public void setUp() throws Exception {
        // skips all test cases if FUSE is not active.
        assumeTrue(getBoolean("persist.sys.fuse", false));

        pollForExternalStorageState();
        EXTERNAL_FILES_DIR.mkdirs();
    }

    /**
     * Test that we enforce certain media types can only be created in certain directories.
     */
    @Test
    public void testTypePathConformity() throws Exception {
        // Only music files can be created in Music
        assertThrows(IOException.class, "Operation not permitted", () -> {
            new File(MUSIC_DIR, NONMEDIA_FILE_NAME).createNewFile();
        });
        assertThrows(IOException.class, "Operation not permitted", () -> {
            new File(MUSIC_DIR, VIDEO_FILE_NAME).createNewFile();
        });
        assertThrows(IOException.class, "Operation not permitted", () -> {
            new File(MUSIC_DIR, IMAGE_FILE_NAME).createNewFile();
        });
        // Only video files can be created in Movies
        assertThrows(IOException.class, "Operation not permitted", () -> {
            new File(MOVIES_DIR, NONMEDIA_FILE_NAME).createNewFile();
        });
        assertThrows(IOException.class, "Operation not permitted", () -> {
            new File(MOVIES_DIR, MUSIC_FILE_NAME).createNewFile();
        });
        assertThrows(IOException.class, "Operation not permitted", () -> {
            new File(MOVIES_DIR, IMAGE_FILE_NAME).createNewFile();
        });
        // Only image and video files can be created in DCIM
        assertThrows(IOException.class, "Operation not permitted", () -> {
            new File(DCIM_DIR, NONMEDIA_FILE_NAME).createNewFile();
        });
        assertThrows(IOException.class, "Operation not permitted", () -> {
            new File(DCIM_DIR, MUSIC_FILE_NAME).createNewFile();
        });

        assertCanCreateFile(new File(DCIM_DIR, IMAGE_FILE_NAME));
        assertCanCreateFile(new File(MUSIC_DIR, MUSIC_FILE_NAME));
        assertCanCreateFile(new File(MOVIES_DIR, VIDEO_FILE_NAME));
        assertCanCreateFile(new File(DOWNLOAD_DIR, NONMEDIA_FILE_NAME));

        // No file whatsoever can be created in the top level directory
        assertThrows(IOException.class, "Operation not permitted", () -> {
            new File(EXTERNAL_STORAGE_DIR, NONMEDIA_FILE_NAME).createNewFile();
        });
        assertThrows(IOException.class, "Operation not permitted", () -> {
            new File(EXTERNAL_STORAGE_DIR, MUSIC_FILE_NAME).createNewFile();
        });
        assertThrows(IOException.class, "Operation not permitted", () -> {
            new File(EXTERNAL_STORAGE_DIR, IMAGE_FILE_NAME).createNewFile();
        });
        assertThrows(IOException.class, "Operation not permitted", () -> {
            new File(EXTERNAL_STORAGE_DIR, VIDEO_FILE_NAME).createNewFile();
        });
    }

    /**
     * Test that we can create a file in app's external files directory,
     * and that we can write and read to/from the file.
     */
    @Test
    public void testCreateFileInAppExternalDir() throws Exception {
        final File file = new File(EXTERNAL_FILES_DIR, "text.txt");
        try {
            assertThat(file.createNewFile()).isTrue();
            assertThat(file.delete()).isTrue();
            // Ensure the file is properly deleted and can be created again
            assertThat(file.createNewFile()).isTrue();

            // Write to file
            try (final FileOutputStream fos = new FileOutputStream(file)) {
                fos.write(BYTES_DATA1);
            }

            // Read the same data from file
            assertFileContent(file, BYTES_DATA1);
        } finally {
            file.delete();
        }
    }

    /**
     * Test that we can't create a file in another app's external files directory,
     * and that we'll get the same error regardless of whether the app exists or not.
     */
    @Test
    public void testCreateFileInOtherAppExternalDir() throws Exception {
        // Creating a file in a non existent package dir should return ENOENT, as expected
        final File nonexistentPackageFileDir = new File(EXTERNAL_FILES_DIR.getPath()
                .replace(THIS_PACKAGE_NAME, "no.such.package"));
        final File file1 = new File(nonexistentPackageFileDir, NONMEDIA_FILE_NAME);
        assertThrows(IOException.class, FILE_CREATION_ERROR_MESSAGE,
                () -> { file1.createNewFile(); });

        // Creating a file in an existent package dir should give the same error string to avoid
        // leaking installed app names, and we know the following directory exists because shell
        // mkdirs it in test setup
        final File shellPackageFileDir = new File(EXTERNAL_FILES_DIR.getPath()
                .replace(THIS_PACKAGE_NAME, "com.android.shell"));
        final File file2 = new File(shellPackageFileDir, NONMEDIA_FILE_NAME);
        assertThrows(IOException.class, FILE_CREATION_ERROR_MESSAGE,
                () -> { file1.createNewFile(); });
    }

    /**
     * Test that we can contribute media without any permissions.
     */
    @Test
    public void testContributeMediaFile() throws Exception {
        final File imageFile = new File(DCIM_DIR, IMAGE_FILE_NAME);

        ContentResolver cr = getContentResolver();
        final String selection = MediaColumns.RELATIVE_PATH + " = ? AND "
                + MediaColumns.DISPLAY_NAME + " = ?";
        final String[] selectionArgs = { Environment.DIRECTORY_DCIM + '/', IMAGE_FILE_NAME };

        try {
            assertThat(imageFile.createNewFile()).isTrue();

            // Ensure that the file was successfully added to the MediaProvider database
            try (final Cursor c = cr.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    /* projection */new String[] {MediaColumns.OWNER_PACKAGE_NAME},
                    selection, selectionArgs, null)) {
                assertThat(c.getCount()).isEqualTo(1);
                c.moveToFirst();
                assertThat(c.getString(c.getColumnIndex(MediaColumns.OWNER_PACKAGE_NAME)))
                        .isEqualTo("com.android.tests.fused");
            }

            // Try to write random data to the file
            try (final FileOutputStream fos = new FileOutputStream(imageFile)) {
                fos.write(BYTES_DATA1);
                fos.write(BYTES_DATA2);
            }

            final byte[] expected = (STR_DATA1 + STR_DATA2).getBytes();
            assertFileContent(imageFile, expected);

            // Closing the file after writing will not trigger a MediaScan. Call scanFile to update
            // file's entry in MediaProvider's database.
            assertThat(MediaStore.scanFile(getContentResolver(), imageFile)).isNotNull();

            // Ensure that the scan was completed and the file's size was updated.
            try (final Cursor c = cr.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                            /* projection */new String[] {MediaColumns.SIZE},
                            selection, selectionArgs, null)) {
                assertThat(c.getCount()).isEqualTo(1);
                c.moveToFirst();
                assertThat(c.getInt(c.getColumnIndex(MediaColumns.SIZE)))
                        .isEqualTo(BYTES_DATA1.length + BYTES_DATA2.length);
            }
        } finally {
            imageFile.delete();
        }
        // Ensure that delete makes a call to MediaProvider to remove the file from its database.
        try (final Cursor c = cr.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                /* projection */new String[] {MediaColumns.OWNER_PACKAGE_NAME},
                selection, selectionArgs, null)) {
            assertThat(c.getCount()).isEqualTo(0);
        }
    }

    @Test
    public void testCreateAndDeleteEmptyDir() throws Exception {
        // Remove directory in order to create it again
        EXTERNAL_FILES_DIR.delete();

        // Can create own external files dir
        assertThat(EXTERNAL_FILES_DIR.mkdir()).isTrue();

        final File dir1 = new File(EXTERNAL_FILES_DIR, "random_dir");
        // Can create dirs inside it
        assertThat(dir1.mkdir()).isTrue();

        final File dir2 = new File(dir1, "random_dir_inside_random_dir");
        // And create a dir inside the new dir
        assertThat(dir2.mkdir()).isTrue();

        // And can delete them all
        assertThat(dir2.delete()).isTrue();
        assertThat(dir1.delete()).isTrue();
        assertThat(EXTERNAL_FILES_DIR.delete()).isTrue();

        // Can't create external dir for other apps
        final File nonexistentPackageFileDir = new File(EXTERNAL_FILES_DIR.getPath()
                .replace(THIS_PACKAGE_NAME, "no.such.package"));
        final File shellPackageFileDir = new File(EXTERNAL_FILES_DIR.getPath()
                .replace(THIS_PACKAGE_NAME, "com.android.shell"));

        assertThat(nonexistentPackageFileDir.mkdir()).isFalse();
        assertThat(shellPackageFileDir.mkdir()).isFalse();
    }

    @Test
    public void testCantAccessOtherAppsContents() throws Exception {
        final File mediaFile = new File(PICTURES_DIR, IMAGE_FILE_NAME);
        final File nonMediaFile = new File(DOWNLOAD_DIR, NONMEDIA_FILE_NAME);
        try {
            installApp(TEST_APP_A, false);

            assertThat(createFileAs(TEST_APP_A, mediaFile.getPath())).isTrue();
            assertThat(createFileAs(TEST_APP_A, nonMediaFile.getPath())).isTrue();

            // We can still see that the files exist
            assertThat(mediaFile.exists()).isTrue();
            assertThat(nonMediaFile.exists()).isTrue();

            // But we can't access their content
            try (FileInputStream fis = new FileInputStream(mediaFile)) {
                fail("Opening for read succeeded when it should have failed: " + mediaFile);
            } catch (IOException expected) {}

            try (FileInputStream fis = new FileInputStream(nonMediaFile)) {
                fail("Opening for read succeeded when it should have failed: " + nonMediaFile);
            } catch (IOException expected) {}

            try (FileOutputStream fos = new FileOutputStream(mediaFile)) {
                fail("Opening for write succeeded when it should have failed: " + mediaFile);
            } catch (IOException expected) {}

            try (FileOutputStream fos = new FileOutputStream(nonMediaFile)) {
                fail("Opening for write succeeded when it should have failed: " + nonMediaFile);
            } catch (IOException expected) {}

        } finally {
            deleteFileAsNoThrow(TEST_APP_A, nonMediaFile.getPath());
            deleteFileAsNoThrow(TEST_APP_A, mediaFile.getPath());
            uninstallAppNoThrow(TEST_APP_A);
        }
    }

    @Test
    public void testCantDeleteOtherAppsContents() throws Exception {
        final File dirInDownload = new File(DOWNLOAD_DIR, TEST_DIRECTORY_NAME);
        final File mediaFile = new File(dirInDownload, IMAGE_FILE_NAME);
        final File nonMediaFile = new File(dirInDownload, NONMEDIA_FILE_NAME);
        try {
            installApp(TEST_APP_A, false);
            assertThat(dirInDownload.mkdir()).isTrue();
            // Have another app create a media file in the directory
            assertThat(createFileAs(TEST_APP_A, mediaFile.getPath())).isTrue();

            // Can't delete the directory since it contains another app's content
            assertThat(dirInDownload.delete()).isFalse();
            // Can't delete another app's content
            assertThat(deleteRecursively(dirInDownload)).isFalse();

            // Have another app create a non-media file in the directory
            assertThat(createFileAs(TEST_APP_A, nonMediaFile.getPath())).isTrue();

            // Can't delete the directory since it contains another app's content
            assertThat(dirInDownload.delete()).isFalse();
            // Can't delete another app's content
            assertThat(deleteRecursively(dirInDownload)).isFalse();

            // Delete only the media file and keep the non-media file
            assertThat(deleteFileAs(TEST_APP_A, mediaFile.getPath())).isTrue();
            // Directory now has only the non-media file contributed by another app, so we still
            // can't delete it nor its content
            assertThat(dirInDownload.delete()).isFalse();
            assertThat(deleteRecursively(dirInDownload)).isFalse();

            // Delete the last file belonging to another app
            assertThat(deleteFileAs(TEST_APP_A, nonMediaFile.getPath())).isTrue();
            // Create our own file
            assertThat(nonMediaFile.createNewFile()).isTrue();

            // Now that the directory only has content that was contributed by us, we can delete it
            assertThat(deleteRecursively(dirInDownload)).isTrue();
        } finally {
            deleteFileAsNoThrow(TEST_APP_A, nonMediaFile.getPath());
            deleteFileAsNoThrow(TEST_APP_A, mediaFile.getPath());
            // At this point, we're not sure who created this file, so we'll have both apps
            // deleting it
            mediaFile.delete();
            uninstallAppNoThrow(TEST_APP_A);
            dirInDownload.delete();
        }
    }

    /**
     * This test relies on the fact that {@link File#list} uses opendir internally, and that it
     * returns {@code null} if opendir fails.
     */
    @Test
    public void testOpendirRestrictions() throws Exception {
        // Opening a non existent package directory should fail, as expected
        final File nonexistentPackageFileDir = new File(EXTERNAL_FILES_DIR.getPath()
                .replace(THIS_PACKAGE_NAME, "no.such.package"));
        assertThat(nonexistentPackageFileDir.list()).isNull();

        // Opening another package's external directory should fail as well, even if it exists
        final File shellPackageFileDir = new File(EXTERNAL_FILES_DIR.getPath()
                .replace(THIS_PACKAGE_NAME, "com.android.shell"));
        assertThat(shellPackageFileDir.list()).isNull();

        // We can open our own external files directory
        final String[] filesList = EXTERNAL_FILES_DIR.list();
        assertThat(filesList).isNotNull();
        assertThat(filesList).isEmpty();

        // We can open any public directory in external storage
        assertThat(DCIM_DIR.list()).isNotNull();
        assertThat(DOWNLOAD_DIR.list()).isNotNull();
        assertThat(MOVIES_DIR.list()).isNotNull();
        assertThat(MUSIC_DIR.list()).isNotNull();

        // We can open the root directory of external storage
        final String[] topLevelDirs = EXTERNAL_STORAGE_DIR.list();
        assertThat(topLevelDirs).isNotNull();
        // TODO(b/145287327): This check fails on a device with no visible files.
        // This can be fixed if we display default directories.
        // assertThat(topLevelDirs).isNotEmpty();
    }

    @Test
    public void testLowLevelFileIO() throws Exception {
        String filePath = new File(DOWNLOAD_DIR, NONMEDIA_FILE_NAME).toString();
        try {
            int createFlags = OsConstants.O_CREAT | OsConstants.O_RDWR;
            int createExclFlags = createFlags | OsConstants.O_EXCL;

            FileDescriptor fd = Os.open(filePath, createExclFlags, OsConstants.S_IRWXU);
            Os.close(fd);
            assertThrows(ErrnoException.class, () -> {
                Os.open(filePath, createExclFlags, OsConstants.S_IRWXU);
            });

            fd = Os.open(filePath, createFlags, OsConstants.S_IRWXU);
            try {
                assertThat(Os.write(fd, ByteBuffer.wrap(BYTES_DATA1)))
                        .isEqualTo(BYTES_DATA1.length);
                assertFileContent(fd, BYTES_DATA1);
            } finally {
                Os.close(fd);
            }
            // should just append the data
            fd = Os.open(filePath, createFlags | OsConstants.O_APPEND, OsConstants.S_IRWXU);
            try {
                assertThat(Os.write(fd, ByteBuffer.wrap(BYTES_DATA2)))
                        .isEqualTo(BYTES_DATA2.length);
                final byte[] expected = (STR_DATA1 + STR_DATA2).getBytes();
                assertFileContent(fd, expected);
            } finally {
                Os.close(fd);
            }
            // should overwrite everything
            fd = Os.open(filePath, createFlags | OsConstants.O_TRUNC, OsConstants.S_IRWXU);
            try {
                final byte[] otherData = "this is different data".getBytes();
                assertThat(Os.write(fd, ByteBuffer.wrap(otherData))).isEqualTo(otherData.length);
                assertFileContent(fd, otherData);
            } finally {
                Os.close(fd);
            }
        } finally {
            new File(filePath).delete();
        }
    }

    /**
     * Test that media files from other packages are only visible to apps with storage permission.
     */
    @Test
    public void testListDirectoriesWithMediaFiles() throws Exception {
        final File dir = new File(DCIM_DIR, TEST_DIRECTORY_NAME);
        final File videoFile = new File(dir, VIDEO_FILE_NAME);
        final String videoFileName = videoFile.getName();
        try {
            if (!dir.exists()) {
                assertThat(dir.mkdir()).isTrue();
            }

            // Install TEST_APP_A and create media file in the new directory.
            installApp(TEST_APP_A, false);
            assertThat(createFileAs(TEST_APP_A, videoFile.getPath())).isTrue();
            // TEST_APP_A should see TEST_DIRECTORY in DCIM and new file in TEST_DIRECTORY.
            assertThat(listAs(TEST_APP_A, DCIM_DIR.getPath())).contains(TEST_DIRECTORY_NAME);
            assertThat(listAs(TEST_APP_A, dir.getPath())).containsExactly(videoFileName);

            // Install TEST_APP_B with storage permission.
            installApp(TEST_APP_B, true);
            // TEST_APP_B with storage permission should see TEST_DIRECTORY in DCIM and new file
            // in TEST_DIRECTORY.
            assertThat(listAs(TEST_APP_B, DCIM_DIR.getPath())).contains(TEST_DIRECTORY_NAME);
            assertThat(listAs(TEST_APP_B, dir.getPath())).containsExactly(videoFileName);

            // Revoke storage permission for TEST_APP_B
            revokeReadExternalStorage(TEST_APP_B.getPackageName());
            // TEST_APP_B without storage permission should see TEST_DIRECTORY in DCIM and should
            // not see new file in new TEST_DIRECTORY.
            assertThat(listAs(TEST_APP_B, DCIM_DIR.getPath())).contains(TEST_DIRECTORY_NAME);
            assertThat(listAs(TEST_APP_B, dir.getPath())).doesNotContain(videoFileName);
        } finally {
            uninstallAppNoThrow(TEST_APP_B);
            if(videoFile.exists()) {
                deleteFileAsNoThrow(TEST_APP_A, videoFile.getPath());
            }
            if (dir.exists()) {
                  // Try deleting the directory. Do we delete directory if app doesn't own all
                  // files in it?
                  dir.delete();
            }
            uninstallAppNoThrow(TEST_APP_A);
        }
    }

    /**
     * Test that app can't see non-media files created by other packages
     */
    @Test
    public void testListDirectoriesWithNonMediaFiles() throws Exception {
        final File dir = new File(DOWNLOAD_DIR, TEST_DIRECTORY_NAME);
        final File pdfFile = new File(dir, NONMEDIA_FILE_NAME);
        final String pdfFileName = pdfFile.getName();
        try {
            if (!dir.exists()) {
                assertThat(dir.mkdir()).isTrue();
            }

            // Install TEST_APP_A and create non media file in the new directory.
            installApp(TEST_APP_A, false);
            assertThat(createFileAs(TEST_APP_A, pdfFile.getPath())).isTrue();

            // TEST_APP_A should see TEST_DIRECTORY in DOWNLOAD_DIR and new non media file in
            // TEST_DIRECTORY.
            assertThat(listAs(TEST_APP_A, DOWNLOAD_DIR.getPath())).contains(TEST_DIRECTORY_NAME);
            assertThat(listAs(TEST_APP_A, dir.getPath())).containsExactly(pdfFileName);

            // Install TEST_APP_B with storage permission.
            installApp(TEST_APP_B, true);
            // TEST_APP_B with storage permission should see TEST_DIRECTORY in DOWNLOAD_DIR
            // and should not see new non media file in TEST_DIRECTORY.
            assertThat(listAs(TEST_APP_B, DOWNLOAD_DIR.getPath())).contains(TEST_DIRECTORY_NAME);
            assertThat(listAs(TEST_APP_B, dir.getPath())).doesNotContain(pdfFileName);
        } finally {
            uninstallAppNoThrow(TEST_APP_B);
            if(pdfFile.exists()) {
                deleteFileAsNoThrow(TEST_APP_A, pdfFile.getPath());
            }
            if (dir.exists()) {
                  dir.delete();
            }
            uninstallAppNoThrow(TEST_APP_A);
        }
    }

    /**
     * Test that app can only see its directory in Android/data.
     */
    @Test
    public void testListFilesFromExternalFilesDirectory() throws Exception {
        final String packageName = THIS_PACKAGE_NAME;
        final File videoFile = new File(EXTERNAL_FILES_DIR, NONMEDIA_FILE_NAME);
        final String videoFileName = videoFile.getName();

        try {
            // Create a file in app's external files directory
            if (!videoFile.exists()) {
                assertThat(videoFile.createNewFile()).isTrue();
            }
            // App should see its directory and directories of shared packages. App should see all
            // files and directories in its external directory.
            assertThat(ReaddirTestHelper.readDirectory(videoFile.getParentFile()))
                    .containsExactly(videoFileName);

            // Install TEST_APP_A with READ_EXTERNAL_STORAGE permission.
            // TEST_APP_A should not see other app's external files directory.
            installApp(TEST_APP_A, true);
            // TODO(b/146497700): This is passing because ReaddirTestHelper ignores IOException and
            //  returns empty list.
            assertThat(listAs(TEST_APP_A, ANDROID_DATA_DIR.getPath())).doesNotContain(packageName);
            assertThat(listAs(TEST_APP_A, EXTERNAL_FILES_DIR.getPath())).isEmpty();
        } finally {
            videoFile.delete();
            uninstallAppNoThrow(TEST_APP_A);
        }
    }

    /**
     * Test that app can see files and directories in Android/media.
     */
    @Test
    public void testListFilesFromExternalMediaDirectory() throws Exception {
        final File videoFile = new File(EXTERNAL_MEDIA_DIR, VIDEO_FILE_NAME);
        final String videoFileName = videoFile.getName();

        try {
            // Create a file in app's external media directory
            if (!videoFile.exists()) {
                assertThat(videoFile.createNewFile()).isTrue();
            }

            // App should see its directory and other app's external media directories with media
            // files.
            assertThat(ReaddirTestHelper.readDirectory(EXTERNAL_MEDIA_DIR))
                    .containsExactly(videoFileName);

            // Install TEST_APP_A with READ_EXTERNAL_STORAGE permission.
            // TEST_APP_A with storage permission should see other app's external media directory.
            installApp(TEST_APP_A, true);
            // Apps can't list files in other app's external media directory.
            assertThat(listAs(TEST_APP_A, ANDROID_MEDIA_DIR.getPath())).isEmpty();
            assertThat(listAs(TEST_APP_A, EXTERNAL_MEDIA_DIR.getPath())).isEmpty();
        } finally {
            videoFile.delete();
        }
    }

    /**
     * Test that readdir lists unsupported file types in default directories.
     */
    @Test
    public void testListUnsupportedFileType() throws Exception {
        final File pdfFile = new File(DCIM_DIR, NONMEDIA_FILE_NAME);
        final File videoFile = new File(MUSIC_DIR, VIDEO_FILE_NAME);
        try {
            // TEST_APP_A with storage permission should not see pdf file in DCIM
            executeShellCommand("touch " + pdfFile.getAbsolutePath());
            assertThat(pdfFile.exists()).isTrue();
            assertThat(MediaStore.scanFile(getContentResolver(), pdfFile)).isNotNull();

            installApp(TEST_APP_A, true);
            assertThat(listAs(TEST_APP_A, DCIM_DIR.getPath())).doesNotContain(NONMEDIA_FILE_NAME);


            executeShellCommand("touch " + videoFile.getAbsolutePath());
            // ScanFile doesn't insert an empty media file to database. Write some data to ensure
            // file is inserted into database.
            executeShellCommand("echo " + STR_DATA1 + " > " + videoFile.getAbsolutePath());
            // TEST_APP_A with storage permission should see video file in Music directory.
            assertThat(listAs(TEST_APP_A, MUSIC_DIR.getPath())).contains(VIDEO_FILE_NAME);
        } finally {
            executeShellCommand("rm " + pdfFile.getAbsolutePath());
            executeShellCommand("rm " + videoFile.getAbsolutePath());
            uninstallAppNoThrow(TEST_APP_A);
        }
    }

    @Test
    public void testMetaDataRedaction() throws Exception {
        File jpgFile = new File(PICTURES_DIR, "img_metadata.jpg");
        try {
            if (jpgFile.exists()) {
                assertThat(jpgFile.delete()).isTrue();
            }

            HashMap<String, String> originalExif = getExifMetadataFromRawResource(
                    R.raw.img_with_metadata);

            try (InputStream in = getContext().getResources().openRawResource(
                    R.raw.img_with_metadata);
                 OutputStream out = new FileOutputStream(jpgFile)) {
                // Dump the image we have to external storage
                FileUtils.copy(in, out);
            }

            HashMap<String, String> exif = getExifMetadata(jpgFile);
            assertExifMetadataMatch(exif, originalExif);

            installApp(TEST_APP_A, /*grantStoragePermissions*/ true);
            HashMap<String, String> exifFromTestApp = readExifMetadataFromTestApp(TEST_APP_A,
                    jpgFile.getPath());
            // Other apps shouldn't have access to the same metadata without explicit permission
            assertExifMetadataMismatch(exifFromTestApp, originalExif);

            // TODO(b/146346138): Test that if we give TEST_APP_A write URI permission,
            //  it would be able to access the metadata.
        } finally {
            jpgFile.delete();
            uninstallAppNoThrow(TEST_APP_A);
        }
    }

    @Test
    public void testOpenFilePathFirstWriteContentResolver() throws Exception {
        String displayName = "open_file_path_write_content_resolver.jpg";
        File file = new File(DCIM_DIR, displayName);

        try {
            assertThat(file.createNewFile()).isTrue();

            ParcelFileDescriptor readPfd = ParcelFileDescriptor.open(file,
                    ParcelFileDescriptor.MODE_READ_WRITE);
            ParcelFileDescriptor writePfd = openWithMediaProvider(file, "rw");

            assertRWR(readPfd.getFileDescriptor(), writePfd.getFileDescriptor());
            assertUpperFsFd(writePfd); // With cache
        } finally {
            file.delete();
        }
    }

    @Test
    public void testOpenContentResolverFirstWriteContentResolver() throws Exception {
        String displayName = "open_content_resolver_write_content_resolver.jpg";
        File file = new File(DCIM_DIR, displayName);

        try {
            assertThat(file.createNewFile()).isTrue();

            ParcelFileDescriptor writePfd = openWithMediaProvider(file, "rw");
            ParcelFileDescriptor readPfd = ParcelFileDescriptor.open(file,
                    ParcelFileDescriptor.MODE_READ_WRITE);

            assertRWR(readPfd.getFileDescriptor(), writePfd.getFileDescriptor());
            assertLowerFsFd(writePfd);
        } finally {
            file.delete();
        }
    }

    @Test
    public void testOpenFilePathFirstWriteFilePath() throws Exception {
        String displayName = "open_file_path_write_file_path.jpg";
        File file = new File(DCIM_DIR, displayName);

        try {
            assertThat(file.createNewFile()).isTrue();

            ParcelFileDescriptor writePfd = ParcelFileDescriptor.open(file,
                    ParcelFileDescriptor.MODE_READ_WRITE);
            ParcelFileDescriptor readPfd = openWithMediaProvider(file, "rw");

            assertRWR(readPfd.getFileDescriptor(), writePfd.getFileDescriptor());
            assertUpperFsFd(readPfd); // With cache
        } finally {
            file.delete();
        }
    }

    @Test
    public void testOpenContentResolverFirstWriteFilePath() throws Exception {
        String displayName = "open_content_resolver_write_file_path.jpg";
        File file = new File(DCIM_DIR, displayName);

        try {
            assertThat(file.createNewFile()).isTrue();

            ParcelFileDescriptor readPfd = openWithMediaProvider(file, "rw");
            ParcelFileDescriptor writePfd = ParcelFileDescriptor.open(file,
                    ParcelFileDescriptor.MODE_READ_WRITE);

            assertRWR(readPfd.getFileDescriptor(), writePfd.getFileDescriptor());
            assertLowerFsFd(readPfd);
        } finally {
            file.delete();
        }
    }

    @Test
    public void testOpenContentResolverWriteOnly() throws Exception {
        String displayName = "open_content_resolver_write_only.jpg";
        File file = new File(DCIM_DIR, displayName);

        try {
            assertThat(file.createNewFile()).isTrue();

            // Since we can only place one F_WRLCK, the second open for readPfd will go
            // throuh FUSE
            ParcelFileDescriptor writePfd = openWithMediaProvider(file, "w");
            ParcelFileDescriptor readPfd = openWithMediaProvider(file, "rw");

            assertRWR(readPfd.getFileDescriptor(), writePfd.getFileDescriptor());
            assertLowerFsFd(writePfd);
            assertUpperFsFd(readPfd); // Without cache
        } finally {
            file.delete();
        }
    }

    @Test
    public void testOpenContentResolverDup() throws Exception {
        String displayName = "open_content_resolver_dup.jpg";
        File file = new File(DCIM_DIR, displayName);

        try {
            assertThat(file.createNewFile()).isTrue();

            // Even if we close the original fd, since we have a dup open
            // the FUSE IO should still bypass the cache
            ParcelFileDescriptor writePfd = openWithMediaProvider(file, "rw");
            ParcelFileDescriptor writePfdDup = writePfd.dup();
            writePfd.close();
            ParcelFileDescriptor readPfd = ParcelFileDescriptor.open(file,
                    ParcelFileDescriptor.MODE_READ_WRITE);

            assertRWR(readPfd.getFileDescriptor(), writePfdDup.getFileDescriptor());
            assertLowerFsFd(writePfdDup);
        } finally {
            file.delete();
        }
    }

    @Test
    public void testContentResolverDelete() throws Exception {
        String displayName = "content_resolver_delete.jpg";
        File file = new File(DCIM_DIR, displayName);

        try {
            assertThat(file.createNewFile()).isTrue();

            deleteWithMediaProvider(file);

            assertThat(file.exists()).isFalse();
            assertThat(file.createNewFile()).isTrue();
        } finally {
            file.delete();
        }
    }

    @Test
    public void testContentResolverUpdate() throws Exception {
        String oldDisplayName = "content_resolver_update_old.jpg";
        String newDisplayName = "content_resolver_update_new.jpg";
        File oldFile = new File(DCIM_DIR, oldDisplayName);
        File newFile = new File(DCIM_DIR, newDisplayName);

        try {
            assertThat(oldFile.createNewFile()).isTrue();

            updateDisplayNameWithMediaProvider(Environment.DIRECTORY_DCIM, oldDisplayName,
                    newDisplayName);

            assertThat(oldFile.exists()).isFalse();
            assertThat(oldFile.createNewFile()).isTrue();
            assertThat(newFile.exists()).isTrue();
            assertThat(newFile.createNewFile()).isFalse();
        } finally {
            oldFile.delete();
            newFile.delete();
        }
    }

    @Test
    public void testSystemGalleryAppHasFullAccessToImages() throws Exception {
        final File otherAppImageFile = new File(DCIM_DIR, "other_" + IMAGE_FILE_NAME);
        final File topLevelImageFile = new File(EXTERNAL_STORAGE_DIR, IMAGE_FILE_NAME);
        final File imageInAnObviouslyWrongPlace = new File(MUSIC_DIR, IMAGE_FILE_NAME);

        try {
            installApp(TEST_APP_A, false);
            allowAppOpsToUid(Process.myUid(), SYSTEM_GALERY_APPOPS);

            // Have another app create an image file
            assertThat(createFileAs(TEST_APP_A, otherAppImageFile.getPath())).isTrue();
            assertThat(otherAppImageFile.exists()).isTrue();

            // Assert we can write to the file
            try (final FileOutputStream fos = new FileOutputStream(otherAppImageFile)) {
                fos.write(BYTES_DATA1);
            }

            // Assert we can read from the file
            assertFileContent(otherAppImageFile, BYTES_DATA1);

            // Assert we can delete the file
            assertThat(otherAppImageFile.delete()).isTrue();
            assertThat(otherAppImageFile.exists()).isFalse();

            // Can create an image anywhere
            assertCanCreateFile(topLevelImageFile);
            assertCanCreateFile(imageInAnObviouslyWrongPlace);
        } finally {
            otherAppImageFile.delete();
            uninstallApp(TEST_APP_A);
            denyAppOpsToUid(Process.myUid(), SYSTEM_GALERY_APPOPS);
        }
    }

    @Test
    public void testSystemGalleryAppHasNoFullAccessToAudio() throws Exception {
        final File otherAppAudioFile = new File(MUSIC_DIR, "other_" + MUSIC_FILE_NAME);
        final File topLevelAudioFile = new File(EXTERNAL_STORAGE_DIR, MUSIC_FILE_NAME);
        final File audioInAnObviouslyWrongPlace = new File(PICTURES_DIR, MUSIC_FILE_NAME);

        try {
            installApp(TEST_APP_A, false);
            allowAppOpsToUid(Process.myUid(), SYSTEM_GALERY_APPOPS);

            // Have another app create an audio file
            assertThat(createFileAs(TEST_APP_A, otherAppAudioFile.getPath())).isTrue();
            assertThat(otherAppAudioFile.exists()).isTrue();

            // Assert we can't write to the file
            try (FileInputStream fis = new FileInputStream(otherAppAudioFile)) {
                fail("Opening for read succeeded when it should have failed: " + otherAppAudioFile);
            } catch (IOException expected) {}

            // Assert we can't read from the file
            try (FileOutputStream fos = new FileOutputStream(otherAppAudioFile)) {
                fail("Opening for write succeeded when it should have failed: "
                        + otherAppAudioFile);
            } catch (IOException expected) {}

            // Assert we can't delete the file
            assertThat(otherAppAudioFile.delete()).isFalse();

            // Can't create an audio file where it doesn't belong
            assertThrows(IOException.class, "Operation not permitted", () -> {
                topLevelAudioFile.createNewFile();
            });
            assertThrows(IOException.class, "Operation not permitted", () -> {
                audioInAnObviouslyWrongPlace.createNewFile();
            });
        } finally {
            deleteFileAs(TEST_APP_A, otherAppAudioFile.getPath());
            uninstallApp(TEST_APP_A);
            topLevelAudioFile.delete();
            audioInAnObviouslyWrongPlace.delete();
            denyAppOpsToUid(Process.myUid(), SYSTEM_GALERY_APPOPS);
        }
    }

    @Test
    public void testSystemGalleryCanRenameImagesAndVideos() throws Exception {
        final File otherAppVideoFile = new File(DCIM_DIR, "other_" + VIDEO_FILE_NAME);
        final File imageFile = new File(PICTURES_DIR, IMAGE_FILE_NAME);
        final File videoFile = new File(PICTURES_DIR, VIDEO_FILE_NAME);
        final File topLevelVideoFile = new File(EXTERNAL_STORAGE_DIR, VIDEO_FILE_NAME);
        final File musicFile = new File(MUSIC_DIR, MUSIC_FILE_NAME);
        try {
            installApp(TEST_APP_A, false);
            allowAppOpsToUid(Process.myUid(), SYSTEM_GALERY_APPOPS);

            // Have another app create a video file
            assertThat(createFileAs(TEST_APP_A, otherAppVideoFile.getPath())).isTrue();
            assertThat(otherAppVideoFile.exists()).isTrue();

            // Write some data to the file
            try (final FileOutputStream fos = new FileOutputStream(otherAppVideoFile)) {
                fos.write(BYTES_DATA1);
            }
            assertFileContent(otherAppVideoFile, BYTES_DATA1);

            // Assert we can rename the file and ensure the file has the same content
            assertThat(otherAppVideoFile.renameTo(videoFile)).isTrue();
            assertThat(otherAppVideoFile.exists()).isFalse();
            assertFileContent(videoFile, BYTES_DATA1);
            // We can even move it to the top level directory
            assertThat(videoFile.renameTo(topLevelVideoFile)).isTrue();
            assertThat(videoFile.exists()).isFalse();
            assertFileContent(topLevelVideoFile, BYTES_DATA1);
            // And we can even convert it into an image file, because why not?
            assertThat(topLevelVideoFile.renameTo(imageFile)).isTrue();
            assertThat(topLevelVideoFile.exists()).isFalse();
            assertFileContent(imageFile, BYTES_DATA1);

            // However, we can't convert it to a music file, because system gallery has full access
            // to images and video only
            assertThat(imageFile.renameTo(musicFile)).isFalse();

            // Rename file back to it's original name so that the test app can clean it up
            assertThat(imageFile.renameTo(otherAppVideoFile)).isTrue();
        } finally {
            deleteFileAs(TEST_APP_A, otherAppVideoFile.getPath());
            uninstallApp(TEST_APP_A);
            imageFile.delete();
            videoFile.delete();
            topLevelVideoFile.delete();
            musicFile.delete();
            denyAppOpsToUid(Process.myUid(), SYSTEM_GALERY_APPOPS);
        }
    }

    /**
     * Test that basic file path restrictions are enforced on file rename.
     */
    @Test
    public void testRenameFile() throws Exception {
        final File nonMediaDir = new File(DOWNLOAD_DIR, TEST_DIRECTORY_NAME);
        final File pdfFile1 = new File(DOWNLOAD_DIR, NONMEDIA_FILE_NAME);
        final File pdfFile2 = new File(nonMediaDir, NONMEDIA_FILE_NAME);
        final File videoFile1 = new File(DCIM_DIR, VIDEO_FILE_NAME);
        final File videoFile2 = new File(MOVIES_DIR, VIDEO_FILE_NAME);
        final File videoFile3 = new File(DOWNLOAD_DIR, VIDEO_FILE_NAME);

        try {
            // Renaming non media file to media directory is not allowed.
            assertThat(pdfFile1.createNewFile()).isTrue();
            assertCantRenameFile(pdfFile1, new File(DCIM_DIR, NONMEDIA_FILE_NAME));
            assertCantRenameFile(pdfFile1, new File(MUSIC_DIR, NONMEDIA_FILE_NAME));
            assertCantRenameFile(pdfFile1, new File(MOVIES_DIR, NONMEDIA_FILE_NAME));

            // Renaming non media files to non media directories is allowed.
            if (!nonMediaDir.exists()) {
                assertThat(nonMediaDir.mkdirs()).isTrue();
            }
            // App can rename pdfFile to non media directory.
            assertCanRenameFile(pdfFile1, pdfFile2);

            assertThat(videoFile1.createNewFile()).isTrue();
            // App can rename video file to Movies directory
            assertCanRenameFile(videoFile1, videoFile2);
            // App can rename video file to Download directory
            assertCanRenameFile(videoFile2, videoFile3);
        } finally {
            pdfFile1.delete();
            pdfFile2.delete();
            videoFile1.delete();
            videoFile2.delete();
            videoFile3.delete();
            nonMediaDir.delete();
        }
    }

    /**
     * Test that renaming file to different mime type is allowed.
     */
    @Test
    public void testRenameFileType() throws Exception {
        final File pdfFile = new File(DOWNLOAD_DIR, NONMEDIA_FILE_NAME);
        final File videoFile = new File(DCIM_DIR, VIDEO_FILE_NAME);
        try {
            assertThat(pdfFile.createNewFile()).isTrue();
            assertThat(videoFile.exists()).isFalse();
            // Moving pdfFile to DCIM directory is not allowed.
            assertCantRenameFile(pdfFile, new File(DCIM_DIR, NONMEDIA_FILE_NAME));
            // However, moving pdfFile to DCIM directory with changing the mime type to video is
            // allowed.
            assertCanRenameFile(pdfFile, videoFile);

            // On rename, MediaProvider database entry for pdfFile should be updated with new
            // videoFile path and mime type should be updated to video/mp4.
            assertThat(getFileMimeTypeFromDatabase(videoFile))
                    .isEqualTo("video/mp4");
        } finally {
            pdfFile.delete();
            videoFile.delete();
        }
    }

    /**
     * Test that renaming files overwrites files in newPath.
     */
    @Test
    public void testRenameAndReplaceFile() throws Exception {
        final File videoFile1 = new File(DCIM_DIR, VIDEO_FILE_NAME);
        final File videoFile2 = new File(MOVIES_DIR, VIDEO_FILE_NAME);
        try {
            assertThat(videoFile1.createNewFile()).isTrue();
            assertThat(videoFile2.createNewFile()).isTrue();
            final String[] projection = new String[] {MediaColumns._ID};
            // Get id of video file in movies which will be deleted on rename.
            final int id = getFileRowIdFromDatabase(videoFile2);

            // Renaming a file which replaces file in newPath videoFile2 is allowed.
            assertCanRenameFile(videoFile1, videoFile2);

            // MediaProvider database entry for videoFile2 should be deleted on rename.
            assertThat(getFileRowIdFromDatabase(videoFile2)).isNotEqualTo(id);
        } finally {
            videoFile1.delete();
            videoFile2.delete();
        }
    }

    /**
     * Test that app without write permission for file can't update the file.
     */
    @Test
    public void testRenameFileNotOwned() throws Exception {
        final File videoFile1 = new File(DCIM_DIR, VIDEO_FILE_NAME);
        final File videoFile2 = new File(MOVIES_DIR, VIDEO_FILE_NAME);
        try {
            installApp(TEST_APP_A, false);
            assertThat(createFileAs(TEST_APP_A, videoFile1.getAbsolutePath())).isTrue();
            // App can't rename a file owned by TEST_APP_A.
            assertCantRenameFile(videoFile1, videoFile2);

            assertThat(videoFile2.createNewFile()).isTrue();
            // App can't rename a file to videoFile1 which is owned by TEST_APP_A
            assertCantRenameFile(videoFile2, videoFile1);
            // TODO(b/146346138): Test that app with right URI permission should be able to rename
            // the corresponding file
        } finally {
            if(videoFile1.exists()) {
                deleteFileAsNoThrow(TEST_APP_A, videoFile1.getAbsolutePath());
            }
            videoFile2.delete();
            uninstallAppNoThrow(TEST_APP_A);
        }
    }

    /**
     * Test that renaming directories is allowed and aligns to default directory restrictions.
     */
    @Test
    public void testRenameDirectory() throws Exception {
        final String nonMediaDirectoryName = TEST_DIRECTORY_NAME + "NonMedia";
        final File nonMediaDirectory = new File(DOWNLOAD_DIR, nonMediaDirectoryName);
        final File pdfFile = new File(nonMediaDirectory, NONMEDIA_FILE_NAME);

        final String mediaDirectoryName = TEST_DIRECTORY_NAME + "Media";
        final File mediaDirectory1 = new File(DCIM_DIR, mediaDirectoryName);
        final File videoFile1 = new File(mediaDirectory1, VIDEO_FILE_NAME);
        final File mediaDirectory2 =  new File(DOWNLOAD_DIR, mediaDirectoryName);
        final File videoFile2 = new File(mediaDirectory2, VIDEO_FILE_NAME);
        final File mediaDirectory3 =  new File(MOVIES_DIR, TEST_DIRECTORY_NAME);
        final File videoFile3 = new File(mediaDirectory3, VIDEO_FILE_NAME);
        final File mediaDirectory4 =  new File(mediaDirectory3, mediaDirectoryName) ;

        try {
            if (!nonMediaDirectory.exists()) {
                assertThat(nonMediaDirectory.mkdirs()).isTrue();
            }
            assertThat(pdfFile.createNewFile()).isTrue();
            // Move directory with pdf file to DCIM directory is not allowed.
            assertThat(nonMediaDirectory.renameTo(new File(DCIM_DIR, nonMediaDirectoryName)))
                    .isFalse();

            if (!mediaDirectory1.exists()) {
                assertThat(mediaDirectory1.mkdirs()).isTrue();
            }
            assertThat(videoFile1.createNewFile()).isTrue();
            // Renaming to and from default directories is not allowed.
            assertThat(mediaDirectory1.renameTo(DCIM_DIR)).isFalse();
            // Moving top level default directories is not allowed.
            assertCantRenameDirectory(DOWNLOAD_DIR, new File(DCIM_DIR, TEST_DIRECTORY_NAME), null);

            // Moving media directory to Download directory is allowed.
            assertCanRenameDirectory(mediaDirectory1, mediaDirectory2, new File[] {videoFile1},
                    new File[] {videoFile2});

            // Moving media directory to Movies directory and renaming directory in new path is
            // allowed.
            assertCanRenameDirectory(mediaDirectory2, mediaDirectory3,  new File[] {videoFile2},
                    new File[] {videoFile3});

            // Can't rename a mediaDirectory to non empty non Media directory.
            assertCantRenameDirectory(mediaDirectory3, nonMediaDirectory, new File[] {videoFile3});
            // Can't rename a file to a directory.
            assertCantRenameFile(videoFile3, mediaDirectory3);
            // Can't rename a directory to file.
            assertCantRenameDirectory(mediaDirectory3, pdfFile, null);
            if (!mediaDirectory4.exists()) {
                assertThat(mediaDirectory4.mkdir()).isTrue();
            }
            // Can't rename a directory to subdirectory of itself.
            assertCantRenameDirectory(mediaDirectory3, mediaDirectory4, new File[] {videoFile3});

        } finally {
            pdfFile.delete();
            nonMediaDirectory.delete();

            videoFile1.delete();
            videoFile2.delete();
            videoFile3.delete();
            mediaDirectory1.delete();
            mediaDirectory2.delete();
            mediaDirectory3.delete();
            mediaDirectory4.delete();
        }
    }

    /**
     * Test that renaming directory checks file ownership permissions.
     */
    @Test
    public void testRenameDirectoryNotOwned() throws Exception {
        final String mediaDirectoryName = TEST_DIRECTORY_NAME + "Media";
        File mediaDirectory1 = new File(DCIM_DIR, mediaDirectoryName);
        File mediaDirectory2 = new File(MOVIES_DIR, mediaDirectoryName);
        File videoFile = new File(mediaDirectory1, VIDEO_FILE_NAME);

        try {
            installApp(TEST_APP_A, false);

            if (!mediaDirectory1.exists()) {
                assertThat(mediaDirectory1.mkdirs()).isTrue();
            }
            assertThat(createFileAs(TEST_APP_A, videoFile.getAbsolutePath())).isTrue();
            // App doesn't have access to videoFile1, can't rename mediaDirectory1.
            assertThat(mediaDirectory1.renameTo(mediaDirectory2)).isFalse();
            assertThat(videoFile.exists()).isTrue();
            // Test app can delete the file since the file is not moved to new directory.
            assertThat(deleteFileAs(TEST_APP_A, videoFile.getAbsolutePath())).isTrue();
        } finally {
            deleteFileAsNoThrow(TEST_APP_A, videoFile.getAbsolutePath());
            uninstallAppNoThrow(TEST_APP_A);
            mediaDirectory1.delete();
        }
    }

    /**
     * Test renaming empty directory is allowed
     */
    @Test
    public void testRenameEmptyDirectory() throws Exception {
        final String emptyDirectoryName = TEST_DIRECTORY_NAME + "Media";
        File emptyDirectoryOldPath = new File(DCIM_DIR, emptyDirectoryName);
        File emptyDirectoryNewPath = new File(MOVIES_DIR, TEST_DIRECTORY_NAME);
        try {
            if (!emptyDirectoryOldPath.exists()) {
                assertThat(emptyDirectoryOldPath.mkdirs()).isTrue();
                assertCanRenameDirectory(emptyDirectoryOldPath, emptyDirectoryNewPath, null, null);
            }
        } finally {
            emptyDirectoryOldPath.delete();
            emptyDirectoryNewPath.delete();
        }
    }

    @Test
    public void testManageExternalStorageCanCreateFilesAnywhere() throws Exception {
        final File topLevelPdf = new File(EXTERNAL_STORAGE_DIR, NONMEDIA_FILE_NAME);
        final File musicFileInMovies = new File(MOVIES_DIR, MUSIC_FILE_NAME);
        final File imageFileInDcim = new File(DCIM_DIR, IMAGE_FILE_NAME);
        try {
            adoptShellPermissionIdentity(Manifest.permission.MANAGE_EXTERNAL_STORAGE);
            // Nothing special about this, anyone can create an image file in DCIM
            assertCanCreateFile(imageFileInDcim);
            // This is where we see the special powers of MANAGE_EXTERNAL_STORAGE, because it can
            // create a top level file
            assertCanCreateFile(topLevelPdf);
            // It can even create a music file in Pictures
            assertCanCreateFile(musicFileInMovies);
        } finally {
            dropShellPermissionIdentity();
        }
    }

    /**
     * Test that apps can create hidden file
     */
    @Test
    public void testCanCreateHiddenFile() throws Exception {
        final File hiddenFile = new File(DOWNLOAD_DIR, ".hiddenFile");
        try {
            assertThat(hiddenFile.createNewFile()).isTrue();
            // Write to hidden file is allowed.
            try (final FileOutputStream fos = new FileOutputStream(hiddenFile)) {
                fos.write(BYTES_DATA1);
            }
            assertFileContent(hiddenFile, BYTES_DATA1);
            // We can delete hidden file
            assertThat(hiddenFile.delete()).isTrue();
            assertThat(hiddenFile.exists()).isFalse();
        } finally {
            hiddenFile.delete();
        }
    }

    @Test
    public void testManageExternalStorageCanDeleteOtherAppsContents() throws Exception {
        final File otherAppPdf = new File(DOWNLOAD_DIR, "other" + NONMEDIA_FILE_NAME);
        final File otherAppImage = new File(DCIM_DIR, "other" + IMAGE_FILE_NAME);
        final File otherAppMusic = new File(MUSIC_DIR, "other" + MUSIC_FILE_NAME);
        try {
            installApp(TEST_APP_A, false);

            // Create all of the files as another app
            assertThat(createFileAs(TEST_APP_A, otherAppPdf.getPath())).isTrue();
            assertThat(createFileAs(TEST_APP_A, otherAppImage.getPath())).isTrue();
            assertThat(createFileAs(TEST_APP_A, otherAppMusic.getPath())).isTrue();

            // Now we get the permission, since some earlier method calls drop shell permission
            // identity.
            adoptShellPermissionIdentity(Manifest.permission.MANAGE_EXTERNAL_STORAGE);

            assertThat(otherAppPdf.delete()).isTrue();
            assertThat(otherAppPdf.exists()).isFalse();

            assertThat(otherAppImage.delete()).isTrue();
            assertThat(otherAppImage.exists()).isFalse();

            assertThat(otherAppMusic.delete()).isTrue();
            assertThat(otherAppMusic.exists()).isFalse();

            // Create the files again to allow the helper app to clean them up
            assertThat(otherAppPdf.createNewFile()).isTrue();
            assertThat(otherAppImage.createNewFile()).isTrue();
            assertThat(otherAppMusic.createNewFile()).isTrue();
        } finally {
            otherAppPdf.delete();
            otherAppImage.delete();
            otherAppMusic.delete();
            dropShellPermissionIdentity();
            uninstallApp(TEST_APP_A);
        }
    }

    @Test
    public void testManageExternalStorageCanRenameOtherAppsContents() throws Exception {
        final File otherAppPdf = new File(DOWNLOAD_DIR, "other" + NONMEDIA_FILE_NAME);
        final File pdf = new File(DOWNLOAD_DIR, NONMEDIA_FILE_NAME);
        final File pdfInObviouslyWrongPlace = new File(PICTURES_DIR, NONMEDIA_FILE_NAME);
        final File topLevelPdf = new File(EXTERNAL_STORAGE_DIR, NONMEDIA_FILE_NAME);
        final File musicFile = new File(MUSIC_DIR, MUSIC_FILE_NAME);
        try {
            installApp(TEST_APP_A, false);

            // Have another app create a PDF
            assertThat(createFileAs(TEST_APP_A, otherAppPdf.getPath())).isTrue();
            assertThat(otherAppPdf.exists()).isTrue();

            // Now we get the permission, since some earlier method calls drop shell permission
            // identity.
            adoptShellPermissionIdentity(Manifest.permission.MANAGE_EXTERNAL_STORAGE);

            // Write some data to the file
            try (final FileOutputStream fos = new FileOutputStream(otherAppPdf)) {
                fos.write(BYTES_DATA1);
            }
            assertFileContent(otherAppPdf, BYTES_DATA1);

            // Assert we can rename the file and ensure the file has the same content
            assertThat(otherAppPdf.renameTo(pdf)).isTrue();
            assertThat(otherAppPdf.exists()).isFalse();
            assertFileContent(pdf, BYTES_DATA1);
            // We can even move it to the top level directory
            assertThat(pdf.renameTo(topLevelPdf)).isTrue();
            assertThat(pdf.exists()).isFalse();
            assertFileContent(topLevelPdf, BYTES_DATA1);
            // And even rename to a place where PDFs don't belong, because we're an omnipotent
            // external storage manager
            assertThat(topLevelPdf.renameTo(pdfInObviouslyWrongPlace)).isTrue();
            assertThat(topLevelPdf.exists()).isFalse();
            assertFileContent(pdfInObviouslyWrongPlace, BYTES_DATA1);

            // And we can even convert it into a music file, because why not?
            assertThat(pdfInObviouslyWrongPlace.renameTo(musicFile)).isTrue();
            assertThat(pdfInObviouslyWrongPlace.exists()).isFalse();
            assertFileContent(musicFile, BYTES_DATA1);

            // Rename file back to it's original name so that the test app can clean it up
            assertThat(musicFile.renameTo(otherAppPdf)).isTrue();
            assertThat(deleteFileAs(TEST_APP_A, otherAppPdf.getPath())).isTrue();
        } finally {
            pdf.delete();
            pdfInObviouslyWrongPlace.delete();
            topLevelPdf.delete();
            musicFile.delete();
            dropShellPermissionIdentity();
            otherAppPdf.delete();
            uninstallApp(TEST_APP_A);
        }
    }

    @Test
    public void testManageExternalStorageQueryOtherAppsFile() throws Exception {
        final File otherAppPdf = new File(DOWNLOAD_DIR, "other" + NONMEDIA_FILE_NAME);
        final File otherAppImg = new File(DCIM_DIR, "other" + IMAGE_FILE_NAME);
        final File otherAppMusic = new File(MUSIC_DIR, "other" + MUSIC_FILE_NAME);
        try {
            installApp(TEST_APP_A, false);
            assertCreateFilesAs(TEST_APP_A, otherAppImg, otherAppMusic, otherAppPdf);

            // Once we have permission to manage external storage, we can query for other apps'
            // files and open them for read and write
            adoptShellPermissionIdentity(Manifest.permission.MANAGE_EXTERNAL_STORAGE);

            assertCanQueryAndOpenFile(otherAppPdf, "rw");
            assertCanQueryAndOpenFile(otherAppImg, "rw");
            assertCanQueryAndOpenFile(otherAppMusic, "rw");
        } finally {
            dropShellPermissionIdentity();
            deleteFilesAs(TEST_APP_A, otherAppImg, otherAppMusic, otherAppPdf);
            uninstallApp(TEST_APP_A);
        }
    }

    private static void assertCreateFilesAs(TestApp testApp, File... files) throws Exception {
        for (File file : files) {
            assertThat(createFileAs(testApp, file.getPath())).isTrue();
        }
    }

    private static void deleteFilesAs(TestApp testApp, File... files) throws Exception {
        for (File file : files) {
            deleteFileAs(testApp, file.getPath());
        }
    }

    private static void assertCanQueryAndOpenFile(File file, String mode) throws IOException {
        // This call performs the query
        final Uri fileUri = getFileUri(file);
        // The query succeeds iff it didn't return null
        assertThat(fileUri).isNotNull();
        // Now we assert that we can open the file through ContentResolver
        try (final ParcelFileDescriptor pfd =
                     getContentResolver().openFileDescriptor(fileUri, mode)) {
            assertThat(pfd).isNotNull();
        }
    }

    /**
     * Assert that the last read in: read - write - read using {@code readFd} and {@code writeFd}
     * see the last write. {@code readFd} and {@code writeFd} are fds pointing to the same
     * underlying file on disk but may be derived from different mount points and in that case
     * have separate VFS caches.
     */
    private void assertRWR(FileDescriptor readFd, FileDescriptor writeFd) throws Exception {
        byte[] readBuffer = new byte[10];
        byte[] writeBuffer = new byte[10];
        Arrays.fill(writeBuffer, (byte) 1);

        // Write so readFd has content to read from next
        Os.pwrite(readFd, readBuffer, 0, 10, 0);
        // Read so readBuffer is in readFd's mount VFS cache
        Os.pread(readFd, readBuffer, 0, 10, 0);

        // Assert that readBuffer is zeroes
        assertThat(readBuffer).isEqualTo(new byte[10]);

        // Write so writeFd and readFd should now see writeBuffer
        Os.pwrite(writeFd, writeBuffer, 0, 10, 0);

        // Read so the last write can be verified on readFd
        Os.pread(readFd, readBuffer, 0, 10, 0);

        // Assert that the last write is indeed visible via readFd
        assertThat(readBuffer).isEqualTo(writeBuffer);
    }

    private void assertLowerFsFd(ParcelFileDescriptor pfd) throws Exception {
        assertThat(Os.readlink("/proc/self/fd/" + pfd.getFd()).startsWith("/storage")).isTrue();
    }

    private void assertUpperFsFd(ParcelFileDescriptor pfd) throws Exception {
        assertThat(Os.readlink("/proc/self/fd/" + pfd.getFd()).startsWith("/mnt/user")).isTrue();
    }

    private static void assertCanCreateFile(File file) throws IOException {
        // If the file somehow managed to survive a previous run, then the test app was uninstalled
        // and MediaProvider will remove our its ownership of the file, so it's not guaranteed that
        // we can create nor delete it.
        if (!file.exists()) {
            assertThat(file.createNewFile()).isTrue();
            assertThat(file.delete()).isTrue();
        } else {
            Log.w(TAG, "Couldn't assertCanCreateFile(" + file + ") because file existed prior to "
                    + "running the test!");
        }
    }

    private static void assertCanRenameFile(File oldFile, File newFile) {
        assertThat(oldFile.renameTo(newFile)).isTrue();
        assertThat(oldFile.exists()).isFalse();
        assertThat(newFile.exists()).isTrue();
        assertThat(getFileRowIdFromDatabase(oldFile)).isEqualTo(-1);
        assertThat(getFileRowIdFromDatabase(newFile)).isNotEqualTo(-1);
    }

    private static void assertCantRenameFile(File oldFile, File newFile) {
        final int rowId = getFileRowIdFromDatabase(oldFile);
        assertThat(oldFile.renameTo(newFile)).isFalse();
        assertThat(oldFile.exists()).isTrue();
        assertThat(getFileRowIdFromDatabase(oldFile)).isEqualTo(rowId);
    }

    private static void assertCanRenameDirectory(File oldDirectory, File newDirectory,
            @Nullable File[] oldFilesList, @Nullable File[] newFilesList) {
        assertThat(oldDirectory.renameTo(newDirectory)).isTrue();
        assertThat(oldDirectory.exists()).isFalse();
        assertThat(newDirectory.exists()).isTrue();
        for (File file  : oldFilesList != null ? oldFilesList : new File[0]) {
            assertThat(file.exists()).isFalse();
            assertThat(getFileRowIdFromDatabase(file)).isEqualTo(-1);
        }
        for (File file : newFilesList != null ? newFilesList : new File[0]) {
            assertThat(file.exists()).isTrue();
            assertThat(getFileRowIdFromDatabase(file)).isNotEqualTo(-1);
        };
    }

    private static void assertCantRenameDirectory(File oldDirectory, File newDirectory,
            @Nullable File[] oldFilesList) {
        assertThat(oldDirectory.renameTo(newDirectory)).isFalse();
        assertThat(oldDirectory.exists()).isTrue();
        for (File file  : oldFilesList != null ? oldFilesList : new File[0]) {
            assertThat(file.exists()).isTrue();
            assertThat(getFileRowIdFromDatabase(file)).isNotEqualTo(-1);
        }
    }

    /**
     * Asserts the entire content of the file equals exactly {@code expectedContent}.
     */
    private static void assertFileContent(File file, byte[] expectedContent) throws IOException {
        try (final FileInputStream fis = new FileInputStream(file)) {
            assertInputStreamContent(fis, expectedContent);
        }
    }

    /**
     * Asserts the entire content of the file equals exactly {@code expectedContent}.
     * <p>Sets {@code fd} to beginning of file first.
     */
    private static void assertFileContent(FileDescriptor fd, byte[] expectedContent)
            throws IOException, ErrnoException {
        Os.lseek(fd, 0, OsConstants.SEEK_SET);
        try (final FileInputStream fis = new FileInputStream(fd)) {
            assertInputStreamContent(fis, expectedContent);
        }
    }

    private static void assertInputStreamContent(InputStream in, byte[] expectedContent)
            throws IOException {
        assertThat(ByteStreams.toByteArray(in)).isEqualTo(expectedContent);
    }
}
