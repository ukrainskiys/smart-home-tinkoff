import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

public class Solution {

	private static final Base64Decoder base64Decoder = new Base64Decoder();

	public static void main(String[] args) {
		System.out.println(base64Decoder.decodePacket("IgP_fwcDAQhTV0lUQ0gwMQMFREVWMDEFREVWMDIFREVWMDO1"));
	}
}

final class Base64Decoder {
	private static final Base64.Decoder decoder = Base64.getUrlDecoder();

	public Packet decodePacket(String base64) {
		final byte[] bytes = decoder.decode(base64.getBytes(StandardCharsets.UTF_8));
		final int length = TypeConverter.readByte(bytes[0]);
		final Payload payload = decodePayload(Arrays.copyOfRange(bytes, 1, length + 1));
		final int crc8 = TypeConverter.readByte(bytes[length + 1]);
		return new Packet(length, payload, crc8);
	}

	private Payload decodePayload(byte[] bytes) {
		System.out.println(Arrays.toString(bytes));

		int readIndex = 0;

		final Pair<Long, Integer> src = TypeConverter.readUleb128(Arrays.copyOfRange(bytes, readIndex, bytes.length));
		readIndex += src.two();

		final Pair<Long, Integer> dst = TypeConverter.readUleb128(Arrays.copyOfRange(bytes, readIndex, bytes.length));
		readIndex += dst.two();

		final Pair<Long, Integer> serial = TypeConverter.readUleb128(Arrays.copyOfRange(bytes, readIndex, bytes.length));
		readIndex += serial.two();

		final DevType dev_type = DevType.of(TypeConverter.readByte(bytes[readIndex++]));

		final Command cmd = Command.of(TypeConverter.readByte(bytes[readIndex++]));

		final CmdBody cmd_body = decodeCmdBody(Arrays.copyOfRange(bytes, readIndex, bytes.length), dev_type, cmd);

		return new Payload(src.one(), dst.one(), serial.one(), dev_type, cmd, cmd_body);
	}

	private CmdBody decodeCmdBody(byte[] bytes, DevType dev_type, Command cmd) {
		switch (cmd) {
			case WHOISHERE:
			case IAMHERE: {
				if (dev_type == DevType.EnvSensor) {
					return decodeCmdBodyWhereForSensor(bytes);
				} else if (dev_type == DevType.Switch) {
					return decodeCmdBodyWhereForSwitch(bytes);
				} else {
					return new CmdBodyDevName(TypeConverter.readString(bytes).one());
				}
			}
			case STATUS: {
				if (dev_type == DevType.EnvSensor) {

				} else if (dev_type == DevType.Switch || dev_type == DevType.Lamp || dev_type == DevType.Socket) {

				} else {

				}
			}
			case TICK: return new CmdBodyTick(TypeConverter.readUleb128(bytes).one());
			default: return new CmdBodyTick(0);
		}
	}

	private CmdBodyWhereForSensor decodeCmdBodyWhereForSensor(byte[] bytes) {
		int readIndex = 0;

		final Pair<String, Integer> dev_name = TypeConverter.readString(bytes);
		readIndex += dev_name.two();

		final int sensors = TypeConverter.readByte(bytes[readIndex++]);

		int count = TypeConverter.readByte(bytes[readIndex++]);
		final List<Trigger> triggers = new ArrayList<>(count);
		for (; count > 0; count--) {
			final int op = TypeConverter.readByte(bytes[readIndex++]);

			final Pair<Long, Integer> value = TypeConverter.readUleb128(Arrays.copyOfRange(bytes, readIndex, bytes.length));
			readIndex += value.two();

			final Pair<String, Integer> name = TypeConverter.readString(Arrays.copyOfRange(bytes, readIndex, bytes.length));
			readIndex += name.two();

			triggers.add(new Trigger(op, value.one(), name.one()));
		}

		return new CmdBodyWhereForSensor(dev_name.one(), new DevProps(sensors, triggers));
	}

	public CmdBodyWhereForSwitch decodeCmdBodyWhereForSwitch(byte[] bytes) {
		int readIndex = 0;

		final Pair<String, Integer> dev_name = TypeConverter.readString(bytes);
		readIndex += dev_name.two();

		int count = TypeConverter.readByte(bytes[readIndex++]);
		final List<String> names = new ArrayList<>(count);
		for (; count > 0; count--) {
			final Pair<String, Integer> name = TypeConverter.readString(Arrays.copyOfRange(bytes, readIndex, bytes.length));
			readIndex += name.two();

			names.add(name.one());
		}

		return new CmdBodyWhereForSwitch(dev_name.one(), new DevPropsNames(names));
	}


	static final class TypeConverter {

		public static int readByte(byte b) {
			return Byte.toUnsignedInt(b);
		}

		/**
		 * @param bytes 3 bytes
		 * @return Pair: one - result, two - count reading elements
		 */
		public static Pair<Long, Integer> readUleb128(byte[] bytes) {
			long value = 0;
			int bitSize = 0;
			int read;

			int index = 0;
			do {
				read = bytes[index++];
				value += ((long) read & 0x7f) << bitSize;
				bitSize += 7;
				if (bitSize >= 64) {
					throw new ArithmeticException("ULEB128 value exceeds maximum value for long type.");
				}
			} while ((read & 0x80) != 0);
			return new Pair<>(value, index);
		}

