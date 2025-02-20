package net.sourceforge.jnlp.cache;

import net.adoptopenjdk.icedteaweb.IcedTeaWebConstants;
import net.adoptopenjdk.icedteaweb.logging.Logger;
import net.adoptopenjdk.icedteaweb.logging.LoggerFactory;
import net.adoptopenjdk.icedteaweb.io.FileUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import static net.adoptopenjdk.icedteaweb.JvmPropertyConstants.JAVA_IO_TMPDIR;

/**
 * Handles loading and access of native code loading through a JNLP application or applet.
 * Stores native code in a temporary folder.
 * Be sure to call cleanupTemporaryFolder when finished with the object.
 */
public class NativeLibraryStorage {

    private final static Logger LOG = LoggerFactory.getLogger(NativeLibraryStorage.class);

    private final ResourceTracker tracker;
    private final List<File> nativeSearchDirectories = new ArrayList<>();

    /* Temporary directory to store native jar entries, added to our search path */
    private File jarEntryDirectory = null;

    public NativeLibraryStorage(ResourceTracker tracker) {
        this.tracker = tracker;
    }

    /**
     * Clean up our temporary folder if we created one.
     */
    public void cleanupTemporaryFolder() {
        if (jarEntryDirectory != null) {
            LOG.info("Cleaning up native directory {}", jarEntryDirectory.getAbsolutePath());
            try {
                FileUtils.recursiveDelete(jarEntryDirectory,
                        new File(System.getProperty(JAVA_IO_TMPDIR)));
                jarEntryDirectory = null;
            } catch (IOException e) {
                /*
                 * failed to delete a file in tmpdir, no big deal (as well the VM 
                 * might be shutting down at this point so no much we can do)
                 */
            }
        }
    }

    /**
     * Adds the {@link File} to the search path of this {@link NativeLibraryStorage}
     * when trying to find a native library
     * @param directory directory to be added
     */
    public void addSearchDirectory(File directory) {
        nativeSearchDirectories.add(directory);
    }

    public List<File> getSearchDirectories() {
        return nativeSearchDirectories;
    }

    /**
     * Looks in the search directories for 'fileName',
     * returning a path to the found file if it exists.
     * @param fileName name of library to be found
     * @return path to library if found, null otherwise.
     */
    public File findLibrary(String fileName) {
        for (File dir : getSearchDirectories()) {
            File target = new File(dir, fileName);
            if (target.exists())
                return target;
        }
        return null;
    }

    public static final String[] NATIVE_LIBRARY_EXTENSIONS = { ".so", ".dylib", ".jnilib", ".framework", ".dll" };

    /**
     * Search for and enable any native code contained in a JAR by copying the
     * native files into the filesystem. Called in the security context of the
     * classloader.
     * @param jarLocation location of jar to be searched
     */
    public void addSearchJar(URL jarLocation) {
        LOG.info("Activate native: {}", jarLocation);
        File localFile = tracker.getCacheFile(jarLocation);
        if (localFile == null)
            return;

        try {
            try (JarFile jarFile = new JarFile(localFile, false)) {
                Enumeration<JarEntry> entries = jarFile.entries();
                
                while (entries.hasMoreElements()) {
                    JarEntry e = entries.nextElement();
                    
                    if (e.isDirectory()) {
                        continue;
                    }
                    
                    String name = new File(e.getName()).getName();
                    boolean isLibrary = false;
                    
                    for (String suffix : NATIVE_LIBRARY_EXTENSIONS) {
                        if (name.endsWith(suffix)) {
                            isLibrary = true;
                            break;
                        }
                    }
                    if (!isLibrary) {
                        continue;
                    }
                    
                    ensureNativeStoreDirectory();
                    
                    File outFile = new File(jarEntryDirectory, name);
                    if (!outFile.isFile()) {
                        FileUtils.createRestrictedFile(outFile, true);
                    }
                    CacheUtil.streamCopy(jarFile.getInputStream(e),
                            new FileOutputStream(outFile));
                }
            }
        } catch (IOException ex) {
            LOG.error(IcedTeaWebConstants.DEFAULT_ERROR_MESSAGE, ex);
        }
    }

    void ensureNativeStoreDirectory() {
        if (jarEntryDirectory == null) {
            jarEntryDirectory = createNativeStoreDirectory();
            addSearchDirectory(jarEntryDirectory);
        }
    }

    /**
     * Create a random base directory to store native code files in.
     */
    private static File createNativeStoreDirectory() {
        final int rand = (int)((Math.random()*2 - 1) * Integer.MAX_VALUE);
        File nativeDir = new File(System.getProperty(JAVA_IO_TMPDIR)
                             + File.separator + "netx-native-"
                             + (rand & 0xFFFF));
        File parent = nativeDir.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) {
            return null;
        }

        try {
            FileUtils.createRestrictedDirectory(nativeDir);
            return nativeDir;
        } catch (IOException e) {
            return null;
        }
    }
}
