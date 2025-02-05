/*
This file is part of jpcsp.

Jpcsp is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

Jpcsp is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with Jpcsp.  If not, see <http://www.gnu.org/licenses/>.
 */
package jpcsp.HLE.VFS.local;

import static jpcsp.HLE.kernel.types.SceKernelErrors.ERROR_MEMSTICK_DEVCTL_BAD_PARAMS;
import static jpcsp.HLE.modules.IoFileMgrForUser.PSP_O_CREAT;
import static jpcsp.HLE.modules.IoFileMgrForUser.PSP_O_EXCL;
import static jpcsp.HLE.modules.IoFileMgrForUser.PSP_O_RDWR;
import static jpcsp.HLE.modules.IoFileMgrForUser.PSP_O_TRUNC;
import static jpcsp.HLE.modules.IoFileMgrForUser.PSP_O_WRONLY;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.Map;

import jpcsp.Memory;
import jpcsp.HLE.Modules;
import jpcsp.HLE.SceKernelErrorException;
import jpcsp.HLE.TPointer;
import jpcsp.HLE.VFS.AbstractVirtualFileSystem;
import jpcsp.HLE.VFS.IVirtualFile;
import jpcsp.HLE.kernel.types.SceIoDirent;
import jpcsp.HLE.kernel.types.SceIoStat;
import jpcsp.HLE.kernel.types.SceKernelErrors;
import jpcsp.HLE.kernel.types.SceKernelThreadInfo;
import jpcsp.HLE.kernel.types.ScePspDateTime;
import jpcsp.HLE.modules.IoFileMgrForUser;
import jpcsp.HLE.modules.ThreadManForUser;
import jpcsp.HLE.modules.IoFileMgrForUser.IoOperation;
import jpcsp.HLE.modules.IoFileMgrForUser.IoOperationTiming;
import jpcsp.filesystems.SeekableRandomFile;
import jpcsp.hardware.MemoryStick;
import jpcsp.util.Utilities;

public class LocalVirtualFileSystem extends AbstractVirtualFileSystem {
	protected final String localPath;
	private final boolean useDirExtendedInfo;
    // modeStrings indexed by [0, PSP_O_RDONLY, PSP_O_WRONLY, PSP_O_RDWR]
    // SeekableRandomFile doesn't support write only: take "rw",
    private final static String[] modeStrings = {"r", "r", "rw", "rw"};
    private final static boolean fileNamesAreCaseSensitive = System.getProperty("os.name").equalsIgnoreCase("Linux");

    /**
     * Get the file name as returned from the memory stick.
     * In some cases, the name is uppercased.
     *
     * The following cases have been tested:
     * - "a"                => "A"
     * - "B"                => "B"
     * - "b.txt"            => "B.TXT"
     * - "cC"               => "cC"
     * - "LongFileName.txt" => "LongFileName.txt"
     * - "aaaaaaaa"         => "AAAAAAAA"
     * - "aaaaaaaa.aaa"     => "AAAAAAAA.AAA"
     * - "aaaaaaaaa"        => "aaaaaaaaa"
     * - "aaaaaaaa.aaaa"    => "aaaaaaaa.aaaa"
     *
     * It seems that file names in the format 8.3 only containing lowercase characters
     * are converted to uppercase characters.
     */
    public static String getMsFileName(String fileName) {
    	if (fileName == null) {
    		return fileName;
    	}
    	if (!fileNamesAreCaseSensitive && fileName.matches("[^A-Z]{1,8}(\\.[^A-Z]{1,3})?")) {
    		return fileName.toUpperCase();
    	}
    	return fileName;
    }

    public static String[] fixMsDirectoryFiles(String[] files, String dirName) {
    	if (files == null) {
    		return files;
    	}

    	if (dirName != null && !dirName.isEmpty()) {
			files = Utilities.add(new String[] { "..", "." }, files);
    	}

    	for (int i = 0; i < files.length; i++) {
    		files[i] = getMsFileName(files[i]);
    	}

    	return files;
    }

	public LocalVirtualFileSystem(String localPath, boolean useDirExtendedInfo) {
		this.localPath = localPath;
		this.useDirExtendedInfo = useDirExtendedInfo;
	}

	private File fixLocalFileName(File localPath, String fileName) {
		if (fileName == null || fileName.isEmpty()) {
			return localPath;
		}

		File[] entries = localPath.listFiles();
		if (entries != null) {
			for (File entry : entries) {
				if (fileName.equalsIgnoreCase(entry.getName())) {
					fileName = entry.getName();
					break;
				}
			}
		}

		return new File(localPath, fileName);
	}

