package io.suboptimal.buffjson.internal;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.alibaba.fastjson2.JSONException;
import com.alibaba.fastjson2.JSONReader;
import com.alibaba.fastjson2.JSONWriter;
import com.google.protobuf.*;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;

/**
 * Specialized JSON serialization for protobuf
 * <a href="https://protobuf.dev/reference/protobuf/google.protobuf/">well-known
 * types</a>.
 *
 * <p>
 * These types have special JSON representations defined by the proto3 spec that
 * differ from their standard message serialization. Supports 16 types:
 *
 * <ul>
 * <li><b>Any</b>: {@code {"@type": "type.googleapis.com/...", ...}} with
 * TypeRegistry resolution
 * <li><b>Timestamp</b>: RFC 3339 string ({@code "2024-01-01T00:00:00Z"})
 * <li><b>Duration</b>: seconds string with 's' suffix ({@code "3600.500s"})
 * <li><b>FieldMask</b>: comma-separated camelCase paths ({@code "foo,barBaz"})
 * <li><b>Struct</b>: native JSON object
 * <li><b>Value</b>: native JSON value (string, number, bool, null, object, or
 * array)
 * <li><b>ListValue</b>: native JSON array
 * <li><b>Wrappers</b> (Int32Value, Int64Value, UInt32Value, UInt64Value,
 * FloatValue, DoubleValue, BoolValue, StringValue, BytesValue): unwrapped to
 * primitive JSON values
 * </ul>
 *
 * <p>
 * Nanos formatting uses 3, 6, or 9 digits (matching protobuf's convention of
 * grouping into millis, micros, or nanos — never arbitrary precision).
 *
 * <p>
 * For Timestamp and Duration, {@code writeTimestampDirect()} and
 * {@code writeDurationDirect()} accept primitive seconds/nanos directly,
 * bypassing descriptor lookup and {@code message.getField()} reflection. These
 * are used by generated encoders that know the field type at generation time.
 *
 * <p>
 * Timestamp formatting uses Howard Hinnant's civil calendar algorithm to
 * convert epoch seconds to year/month/day/hour/minute/second using pure integer
 * arithmetic — no {@code Instant} or {@code OffsetDateTime} allocation. Both
 * Timestamp and Duration use exact-size byte buffers (computed from nanos
 * precision and digit count) to avoid {@code Arrays.copyOf()} overhead.
 *
 * <p>
 * Also provides {@code writeUnsignedLongString()} for proto3 uint64/fixed64
 * fields, which avoids {@code Long.toUnsignedString()} String allocation by
 * delegating to fastjson2's {@code writeString(long)} for values in signed
 * range or formatting to {@code byte[]} + {@code writeStringLatin1()} for large
 * unsigned values.
 */
public final class WellKnownTypes {

	private static final Set<String> WELL_KNOWN_TYPE_NAMES = Set.of("google.protobuf.Any", "google.protobuf.Timestamp",
			"google.protobuf.Duration", "google.protobuf.FieldMask", "google.protobuf.Struct", "google.protobuf.Value",
			"google.protobuf.ListValue", "google.protobuf.DoubleValue", "google.protobuf.FloatValue",
			"google.protobuf.Int64Value", "google.protobuf.UInt64Value", "google.protobuf.Int32Value",
			"google.protobuf.UInt32Value", "google.protobuf.BoolValue", "google.protobuf.StringValue",
			"google.protobuf.BytesValue");

	static final Base64.Encoder BASE64 = Base64.getEncoder();

	// Valid proto3 ranges for Timestamp/Duration (mirrors com.google.protobuf.util
	// Timestamps/Durations). Out-of-range values cannot be represented as RFC 3339
	// /
	// duration strings, so serialization rejects them rather than emitting garbage.
	private static final long TIMESTAMP_SECONDS_MIN = -62135596800L; // 0001-01-01T00:00:00Z
	private static final long TIMESTAMP_SECONDS_MAX = 253402300799L; // 9999-12-31T23:59:59Z
	private static final long DURATION_SECONDS_MIN = -315576000000L;
	private static final long DURATION_SECONDS_MAX = 315576000000L;
	private static final int NANOS_MAX = 999_999_999;

	/**
	 * Cached field descriptors for well-known types to avoid repeated
	 * findFieldByName lookups.
	 */
	private static final ConcurrentHashMap<Descriptor, FieldDescriptor[]> WKT_FIELD_CACHE = new ConcurrentHashMap<>();

	private WellKnownTypes() {
	}

	public static boolean isWellKnownType(Descriptor descriptor) {
		return WELL_KNOWN_TYPE_NAMES.contains(descriptor.getFullName());
	}

