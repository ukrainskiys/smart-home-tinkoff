import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Function;

public class Solution {
	private static final Base64Encoder base64Encoder = new Base64Encoder();
	private static final Base64Decoder base64Decoder = new Base64Decoder();

	public static void main(String[] args) {
		log("BQECBQIDew");
	}

	//TODO некорректно работает декодирования
	private static void log(String str) {
		System.out.println("Input - " + str);
		final Packet packet = base64Decoder.decodePacket(str);
		System.out.println("Packet - " + packet);
		final String encoded = base64Encoder.encode(packet);
		System.out.println("Encoded - " + encoded);
	}
}

final class Base64Encoder {
	private static final Base64.Encoder encoder = Base64.getUrlEncoder();

	public <T extends Encodable> String encode(T object) {
		return new String(encoder.encode(object.encode()), StandardCharsets.UTF_8);
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
			case WHOISHERE, IAMHERE -> {
				if (dev_type == DevType.EnvSensor) {
					return decodeCmdBodyWhereForSensor(bytes);
				} else if (dev_type == DevType.Switch) {
					return decodeCmdBodyWhereForSwitch(bytes);
				} else {
					return new CBOnlyDevName(new TString(bytes));
				}
			}
			case STATUS -> {
				if (dev_type == DevType.EnvSensor) {
					return new CBValues(new TArray<>(bytes, byteArray -> {
						final Varuint varuint = new Varuint(byteArray);
						return new Pair<>(varuint, varuint.countBytes());
					}));
				} else if (dev_type == DevType.Switch || dev_type == DevType.Lamp || dev_type == DevType.Socket) {
					return new CBValue(new TByte(bytes[0]));
				} else {
					return CmdBody.EMPTY;
				}
			}
			case SETSTATUS -> {
				return new CBValue(new TByte(bytes[0]));
			}
			case TICK -> {
				return new CBTick(new Varuint(bytes));
			}
			default -> {
				return CmdBody.EMPTY;
			}
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

interface Encodable {
	byte[] encode();
}

record Packet(TByte length, Payload payload, TByte crc8) implements Encodable {
	@Override
	public byte[] encode() {
		final byte[] bLength = length.encode();
		final byte[] bPayload = payload.encode();
		final byte[] bCrc8 = crc8.encode();

		final byte[] bytes = new byte[bLength.length + bPayload.length + bCrc8.length];
		int index = 0;

		index = Utils.concatBytes(bytes, bLength, index);
		index = Utils.concatBytes(bytes, bPayload, index);
		Utils.concatBytes(bytes, bCrc8, index);

		return bytes;
	}
}

record Payload(Varuint src, Varuint dst, Varuint serial, DevType dev_type, Cmd cmd, CmdBody cmd_body)
	implements Encodable {
	@Override
	public byte[] encode() {
		final byte[] bSrc = src.encode();
		final byte[] bDst = dst.encode();
		final byte[] bSerial = serial.encode();
		final byte[] bDetType = dev_type.encode();
		final byte[] bCmd = cmd.encode();
		final byte[] bCmdBody = cmd_body.encode();

		final int length = bSrc.length + bDst.length + bSerial.length + bDetType.length + bCmd.length + bCmdBody.length;
		final byte[] bytes = new byte[length];
		int index = 0;

		index = Utils.concatBytes(bytes, bSrc, index);
		index = Utils.concatBytes(bytes, bDst, index);
		index = Utils.concatBytes(bytes, bSerial, index);
		index = Utils.concatBytes(bytes, bDetType, index);
		index = Utils.concatBytes(bytes, bCmd, index);
		Utils.concatBytes(bytes, bCmdBody, index);

		return bytes;
	}
}

interface CmdBody extends Encodable {
	CmdBody EMPTY = new CBEmpty();
}

record CBEmpty() implements CmdBody {
	@Override
	public byte[] encode() {
		return new byte[0];
	}
}

record CBTick(Varuint timestamp) implements CmdBody {
	@Override
	public byte[] encode() {
		return timestamp.encode();
	}
}

record CBOnlyDevName(TString dev_name) implements CmdBody {
	@Override
	public byte[] encode() {
		return dev_name.encode();
	}
}

record CBSensorWhere(TString dev_name, DP dev_props) implements CmdBody {
	@Override
	public byte[] encode() {
		final byte[] bDevName = dev_name.encode();
		final byte[] bDevProps = dev_props.encode();

		final byte[] bytes = new byte[bDevName.length + bDevProps.length];
		int index = Utils.concatBytes(bytes, bDevName, 0);
		Utils.concatBytes(bytes, bDevProps, index);

		return bytes;
	}
}

record CBSwitchWhere(TString dev_name, DPNameList dev_names) implements CmdBody {
	@Override
	public byte[] encode() {
		final byte[] bDevName = dev_name.encode();
		final byte[] bDevNames = dev_names.encode();

		final byte[] bytes = new byte[bDevName.length + bDevNames.length];
		int index = Utils.concatBytes(bytes, bDevName, 0);
		Utils.concatBytes(bytes, bDevNames, index);

		return bytes;
	}
}

record CBValue(TByte value) implements CmdBody {
	@Override
	public byte[] encode() {
		return value.encode();
	}
}

record CBValues(TArray<Varuint> values) implements CmdBody {
	@Override
	public byte[] encode() {
		return values.encode();
	}
}

record DP(TByte sensors, TArray<Trigger> triggers) implements Encodable {
	@Override
	public byte[] encode() {
		final byte[] bSensors = sensors.encode();
		final byte[] bTriggers = triggers.encode();

		final byte[] bytes = new byte[bSensors.length + bTriggers.length];
		int index = Utils.concatBytes(bytes, bSensors, 0);
		Utils.concatBytes(bytes, bTriggers, index);

		return bytes;
	}
}

record Trigger(TByte op, Varuint value, TString name) implements Encodable {
	@Override
	public byte[] encode() {
		final byte[] bOp = op.encode();
		final byte[] bValue = value.encode();
		final byte[] bName = name.encode();

		final byte[] bytes = new byte[bOp.length + bValue.length + bName.length];
		int index = Utils.concatBytes(bytes, bOp, 0);
		index = Utils.concatBytes(bytes, bValue, index);
		Utils.concatBytes(bytes, bName, index);

		return bytes;
	}
}

record DPNameList(TArray<TString> dev_names) implements Encodable {
	@Override
	public byte[] encode() {
		return dev_names.encode();
	}
}


interface Type<T> extends Encodable {
	T val();
}

interface BigType<T> extends Type<T> {
	int countBytes();
}

final class TByte implements Type<Integer> {
	private final Integer value;

	TByte(byte b) {
		this.value = Byte.toUnsignedInt(b);
	}

	@Override
	public Integer val() {
		return value;
	}

	@Override
	public byte[] encode() {
		return new byte[]{value.byteValue()};
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

final class TArray<T extends Encodable> implements BigType<T> {
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
		final byte[] size = new byte[]{Integer.valueOf(countBytes - 1).byteValue()};
		final List<byte[]> objects = list.stream().map(Encodable::encode).toList();

		final byte[] bytes = new byte[objects.stream().mapToInt(arr -> arr.length).sum()];

		int index = Utils.concatBytes(bytes, size, 0);
		for (byte[] arr : objects) {
			index = Utils.concatBytes(bytes, arr, index);
		}
		return bytes;
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

enum Cmd implements Encodable {
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

	@Override
	public byte[] encode() {
		return new byte[]{Integer.valueOf(ordinal()).byteValue()};
	}
}

enum DevType implements Encodable {
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

	@Override
	public byte[] encode() {
		return new byte[]{Integer.valueOf(ordinal()).byteValue()};
	}
}


record Pair<T, U>(T left, U right) {
}

final class Utils {
	public static int concatBytes(byte[] src, byte[] dst, int index) {
		for (int i = 0; i < dst.length; i++, index++) {
			src[index] = dst[i];
		}
		return index;
	}
}
