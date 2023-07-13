import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Function;

public class Solution {

	private static final Base64Decoder base64Decoder = new Base64Decoder();

	public static void main(String[] args) {
		System.out.println(base64Decoder.decodePacket("OAL_fwQCAghTRU5TT1IwMQ8EDGQGT1RIRVIxD7AJBk9USEVSMgCsjQYGT1RIRVIzCAAGT1RIRVI09w"));
	}
}

final class Base64Decoder {
	private static final Base64.Decoder decoder = Base64.getUrlDecoder();

	public Packet decodePacket(String base64) {
		final byte[] bytes = decoder.decode(base64.getBytes(StandardCharsets.UTF_8));
		final TByte length = new TByte(bytes[0]);
		final Payload payload = decodePayload(Arrays.copyOfRange(bytes, 1, length.val() + 1));
		final TByte crc8 = new TByte(bytes[length.val() + 1]);
		return new Packet(length, payload, crc8);
	}

	private Payload decodePayload(byte[] bytes) {
		int readIndex = 0;

		final Varuint src = new Varuint(Arrays.copyOfRange(bytes, readIndex, bytes.length));
		readIndex += src.countBytes();

		final Varuint dst = new Varuint(Arrays.copyOfRange(bytes, readIndex, bytes.length));
		readIndex += dst.countBytes();

		final Varuint serial = new Varuint(Arrays.copyOfRange(bytes, readIndex, bytes.length));
		readIndex += serial.countBytes();

		final DevType dev_type = DevType.of(new TByte(bytes[readIndex++]).val());

		final Cmd cmd = Cmd.of(new TByte(bytes[readIndex++]).val());

		final CmdBody cmd_body = decodeCmdBody(Arrays.copyOfRange(bytes, readIndex, bytes.length), dev_type, cmd);

		return new Payload(src, dst, serial, dev_type, cmd, cmd_body);
	}

	private CmdBody decodeCmdBody(byte[] bytes, DevType dev_type, Cmd cmd) {
		switch (cmd) {
			case WHOISHERE:
			case IAMHERE: {
				if (dev_type == DevType.EnvSensor) {
					return decodeCmdBodyWhereForSensor(bytes);
				} else if (dev_type == DevType.Switch) {
					return decodeCmdBodyWhereForSwitch(bytes);
				} else {
					return new CBOnlyDevName(new TString(bytes));
				}
			}
			case STATUS: {
				if (dev_type == DevType.EnvSensor) {
					//todo
				} else if (dev_type == DevType.Switch || dev_type == DevType.Lamp || dev_type == DevType.Socket) {
					//todo
				} else {
					return CmdBody.EMPTY;
				}
			}
			case TICK:
				return new CBTick(new Varuint(bytes));
			default:
				return CmdBody.EMPTY;
		}
	}

	private CBSensorWhere decodeCmdBodyWhereForSensor(byte[] bytes) {
		int readIndex = 0;

		final TString dev_name = new TString(bytes);
		readIndex += dev_name.countBytes();

		final TByte sensors = new TByte(bytes[readIndex++]);

		final TArray<Trigger> triggers = new TArray<>(
			Arrays.copyOfRange(bytes, readIndex, bytes.length),
			byteArray -> {
				int index = 0;

				final TByte op = new TByte(byteArray[index++]);

				final Varuint value = new Varuint(Arrays.copyOfRange(byteArray, index, byteArray.length));
				index += value.countBytes();

				final TString name = new TString(Arrays.copyOfRange(byteArray, index, byteArray.length));
				index += name.countBytes();

				return new Pair<>(new Trigger(op, value, name), index);
		});

		return new CBSensorWhere(dev_name, new DP(sensors, triggers));
	}

	public CBSwitchWhere decodeCmdBodyWhereForSwitch(byte[] bytes) {
		int readIndex = 0;

		final TString dev_name = new TString(bytes);
		readIndex += dev_name.countBytes();

		final TArray<TString> names = new TArray<>(
			Arrays.copyOfRange(bytes, readIndex, bytes.length),
			byteArray -> {
				int index = 0;

				final TString name = new TString(Arrays.copyOfRange(byteArray, index, byteArray.length));
				index += name.countBytes();

				return new Pair<>(name, index);
			});

		return new CBSwitchWhere(dev_name, new DPNameList(names));
	}
}

record Packet(TByte length, Payload payload, TByte crc8) {
}

