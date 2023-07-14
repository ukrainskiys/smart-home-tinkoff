import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.function.Function;

public class Solution {
    public static void main(String[] args) {
        if (args == null || args.length != 2) {
            return;
        }

//        log("DAH_fwEBAQVIVUIwMeE");

        final SmartHubService smartHubService = new SmartHubService(args[0], Integer.parseInt(args[1], 16));
        try {
            smartHubService.start();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static void log(String str) {
        System.out.println("Input - " + str);
        final Packet packet = new Base64Decoder().decodePacket(str);
        System.out.println(packet);
        final String encoded = Base64Encoder.encodePacket(packet);
        System.out.println("Encod - " + encoded);

        System.out.println(new Base64Decoder().decodePacket(encoded));
    }
}

class SmartHubService {
    private static final HttpClient httpClient = HttpClient.newHttpClient();
    private static final Base64Decoder base64Decoder = new Base64Decoder();

    private final Requests requests;
    private final Cache cache;

    SmartHubService(String url, int address) {
        this.requests = new Requests(url, address);
        this.cache = new Cache(address);
    }

    public void start() throws IOException {
        final URI uri = requests.getUri();
        final HttpServer server = HttpServer.create(new InetSocketAddress(uri.getHost(), uri.getPort()), 0);
        server.createContext("/", exchange -> {
            if (Objects.equals(exchange.getRequestMethod(), "POST")) {
                handle(exchange);
            }
        });

        httpClient.sendAsync(requests.createWhoIsHereFromHub(), HttpResponse.BodyHandlers.ofString());

        server.start();
    }

    private void handle(HttpExchange exchange) throws IOException {
        final String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        final Packet packet = base64Decoder.decodePacket(requestBody);

        cache.add(packet.payload().src(), packet.payload().cmd_body());

        String rs = packet + "\n";
        exchange.sendResponseHeaders(200, rs.length());
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(rs.getBytes());
        }
    }
}

final class Requests {
    private static final Duration TIMEOUT = Duration.ofMillis(300);
    private static final long BROADCAST_ADDRESS = 16383;
    private static final TString HUB_NAME = new TString("HUB01");

    private final URI uri;
    private final long address;

    private long serial = 0;

    Requests(String url, int address) {
        this.uri = URI.create(url);
        this.address = address;
    }

    public HttpRequest createWhoIsHereFromHub() {
        final Payload payload = new Payload(new Varuint(address),
                new Varuint(BROADCAST_ADDRESS),
                new Varuint(incrementSerial()),
                DevType.SmartHub,
                Cmd.WHOISHERE,
                new CBOnlyDevName(HUB_NAME));
        return createRequest(new Packet(null, payload, null));
    }

    private HttpRequest createRequest(Packet packet) {
        return HttpRequest.newBuilder()
                .uri(uri)
                .POST(HttpRequest.BodyPublishers.ofString(Base64Encoder.encodePacket(packet)))
                .timeout(TIMEOUT)
                .build();
    }

    private long incrementSerial() {
        return ++serial;
    }

    public URI getUri() {
        return uri;
    }
}

final class Cache {
    private final Map<Long, CmdBody> cache = new HashMap<>();
    private final long hubAddress;

    Cache(int hubAddress) {
        this.hubAddress = hubAddress;
    }

    public void add(Varuint varuint, CmdBody body) {
        if (varuint.val() != hubAddress) {
            cache.put(varuint.val(), body);
        }
    }
}

final class Base64Encoder {
    private static final Base64.Encoder encoder = Base64.getUrlEncoder();

    public static String encodePacket(Packet packet) {
        final byte[] bytes = encoder.encode(packet.encode());
        return new String(bytes, StandardCharsets.UTF_8).replace("=", "");
    }
}

final class Base64Decoder {
    private static final Base64.Decoder decoder = Base64.getUrlDecoder();

    public Packet decodePacket(String base64) {
        try {
            final byte[] bytes = decoder.decode(base64.getBytes(StandardCharsets.UTF_8));
            final TByte length = new TByte(bytes[0]);
            final Payload payload = decodePayload(Utils.arrCopyFrom(bytes, 1));
            final TByte crc8 = new TByte(bytes[bytes.length - 1]);
            return new Packet(length, payload, crc8);
        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException();
        }
    }