	public static void write(JSONWriter jsonWriter, Message message, ProtobufMessageWriter writer) {
		var descriptor = message.getDescriptorForType();
		switch (descriptor.getFullName()) {
			case "google.protobuf.Any" -> writeAny(jsonWriter, message, writer);
			case "google.protobuf.Timestamp" -> writeTimestamp(jsonWriter, message);
			case "google.protobuf.Duration" -> writeDuration(jsonWriter, message);
			case "google.protobuf.FieldMask" -> writeFieldMask(jsonWriter, message);
			case "google.protobuf.Struct" -> writeStruct(jsonWriter, message, writer);
			case "google.protobuf.Value" -> writeValue(jsonWriter, message, writer);
			case "google.protobuf.ListValue" -> writeListValue(jsonWriter, message, writer);
			case "google.protobuf.DoubleValue", "google.protobuf.FloatValue", "google.protobuf.Int64Value",
					"google.protobuf.UInt64Value", "google.protobuf.Int32Value", "google.protobuf.UInt32Value",
					"google.protobuf.BoolValue", "google.protobuf.StringValue", "google.protobuf.BytesValue" ->
				writeWrapper(jsonWriter, message, writer);
			default -> throw new IllegalArgumentException("Unknown well-known type: " + descriptor.getFullName());
		}
	}

	private static void writeAny(JSONWriter jsonWriter, Message message, ProtobufMessageWriter writer) {
		var fields = getFields(message, "type_url", "value");
		String typeUrl = (String) message.getField(fields[0]);
		ByteString content = (ByteString) message.getField(fields[1]);

		// Default Any (empty type_url and value) → empty object
		if (typeUrl.isEmpty() && content.isEmpty()) {
			jsonWriter.startObject();
			jsonWriter.endObject();
			return;
		}

		TypeRegistry registry = writer.typeRegistry();
		if (registry == null) {
			// Server-side configuration error (encoder has no registry) — not driven by
			// untrusted input, so an IllegalStateException, not a JSONException.
			throw new IllegalStateException("Cannot serialize google.protobuf.Any without a TypeRegistry. "
					+ "Use BuffJson.encoder().setTypeRegistry(registry).encode(message).");
		}

		Descriptor type;
		try {
			type = registry.getDescriptorForTypeUrl(typeUrl);
		} catch (InvalidProtocolBufferException e) {
			throw new IllegalStateException("Invalid type URL in Any: " + typeUrl, e);
		}
		if (type == null) {
			throw new IllegalStateException("Cannot find type for url: " + typeUrl
					+ ". Register it via TypeRegistry.newBuilder().add(descriptor).build().");
		}

		Message contentMessage;
		try {
			contentMessage = DynamicMessage.parseFrom(type, content);
		} catch (InvalidProtocolBufferException e) {
			throw new IllegalStateException("Failed to parse Any content for type: " + typeUrl, e);
		}

		jsonWriter.startObject();
		jsonWriter.writeName("@type");
		jsonWriter.writeColon();
		jsonWriter.writeString(typeUrl);

		if (isWellKnownType(type)) {
			// WKT packed in Any: {"@type": "...", "value": <wkt-json>}
			jsonWriter.writeName("value");
			jsonWriter.writeColon();
			write(jsonWriter, contentMessage, writer);
		} else {
			// Regular message: {"@type": "...", ...fields...}
			writer.writeFields(jsonWriter, contentMessage);
		}

		jsonWriter.endObject();
	}

	private static void writeTimestamp(JSONWriter jsonWriter, Message message) {
		var fields = getFields(message, "seconds", "nanos");
		long seconds = (long) message.getField(fields[0]);
		int nanos = (int) message.getField(fields[1]);
		writeTimestampDirect(jsonWriter, seconds, nanos);
	}

