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
package jpcsp.HLE.kernel.types;

import java.nio.charset.Charset;

import jpcsp.Memory;
import jpcsp.HLE.ITPointerBase;
import jpcsp.HLE.TPointer;
import jpcsp.HLE.TPointer32;
import jpcsp.HLE.TPointerFunction;
import jpcsp.memory.IMemoryReader;
import jpcsp.memory.IMemoryWriter;
import jpcsp.memory.MemoryReader;
import jpcsp.memory.MemoryWriter;
import jpcsp.util.Utilities;

public abstract class pspAbstractMemoryMappedStructure {
    private final static int unknown = 0x11111111;
	public final static Charset charset16 = Charset.forName("UTF-16LE");

    private int baseAddress;
    private int maxSize = Integer.MAX_VALUE;
    private int offset;
    protected Memory mem;

    public abstract int sizeof();
    protected abstract void read();
    protected abstract void write();

    private void start(Memory mem) {
        this.mem = mem;
        offset = 0;
    }

    protected void start(Memory mem, int address) {
        start(mem);
        baseAddress = address;
    }

    private void start(Memory mem, int address, int maxSize) {
        start(mem, address);
        this.maxSize = maxSize;
    }

    public void setMaxSize(int maxSize) {
    	// maxSize is an unsigned int
    	if (maxSize < 0) {
    		maxSize = Integer.MAX_VALUE;
    	}
        this.maxSize = maxSize;
    }

    public void read(Memory mem, int address) {
        start(mem, address);
        if (address != 0) {
        	read();
        }
    }

    public void read(ITPointerBase pointer) {
    	read(pointer, 0);
    }

    public void read(ITPointerBase pointer, int offset) {
    	start(pointer.getMemory(), pointer.getAddress() + offset);
    	if (pointer.isNotNull()) {
    		read();
    	}
    }

    public void write(Memory mem, int address) {
        start(mem, address);
        write();
    }

    public void write(ITPointerBase pointer) {
    	write(pointer, 0);
    }

    public void write(ITPointerBase pointer, int offset) {
    	write(pointer.getMemory(), pointer.getAddress() + offset);
    }

    public void write(Memory mem) {
        start(mem);
        write();
    }

    protected int read8() {
        int value;
        if (offset >= maxSize) {
            value = 0;
        } else {
            value = mem.read8(baseAddress + offset);
        }
        offset += 1;

        return value;
    }

    protected void read8Array(byte[] array) {
    	for (int i = 0; array != null && i < array.length; i++) {
    		array[i] = (byte) read8();
    	}
    }

    protected void align16() {
    	offset = (offset + 1) & ~1;
    }

    protected void align32() {
    	offset = (offset + 3) & ~3;
    }

    protected int read16() {
    	align16();

    	int value;
        if (offset >= maxSize) {
            value = 0;
        } else {
            value = mem.read16(baseAddress + offset);
        }
        offset += 2;

        if (isBigEndian()) {
        	value = endianSwap16((short) value);
        }

        return value;
    }

    protected int readUnaligned16() {
    	int n0 = read8();
    	int n1 = read8();

    	if (isBigEndian()) {
    		return (n0 << 8) | n1;
    	}
    	return (n1 << 8) | n0;
    }

    protected int read32() {
    	align32();

    	int value;
        if (offset >= maxSize) {
            value = 0;
        } else {
            value = mem.read32(baseAddress + offset);
        }
        offset += 4;

        if (isBigEndian()) {
        	value = endianSwap32(value);
        }

        return value;
    }

    protected int readUnaligned32() {
    	int n01 = readUnaligned16();
    	int n23 = readUnaligned16();

    	if (isBigEndian()) {
    		return (n01 << 16) | n23;
    	}
    	return (n23 << 16) | n01;
    }

    protected long read64() {
    	align32();

    	long value;
        if (offset >= maxSize) {
            value = 0;
        } else {
            value = mem.read64(baseAddress + offset);
        }
        offset += 8;

        if (isBigEndian()) {
        	value = endianSwap64(value);
        }

        return value;
    }

    protected void read32Array(int[] array) {
    	for (int i = 0; array != null && i < array.length; i++) {
    		array[i] = read32();
    	}
    }

    protected boolean readBoolean() {
    	int value = read8();

    	return (value != 0);
    }

    protected void readBooleanArray(boolean[] array) {
    	for (int i = 0; array != null && i < array.length; i++) {
    		array[i] = readBoolean();
    	}
    }

    protected float readFloat() {
    	int int32 = read32();

    	return Float.intBitsToFloat(int32);
    }

    protected void readFloatArray(float[] array) {
    	for (int i = 0; array != null && i < array.length; i++) {
    		array[i] = readFloat();
    	}
    }