	private File getFile(File localPath, String fileName) {
		if (fileName == null || fileName.isEmpty()) {
			return localPath;
		}

		if (!fileNamesAreCaseSensitive) {
			return new File(localPath, fileName);
		}

		String[] fileNameParts = fileName.split("/");
		File localFile = localPath;
		for (int i = 0; i < fileNameParts.length; i++) {
			localFile = fixLocalFileName(localFile, fileNameParts[i]);
		}
		return localFile;
	}

	protected File getFile(String fileName) {
		return getFile(new File(localPath), fileName);
	}

	protected static String getMode(int mode) {
		return modeStrings[mode & PSP_O_RDWR];
	}

	@Override
	public IVirtualFile ioOpen(String fileName, int flags, int mode) {
		File file = getFile(fileName);
        if (file.exists() && hasFlag(flags, PSP_O_CREAT) && hasFlag(flags, PSP_O_EXCL)) {
            if (log.isDebugEnabled()) {
                log.debug("hleIoOpen - file already exists (PSP_O_CREAT + PSP_O_EXCL)");
            }
            throw new SceKernelErrorException(SceKernelErrors.ERROR_ERRNO_FILE_ALREADY_EXISTS);
        }

        // When PSP_O_CREAT is specified, create the parent directories
    	// if they do not yet exist.
        if (!file.exists() && hasFlag(flags, PSP_O_CREAT)) {
        	String parentDir = file.getParent();
        	new File(parentDir).mkdirs();
        }

        SeekableRandomFile raf;
		try {
			raf = new SeekableRandomFile(file, getMode(flags));
		} catch (FileNotFoundException e) {
			return null;
		}

		LocalVirtualFile localVirtualFile = new LocalVirtualFile(raf);

		if (hasFlag(flags, PSP_O_WRONLY) && hasFlag(flags, PSP_O_TRUNC)) {
            // When writing, PSP_O_TRUNC truncates the file at the position of the first write.
        	// E.g.:
        	//    open(PSP_O_TRUNC)
        	//    seek(0x1000)
        	//    write()  -> truncates the file at the position 0x1000 before writing
			localVirtualFile.setTruncateAtNextWrite(true);
        }

		return localVirtualFile;
	}

	@Override
	public int ioGetstat(String fileName, SceIoStat stat) {
        File file = getFile(fileName);
        if (!file.exists()) {
        	return SceKernelErrors.ERROR_ERRNO_FILE_NOT_FOUND;
        }

        long size = file.length();

        // Set attr (dir/file) and copy into mode
        int attr = 0;
        if (file.isDirectory()) {
            attr |= 0x10;
            // Directories have size 0
            size = 0L;
        }
        if (file.isFile()) {
            attr |= 0x20;
        }

        int mode = (file.canRead() ? 4 : 0) + (file.canWrite() ? 2 : 0) + (file.canExecute() ? 1 : 0);
        // Octal extend into user and group
        mode = mode + (mode << 3) + (mode << 6);
        mode |= attr << 8;

        // Java can't see file create/access time
        ScePspDateTime ctime = ScePspDateTime.fromUnixTime(file.lastModified());
        ScePspDateTime atime = ScePspDateTime.fromUnixTime(0);
        ScePspDateTime mtime = ScePspDateTime.fromUnixTime(file.lastModified());

        stat.init(mode, attr, size, ctime, atime, mtime);

        return 0;
	}

	@Override
	public int ioRemove(String name) {
		File file = getFile(name);

		if (!file.delete()) {
			return IO_ERROR;
		}

		return 0;
	}

	@Override
	public String[] ioDopen(String dirName) {
		File file = getFile(dirName);

		if (!file.isDirectory()) {
			if (file.exists()) {
				log.warn(String.format("ioDopen file '%s' is not a directory", dirName));
			} else {
				log.warn(String.format("ioDopen directory '%s' not found", dirName));
			}
			return null;
		}

    	return fixMsDirectoryFiles(file.list(), dirName);
	}

	@Override
	public int ioDread(String dirName, SceIoDirent dir) {
		if (dir != null) {
			// Use ExtendedInfo for the MemoryStick
			dir.setUseExtendedInfo(useDirExtendedInfo);
		}

		return super.ioDread(dirName, dir);
	}