    private Payload decodePayload(byte[] bytes) {
        int readIndex = 0;

        final Varuint src = new Varuint(Utils.arrCopyFrom(bytes, readIndex));
        readIndex += src.countBytes();

        final Varuint dst = new Varuint(Utils.arrCopyFrom(bytes, readIndex));
        readIndex += dst.countBytes();

        final Varuint serial = new Varuint(Utils.arrCopyFrom(bytes, readIndex));
        readIndex += serial.countBytes();

        final DevType dev_type = DevType.of(new TByte(bytes[readIndex++]).val());

        final Cmd cmd = Cmd.of(new TByte(bytes[readIndex++]).val());

        final CmdBody cmd_body = decodeCmdBody(Utils.arrCopyFrom(bytes, readIndex), dev_type, cmd);

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
                    return new CBValues(new TArray<>(bytes, Varuint::new));
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

        final TArray<Trigger> triggers = new TArray<>(Utils.arrCopyFrom(bytes, readIndex), Trigger::of);

        return new CBSensorWhere(dev_name, new DP(sensors, triggers));
    }

    public CBSwitchWhere decodeCmdBodyWhereForSwitch(byte[] bytes) {
        final TString dev_name = new TString(bytes);
        final TArray<TString> names = new TArray<>(Utils.arrCopyFrom(bytes, dev_name.countBytes()), TString::new);
        return new CBSwitchWhere(dev_name, new DPNameList(names));
    }
}

interface Encodable {
    byte[] encode();

    int countBytes();
}

record Packet(TByte length, Payload payload, TByte crc8) implements Encodable {
    @Override
    public byte[] encode() {
        final byte[] payloadBytes = payload.encode();
        final byte[] lengthBytes = new TByte(payloadBytes.length).encode();
        final byte[] crc8Bytes = new TByte(Utils.checkCrc8(payloadBytes)).encode();

        return Utils.concatByteArrays(lengthBytes, payloadBytes, crc8Bytes);
    }

