/*
 * Copyright 2004-2021 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.tools;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;
import org.h2.engine.Constants;
import org.h2.message.DbException;
import org.h2.mvstore.MVStore;
import org.h2.store.FileLister;
import org.h2.store.fs.FilePath;
import org.h2.store.fs.FileUtils;
import org.h2.store.fs.encrypt.FileEncrypt;
import org.h2.store.fs.encrypt.FilePathEncrypt;
import org.h2.util.Tool;

/**
 * Allows changing the database file encryption password or algorithm.
 *
 * This tool can not be used to change a password of a user.
 * The database must be closed before using this tool.
 */
public class ChangeFileEncryption extends Tool {

    private String directory;
    private String cipherType;
    private byte[] decryptKey;
    private byte[] encryptKey;

    /**
     * Options are case sensitive.
     * <table>
     * <caption>Supported options</caption>
     * <tr><td>[-help] or [-?]</td>
     * <td>Print the list of options</td></tr>
     * <tr><td>[-cipher type]</td>
     * <td>The encryption type (AES)</td></tr>
     * <tr><td>[-dir &lt;dir&gt;]</td>
     * <td>The database directory (default: .)</td></tr>
     * <tr><td>[-db &lt;database&gt;]</td>
     * <td>Database name (all databases if not set)</td></tr>
     * <tr><td>[-decrypt &lt;pwd&gt;]</td>
     * <td>The decryption password (if not set: not yet encrypted)</td></tr>
     * <tr><td>[-encrypt &lt;pwd&gt;]</td>
     * <td>The encryption password (if not set: do not encrypt)</td></tr>
     * <tr><td>[-quiet]</td>
     * <td>Do not print progress information</td></tr>
     * </table>
     *
     * @param args the command line arguments
     */
    public static void main(String... args) {
        try {
            new ChangeFileEncryption().runTool(args);
        } catch (SQLException ex) {
            ex.printStackTrace(System.err);
            System.exit(1);
        }
    }

    @Override
    public void runTool(String... args) throws SQLException {
        String dir = ".";
        String cipher = null;
        char[] decryptPassword = null;
        char[] encryptPassword = null;
        String db = null;
        boolean quiet = false;
        for (int i = 0; args != null && i < args.length; i++) {
            String arg = args[i];
            if (arg.equals("-dir")) {
                dir = args[++i];
            } else if (arg.equals("-cipher")) {
                cipher = args[++i];
            } else if (arg.equals("-db")) {
                db = args[++i];
            } else if (arg.equals("-decrypt")) {
                decryptPassword = args[++i].toCharArray();
            } else if (arg.equals("-encrypt")) {
                encryptPassword = args[++i].toCharArray();
            } else if (arg.equals("-quiet")) {
                quiet = true;
            } else if (arg.equals("-help") || arg.equals("-?")) {
                showUsage();
                return;
            } else {
                showUsageAndThrowUnsupportedOption(arg);
            }
        }
        if ((encryptPassword == null && decryptPassword == null) || cipher == null) {
            showUsage();
            throw new SQLException(
                    "Encryption or decryption password not set, or cipher not set");
        }
        try {
            process(dir, db, cipher, decryptPassword, encryptPassword, quiet);
        } catch (Exception e) {
            throw DbException.toSQLException(e);
        }
    }

    /**
     * Changes the password for a database. The passwords must be supplied as
     * char arrays and are cleaned in this method. The database must be closed
     * before calling this method.
     *
     * @param dir the directory (. for the current directory)
     * @param db the database name (null for all databases)
     * @param cipher the cipher (AES)
     * @param decryptPassword the decryption password as a char array
     * @param encryptPassword the encryption password as a char array
     * @param quiet don't print progress information
     * @throws SQLException on failure
     */
    public static void execute(String dir, String db, String cipher,
            char[] decryptPassword, char[] encryptPassword, boolean quiet)
            throws SQLException {
        try {
            new ChangeFileEncryption().process(dir, db, cipher,
                    decryptPassword, encryptPassword, quiet);
        } catch (Exception e) {
            throw DbException.toSQLException(e);
        }
    }