	/**
	 * Writes a Timestamp directly from typed seconds/nanos, bypassing descriptor
	 * lookup and reflection. Formats into a reusable {@code byte[]} and writes via
	 * {@code writeStringLatin1} to avoid String allocation. Used by generated
	 * encoders that know the field type at generation time.
	 */
	public static void writeTimestampDirect(JSONWriter jsonWriter, long seconds, int nanos) {
		// proto3 spec: out-of-range Timestamps are not serializable (JsonFormat rejects
		// them too).
		if (seconds < TIMESTAMP_SECONDS_MIN || seconds > TIMESTAMP_SECONDS_MAX) {
			throw new IllegalArgumentException("Timestamp seconds out of range [" + TIMESTAMP_SECONDS_MIN + ", "
					+ TIMESTAMP_SECONDS_MAX + "]: " + seconds);
		}
		if (nanos < 0 || nanos > NANOS_MAX) {
			throw new IllegalArgumentException("Timestamp nanos out of range [0, " + NANOS_MAX + "]: " + nanos);
		}
		// Exact-size buffer: "yyyy-MM-ddTHH:mm:ssZ" = 20, +4 millis, +7 micros, +10
		// nanos
		int nanosLen = nanosDigitCount(nanos);
		byte[] buf = new byte[20 + nanosLen];

		// Decompose epoch seconds into date/time using integer arithmetic only
		// (Howard Hinnant's civil_from_days algorithm — no Instant/OffsetDateTime
		// allocation)
		long daysSinceEpoch = Math.floorDiv(seconds, 86400);
		int timeOfDay = Math.floorMod(seconds, 86400);

		// civil_from_days: convert days since 1970-01-01 to (year, month, day)
		long z = daysSinceEpoch + 719468; // shift to epoch 0000-03-01
		long era = (z >= 0 ? z : z - 146096) / 146097;
		long doe = z - era * 146097; // day of era [0, 146096]
		long yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365;
		long y = yoe + era * 400;
		long doy = doe - (365 * yoe + yoe / 4 - yoe / 100); // day of year [0, 365]
		long mp = (5 * doy + 2) / 153; // month index [0, 11]
		int day = (int) (doy - (153 * mp + 2) / 5 + 1);
		int month = (int) (mp < 10 ? mp + 3 : mp - 9);
		int year = (int) (y + (month <= 2 ? 1 : 0));

		int hour = timeOfDay / 3600;
		int minute = (timeOfDay % 3600) / 60;
		int second = timeOfDay % 60;

		int off = 0;
		off = writeYear(buf, off, year);
		buf[off++] = '-';
		off = write2Digits(buf, off, month);
		buf[off++] = '-';
		off = write2Digits(buf, off, day);
		buf[off++] = 'T';
		off = write2Digits(buf, off, hour);
		buf[off++] = ':';
		off = write2Digits(buf, off, minute);
		buf[off++] = ':';
		off = write2Digits(buf, off, second);
		if (nanos != 0) {
			buf[off++] = '.';
			off = appendNanosBytes(buf, off, nanos);
		}
		buf[off] = 'Z';
		jsonWriter.writeStringLatin1(buf);
	}

	private static void writeDuration(JSONWriter jsonWriter, Message message) {
		var fields = getFields(message, "seconds", "nanos");
		long seconds = (long) message.getField(fields[0]);
		int nanos = (int) message.getField(fields[1]);
		writeDurationDirect(jsonWriter, seconds, nanos);
	}

	/**
	 * Writes a Duration directly from typed seconds/nanos, bypassing descriptor
	 * lookup and reflection. Used by generated encoders that know the field type at
	 * generation time.
	 */
	public static void writeDurationDirect(JSONWriter jsonWriter, long seconds, int nanos) {
		// proto3 spec: bounded magnitude, and seconds/nanos must not have opposite
		// signs
		// (JsonFormat rejects these too).
		if (seconds < DURATION_SECONDS_MIN || seconds > DURATION_SECONDS_MAX) {
			throw new IllegalArgumentException("Duration seconds out of range [" + DURATION_SECONDS_MIN + ", "
					+ DURATION_SECONDS_MAX + "]: " + seconds);
		}
		if (nanos < -NANOS_MAX || nanos > NANOS_MAX) {
			throw new IllegalArgumentException(
					"Duration nanos out of range [" + (-NANOS_MAX) + ", " + NANOS_MAX + "]: " + nanos);
		}
		if ((seconds < 0 && nanos > 0) || (seconds > 0 && nanos < 0)) {
			throw new IllegalArgumentException(
					"Duration seconds and nanos must have the same sign: seconds=" + seconds + ", nanos=" + nanos);
		}
		boolean negative = seconds < 0 || nanos < 0;
		long absSeconds = Math.abs(seconds);
		int absNanos = Math.abs(nanos);
		int nanosLen = nanosDigitCount(absNanos);
		// Exact size: optional '-' + digits(seconds) + nanosLen + 's'
		int size = (negative ? 1 : 0) + longDigitCount(absSeconds) + nanosLen + 1;
		byte[] buf = new byte[size];
		int off = 0;
		if (negative) {
			buf[off++] = '-';
		}
		off = writeLong(buf, off, absSeconds);
		if (absNanos != 0) {
			buf[off++] = '.';
			off = appendNanosBytes(buf, off, absNanos);
		}
		buf[off] = 's';
		jsonWriter.writeStringLatin1(buf);
	}

	/**
	 * Writes an unsigned long as a quoted JSON string directly into the writer,
	 * bypassing {@code Long.toUnsignedString()} String allocation. Used for proto3
	 * uint64/fixed64 fields.
	 */
	public static void writeUnsignedLongString(JSONWriter jsonWriter, long value) {
		if (value >= 0) {
			// Fits in signed range — delegate to fastjson2's writeString(long)
			jsonWriter.writeString(value);
			return;
		}
		// Negative signed = large unsigned: format into byte[] and write as Latin1
		// Max unsigned long is 18446744073709551615 = 20 digits
		byte[] buf = new byte[20];
		int off = writeUnsignedLong(buf, 0, value);
		buf = java.util.Arrays.copyOf(buf, off);
		jsonWriter.writeStringLatin1(buf);
	}