		/**
		 * @return Pair: one - result, two - count reading elements
		 */
		public static Pair<String, Integer> readString(byte[] bytes) {
			final StringBuilder builder = new StringBuilder();
			int count = readByte(bytes[0]);
			for (int i = 1; i <= count; i++) {
				builder.appendCodePoint(bytes[i]);
			}
			return new Pair<>(builder.toString(), count + 1);
		}
	}
}

record Packet(int length, Payload payload, int crc8) {
}

record Payload(long src, long dst, long serial, DevType dev_type, Command cmd, CmdBody cmd_body) {
}

abstract class CmdBody {
}

class CmdBodyTick extends CmdBody {
	private final long timestamp;

	CmdBodyTick(long timestamp) {
		this.timestamp = timestamp;
	}

	public long getTimestamp() {
		return timestamp;
	}

	@Override
	public String toString() {
		return "CmdBodyTick{" +
			"timestamp=" + timestamp +
			'}';
	}
}

class CmdBodyDevName extends CmdBody {
	private final String dev_name;

	CmdBodyDevName(String devName) {
		dev_name = devName;
	}

	public String getDev_name() {
		return dev_name;
	}

	@Override
	public String toString() {
		return "CmdBodyDevName{" +
			"dev_name='" + dev_name + '\'' +
			'}';
	}
}

class CmdBodyWhereForSensor extends CmdBody {
	private final String dev_name;
	private final DevProps dev_props;

	CmdBodyWhereForSensor(String devName, DevProps devProps) {
		dev_name = devName;
		dev_props = devProps;
	}

	public String getDev_name() {
		return dev_name;
	}

	public DevProps getDev_props() {
		return dev_props;
	}

	@Override
	public String toString() {
		return "CmdBodyWhereForSensor{" +
			"dev_name='" + dev_name + '\'' +
			", dev_props=" + dev_props +
			'}';
	}
}

class CmdBodyWhereForSwitch extends CmdBody {
	private final String dev_name;
	private final DevPropsNames dev_ames;

	CmdBodyWhereForSwitch(String devName, DevPropsNames devAmes) {
		dev_name = devName;
		dev_ames = devAmes;
	}

	public String getDev_name() {
		return dev_name;
	}

	public DevPropsNames getDev_ames() {
		return dev_ames;
	}

	@Override
	public String toString() {
		return "CmdBodyWhereForSwitch{" +
			"dev_name='" + dev_name + '\'' +
			", dev_ames=" + dev_ames +
			'}';
	}
}

record DevProps(int sensors, List<Trigger> triggers) {
}

record Trigger(int op, long value, String name) {
}

record DevPropsNames(List<String> dev_names) {
}

enum Command {
	WHOISHERE(0x01),
	IAMHERE(0x02),
	GETSTATUS(0x03),
	STATUS(0x04),
	SETSTATUS(0x05),
	TICK(0x06);

	Command(int b) {
	}

	public static Command of(int b) {
		return values()[b - 1];
	}
}

enum DevType {
	SmartHub(0x01),
	EnvSensor(0x02),
	Switch(0x03),
	Lamp(0x04),
	Socket(0x05),
	Clock(0x06);

	DevType(int b) {
	}

	public static DevType of(int b) {
		return values()[b - 1];
	}
}

record Pair<T, U> (T one, U two) {
}

//final class Uleb128 {
//	private final static int BITS_LONG = 64;
//	private final static int MASK_DATA = 0x7f;
//	private final static int MASK_CONTINUE = 0x80;
//	private final long value;
//
//	private Uleb128(long value) {
//		this.value = value;
//	}
//
//	public static Uleb128 fromByteStream(ByteArrayInputStream bytes) throws IOException {
//		return new Uleb128(decode(bytes));
//	}
//
//	public static Uleb128 fromLong(long value) {
//		return new Uleb128(value);
//	}
//
//	private static long decode(ByteArrayInputStream bytes) throws IOException {
//		long value = 0;
//		int bitSize = 0;
//		int read;
//
//		do {
//			if ((read = bytes.read()) == -1) {
//				throw new IOException("Unexpected EOF");
//			}
//
//			value += ((long) read & MASK_DATA) << bitSize;
//			bitSize += 7;
//			if (bitSize >= BITS_LONG) {
//				throw new ArithmeticException("ULEB128 value exceeds maximum value for long type.");
//			}
//
//		} while ((read & MASK_CONTINUE) != 0);
//		return value;
//	}
//
//	private static byte[] encode(long value) {
//		ArrayList<Byte> bytes = new ArrayList<>();
//		do {
//			byte b = (byte) (value & MASK_DATA);
//			value >>= 7;
//			if (value != 0) {
//				b |= MASK_CONTINUE;
//			}
//			bytes.add(b);
//		} while (value != 0);
//
//		byte[] ret = new byte[bytes.size()];
//		for (int i = 0; i < bytes.size(); i++) {
//			ret[i] = bytes.get(i);
//		}
//		return ret;
//	}
//
//	public long asLong() {
//		return value;
//	}
//
//	public byte[] asBytes() {
//		return encode(value);
//	}
//
//	public String toString() {
//		return Long.toString(value);
//	}
//}