	@Override
	public int ioMkdir(String name, int mode) {
		File file = getFile(name);

		if (file.exists()) {
			return SceKernelErrors.ERROR_ERRNO_FILE_ALREADY_EXISTS;
		}
		if (!file.mkdir()) {
			return IO_ERROR;
		}

		return 0;
	}

	@Override
	public int ioRmdir(String name) {
		File file = getFile(name);

		if (!file.exists()) {
			return SceKernelErrors.ERROR_ERRNO_FILE_NOT_FOUND;
		}
		if (!file.delete()) {
			return IO_ERROR;
		}

		return 0;
	}

	@Override
	public int ioChstat(String fileName, SceIoStat stat, int bits) {
        File file = getFile(fileName);

        int mode = stat.mode;
        boolean successful = true;

        if ((bits & 0x0001) != 0) {	// Others execute permission
            if (!file.isDirectory() && !file.setExecutable((mode & 0x0001) != 0)) {
                successful = false;
            }
        }
        if ((bits & 0x0002) != 0) {	// Others write permission
            if (!file.setWritable((mode & 0x0002) != 0)) {
                successful = false;
            }
        }
        if ((bits & 0x0004) != 0) {	// Others read permission
            if (!file.setReadable((mode & 0x0004) != 0)) {
                successful = false;
            }
        }

        if ((bits & 0x0040) != 0) {	// User execute permission
            if (!file.setExecutable((mode & 0x0040) != 0, true)) {
                successful = false;
            }
        }
        if ((bits & 0x0080) != 0) {	// User write permission
            if (!file.setWritable((mode & 0x0080) != 0, true)) {
                successful = false;
            }
        }
        if ((bits & 0x0100) != 0) {	// User read permission
            if (!file.setReadable((mode & 0x0100) != 0, true)) {
                successful = false;
            }
        }

        return successful ? 0 : IO_ERROR;
	}

	@Override
	public int ioRename(String oldFileName, String newFileName) {
		File oldFile = getFile(oldFileName);
		File newFile = getFile(newFileName);

		if (log.isDebugEnabled()) {
        	log.debug(String.format("ioRename: renaming file '%s' to '%s'", oldFileName, newFileName));
        }

		if (!oldFile.renameTo(newFile)) {
        	log.warn(String.format("ioRename failed: '%s' to '%s'", oldFileName, newFileName));
        	return IO_ERROR;
        }

        return 0;
	}