	/**
	 * Writes an unsigned long value as ASCII digits into a byte array. Handles
	 * values where the signed representation is negative (i.e. values ≥ 2^63).
	 * Returns the new offset.
	 */
	private static int writeUnsignedLong(byte[] buf, int off, long value) {
		// For unsigned values ≥ 2^63, use Long.divideUnsigned
		int start = off;
		long remaining = value;
		while (Long.compareUnsigned(remaining, 0) != 0) {
			long q = Long.divideUnsigned(remaining, 10);
			int r = (int) Long.remainderUnsigned(remaining, 10);
			buf[off++] = (byte) (r + '0');
			remaining = q;
		}
		// Reverse the digits
		for (int i = start, j = off - 1; i < j; i++, j--) {
			byte tmp = buf[i];
			buf[i] = buf[j];
			buf[j] = tmp;
		}
		return off;
	}

	/**
	 * Writes a long value as ASCII digits into a byte array. Returns the new
	 * offset.
	 */
	private static int writeLong(byte[] buf, int off, long value) {
		if (value == 0) {
			buf[off] = '0';
			return off + 1;
		}
		// Write digits in reverse, then flip
		int start = off;
		while (value > 0) {
			buf[off++] = (byte) (value % 10 + '0');
			value /= 10;
		}
		// Reverse the digits
		for (int i = start, j = off - 1; i < j; i++, j--) {
			byte tmp = buf[i];
			buf[i] = buf[j];
			buf[j] = tmp;
		}
		return off;
	}

	private static void writeFieldMask(JSONWriter jsonWriter, Message message) {
		var fields = getFields(message, "paths");
		@SuppressWarnings("unchecked")
		List<String> paths = (List<String>) message.getField(fields[0]);

		StringBuilder sb = new StringBuilder(paths.size() * 16);
		for (int i = 0; i < paths.size(); i++) {
			if (i > 0)
				sb.append(',');
			sb.append(snakeToCamel(paths.get(i)));
		}
		jsonWriter.writeString(sb.toString());
	}

	private static void writeStruct(JSONWriter jsonWriter, Message message, ProtobufMessageWriter writer) {
		var fields = getFields(message, "fields");
		@SuppressWarnings("unchecked")
		List<Message> entries = (List<Message>) message.getField(fields[0]);

		jsonWriter.startObject();
		for (var entry : entries) {
			var entryFields = getFields(entry, "key", "value");
			String key = (String) entry.getField(entryFields[0]);
			Message value = (Message) entry.getField(entryFields[1]);
			jsonWriter.writeName(key);
			jsonWriter.writeColon();
			writeValue(jsonWriter, value, writer);
		}
		jsonWriter.endObject();
	}

	private static void writeValue(JSONWriter jsonWriter, Message message, ProtobufMessageWriter writer) {
		var desc = message.getDescriptorForType();
		var kindOneof = desc.getOneofs().get(0);
		var activeField = message.getOneofFieldDescriptor(kindOneof);

		if (activeField == null) {
			jsonWriter.writeNull();
			return;
		}

		switch (activeField.getName()) {
			case "null_value" -> jsonWriter.writeNull();
			case "number_value" -> jsonWriter.writeDouble((double) message.getField(activeField));
			case "string_value" -> jsonWriter.writeString((String) message.getField(activeField));
			case "bool_value" -> jsonWriter.writeBool((boolean) message.getField(activeField));
			case "struct_value" -> writeStruct(jsonWriter, (Message) message.getField(activeField), writer);
			case "list_value" -> writeListValue(jsonWriter, (Message) message.getField(activeField), writer);
		}
	}

	private static void writeListValue(JSONWriter jsonWriter, Message message, ProtobufMessageWriter writer) {
		var fields = getFields(message, "values");
		@SuppressWarnings("unchecked")
		List<Message> values = (List<Message>) message.getField(fields[0]);

		jsonWriter.startArray();
		for (int i = 0; i < values.size(); i++) {
			if (i > 0)
				jsonWriter.writeComma();
			writeValue(jsonWriter, values.get(i), writer);
		}
		jsonWriter.endArray();
	}

	private static void writeWrapper(JSONWriter jsonWriter, Message message, ProtobufMessageWriter writer) {
		// Wrapper types have a single "value" field — delegate to FieldWriter
		// which already handles unsigned formatting, NaN/Infinity, Base64, etc.
		var fields = getFields(message, "value");
		FieldWriter.writeValue(jsonWriter, fields[0], message.getField(fields[0]), writer);
	}