record Payload(Varuint src, Varuint dst, Varuint serial, DevType dev_type, Cmd cmd, CmdBody cmd_body) {
}

interface CmdBody {
	CmdBody EMPTY = new CBEmpty();
}

record CBEmpty() implements CmdBody {
}

record CBTick(Varuint timestamp) implements CmdBody {
}

record CBOnlyDevName(TString dev_name) implements CmdBody {
}

record CBSensorWhere(TString dev_name, DP dev_props) implements CmdBody {
}

record CBSwitchWhere(TString dev_name, DPNameList dev_ames) implements CmdBody {
}

record CBValue(TByte value) implements CmdBody {
}

record CBValueList(List<Varuint> values) implements CmdBody {
}

record DP(TByte sensors, TArray<Trigger> triggers) {
}

record Trigger(TByte op, Varuint value, TString name) {
}

record DPNameList(TArray<TString> dev_names) {
}


interface Type<T> {
	T val();
}

interface BigType<T> extends Type<T> {
	byte[] encode();
	int countBytes();
}

interface LittleType<T> extends Type<T> {
	byte encode();
}

final class TByte implements LittleType<Integer> {
	private final Integer value;

	TByte(byte b) {
		this.value = Byte.toUnsignedInt(b);
	}

	@Override
	public Integer val() {
		return value;
	}

	@Override
	public byte encode() {
		return value.byteValue();
	}

	@Override
	public String toString() {
		return value.toString();
	}
}

final class TString implements BigType<String> {
	private final String value;
	private final int countBytes;

	TString(byte[] bytes) {
		final StringBuilder builder = new StringBuilder();
		int count = Byte.toUnsignedInt(bytes[0]);
		for (int i = 1; i <= count; i++) {
			builder.appendCodePoint(bytes[i]);
		}

		this.value = builder.toString();
		this.countBytes = count + 1;
	}

	@Override
	public String val() {
		return value;
	}

	@Override
	public byte[] encode() {
		final byte[] bytes = new byte[countBytes + 1];
		bytes[0] = Integer.valueOf(countBytes).byteValue();
		byte[] valBytes = value.getBytes();
		System.arraycopy(valBytes, 0, bytes, 1, valBytes.length);
		return bytes;
	}

	@Override
	public int countBytes() {
		return countBytes;
	}

	@Override
	public String toString() {
		return value;
	}
}

final class Varuint implements BigType<Long> {
	private final Long value;
	private final int countBytes;

	Varuint(byte[] bytes) {
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

		this.value = value;
		this.countBytes = index;
	}

	@Override
	public Long val() {
		return value;
	}

	@Override
	public byte[] encode() {
		long val = value;
		ArrayList<Byte> bytes = new ArrayList<>();
		do {
			byte b = (byte) (val & 0x7f);
			val >>= 7;
			if (value != 0) {
				b |= 0x80;
			}
			bytes.add(b);
		} while (val != 0);

		byte[] ret = new byte[bytes.size()];
		for (int i = 0; i < bytes.size(); i++) {
			ret[i] = bytes.get(i);
		}
		return ret;
	}

	@Override
	public int countBytes() {
		return countBytes;
	}

	@Override
	public String toString() {
		return value.toString();
	}
}

final class TArray<T> implements BigType<T> {
	private final List<T> list;
	private final int countBytes;

	TArray(byte[] bytes, Function<byte[], Pair<T, Integer>> handler) {
		int readIndex = 0;
		int count = new TByte(bytes[readIndex++]).val();
		final List<T> arrayList = new ArrayList<>(count);
		for (; count > 0; count--) {
			Pair<T, Integer> pair = handler.apply(Arrays.copyOfRange(bytes, readIndex, bytes.length));
			readIndex += pair.right();

			arrayList.add(pair.left());
		}

		this.list = arrayList;
		this.countBytes = readIndex;
	}

	public List<T> list() {
		return list;
	}

	@Override
	public T val() {
		throw new UnsupportedOperationException();
	}

	@Override
	public byte[] encode() {
		return null; //TODO
	}

	@Override
	public int countBytes() {
		return countBytes;
	}

	@Override
	public String toString() {
		return list.toString();
	}
}

enum Cmd {
	WHOISHERE(0x01),
	IAMHERE(0x02),
	GETSTATUS(0x03),
	STATUS(0x04),
	SETSTATUS(0x05),
	TICK(0x06);

	Cmd(int b) {
	}

	public static Cmd of(int b) {
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


record Pair<T, U>(T left, U right) {
}