	@Override
	public int ioDevctl(String deviceName, int command, TPointer inputPointer, int inputLength, TPointer outputPointer, int outputLength) {
		int result;

		switch (command) {
	        // Register memorystick insert/eject callback (fatms0).
	        case 0x02415821: {
	            log.debug("sceIoDevctl register memorystick insert/eject callback (fatms0)");
	            ThreadManForUser threadMan = Modules.ThreadManForUserModule;
	            if (!deviceName.equals("fatms0:")) {
	            	result = ERROR_MEMSTICK_DEVCTL_BAD_PARAMS;
	            } else if (inputPointer.isAddressGood() && inputLength == 4) {
	                int cbid = inputPointer.getValue32();
	                final int callbackType = SceKernelThreadInfo.THREAD_CALLBACK_MEMORYSTICK_FAT;
	                if (threadMan.hleKernelRegisterCallback(callbackType, cbid)) {
	                    // Trigger the registered callback immediately.
	                	// Only trigger this one callback, not all the MS callbacks.
	                    threadMan.hleKernelNotifyCallback(callbackType, cbid, MemoryStick.getStateFatMs());
	                    result = 0;  // Success.
	                } else {
	                	result = SceKernelErrors.ERROR_ERRNO_INVALID_ARGUMENT;
	                }
	            } else {
	            	result = SceKernelErrors.ERROR_ERRNO_INVALID_ARGUMENT;
	            }
	            break;
	        }
	        // Unregister memorystick insert/eject callback (fatms0).
	        case 0x02415822: {
	            log.debug("sceIoDevctl unregister memorystick insert/eject callback (fatms0)");
	            ThreadManForUser threadMan = Modules.ThreadManForUserModule;
	            if (!deviceName.equals("fatms0:")) {
	            	result = ERROR_MEMSTICK_DEVCTL_BAD_PARAMS;
	            } else if (inputPointer.isAddressGood() && inputLength == 4) {
	                int cbid = inputPointer.getValue32();
	                threadMan.hleKernelUnRegisterCallback(SceKernelThreadInfo.THREAD_CALLBACK_MEMORYSTICK_FAT, cbid);
	                result = 0;  // Success.
	            } else {
	            	result = SceKernelErrors.ERROR_ERRNO_INVALID_ARGUMENT;
	            }
	            break;
	        }
	        // Set if the device is assigned/inserted or not (fatms0).
	        case 0x02415823: {
	            log.debug("sceIoDevctl set assigned device (fatms0)");
	            if (!deviceName.equals("fatms0:")) {
	            	result = ERROR_MEMSTICK_DEVCTL_BAD_PARAMS;
	            } else if (inputPointer.isAddressGood() && inputLength >= 4) {
	                // 0 - Device is not assigned (callback not registered).
	                // 1 - Device is assigned (callback registered).
	                MemoryStick.setStateFatMs(inputPointer.getValue32());
	                result = 0;
	            } else {
	            	result = IO_ERROR;
	            }
	            break;
	        }
	        // Check if the device is write protected (fatms0).
	        case 0x02425824: {
	            log.debug("sceIoDevctl check write protection (fatms0)");
	            if (!deviceName.equals("fatms0:") && !deviceName.equals("ms0:")) { // For this command the alias "ms0:" is also supported.
	            	result = ERROR_MEMSTICK_DEVCTL_BAD_PARAMS;
	            } else if (outputPointer.isAddressGood()) {
	                // 0 - Device is not protected.
	                // 1 - Device is protected.
	                outputPointer.setValue32(0);
	                result = 0;
	            } else {
	            	result = IO_ERROR;
	            }
	            break;
	        }
	        // Get MS capacity (fatms0).
	        case 0x02425818: {
	            log.debug("sceIoDevctl get MS capacity (fatms0)");
	            int sectorSize = 0x200;
	            int sectorCount = MemoryStick.getSectorSize() / sectorSize;
	            int maxClusters = (int) ((MemoryStick.getFreeSize() * 95L / 100) / (sectorSize * sectorCount));
	            int freeClusters = maxClusters;
	            int maxSectors = maxClusters;
	            if (inputPointer.isAddressGood() && inputLength >= 4) {
	                int addr = inputPointer.getValue32();
	                if (Memory.isAddressGood(addr)) {
	                    log.debug("sceIoDevctl refer ms free space");
	                    Memory mem = Memory.getInstance();
	                    mem.write32(addr, maxClusters);
	                    mem.write32(addr + 4, freeClusters);
	                    mem.write32(addr + 8, maxSectors);
	                    mem.write32(addr + 12, sectorSize);
	                    mem.write32(addr + 16, sectorCount);
	                    result = 0;
	                } else {
	                    log.warn("sceIoDevctl 0x02425818 bad save address " + String.format("0x%08X", addr));
	                    result = IO_ERROR;
	                }
	            } else {
	                log.warn("sceIoDevctl 0x02425818 bad param address " + String.format("0x%08X", inputPointer) + " or size " + inputLength);
	                result = IO_ERROR;
	            }
	            break;
	        }
	        // Check if the device is assigned/inserted (fatms0).
	        case 0x02425823: {
	            log.debug("sceIoDevctl check assigned device (fatms0)");
	            if (!deviceName.equals("fatms0:")) {
	            	result = ERROR_MEMSTICK_DEVCTL_BAD_PARAMS;
	            } else if (outputPointer.isAddressGood() && outputLength >= 4) {
	                // 0 - Device is not assigned (callback not registered).
	                // 1 - Device is assigned (callback registered).
	                outputPointer.setValue32(MemoryStick.getStateFatMs());
	                result = 0;
	            } else {
	            	result = IO_ERROR;
	            }
	            break;
	        }
	        case 0x00005802: {
	        	if (!"flash1:".equals(deviceName) || inputLength != 0 || outputLength != 0) {
	        		result = IO_ERROR;
	        	} else {
	        		result = 0;
	        	}
	        	break;
	        }
	        default: {
	        	result = super.ioDevctl(deviceName, command, inputPointer, inputLength, outputPointer, outputLength);
	        }
		}

		return result;
	}

	@Override
	public Map<IoOperation, IoOperationTiming> getTimings() {
		return IoFileMgrForUser.noDelayTimings;
	}

    @Override
	public String toString() {
		return String.format("LocalVirtualFileSystem localPath='%s', useDirExtendedInfo=%b", localPath, useDirExtendedInfo);
	}
}