	/**
	 * Looks up and caches field descriptors for a well-known type message by name.
	 * Avoids repeated {@code findFieldByName()} calls on the hot path.
	 *
	 * <p>
	 * Each WKT Descriptor must always be called with the same {@code names} — the
	 * cache is keyed by Descriptor alone.
	 */
	private static FieldDescriptor[] getFields(Message message, String... names) {
		var desc = message.getDescriptorForType();
		// Fast path: avoid varargs-driven computeIfAbsent on cache hit
		var cached = WKT_FIELD_CACHE.get(desc);
		if (cached != null) {
			return cached;
		}
		return WKT_FIELD_CACHE.computeIfAbsent(desc, d -> {
			FieldDescriptor[] result = new FieldDescriptor[names.length];
			for (int i = 0; i < names.length; i++) {
				result[i] = d.findFieldByName(names[i]);
			}
			return result;
		});
	}

	/**
	 * Returns the number of characters needed for the fractional seconds portion
	 * (including the dot). Returns 0 if nanos == 0, 4 for millis, 7 for micros, 10
	 * for nanos.
	 */
	private static int nanosDigitCount(int nanos) {
		if (nanos == 0)
			return 0;
		if (nanos % 1_000_000 == 0)
			return 4; // .nnn
		if (nanos % 1_000 == 0)
			return 7; // .nnnnnn
		return 10; // .nnnnnnnnn
	}

	/**
	 * Returns the number of decimal digits in a non-negative long value.
	 */
	private static int longDigitCount(long value) {
		if (value == 0)
			return 1;
		int digits = 0;
		while (value > 0) {
			digits++;
			value /= 10;
		}
		return digits;
	}

	/** Writes a 4-digit year into a byte array. Returns the new offset. */
	private static int writeYear(byte[] buf, int off, int year) {
		buf[off] = (byte) (year / 1000 + '0');
		buf[off + 1] = (byte) (year / 100 % 10 + '0');
		buf[off + 2] = (byte) (year / 10 % 10 + '0');
		buf[off + 3] = (byte) (year % 10 + '0');
		return off + 4;
	}

	/**
	 * Writes a zero-padded 2-digit number into a byte array. Returns the new
	 * offset.
	 */
	private static int write2Digits(byte[] buf, int off, int value) {
		buf[off] = (byte) (value / 10 + '0');
		buf[off + 1] = (byte) (value % 10 + '0');
		return off + 2;
	}

	/**
	 * Appends nanos as 3, 6, or 9 ASCII digits into a byte array. Returns the new
	 * offset.
	 */
	private static int appendNanosBytes(byte[] buf, int off, int nanos) {
		int digits;
		int value;
		if (nanos % 1_000_000 == 0) {
			digits = 3;
			value = nanos / 1_000_000;
		} else if (nanos % 1_000 == 0) {
			digits = 6;
			value = nanos / 1_000;
		} else {
			digits = 9;
			value = nanos;
		}
		for (int i = digits - 1; i >= 0; i--) {
			buf[off + i] = (byte) (value % 10 + '0');
			value /= 10;
		}
		return off + digits;
	}

	/**
	 * Converts snake_case to lowerCamelCase for FieldMask paths per proto3 JSON
	 * spec.
	 */
	private static String snakeToCamel(String snake) {
		StringBuilder sb = new StringBuilder(snake.length());
		boolean upperNext = false;
		for (int i = 0; i < snake.length(); i++) {
			char c = snake.charAt(i);
			if (c == '_') {
				upperNext = true;
			} else {
				sb.append(upperNext ? Character.toUpperCase(c) : c);
				upperNext = false;
			}
		}
		return sb.toString();
	}

	// =========================================================================
	// Deserialization (JSON → protobuf)
	// =========================================================================

	/**
	 * Maximum nesting depth for the recursive {@code Struct}/{@code Value}/
	 * {@code ListValue} reader. Matches protobuf's own recursion limit
	 * ({@code CodedInputStream.DEFAULT_RECURSION_LIMIT} and
	 * {@code JsonFormat.Parser}'s default, both 100), so deeply nested untrusted
	 * JSON fails with a clean {@link JSONException} instead of a
	 * {@code StackOverflowError}.
	 */
	private static final int MAX_RECURSION_DEPTH = 100;

	private static void checkDepth(JSONReader reader, int depth) {
		if (depth > MAX_RECURSION_DEPTH) {
			throw new JSONException(reader.info("JSON nesting depth exceeds " + MAX_RECURSION_DEPTH));
		}
	}