    protected void readFloatArray(float[][] array) {
    	for (int i = 0; array != null && i < array.length; i++) {
    		readFloatArray(array[i]);
    	}
    }

    protected void readUnknown(int length) {
        offset += length;
    }

    protected String readStringNZ(int n) {
        String s;
        if (offset >= maxSize) {
            s = null;
        } else {
            s = Utilities.readStringNZ(mem, baseAddress + offset, n);
        }
        offset += n;

        return s;
    }

    protected String readStringZ(int addr) {
    	if (addr == 0) {
    		return null;
    	}
        return Utilities.readStringZ(mem, addr);
    }

    protected String readStringUTF16NZ(int n) {
    	StringBuilder s = new StringBuilder();
    	while (n > 0) {
    		int char16 = read16();
    		n -= 2;
    		if (char16 == 0) {
    			break;
    		}
    		byte[] bytes = new byte[2];
    		bytes[0] = (byte) char16;
    		bytes[1] = (byte) (char16 >> 8);
    		s.append(new String(bytes, charset16));
    	}

    	if (n > 0) {
    		readUnknown(n);
    	}

    	return s.toString();
    }

    protected int writeStringUTF16NZ(int n, String s) {
    	byte[] bytes = s.getBytes(charset16);
    	int length = 0;
    	if (bytes != null) {
    		length = bytes.length;
	    	for (int i = 0; i < bytes.length && n > 0; i++, n--) {
	    		write8(bytes[i]);
	    	}
    	}

    	// Write trailing '\0\0'
    	if (n > 0) {
    		write8((byte) 0);
    		n--;
    	}
    	if (n > 0) {
    		write8((byte) 0);
    		n--;
    	}

    	if (n > 0) {
    		offset += n;
    	}

    	return length;
    }

    /**
     * Read a string in UTF16, until '\0\0'
     * @param addr address of the string
     * @return the string
     */
    protected String readStringUTF16Z(int addr) {
    	if (addr == 0) {
    		return null;
    	}

    	IMemoryReader memoryReader = MemoryReader.getMemoryReader(addr, 2);
    	StringBuilder s = new StringBuilder();
    	while (true) {
    		int char16 = memoryReader.readNext();
    		if (char16 == 0) {
    			break;
    		}
    		byte[] bytes = new byte[2];
    		bytes[0] = (byte) char16;
    		bytes[1] = (byte) (char16 >> 8);
    		s.append(new String(bytes, charset16));
    	}

    	return s.toString();
    }

    protected TPointer readPointer() {
    	int value = read32();
    	if (value == 0) {
    		return TPointer.NULL;
    	}

    	return new TPointer(mem, value);
    }

    protected TPointer32 readPointer32() {
    	int value = read32();
    	if (value == 0) {
    		return TPointer32.NULL;
    	}

    	return new TPointer32(mem, value);
    }

    protected TPointerFunction readPointerFunction() {
    	int value = read32();
    	return new TPointerFunction(mem, value);
    }

    protected void readPointerArray(TPointer[] array) {
    	for (int i = 0; array != null && i < array.length; i++) {
    		array[i] = readPointer();
    	}
    }

    /**
     * Write a string in UTF16, including a trailing '\0\0'
     * @param addr address where to write the string
     * @param s the string to write
     * @return the number of bytes written (not including the trailing '\0\0')
     */
    protected int writeStringUTF16Z(int addr, String s) {
    	if (addr == 0 || s == null) {
    		return 0;
    	}

    	byte[] bytes = s.getBytes(charset16);
    	if (bytes == null) {
    		return 0;
    	}

    	IMemoryWriter memoryWriter = MemoryWriter.getMemoryWriter(addr, bytes.length + 2, 1);
    	for (int i = 0; i < bytes.length; i++) {
    		memoryWriter.writeNext(bytes[i] & 0xFF);
    	}

    	// Write trailing '\0\0'
    	memoryWriter.writeNext(0);
    	memoryWriter.writeNext(0);
    	memoryWriter.flush();

    	return bytes.length;
    }

    protected void read(pspAbstractMemoryMappedStructure object) {
        if (object == null) {
            return;
        }

        if (offset < maxSize) {
            object.start(mem, baseAddress + offset, maxSize - offset);
            object.read();
        }
        offset += object.sizeof();
    }

    protected void write8(byte data) {
        if (offset < maxSize) {
            mem.write8(baseAddress + offset, data);
        }
        offset += 1;
    }

    protected void write8Array(byte[] array) {
    	for (int i = 0; array != null && i < array.length; i++) {
    		write8(array[i]);
    	}
    }

    protected void write16(short data) {
    	align16();
        if (offset < maxSize) {
        	if (isBigEndian()) {
        		data = (short) endianSwap16(data);
        	}

        	mem.write16(baseAddress + offset, data);
        }
        offset += 2;
    }

