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
package jpcsp.memory.mmio.syscon;

import static jpcsp.memory.mmio.syscon.MMIOHandlerSysconFirmwareSfr.IICIF0;
import static jpcsp.memory.mmio.syscon.SysconSfrNames.getSfr1Name;
import static jpcsp.memory.mmio.syscon.SysconSfrNames.getSfr1Names;
import static jpcsp.util.Utilities.clearBit;
import static jpcsp.util.Utilities.hasBit;
import static jpcsp.util.Utilities.setBit;

import java.io.IOException;

import org.apache.log4j.Logger;

import jpcsp.HLE.Modules;
import jpcsp.nec78k0.Nec78k0Processor;
import jpcsp.state.IState;
import jpcsp.state.StateInputStream;
import jpcsp.state.StateOutputStream;

/**
 * @author gid15
 *
 */
public class SysconI2c implements IState {
	protected Logger log = Nec78k0Processor.log;
	private static final int STATE_VERSION = 0;
	// I2C Control
	public static final int IICE0 = 7; // I2C operation enable
	public static final int LREL0 = 6; // Exit from communications
	public static final int WREL0 = 5; // Wait cancellation
	public static final int SPIE0 = 4; // Enable/disable generation of interrupt request when stop condition is detected
	public static final int WTIM0 = 3; // Control of wait and interrupt request generation
	public static final int ACKE0 = 2; // Acknowledgement control
	public static final int STT0  = 1; // Start condition trigger
	public static final int SPT0  = 0; // Stop condition trigger
	// I2C Status
	public static final int MSTS0 = 7; // Master device status
	public static final int ALD0  = 6; // Detection of arbitration loss
	public static final int EXC0  = 5; // Detection of extension code reception
	public static final int COI0  = 4; // Detection of matching addresses
	public static final int TRC0  = 3; // Detection of transmit/receive status
	public static final int ACKD0 = 2; // Detection of acknowledge
	public static final int STD0  = 1; // Detection of start condition
	public static final int SPD0  = 0; // Detection of stop condition
	// I2c slave addresses
	public static final int I2C_SLAVE_ADDRESS_POLESTAR = 0xAA;
	public static final int I2C_SLAVE_ADDRESS_POMMEL   = 0xD0;
	//
	protected final MMIOHandlerSysconFirmwareSfr sfr;
	private int shift;
	private int slaveAddress;
	private int control;
	private int status;
	private int flag;
	private int clockSelection;
	private int functionExpansion;
	private final int[] buffer = new int[MMIOHandlerSyscon.MAX_DATA_LENGTH];
	private int bufferIndex;

	public SysconI2c(MMIOHandlerSysconFirmwareSfr sfr) {
		this.sfr = sfr;
	}

	@Override
	public void read(StateInputStream stream) throws IOException {
		stream.readVersion(STATE_VERSION);
		shift = stream.readInt();
		slaveAddress = stream.readInt();
		control = stream.readInt();
		status = stream.readInt();
		flag = stream.readInt();
		clockSelection = stream.readInt();
		functionExpansion = stream.readInt();
		stream.readInts(buffer);
		bufferIndex = stream.readInt();
	}

	@Override
	public void write(StateOutputStream stream) throws IOException {
		stream.writeVersion(STATE_VERSION);
		stream.writeInt(shift);
		stream.writeInt(slaveAddress);
		stream.writeInt(control);
		stream.writeInt(status);
		stream.writeInt(flag);
		stream.writeInt(clockSelection);
		stream.writeInt(functionExpansion);
		stream.writeInts(buffer);
		stream.writeInt(bufferIndex);
	}

	public void reset() {
		shift = 0x00;
		slaveAddress = 0x00;
		control = 0x00;
		status = 0x00;
		flag = 0x00;
		clockSelection = 0x00;
		functionExpansion = 0x00;
		bufferIndex = 0;
	}