	/**
	 * Reads a well-known type from JSON. Dispatches by descriptor full name.
	 */
	public static Message readWkt(JSONReader reader, Descriptor descriptor, ProtobufMessageReader msgReader) {
		return switch (descriptor.getFullName()) {
			case "google.protobuf.Any" -> readAny(reader, msgReader);
			case "google.protobuf.Timestamp" -> readTimestamp(reader);
			case "google.protobuf.Duration" -> readDuration(reader);
			case "google.protobuf.FieldMask" -> readFieldMask(reader);
			case "google.protobuf.Struct" -> readStruct(reader);
			case "google.protobuf.Value" -> readJsonValue(reader);
			case "google.protobuf.ListValue" -> readListValue(reader);
			case "google.protobuf.DoubleValue" -> DoubleValue.of(FieldReader.readDoubleValue(reader));
			case "google.protobuf.FloatValue" -> FloatValue.of(FieldReader.readFloatValue(reader));
			case "google.protobuf.Int64Value" -> Int64Value.of(FieldReader.readSignedLong(reader));
			case "google.protobuf.UInt64Value" -> UInt64Value.of(FieldReader.readUnsignedLong(reader));
			case "google.protobuf.Int32Value" -> Int32Value.of(reader.readInt32Value());
			case "google.protobuf.UInt32Value" -> UInt32Value.of((int) reader.readInt64Value());
			case "google.protobuf.BoolValue" -> BoolValue.of(reader.readBoolValue());
			case "google.protobuf.StringValue" -> StringValue.of(reader.readString());
			case "google.protobuf.BytesValue" -> BytesValue.of(FieldReader.readBytes(reader));
			default -> throw new IllegalArgumentException("Unknown well-known type: " + descriptor.getFullName());
		};
	}

	/** Reads an RFC 3339 timestamp string and returns a Timestamp message. */
	public static Timestamp readTimestamp(JSONReader reader) {
		String rfc3339 = reader.readString();
		try {
			return parseTimestamp(rfc3339);
		} catch (DateTimeParseException | IllegalArgumentException e) {
			// DateTimeParseException: malformed RFC 3339. IllegalArgumentException: the
			// parsed instant is outside the proto3 Timestamp range (see parseTimestamp).
			// Both are rejected at parse time. Normalize to JSONException and attach
			// position context via reader.info(...).
			throw new JSONException(reader.info("Invalid RFC 3339 timestamp for google.protobuf.Timestamp"), e);
		}
	}

	/**
	 * Reads a duration string (e.g., "3600.500s") and returns a Duration message.
	 */
	public static Duration readDuration(JSONReader reader) {
		String s = reader.readString();
		try {
			return parseDuration(s);
		} catch (IllegalArgumentException e) {
			// IllegalArgumentException covers NumberFormatException (parse) and the
			// explicit "missing 's' suffix" check in parseDuration.
			throw new JSONException(reader.info("Invalid duration for google.protobuf.Duration"), e);
		}
	}

	static Timestamp parseTimestamp(String rfc3339) {
		Instant instant = Instant.parse(rfc3339);
		long seconds = instant.getEpochSecond();
		// proto3 spec: Timestamps outside [0001-01-01, 9999-12-31] are invalid. Reject
		// at parse time so the failure is a clean parse error (not a deferred serialize
		// error). Two long comparisons on an already-parsed value — no allocation.
		if (seconds < TIMESTAMP_SECONDS_MIN || seconds > TIMESTAMP_SECONDS_MAX) {
			throw new IllegalArgumentException("Timestamp seconds out of range [" + TIMESTAMP_SECONDS_MIN + ", "
					+ TIMESTAMP_SECONDS_MAX + "]: " + seconds);
		}
		return Timestamp.newBuilder().setSeconds(seconds).setNanos(instant.getNano()).build();
	}

	static Duration parseDuration(String s) {
		if (!s.endsWith("s")) {
			throw new IllegalArgumentException("Invalid duration: " + s);
		}
		String numPart = s.substring(0, s.length() - 1);
		boolean negative = numPart.startsWith("-");
		if (negative) {
			numPart = numPart.substring(1);
		}

		long seconds;
		int nanos = 0;
		int dotIndex = numPart.indexOf('.');
		if (dotIndex >= 0) {
			seconds = Long.parseLong(numPart.substring(0, dotIndex));
			String fracStr = numPart.substring(dotIndex + 1);
			// Pad to 9 digits
			fracStr = (fracStr + "000000000").substring(0, 9);
			nanos = Integer.parseInt(fracStr);
		} else {
			seconds = Long.parseLong(numPart);
		}

		if (negative) {
			seconds = -seconds;
			if (nanos != 0) {
				nanos = -nanos;
			}
		}
		// proto3 spec: Durations outside ±315,576,000,000s are invalid. Reject at parse
		// time (matching writeDurationDirect's serialize-side guard) so the failure is
		// a
		// clean parse error. Two long comparisons — no allocation.
		if (seconds < DURATION_SECONDS_MIN || seconds > DURATION_SECONDS_MAX) {
			throw new IllegalArgumentException("Duration seconds out of range [" + DURATION_SECONDS_MIN + ", "
					+ DURATION_SECONDS_MAX + "]: " + seconds);
		}
		return Duration.newBuilder().setSeconds(seconds).setNanos(nanos).build();
	}