    private void process(String dir, String db, String cipher,
            char[] decryptPassword, char[] encryptPassword, boolean quiet)
            throws SQLException {
        dir = FileLister.getDir(dir);
        ChangeFileEncryption change = new ChangeFileEncryption();
        if (encryptPassword != null) {
            for (char c : encryptPassword) {
                if (c == ' ') {
                    throw new SQLException("The file password may not contain spaces");
                }
            }
            change.encryptKey = FilePathEncrypt.getPasswordBytes(encryptPassword);
        }
        if (decryptPassword != null) {
            change.decryptKey = FilePathEncrypt.getPasswordBytes(decryptPassword);
        }
        change.out = out;
        change.directory = dir;
        change.cipherType = cipher;

        ArrayList<String> files = FileLister.getDatabaseFiles(dir, db, true);
        FileLister.tryUnlockDatabase(files, "encryption");
        files = FileLister.getDatabaseFiles(dir, db, false);
        if (files.isEmpty() && !quiet) {
            printNoDatabaseFilesFound(dir, db);
        }
        // first, test only if the file can be renamed
        // (to find errors with locked files early)
        for (String fileName : files) {
            String temp = dir + "/temp.db";
            FileUtils.delete(temp);
            FileUtils.move(fileName, temp);
            FileUtils.move(temp, fileName);
        }
        // if this worked, the operation will (hopefully) be successful
        // TODO changeFileEncryption: this is a workaround!
        // make the operation atomic (all files or none)
        for (String fileName : files) {
            // don't process a lob directory, just the files in the directory.
            if (!FileUtils.isDirectory(fileName)) {
                change.process(fileName, quiet, decryptPassword);
            }
        }
    }

    private void process(String fileName, boolean quiet, char[] decryptPassword) throws SQLException {
        if (fileName.endsWith(Constants.SUFFIX_MV_FILE)) {
            try {
                copyMvStore(fileName, quiet, decryptPassword);
            } catch (IOException e) {
                throw DbException.convertIOException(e,
                        "Error encrypting / decrypting file " + fileName);
            }
            return;
        }
    }

    private void copyMvStore(String fileName, boolean quiet, char[] decryptPassword) throws IOException, SQLException {
        if (FileUtils.isDirectory(fileName)) {
            return;
        }
        // check that we have the right encryption key
        try {
            final MVStore source = new MVStore.Builder().
                    fileName(fileName).
                    readOnly().
                    encryptionKey(decryptPassword).
                    open();
            source.close();
        } catch (IllegalStateException ex) {
            throw new SQLException("error decrypting file " + fileName, ex);
        }

        String temp = directory + "/temp.db";
        try (FileChannel fileIn = getFileChannel(fileName, "r", decryptKey)){
            try (InputStream inStream = Channels.newInputStream(fileIn)) {
                FileUtils.delete(temp);
                try (OutputStream outStream = Channels.newOutputStream(getFileChannel(temp, "rw", encryptKey))) {
                    final byte[] buffer = new byte[4 * 1024];
                    long remaining = fileIn.size();
                    long total = remaining;
                    long time = System.nanoTime();
                    while (remaining > 0) {
                        if (!quiet && System.nanoTime() - time > TimeUnit.SECONDS.toNanos(1)) {
                            out.println(fileName + ": " + (100 - 100 * remaining / total) + "%");
                            time = System.nanoTime();
                        }
                        int len = (int) Math.min(buffer.length, remaining);
                        len = inStream.read(buffer, 0, len);
                        outStream.write(buffer, 0, len);
                        remaining -= len;
                    }
                }
            }
        }
        FileUtils.delete(fileName);
        FileUtils.move(temp, fileName);
    }

    private static FileChannel getFileChannel(String fileName, String r,
            byte[] decryptKey) throws IOException {
        FileChannel fileIn = FilePath.get(fileName).open(r);
        if (decryptKey != null) {
            fileIn = new FileEncrypt(fileName, decryptKey,
                    fileIn);
        }
        return fileIn;
    }

}