	public int getShift() {
		if (log.isDebugEnabled()) {
			log.debug(String.format("I2c getShift 0x%02X", shift));
			if (isRead()) {
				log.debug(String.format("I2c read from slaveAddress 0x%02X, data#%d 0x%02X", getSlaveAddress(), bufferIndex - 1, shift));
			}
		}
		return shift;
	}

//int n = 0;
	public void setShift(int value) {
		if (log.isDebugEnabled()) {
			log.debug(String.format("I2c setShift 0x%02X", value));
		}
		shift = value;

		// Start condition detected?
		if (hasStatusBit(STD0)) {
			setSlaveAddress(shift);
			clearStatusBit(STD0);
			clearStatusBit(SPD0); // Clear detection of stop condition
			bufferIndex = 0;
			if (isRead()) {
				int registerNumber;
				int registerValue;
				switch (getSlaveAddress()) {
					case I2C_SLAVE_ADDRESS_POMMEL:
						registerNumber = buffer[0];
						registerValue = Modules.sceSysconModule.readPommelRegister(registerNumber);
						if (log.isDebugEnabled()) {
							log.debug(String.format("readPommelRegister(0x%02X)=0x%04X", registerNumber, registerValue));
						}
						buffer[0] = (registerValue     ) & 0xFF;
						buffer[1] = (registerValue >> 8) & 0xFF;
						break;
					case I2C_SLAVE_ADDRESS_POLESTAR:
						registerNumber = buffer[0];
						registerValue = Modules.sceSysconModule.readPolestarRegister(registerNumber);
						if (log.isDebugEnabled()) {
							log.debug(String.format("readPolestarRegister(0x%02X)=0x%04X", registerNumber, registerValue));
						}
						buffer[0] = (registerValue     ) & 0xFF;
						buffer[1] = (registerValue >> 8) & 0xFF;
						break;
					default:
						log.error(String.format("I2c unimplemented read from slaveAddress 0x%02X", getSlaveAddress()));
						break;
				}
//				if (dummyTesting) {
//					if (n <= 1) {
//						buffer[0] = Battery.isPresent() ? 0x00 : 0x02;
//						buffer[0] = 0x62; // Battery not present
//						buffer[1] = 0xC0;
//						// first byte will be stored at FEC1
//						//   bits 0..1: OR-ed at FE7C 0..1 (with inverted bit 1)
//						//   bits 3..4: OR-ed at FE7C 2..3
//						//   bits 5..7: possible values 0x00, 0xC0, 0x60
//						// second byte will be stored at FEC2
//						//   bit 7: set at FE7C.6
//						//   bit 6: set at FE7C.7
//					} else {
//						buffer[0] = 0x01;
//						buffer[1] = 0xC0;
//					}
//					n++;
//				}
			}
			if (log.isDebugEnabled()) {
				log.debug(String.format("I2c start %s to slaveAddress 0x%02X", isRead() ? "read" : "write", getSlaveAddress()));
			}
		} else if (isWrite()) {
			if (log.isDebugEnabled()) {
				log.debug(String.format("I2c write to slaveAddress 0x%02X, data#%d 0x%02X", getSlaveAddress(), bufferIndex, shift));
			}
			buffer[bufferIndex++] = shift;
		}

		setStatusBit(ACKD0); // Detection of acknowledge
		sfr.setInterruptRequest(IICIF0);
	}

	public int getSlaveAddress() {
		// Bit 0 is fixed to 0
		return clearBit(slaveAddress, 0);
	}

	public void setSlaveAddress(int value) {
		if (log.isDebugEnabled()) {
			log.debug(String.format("I2c setSlaveAddress 0x%02X", value));
		}
		slaveAddress = value;
	}

	private boolean isRead() {
		return hasBit(slaveAddress, 0);
	}

	private boolean isWrite() {
		return !isRead();
	}

	public int getControl() {
		return control;
	}

	public void setControl(int value) {
		if (log.isDebugEnabled()) {
			log.debug(String.format("I2c setControl 0x%02X(%s)", value, getSfr1Names(0xFFA6, value)));
		}

		control = value;

		// Trigger stop condition?
		if (hasBit(value, SPT0)) {
			clearStatusBit(ACKD0); // Clear detection of acknowledge
			setSlaveAddress(0); // Clear slave address
			setStatusBit(SPD0); // Detection of stop condition
			control = clearBit(control, SPT0);
			bufferIndex = 0;
		}

		// Trigger start condition?
		if (hasBit(control, STT0)) {
			setStatusBit(STD0); // Detection of start condition
			control = clearBit(control, STT0);
		}

		// Wait cancellation?
		if (hasBit(control, WREL0)) {
			if (isRead()) {
				int data = buffer[bufferIndex++];
				setShift(data);
			}
			control = clearBit(control, WREL0);
		}
	}

	public int getStatus() {
		if (log.isDebugEnabled()) {
			log.debug(String.format("I2c getStatus 0x%02X(%s)", status, getSfr1Names(0xFFAA, status)));
		}
		return status;
	}

	private void setStatusBit(int bit) {
		if (log.isDebugEnabled()) {
			log.debug(String.format("I2c setStatusBit %s", getSfr1Name(0xFFAA, bit)));
		}
		status = setBit(status, bit);
	}

	private void clearStatusBit(int bit) {
		if (log.isDebugEnabled()) {
			log.debug(String.format("I2c clearStatusBit %s", getSfr1Name(0xFFAA, bit)));
		}
		status = clearBit(status, bit);
	}

	private boolean hasStatusBit(int bit) {
		boolean result = hasBit(status, bit);
		if (log.isDebugEnabled()) {
			log.debug(String.format("I2c hasStatusBit %s returning %b", getSfr1Name(0xFFAA, bit), result));
		}
		return result;
	}

	public int getFlag() {
		return flag;
	}

	public void setFlag(int value) {
		if (log.isDebugEnabled()) {
			log.debug(String.format("I2c setFlag 0x%02X", value));
		}
		flag = value;
	}

	public int getClockSelection() {
		return clockSelection;
	}

	public void setClockSelection(int value) {
		if (log.isDebugEnabled()) {
			log.debug(String.format("I2c setClockSelection 0x%02X", value));
		}
		clockSelection = value;
	}

	public int getFunctionExpansion() {
		return functionExpansion;
	}

	public void setFunctionExpansion(int value) {
		if (log.isDebugEnabled()) {
			log.debug(String.format("I2c setFunctionExpansion 0x%02X", value));
		}
		functionExpansion = value;
	}
}