	private static FieldMask readFieldMask(JSONReader reader) {
		String camelCase = reader.readString();
		FieldMask.Builder builder = FieldMask.newBuilder();
		if (!camelCase.isEmpty()) {
			for (String path : camelCase.split(",")) {
				builder.addPaths(camelToSnake(path));
			}
		}
		return builder.build();
	}

	/** Reads a native JSON object as a protobuf Struct. */
	public static Struct readStruct(JSONReader reader) {
		return readStruct(reader, 1);
	}

	private static Struct readStruct(JSONReader reader, int depth) {
		checkDepth(reader, depth);
		Struct.Builder builder = Struct.newBuilder();
		reader.nextIfObjectStart();
		while (!reader.nextIfObjectEnd()) {
			String key = reader.readFieldName();
			if (key == null) {
				break;
			}
			Value value = readJsonValueImpl(reader, depth);
			builder.putFields(key, value);
		}
		return builder.build();
	}

	/** Reads a native JSON value as a protobuf Value (dispatch on token type). */
	public static Value readJsonValue(JSONReader reader) {
		return readJsonValueImpl(reader, 1);
	}

	private static Value readJsonValueImpl(JSONReader reader, int depth) {
		if (reader.nextIfNull()) {
			return Value.newBuilder().setNullValue(NullValue.NULL_VALUE).build();
		}
		if (reader.isString()) {
			return Value.newBuilder().setStringValue(reader.readString()).build();
		}
		char c = reader.current();
		if (c == 't' || c == 'f') {
			return Value.newBuilder().setBoolValue(reader.readBoolValue()).build();
		}
		if (c == '{') {
			return Value.newBuilder().setStructValue(readStruct(reader, depth + 1)).build();
		}
		if (c == '[') {
			return Value.newBuilder().setListValue(readListValue(reader, depth + 1)).build();
		}
		// Must be a number
		return Value.newBuilder().setNumberValue(reader.readDoubleValue()).build();
	}

	/** Reads a native JSON array as a protobuf ListValue. */
	public static ListValue readListValue(JSONReader reader) {
		return readListValue(reader, 1);
	}

	private static ListValue readListValue(JSONReader reader, int depth) {
		checkDepth(reader, depth);
		ListValue.Builder builder = ListValue.newBuilder();
		reader.nextIfArrayStart();
		while (!reader.nextIfArrayEnd()) {
			builder.addValues(readJsonValueImpl(reader, depth));
		}
		return builder.build();
	}

	private static Any readAny(JSONReader reader, ProtobufMessageReader msgReader) {
		reader.nextIfObjectStart();

		if (reader.nextIfObjectEnd()) {
			return Any.getDefaultInstance();
		}

		String firstKey = reader.readFieldName();

		// Fast path: the canonical proto3 form lists "@type" first. When it does, we
		// can resolve the content descriptor before reading any content and decode
		// the remaining fields straight off the live reader — no buffering into a
		// Map, no JSON.toJSONString + re-parse (which roughly doubles the work).
		if ("@type".equals(firstKey)) {
			String typeUrl = reader.readString();
			if (typeUrl == null || typeUrl.isEmpty()) {
				// proto3: a non-empty Any object must carry a resolvable @type. An empty
				// "@type" is unresolvable — only a bare {} is a valid typeless Any (handled
				// above) — so reject rather than silently producing a default Any.
				throw new JSONException(reader.info("Any @type must not be empty"));
			}
			Descriptor type = resolveAnyType(reader, typeUrl, msgReader);
			Message content = isWellKnownType(type)
					? readPackedWktValue(reader, type, msgReader)
					: msgReader.readRemainingMessageFields(reader, type);
			return Any.pack(content);
		}

		// Slow path: "@type" appears after content (or is absent), so we cannot know
		// the descriptor up front. Buffer every field, then resolve and re-parse.
		String typeUrl = null;
		Map<String, Object> allFields = new LinkedHashMap<>();
		String key = firstKey;
		while (key != null) {
			if ("@type".equals(key)) {
				typeUrl = reader.readString();
			} else {
				allFields.put(key, reader.readAny());
			}
			if (reader.nextIfObjectEnd()) {
				break;
			}
			key = reader.readFieldName();
		}

		if (typeUrl == null || typeUrl.isEmpty()) {
			// Reaching the slow path means we already read a non-@type field, so the
			// object is non-empty; a missing or empty @type leaves it unresolvable and is
			// rejected (mirrors the fast-path rule and protobuf's reference parser).
			throw new JSONException(reader.info("Any with content requires a non-empty @type"));
		}

		Descriptor type = resolveAnyType(reader, typeUrl, msgReader);

		Message contentMessage;
		if (isWellKnownType(type)) {
			// WKT packed in Any: {"@type": "...", "value": <wkt-json>}
			Object valueObj = allFields.get("value");
			if (valueObj == null) {
				// Missing or explicit-null "value": match the fast path / top-level field
				// rule instead of stringifying null and NPE-ing inside the WKT reader.
				contentMessage = allFields.containsKey("value")
						? nullPackedWktValue(type)
						: DynamicMessage.getDefaultInstance(type);
			} else {
				String valueJson = com.alibaba.fastjson2.JSON.toJSONString(valueObj);
				try (JSONReader valueReader = JSONReader.of(valueJson)) {
					contentMessage = readWkt(valueReader, type, msgReader);
				}
			}
		} else {
			// Regular message: {"@type": "...", ...fields...}
			String fieldsJson = com.alibaba.fastjson2.JSON.toJSONString(allFields);
			try (JSONReader fieldsReader = JSONReader.of(fieldsJson)) {
				contentMessage = msgReader.readMessage(fieldsReader, type);
			}
		}

		return Any.pack(contentMessage);
	}