    @Override
    public int countBytes() {
        return length.countBytes() + payload.countBytes() + crc8.countBytes();
    }
}

record Payload(Varuint src, Varuint dst, Varuint serial, DevType dev_type, Cmd cmd, CmdBody cmd_body)
        implements Encodable {
    @Override
    public byte[] encode() {
        return Utils.concatByteArrays(src.encode(), dst.encode(), serial.encode(), dev_type.encode(), cmd.encode(), cmd_body.encode());
    }

    @Override
    public int countBytes() {
        return src.countBytes() + dst.countBytes() + serial.countBytes() + dev_type.countBytes() + cmd.countBytes() + cmd_body.countBytes();
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

    @Override
    public int countBytes() {
        return 0;
    }
}

record CBTick(Varuint timestamp) implements CmdBody {
    @Override
    public byte[] encode() {
        return timestamp.encode();
    }

    @Override
    public int countBytes() {
        return timestamp.countBytes();
    }
}

record CBOnlyDevName(TString dev_name) implements CmdBody {
    @Override
    public byte[] encode() {
        return dev_name.encode();
    }

    @Override
    public int countBytes() {
        return dev_name.countBytes();
    }
}

record CBSensorWhere(TString dev_name, DP dev_props) implements CmdBody {
    @Override
    public byte[] encode() {
        return Utils.concatByteArrays(dev_name.encode(), dev_props.encode());
    }

    @Override
    public int countBytes() {
        return dev_name.countBytes() + dev_props.countBytes();
    }
}

record CBSwitchWhere(TString dev_name, DPNameList dev_names) implements CmdBody {
    @Override
    public byte[] encode() {
        return Utils.concatByteArrays(dev_name.encode(), dev_names.encode());
    }

    @Override
    public int countBytes() {
        return dev_name.countBytes() + dev_names.countBytes();
    }
}

record CBValue(TByte value) implements CmdBody {
    @Override
    public byte[] encode() {
        return value.encode();
    }

    @Override
    public int countBytes() {
        return value.countBytes();
    }
}

record CBValues(TArray<Varuint> values) implements CmdBody {
    @Override
    public byte[] encode() {
        return values.encode();
    }

    @Override
    public int countBytes() {
        return values.countBytes();
    }
}

record DP(TByte sensors, TArray<Trigger> triggers) implements Encodable {
    @Override
    public byte[] encode() {
        return Utils.concatByteArrays(sensors.encode(), triggers.encode());
    }

    @Override
    public int countBytes() {
        return sensors.countBytes() + triggers.countBytes();
    }
}

record Trigger(TByte op, Varuint value, TString name) implements Encodable {

    public static Trigger of(byte[] bytes) {
        int index = 0;

        final TByte op = new TByte(bytes[index++]);

        final Varuint value = new Varuint(Utils.arrCopyFrom(bytes, index));
        index += value.countBytes();

        final TString name = new TString(Utils.arrCopyFrom(bytes, index));

        return new Trigger(op, value, name);
    }

    @Override
    public byte[] encode() {
        return Utils.concatByteArrays(op.encode(), value.encode(), name.encode());
    }

    @Override
    public int countBytes() {
        return op.countBytes() + value.countBytes() + name.countBytes();
    }
}

record DPNameList(TArray<TString> dev_names) implements Encodable {
    @Override
    public byte[] encode() {
        return dev_names.encode();
    }

    @Override
    public int countBytes() {
        return dev_names.countBytes();
    }
}


interface Type<T> extends Encodable {
    T val();
}

final class TByte implements Type<Integer> {
    private final Integer value;

    TByte(byte b) {
        this.value = Byte.toUnsignedInt(b);
    }

    TByte(Integer value) {
        this.value = value;
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
    public int countBytes() {
        return 1;
    }

    @Override
    public String toString() {
        return value.toString();
    }
}

final class TString implements Type<String> {
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

    TString(String value) {
        this.value = value;
        this.countBytes = value.length() + 1;
    }

    @Override
    public String val() {
        return value;
    }

    @Override
    public byte[] encode() {
        final byte[] bytes = new byte[value.length() + 1];
        bytes[0] = Integer.valueOf(value.length()).byteValue();
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

final class Varuint implements Type<Long> {
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

    Varuint(Long value) {
        this.value = value;
        this.countBytes = -1;
    }

    @Override
    public Long val() {
        return value;
    }

    @Override
    public byte[] encode() {
        long val = value;
        List<Byte> bytes = new ArrayList<>();
        do {
            byte b = (byte) (val & 0x7f);
            val >>= 7;
            if (val != 0) {
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

final class TArray<T extends Encodable> implements Type<T> {
    private final List<T> list;
    private final int countBytes;

    TArray(byte[] bytes, Function<byte[], T> mapper) {
        int readIndex = 0;
        int count = new TByte(bytes[readIndex++]).val();
        final List<T> arrayList = new ArrayList<>(count);
        for (; count > 0; count--) {
            T element = mapper.apply(Utils.arrCopyFrom(bytes, readIndex));
            readIndex += element.countBytes();
            arrayList.add(element);
        }

        this.list = arrayList;
        this.countBytes = readIndex;
    }

    TArray(List<T> list) {
        this.list = list;
        this.countBytes = -1;
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
        final byte[] size = new byte[]{Integer.valueOf(list.size()).byteValue()};
        final List<byte[]> objects = list.stream().map(Encodable::encode).toList();

        final byte[] bytes = new byte[objects.stream().mapToInt(arr -> arr.length).sum() + 1];

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
    WHOISHERE(),
    IAMHERE(),
    GETSTATUS(),
    STATUS(),
    SETSTATUS(),
    TICK();

    Cmd() {
    }

    public static Cmd of(int b) {
        return values()[b - 1];
    }

    @Override
    public byte[] encode() {
        return new byte[]{Integer.valueOf(ordinal() + 1).byteValue()};
    }

    @Override
    public int countBytes() {
        return 1;
    }
}

enum DevType implements Encodable {
    SmartHub(),
    EnvSensor(),
    Switch(),
    Lamp(),
    Socket(),
    Clock();

    DevType() {
    }

    public static DevType of(int b) {
        return values()[b - 1];
    }

    @Override
    public byte[] encode() {
        return new byte[]{Integer.valueOf(ordinal() + 1).byteValue()};
    }

    @Override
    public int countBytes() {
        return 1;
    }
}


final class Utils {
    public static int concatBytes(byte[] src, byte[] dst, int index) {
        for (int i = 0; i < dst.length; i++, index++) {
            src[index] = dst[i];
        }
        return index;
    }

    public static byte[] concatByteArrays(byte[]... arrays) {
        if (arrays.length == 0) {
            return new byte[0];
        }

        final int length = Arrays.stream(arrays).mapToInt(arr -> arr.length).sum();
        final byte[] bytes = new byte[length];

        int index = Utils.concatBytes(bytes, arrays[0], 0);
        for (int i = 1; i < arrays.length; i++) {
            index = Utils.concatBytes(bytes, arrays[i], index);
        }

        return bytes;
    }

    public static byte[] arrCopyFrom(byte[] bytes, int from) {
        return Arrays.copyOfRange(bytes, from, bytes.length);
    }

    public static int checkCrc8(byte[] bytes) {
        int crc = 0;

        for (byte b : bytes) {
            crc ^= b;
            for (int j = 0; j < 8; j++) {
                if ((crc & 0x80) != 0) {
                    crc = ((crc << 1) ^ 0x1D);
                } else {
                    crc <<= 1;
                }
            }
            crc &= 0xFF;
        }

        return crc & 0xFF;
    }
}