    protected void writeUnaligned16(short data) {
    	if (isBigEndian()) {
    		write8((byte) (data >>> 8));
    		write8((byte) data);
    	} else {
    		write8((byte) data);
    		write8((byte) (data >>> 8));
    	}
    }

    protected void write32(int data) {
    	align32();
        if (offset < maxSize) {
        	if (isBigEndian()) {
        		data = endianSwap32(data);
        	}

            mem.write32(baseAddress + offset, data);
        }
        offset += 4;
    }

    protected void writeUnaligned32(int data) {
    	if (isBigEndian()) {
    		writeUnaligned16((short) (data >>> 16));
    		writeUnaligned16((short) data);
    	} else {
    		writeUnaligned16((short) data);
    		writeUnaligned16((short) (data >>> 16));
    	}
    }

    protected void write64(long data) {
    	align32();
        if (offset < maxSize) {
        	if (isBigEndian()) {
        		data = endianSwap64(data);
        	}

            mem.write64(baseAddress + offset, data);
        }
        offset += 8;
    }

    protected void write32Array(int[] array) {
    	for (int i = 0; array != null && i < array.length; i++) {
    		write32(array[i]);
    	}
    }

    protected void writeBoolean(boolean data) {
    	write8(data ? (byte) 1 : (byte) 0);
    }

    protected void writeBooleanArray(boolean[] array) {
    	for (int i = 0; array != null && i < array.length; i++) {
    		writeBoolean(array[i]);
    	}
    }

    protected void writeFloat(float data) {
    	int int32 = Float.floatToIntBits(data);
    	write32(int32);
    }

    protected void writeFloatArray(float[] array) {
    	for (int i = 0; array != null && i < array.length; i++) {
    		writeFloat(array[i]);
    	}
    }

    protected void writeFloatArray(float[][] array) {
    	for (int i = 0; array != null && i < array.length; i++) {
    		writeFloatArray(array[i]);
    	}
    }

    protected void writeUnknown(int length) {
        for (int i = 0; i < length; i++) {
            write8((byte) unknown);
        }
    }

    protected void writeSkip(int length) {
        offset += length;
    }

    protected void writeStringN(int n, String s) {
        if (offset < maxSize) {
            Utilities.writeStringNZ(mem, baseAddress + offset, n, s);
            // A NULL-byte has only been written at the end of the string
            // when enough space was available.
        }
        offset += n;
    }

    protected void writeStringNZ(int n, String s) {
        if (offset < maxSize) {
            Utilities.writeStringNZ(mem, baseAddress + offset, n - 1, s);
            // Write always a NULL-byte at the end of the string.
            mem.write8(baseAddress + offset + n - 1, (byte) 0);
        }
        offset += n;
    }

    protected void writeStringZ(String s, int addr) {
    	if (s != null && addr != 0) {
    		Utilities.writeStringZ(mem, addr, s);
    	}
    }

    protected void write(pspAbstractMemoryMappedStructure object) {
        if (object == null) {
            return;
        }

        if (offset < maxSize) {
            object.start(mem, baseAddress + offset, maxSize - offset);
            object.write();
        }
        offset += object.sizeof();
    }

    protected void writePointer(TPointer pointer) {
    	if (pointer == null) {
    		write32(0);
    	} else {
    		write32(pointer.getAddress());
    	}
    }

    protected void writePointer32(TPointer32 pointer) {
    	if (pointer == null) {
    		write32(0);
    	} else {
    		write32(pointer.getAddress());
    	}
    }

    protected void writePointerFunction(TPointerFunction pointer) {
    	if (pointer == null) {
    		write32(0);
    	} else {
    		write32(pointer.getAddress());
    	}
    }

    protected void writePointerArray(TPointer[] array) {
    	for (int i = 0; array != null && i < array.length; i++) {
    		writePointer(array[i]);
    	}
    }

    protected int getOffset() {
    	return offset;
    }

    public int getBaseAddress() {
    	return baseAddress;
    }

    public boolean isNull() {
    	return baseAddress == 0;
    }

    public boolean isNotNull() {
    	return baseAddress != 0;
    }

    protected int endianSwap16(short data) {
    	return Short.reverseBytes(data) & 0xFFFF;
    }

    protected int endianSwap32(int data) {
    	return Integer.reverseBytes(data);
    }

    protected long endianSwap64(long data) {
    	return Long.reverseBytes(data);
    }

    protected long longSwap64(long data) {
    	return (data >>> 32) | (data << 32);
    }

    protected boolean isBigEndian() {
    	return false;
    }

    @Override
	public String toString() {
    	return String.format("0x%08X", getBaseAddress());
    }
}