	/**
	 * Resolves an Any {@code @type} URL (from untrusted JSON) to its message
	 * descriptor via the registry.
	 *
	 * <p>
	 * A missing {@link TypeRegistry} is a <em>server-side configuration</em> error
	 * (the decoder was never given a registry) and throws
	 * {@link IllegalStateException}. A malformed or unregistered {@code @type} is a
	 * <em>client input</em> error and throws {@link JSONException} with the JSON
	 * offset.
	 */
	private static Descriptor resolveAnyType(JSONReader reader, String typeUrl, ProtobufMessageReader msgReader) {
		TypeRegistry registry = msgReader.typeRegistry();
		if (registry == null) {
			throw new IllegalStateException("Cannot deserialize google.protobuf.Any without a TypeRegistry. "
					+ "Use BuffJson.decoder().setTypeRegistry(registry).decode(json, clazz).");
		}
		Descriptor type;
		try {
			type = registry.getDescriptorForTypeUrl(typeUrl);
		} catch (InvalidProtocolBufferException e) {
			throw new JSONException(reader.info("Invalid type URL in google.protobuf.Any"), e);
		}
		if (type == null) {
			throw new JSONException(reader.info("Cannot find type for url: " + typeUrl));
		}
		return type;
	}

	/**
	 * Fast-path read of a well-known type packed in an Any whose {@code @type} was
	 * already consumed. Reads the remaining fields off the live reader, parsing the
	 * {@code "value"} field directly. A JSON {@code null} value is handled like the
	 * top-level field path ({@code google.protobuf.Value} → {@code NullValue},
	 * other WKTs → default instance) rather than fed into the WKT reader (which
	 * would {@code NullPointerException}). Returns the type's default instance if
	 * no {@code "value"} field is present.
	 */
	private static Message readPackedWktValue(JSONReader reader, Descriptor type, ProtobufMessageReader msgReader) {
		Message content = null;
		while (!reader.nextIfObjectEnd()) {
			String name = reader.readFieldName();
			if (name == null) {
				break;
			}
			if ("value".equals(name)) {
				content = reader.nextIfNull() ? nullPackedWktValue(type) : readWkt(reader, type, msgReader);
			} else {
				reader.skipValue();
			}
		}
		return content != null ? content : DynamicMessage.getDefaultInstance(type);
	}

	/**
	 * Content message for a packed WKT whose {@code "value"} is an explicit JSON
	 * {@code null}. Mirrors the top-level field rule: {@code google.protobuf.Value}
	 * becomes {@code NullValue}; every other WKT becomes its default instance.
	 */
	private static Message nullPackedWktValue(Descriptor type) {
		if ("google.protobuf.Value".equals(type.getFullName())) {
			return Value.newBuilder().setNullValue(NullValue.NULL_VALUE).build();
		}
		return DynamicMessage.getDefaultInstance(type);
	}

	/**
	 * Converts lowerCamelCase to snake_case for FieldMask path parsing.
	 */
	static String camelToSnake(String camel) {
		StringBuilder sb = new StringBuilder(camel.length() + 4);
		for (int i = 0; i < camel.length(); i++) {
			char c = camel.charAt(i);
			if (Character.isUpperCase(c)) {
				sb.append('_').append(Character.toLowerCase(c));
			} else {
				sb.append(c);
			}
		}
		return sb.toString();
	}
}
